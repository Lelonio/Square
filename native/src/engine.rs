//! Session + playback engine.
//!
//! One process-wide instance, guarded by a mutex. All librespot work happens on
//! a dedicated multi-thread tokio runtime that outlives individual JNI calls;
//! JNI methods only enqueue commands and return immediately, so the Android main
//! thread is never blocked on network I/O.

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;
use librespot_connect::{
    ConnectConfig, LoadRequest, LoadRequestOptions, PlayingTrack, Spirc,
};
use librespot_core::{
    authentication::Credentials, cache::Cache, config::DeviceType, config::SessionConfig,
    session::Session, spotify_uri::SpotifyUri,
};
use librespot_playback::{
    config::{AudioFormat, Bitrate, NormalisationMethod, PlayerConfig},
    mixer::{softmixer::SoftMixer, Mixer, MixerConfig},
    player::{Player, PlayerEvent},
};
use crate::events::{self, EndReason, EventService, Listen};
use once_cell::sync::OnceCell;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Runtime;

pub(crate) static JAVA_VM: OnceCell<JavaVM> = OnceCell::new();
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static CONTEXT_INITIALIZED: AtomicBool = AtomicBool::new(false);
/// Set when a transport command had to go around Spirc; see [`transport`].
///
/// The engine is still making sound at that point, but the Connect device is
/// gone: the account has a stale idea of this phone, and nothing arriving from
/// another client will be obeyed. Only a rebuild fixes that, and only the Kotlin
/// side can decide when to do it, so this is a flag it can ask about rather than
/// something acted on here.
static SPIRC_LOST: AtomicBool = AtomicBool::new(false);

/// Whether the sink may make sound.
///
/// Every load shuts it, and the track that load asked for opens it again.
///
/// A load is a message to the Connect device, and it returns long before the
/// music changes: about a second passes while the new track is fetched, and the
/// old one is still being decoded throughout. The fade that wraps a skip is over
/// by then, so the volume came back up on the *previous* song, which played on
/// for a moment before the right one cut in.
///
/// Keyed on the track rather than on the load, which is where this was first put
/// and why it did not work: a load has to activate the device before it can hand
/// over a queue, and activating is what makes a freshly built device take up
/// whatever the account still believes this phone was playing.
static PLAYBACK_ARMED: AtomicBool = AtomicBool::new(true);

/// What the output is waiting for, when it is waiting for something.
///
/// A load knows the track it asked for, so it waits for that one. A skip does
/// not: `next` is a message to the Connect device, which picks the track
/// itself, so all this side knows is which one it wants to stop hearing.
enum Gate {
    /// Open when this track starts.
    Expect(String),
    /// Open when anything other than this track starts.
    Leave(String),
}

static GATE: Mutex<Option<Gate>> = Mutex::new(None);

/// The track currently being played, so a skip knows what it is leaving.
static CURRENT_URI: Mutex<String> = Mutex::new(String::new());

/// When to give up waiting, in milliseconds since the first call to [`uptime_ms`].
///
/// Silence that outlives its reason is worse than the noise it was there to
/// stop: a command that never lands would otherwise leave the app mute with no
/// way back except a restart.
static ARM_BY_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// How long the output stays shut while waiting.
const ARM_TIMEOUT_MS: u64 = 15_000;

static STARTED: OnceCell<std::time::Instant> = OnceCell::new();

fn uptime_ms() -> u64 {
    STARTED
        .get_or_init(std::time::Instant::now)
        .elapsed()
        .as_millis() as u64
}

/// Whether the sink may write. See [`PLAYBACK_ARMED`].
pub(crate) fn playback_armed() -> bool {
    PLAYBACK_ARMED.load(Ordering::SeqCst) || uptime_ms() >= ARM_BY_MS.load(Ordering::SeqCst)
}

/// Shuts the output until `gate` is satisfied.
fn shut(gate: Gate) {
    if let Ok(mut current) = GATE.lock() {
        *current = Some(gate);
    }
    ARM_BY_MS.store(uptime_ms() + ARM_TIMEOUT_MS, Ordering::SeqCst);
    PLAYBACK_ARMED.store(false, Ordering::SeqCst);
}

/// The track this device is on, for a skip to name as the one it is leaving.
fn current_uri() -> String {
    CURRENT_URI.lock().map(|uri| uri.clone()).unwrap_or_default()
}

/// Notes which track is playing, and opens the output if this is the one it was
/// waiting for.
fn track_started(uri: &str) {
    if let Ok(mut current) = CURRENT_URI.lock() {
        *current = uri.to_string();
    }
    if PLAYBACK_ARMED.load(Ordering::SeqCst) {
        return;
    }
    let satisfied = match GATE.lock().as_deref() {
        Ok(Some(Gate::Expect(wanted))) => wanted == uri,
        Ok(Some(Gate::Leave(old))) => old != uri,
        // Nothing to wait for: whatever shut the output has no opinion left.
        _ => true,
    };
    if satisfied {
        log::info!("output open again on {uri}");
        PLAYBACK_ARMED.store(true, Ordering::SeqCst);
    }
}

/// Set while [`reconnect`] is between two bundles.
///
/// Read without the engine lock, which is the point: the lock is held across a
/// handshake and the questions asked during one must still be answerable.
static RECONNECTING: AtomicBool = AtomicBool::new(false);

pub fn store_java_vm(vm: JavaVM) {
    let _ = JAVA_VM.set(vm);
}

/// The parts that outlive a reconnection.
///
/// Split from [`Bundle`] because losing the network costs the session, and
/// getting it back means building another one: librespot hands out the Spirc
/// builder once per session, so nothing short of a new session brings the
/// Connect device back. The two things that must *not* be rebuilt are here.
///
/// The runtime is one of them. Dropping it means `shutdown_timeout` from a JNI
/// thread while librespot's own threads are still inside it, and that aborted
/// the process on a destroyed mutex. The audio output is the other: it belongs
/// to the Android service, not to any session, and clearing it mid-life is the
/// second half of the same crash. Neither is touched by [`reconnect`].
pub struct Engine {
    rt: Runtime,
    mixer: Arc<SoftMixer>,
    /// Everything needed to build another [`Bundle`], kept so a reconnection
    /// needs nothing from the Kotlin side.
    recipe: Recipe,
    /// Where player events go. Owned here so the pump thread, and with it the
    /// listener reference, lives as long as the engine rather than as long as
    /// any one player.
    events_tx: std::sync::mpsc::Sender<Pump>,
    /// `None` only while [`reconnect`] is between two of them.
    bundle: Option<Bundle>,
}

/// Session, player and Connect device: one network lifetime, thrown away whole.
struct Bundle {
    session: Session,
    player: Arc<Player>,
    /// The Connect device. Every transport command goes through it; see [`load`].
    spirc: Spirc,
}

/// The inputs to a [`Bundle`], all of them cheap to clone.
struct Recipe {
    session_config: SessionConfig,
    player_config: PlayerConfig,
    connect_config: ConnectConfig,
    /// The OAuth credentials from the original login, if the caller had a token
    /// to give. Only the first login really needs one: the access point answers
    /// a successful handshake with a reusable credential, which is kept and used
    /// from then on. Empty means "use what was kept".
    credentials: Option<Credentials>,
    credentials_dir: String,
    cache_dir: String,
}

/// What the event pump reads.
///
/// Events are the reason it exists; the session arrives with every new bundle,
/// because listening history is reported through the session and the pump holds
/// one for the life of the engine.
enum Pump {
    Event(PlayerEvent),
    Session(Session),
    /// Something changed on another of the account's devices; see `remote`.
    Cluster,
}

/// Errors are flattened to a string because they cross the JNI boundary and the
/// Kotlin side only ever surfaces them as a message.
pub type EngineResult<T> = Result<T, String>;

fn with_engine<T>(f: impl FnOnce(&Engine) -> T) -> EngineResult<T> {
    let guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_ref().ok_or("engine not started")?;
    Ok(f(engine))
}

/// Borrow the current session, player and Connect device together.
///
/// Separate from [`with_engine`] because these three are replaced as a set: a
/// caller holding one of them alongside a stale copy of another would be holding
/// two different network lifetimes.
fn with_bundle<T>(f: impl FnOnce(&Bundle) -> T) -> EngineResult<T> {
    let guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_ref().ok_or("engine not started")?;
    let bundle = engine.bundle.as_ref().ok_or("engine is reconnecting")?;
    Ok(f(bundle))
}

/// Borrow the authenticated session, e.g. to clone it for a catalogue request.
pub fn with_session<T>(f: impl FnOnce(&Session) -> T) -> EngineResult<T> {
    with_bundle(|bundle| f(&bundle.session))
}

/// A handle to the engine's runtime.
///
/// Returned by value so callers can release the engine lock before blocking on
/// a request; holding it across an await would serialise playback commands
/// behind network I/O.
pub fn runtime_handle() -> EngineResult<tokio::runtime::Handle> {
    with_engine(|engine| engine.rt.handle().clone())
}

/// Publish an application `Context` to `ndk_context` so cpal's AAudio host can
/// resolve the output device. Must run before any playback starts.
///
/// Idempotent. `ndk_context::initialize_android_context` asserts that no context
/// was set before and aborts the process otherwise, and the Android service that
/// calls this can be destroyed and recreated any number of times within one
/// process. The context never changes over that lifetime, so the second and
/// later calls are simply no-ops.
pub fn init_android_context(context: &JObject) -> EngineResult<()> {
    if CONTEXT_INITIALIZED.swap(true, Ordering::SeqCst) {
        return Ok(());
    }

    let vm = JAVA_VM.get().ok_or("JNI_OnLoad did not run")?;
    let env = vm.get_env().map_err(|e| e.to_string())?;
    let global = env
        .new_global_ref(context)
        .map_err(|e| format!("failed to pin Context: {e}"))?;

    // SAFETY: both pointers stay valid for the lifetime of the process — the VM
    // is never destroyed on Android, and `global` is deliberately leaked so the
    // Context reference is never collected while ndk_context holds it.
    #[cfg(target_os = "android")]
    unsafe {
        ndk_context::initialize_android_context(
            vm.get_java_vm_pointer() as *mut _,
            global.as_raw() as *mut _,
        );
    }
    std::mem::forget(global);
    Ok(())
}

/// Build the session and player, then authenticate with an OAuth access token
/// obtained by the Kotlin side.
///
/// `listener` must implement `dev.lelonio.square.nativecore.NativeEvents`.
pub fn start(
    client_id: &str,
    device_name: &str,
    device_id: &str,
    access_token: &str,
    credentials_dir: &str,
    cache_dir: &str,
    language: &str,
    bitrate_kbps: i32,
    crossfade_ms: i32,
    listener: GlobalRef,
) -> EngineResult<()> {
    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    if guard.is_some() {
        return Err("engine already started".into());
    }

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("squarecore")
        .build()
        .map_err(|e| format!("failed to build runtime: {e}"))?;

    // An empty `client_id` means "use the platform default", which on Android is
    // the client id librespot ships for the official mobile app. login5 only
    // grants streaming to ids it recognises, so overriding this with a client id
    // registered in the Spotify dashboard will authenticate against the Web API
    // but be rejected at the access point.
    let mut session_config = SessionConfig::default();
    session_config.tmp_dir = std::path::PathBuf::from(cache_dir);
    // What Spotify should answer in. Artwork for the generated playlists is
    // localised — the language is in the cover's own URL — so a client that
    // never says which one it wants gets English tiles.
    if !language.is_empty() {
        session_config.language = language.to_string();
    }
    if !client_id.is_empty() {
        session_config.client_id = client_id.to_string();
    }
    // The same id on every launch, chosen by the app and kept by it.
    //
    // librespot's default is a fresh uuid per session, which is right for a
    // daemon started once and wrong for a phone: the account saw a new "A059"
    // every time the engine came up, the list filled with ghosts of this same
    // device, and nothing could recognise the phone it had been playing on an
    // hour earlier.
    if !device_id.is_empty() {
        session_config.device_id = device_id.to_string();
    }

    let credentials = if access_token.is_empty() {
        None
    } else {
        Some(Credentials::with_access_token(access_token))
    };

    let mixer = Arc::new(
        SoftMixer::open(MixerConfig::default()).map_err(|e| format!("mixer failed: {e}"))?,
    );

    // SoftMixer::open starts at an attenuation factor of 0.5 — half amplitude,
    // about -6 dB — and nothing else ever sets it, so every track played through
    // this engine was quiet by default. The device's own volume keys are the
    // right place to control loudness, so the software mixer is left wide open.
    mixer.set_volume(u16::MAX);

    let player_config = PlayerConfig {
        // Chosen by the caller. The bitrate is read when a track is loaded but
        // the player owns its config for its whole life, so changing this means
        // building a new engine: see PlaybackService.
        bitrate: match bitrate_kbps {
            96 => Bitrate::Bitrate96,
            160 => Bitrate::Bitrate160,
            _ => Bitrate::Bitrate320,
        },
        // Loudness normalisation, which is what keeps one track from being
        // twice as loud as the next.
        //
        // On its own it made the app quieter than everything else on the phone:
        // it pulls every track down towards a target and nothing puts the level
        // back. The pregain is what puts it back — Spotify's own "loud" setting
        // is about three decibels over its normal one, and a couple more brings
        // this level with apps that do no normalisation at all.
        normalisation: true,
        normalisation_pregain_db: 5.0,
        // The limiter, not a flat scale: with five decibels added, the loudest
        // moments of a loud master would otherwise clip.
        normalisation_method: NormalisationMethod::Dynamic,
        // Dithering runs per sample — 88200 times a second at 44.1 kHz stereo —
        // for a noise-floor benefit nobody hears on a phone. Off, to leave the
        // decoder more headroom against output underruns.
        ditherer: None,
        // Every update crosses JNI and rebuilds the Media3 state on the main
        // thread; once a second is plenty for a seek bar.
        position_update_interval: Some(Duration::from_secs(1)),
        // How long one track dissolves into the next, chosen by the user. See
        // the patch notes in native/vendor/README.md: the player asks for the
        // next track a fade early and mixes the one going out underneath it.
        // Fixed for the life of the player, like the bitrate, so the service
        // builds a new engine when the setting changes.
        crossfade_duration_ms: crossfade_ms.max(0) as u32,
        ..PlayerConfig::default()
    };

    // Spirc is what makes this a Spotify Connect device: it publishes the
    // playback state to the account, which is also the only way plays are
    // recorded in listening history — Spotify counts what a device reports, not
    // what it decodes. That is why nothing appeared in the history before this
    // existed.
    //
    // It owns the player from here on. Driving the player directly as well would
    // play audio the published state knows nothing about, so every transport
    // command below goes through the Spirc handle instead.
    let connect_config = ConnectConfig {
        name: device_name.to_string(),
        // Smartphone rather than the librespot default of Speaker: the icon in
        // the device list should match what the user is holding.
        device_type: DeviceType::Smartphone,
        // Full scale, matching the mixer. Loudness belongs to the phone's own
        // volume keys.
        initial_volume: u16::MAX,
        ..ConnectConfig::default()
    };

    let recipe = Recipe {
        session_config,
        player_config,
        connect_config,
        credentials,
        credentials_dir: credentials_dir.to_string(),
        cache_dir: cache_dir.to_string(),
    };

    // Started before the first bundle, and outliving every one of them: the pump
    // owns the listener reference, and that must be dropped on a thread still
    // attached to the JVM. Tying it to a player would mean dropping it whenever
    // a reconnection replaced one.
    let (events_tx, events_rx) = std::sync::mpsc::channel();
    spawn_event_pump(
        events_rx,
        listener,
        device_name.to_string(),
        cache_dir.to_string(),
    );

    let bundle = build_bundle(&rt, &recipe, &mixer, &events_tx)?;

    *guard = Some(Engine {
        rt,
        mixer,
        recipe,
        events_tx,
        bundle: Some(bundle),
    });
    Ok(())
}

/// Builds a session, a player and a Connect device, and wires them to the pump.
///
/// Everything here is disposable. It runs on the caller's thread and blocks on
/// the handshake, so it is only ever reached from a JNI call the Kotlin side
/// makes off the main thread.
fn build_bundle(
    rt: &Runtime,
    recipe: &Recipe,
    mixer: &Arc<SoftMixer>,
    events_tx: &std::sync::mpsc::Sender<Pump>,
) -> EngineResult<Bundle> {
    // A credentials cache is not an optimisation here, it is required for
    // catalogue access. `connect` persists reusable credentials into it, and
    // login5 then issues the access point's HTTP token as a *stored credential*
    // request, which it signs with the session's client id. Without a cache it
    // falls back to the platform default id instead, which no longer matches the
    // id the OAuth token was minted for, and every spclient call fails with
    // "Login request was denied: BAD_REQUEST".
    //
    // Bounded, and swept before it is opened: an audio cache with no limit had
    // grown past a gigabyte on the test phone, and the half-finished downloads
    // librespot writes next to it — one temporary file per interrupted load, and
    // skipping interrupts a lot of loads — were never collected at all.
    sweep_stale_downloads(&recipe.cache_dir);
    let cache = Cache::new(
        Some(std::path::Path::new(&recipe.credentials_dir)),
        None,
        Some(std::path::Path::new(&recipe.cache_dir)),
        Some(AUDIO_CACHE_LIMIT),
    )
    .map_err(|e| format!("cache failed: {e}"))?;

    // Taken before anything can report against it, and before the player exists:
    // the sink each player builds carries this number, which is what keeps a
    // replaced player's last packets out of the new one's output.
    //
    // A discarded session does not fall silent the moment it is replaced. Its
    // Spirc task ends a little later, and it ends the same way a lost one does;
    // without a number to check, that tidy ending would land on the bundle that
    // replaced it and mark a device that is perfectly alive as gone.
    let generation = GENERATION.fetch_add(1, Ordering::SeqCst) + 1;

    // Cloned before the async block takes ownership of the Arc as a `dyn Mixer`.
    let mixer_for_spirc: Arc<dyn Mixer> = mixer.clone();
    let session_config = recipe.session_config.clone();
    let player_config = recipe.player_config.clone();
    let connect_config = recipe.connect_config.clone();

    // The credential the access point issued the first time this device logged
    // in, kept in a directory of its own.
    //
    // It is the difference between a session that lasts and one that has to be
    // renewed. An OAuth access token is good for an hour, and the refresh token
    // behind it is rotated on every use and revoked on the first mistake, so an
    // engine that needs one at every launch is one bad refresh away from asking
    // the listener to sign in again. The blob the handshake answers with has
    // neither property: it is what go-librespot writes to `credentials.json` and
    // then reuses forever, and it is why signing in there is something you do
    // once.
    //
    // Kept apart from librespot's own credentials file because that file is
    // written by the connection itself, and what has to survive is the copy
    // nothing else touches.
    let kept = Cache::new(
        Some(&std::path::Path::new(&recipe.credentials_dir).join("reusable")),
        None,
        None,
        None,
    )
    .map_err(|e| format!("cache failed: {e}"))?;

    // The kept credential first, the token second. A token is only ever the way
    // in for a device that has never been in.
    let attempts: Vec<(&str, Credentials)> = kept
        .credentials()
        .into_iter()
        .map(|c| ("the kept credential", c))
        .chain(recipe.credentials.clone().map(|c| ("an access token", c)))
        .collect();
    if attempts.is_empty() {
        return Err("no credentials".into());
    }

    // The account's other devices, watched over the same dealer.
    //
    // Subscribed before the session connects, which is the whole point of doing
    // it here rather than after the device is built: Spotify pushes a cluster
    // update when this device appears, and that push is the only one that
    // arrives without something changing later. Subscribing afterwards meant
    // opening the app next to a speaker that was already playing and being told
    // nothing until the speaker's track ended.
    let watch_tx = events_tx.clone();

    let (session, player, spirc, spirc_task) = rt.block_on(async move {
        let last = attempts.len() - 1;
        let mut failure = String::new();

        for (n, (kind, credentials)) in attempts.into_iter().enumerate() {
            log::info!("logging in with {kind}");
            // Seed the cache, then hand the cached copy to `connect` with
            // storing switched off. Letting `connect` store instead would
            // overwrite the cache with the handshake's own blob, and what is in
            // the cache is what gets sent as the stored credential when login5
            // issues the access point's HTTP token.
            let cache = cache.clone();
            cache.save_credentials(&credentials);
            let session = Session::new(session_config.clone(), Some(cache));

            let watch_tx = watch_tx.clone();
            crate::remote::watch(&session, move || {
                let _ = watch_tx.send(Pump::Cluster);
            });

            // No `session.connect` here: `Spirc::new` registers its dealer
            // listeners and then connects the session itself. Connecting first
            // authenticated twice and left the second attempt reporting
            // "Session is not connected", which is what this looked like from
            // the outside.

            // Read per attempt: the getter is a box the player takes, so one
            // made in advance would be gone by the second time round.
            let player = Player::new(
                player_config.clone(),
                session.clone(),
                mixer.get_soft_volume(),
                move || Box::new(crate::sink::AndroidSink::new(AudioFormat::S16, generation)),
            );

            let connected = Spirc::new(
                connect_config.clone(),
                session.clone(),
                credentials.clone(),
                player.clone(),
                mixer_for_spirc.clone(),
            )
            .await;

            let (spirc, spirc_task) = match connected {
                Ok(pair) => pair,
                Err(e) => {
                    session.shutdown();
                    failure = format!("connect failed: {e}");
                    // A kept credential the account no longer honours — the
                    // password changed, the device was removed from the list —
                    // is a dead end, not a reason to stop: it is thrown away so
                    // the token behind it gets its turn, and so the next launch
                    // does not try it again.
                    if n < last {
                        log::warn!("{failure}, trying the next credential");
                        let _ = std::fs::remove_dir_all(
                            std::path::Path::new(&recipe.credentials_dir).join("reusable"),
                        );
                        continue;
                    }
                    return Err(failure);
                }
            };

            // Spirc connects with credential storing switched on, so the cache
            // now holds the blob the access point answered with. That is the
            // one worth keeping, and the seed goes back into the cache after it
            // is taken: login5 signs its stored-credential request with the
            // session's client id, and the pair that is known to agree is the
            // one this session actually logged in with.
            if let Some(cache) = session.cache() {
                if let Some(reusable) = cache.credentials() {
                    kept.save_credentials(&reusable);
                }
                cache.save_credentials(&credentials);
            }

            // Checked here rather than left to the library. The vendored
            // librespot-core used to call exit(1) on a non-premium account,
            // which took the app's process down and left Android restarting the
            // service in a loop; it now only logs, so the refusal has to be
            // made an error the caller can show.
            if let Some(account_type) = session.get_user_attribute("type") {
                if account_type != "premium" {
                    session.shutdown();
                    return Err(PREMIUM_REQUIRED.to_string());
                }
            }

            return Ok::<_, String>((session, player, spirc, spirc_task));
        }

        Err(failure)
    })?;

    // The event loop runs for the life of the bundle: it is what answers the
    // dealer, so without it the device appears once and then goes stale.
    rt.spawn(async move {
        spirc_task.await;
        if GENERATION.load(Ordering::SeqCst) != generation {
            log::info!("connect device {generation} stopped, already replaced");
            return;
        }
        // The authoritative moment the Connect device is gone.
        //
        // Not a failed command, which is what this used to key off and why the
        // repair never fired: when the task ends, its channel still accepts
        // messages and nobody reads them. A load sent afterwards returns Ok,
        // nothing loads, and the previous track plays on. Watching the task
        // itself is the only signal that does not depend on being lucky enough
        // to get an error back.
        log::info!("connect device stopped");
        SPIRC_LOST.store(true, Ordering::SeqCst);
    });

    // The pump reports listening history through the session, so it is told
    // about this one before any of its events can arrive.
    let _ = events_tx.send(Pump::Session(session.clone()));
    spawn_event_forwarder(rt, player.clone(), events_tx.clone(), generation);

    // A fresh bundle has a live Connect device again, whatever the last one
    // ended up as.
    SPIRC_LOST.store(false, Ordering::SeqCst);

    Ok(Bundle {
        session,
        player,
        spirc,
    })
}

/// Which bundle is current. See the note where it is taken.
static GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// The current bundle's number, for anything holding an older one.
pub(crate) fn live_generation() -> u64 {
    GENERATION.load(Ordering::SeqCst)
}

/// Feeds one player's events to the engine-wide pump, and preloads ahead of it.
///
/// Ends on its own when the player is dropped, which is what a reconnection does
/// to it. The generation check is for the events already in flight at that
/// moment: a dying player emits a `Stopped`, and delivered late that would tell
/// Kotlin the new player had stopped before it started.
fn spawn_event_forwarder(
    rt: &Runtime,
    player: Arc<Player>,
    events_tx: std::sync::mpsc::Sender<Pump>,
    generation: u64,
) {
    let mut events = player.get_player_event_channel();
    rt.spawn(async move {
        // The track whose successor has already been fetched.
        let mut preloaded: Option<String> = None;

        while let Some(event) = events.recv().await {
            if GENERATION.load(Ordering::SeqCst) != generation {
                break;
            }

            match &event {
                PlayerEvent::Playing { .. } => LOCAL_PLAYING.store(true, Ordering::SeqCst),
                PlayerEvent::Paused { .. } | PlayerEvent::Stopped { .. } => {
                    LOCAL_PLAYING.store(false, Ordering::SeqCst)
                }
                _ => {}
            }

            // The track the app asked for is on its way: let it be heard.
            // `Loading` rather than `Playing`, so nothing of its opening is lost
            // while the two sides agree the silence is over.
            if let PlayerEvent::Loading { track_id, .. } | PlayerEvent::Playing { track_id, .. } =
                &event
            {
                track_started(&uri_string(track_id));
            }

            // Warm up the next track — but not the instant this one starts.
            //
            // Preloading on the first note meant a run of skips fired a burst
            // of audio-key requests, Spotify refused them ("error audio key"),
            // and librespot went on to decode the still-encrypted bytes:
            // megabytes of noise through the decoder, thousands of log lines,
            // and an app that looked frozen. A few seconds in, a track is one
            // the listener is actually on, and a skipped-past track asks for
            // nothing.
            if let PlayerEvent::PositionChanged {
                track_id,
                position_ms,
                ..
            } = &event
            {
                let uri = uri_string(track_id);
                if *position_ms > PRELOAD_AFTER_MS && preloaded.as_deref() != Some(&uri) {
                    preload_after(&player, &uri);
                    preloaded = Some(uri);
                }
            }

            if events_tx.send(Pump::Event(event)).is_err() {
                break;
            }
        }
        log::info!("event forwarder {generation} ended");
    });
}

/// Changes what the player is built with, and builds it again.
///
/// The bitrate and the crossfade belong to the player's configuration, which is
/// fixed for the life of a player, so a new value means a new player. That used
/// to mean tearing the whole engine down from the Kotlin side, and that is the
/// call that aborted the process on a destroyed mutex: the runtime cannot be
/// dropped from a JNI thread while librespot's own threads are still inside it.
///
/// Replacing the bundle does the same job without touching the runtime or the
/// audio output. The queue is not restored here; the caller owns that.
pub fn set_quality(bitrate_kbps: i32, crossfade_ms: i32) -> EngineResult<()> {
    {
        let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
        let engine = guard.as_mut().ok_or("engine not started")?;
        engine.recipe.player_config.bitrate = match bitrate_kbps {
            96 => Bitrate::Bitrate96,
            160 => Bitrate::Bitrate160,
            _ => Bitrate::Bitrate320,
        };
        engine.recipe.player_config.crossfade_duration_ms = crossfade_ms.max(0) as u32;
    }
    reconnect()
}

/// Changes the quality of what is asked for next, without rebuilding anything.
///
/// The bitrate is read while a track is being loaded, to put the formats it
/// exists in into order of preference, and nowhere else. Rebuilding the session
/// for it — which is what [`set_quality`] does, because the crossfade beside it
/// really is fixed for the life of a player — costs a second of silence, so
/// following a connection as it changes was never worth doing. This is the same
/// change at the cost of a message.
///
/// What is playing keeps the file it started with. The next track gets this.
pub fn set_bitrate(bitrate_kbps: i32) -> EngineResult<()> {
    let bitrate = match bitrate_kbps {
        96 => Bitrate::Bitrate96,
        160 => Bitrate::Bitrate160,
        _ => Bitrate::Bitrate320,
    };
    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_mut().ok_or("engine not started")?;
    // Kept on the recipe as well, so a session rebuilt for any other reason
    // starts where the listening left it rather than back at the setting.
    engine.recipe.player_config.bitrate = bitrate;
    if let Some(bundle) = engine.bundle.as_ref() {
        bundle.player.set_bitrate(bitrate);
    }
    Ok(())
}

/// Throws away a bundle and builds another one, leaving the runtime and the
/// audio output alone.
///
/// This is the whole answer to a network drop. librespot's Spirc builder is
/// handed out once per session, so a Connect device that has died cannot be
/// replaced on the session it belonged to; go-librespot reaches the same
/// conclusion and discards the session too. Everything that made rebuilding the
/// engine crash, dropping the tokio runtime from a JNI thread and detaching the
/// Android sink, lives in [`Engine`] and is not touched here.
///
/// The engine lock is held throughout, so commands arriving mid-reconnection
/// wait rather than seeing half a bundle.
pub fn reconnect() -> EngineResult<()> {
    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_mut().ok_or("engine not started")?;
    RECONNECTING.store(true, Ordering::SeqCst);
    // Every path out of here from this point has to clear it, which is what the
    // closure is for: a `?` on the build would otherwise leave the engine
    // looking permanently mid-reconnection.
    let result = (|| {
        // Taken out first, and dropped before the new one is built: two live
        // sessions would be two devices in the account's list, and the old one
        // would go on publishing state for a player nobody is listening to.
        if let Some(old) = engine.bundle.take() {
            log::info!("discarding the old session");
            crate::remote::clear();
            let _ = old.spirc.disconnect(true);
            let _ = old.spirc.shutdown();
            old.player.stop();
            old.session.shutdown();
            // The device is emptied here rather than left to the player's own
            // sink: by the time that one is told to stop, the audio it wrote is
            // already in the AudioTrack, and that is what plays over the start
            // of the next track.
            crate::sink::silence();
        }

        let bundle = build_bundle(&engine.rt, &engine.recipe, &engine.mixer, &engine.events_tx)?;
        engine.bundle = Some(bundle);
        log::info!("reconnected");
        Ok(())
    })();
    RECONNECTING.store(false, Ordering::SeqCst);
    result
}

/// Forward player events to Kotlin. Runs for the life of the engine.
///
/// Deliberately a dedicated OS thread rather than a tokio task. The thread is
/// attached to the JVM once for its whole life, which avoids attaching and
/// detaching on every event and — more importantly — guarantees the listener's
/// `GlobalRef` is dropped while the thread is still attached. A tokio task
/// migrates between workers, so its `GlobalRef` could be released on a detached
/// thread, where JNI cannot free it ("Dropping a GlobalRef in a detached
/// thread").
/// Runs for the life of the engine, across any number of sessions: whichever
/// player is current has a forwarder feeding this one channel.
fn spawn_event_pump(
    events: std::sync::mpsc::Receiver<Pump>,
    listener: GlobalRef,
    device_name: String,
    state_dir: String,
) {
    std::thread::Builder::new()
        .name("squarecore-events".into())
        .spawn(move || {
            let vm = match JAVA_VM.get() {
                Some(vm) => vm,
                None => return,
            };
            // Permanent attach: detached only when this thread exits, by which
            // point `listener` has already been dropped.
            if let Err(e) = vm.attach_current_thread_permanently() {
                log::error!("event pump could not attach to the JVM: {e}");
                return;
            }

            log::info!("event pump started for device {device_name}");
            // What the account's listening history is built from; see events.rs.
            // Built on the first session, which arrives before any of its
            // events, and rebound to each one after that.
            let mut history: Option<History> = None;

            while let Ok(message) = events.recv() {
                let event = match message {
                    Pump::Event(event) => event,
                    // Carries nothing: the Kotlin side reads the state it wants
                    // when it hears there is a new one, rather than having every
                    // field of it pushed through this boundary.
                    Pump::Cluster => {
                        if let Err(e) = emit(&listener, "cluster", "", 0) {
                            log::warn!("dropping a cluster update: {e}");
                        }
                        continue;
                    }
                    Pump::Session(session) => {
                        match history.as_mut() {
                            // The listen in progress survives: the same person
                            // is hearing the same track, and only the socket it
                            // will be reported over has changed.
                            Some(history) => history.rebind(session),
                            None => history = Some(History::new(session, state_dir.clone())),
                        }
                        continue;
                    }
                };

                if let Some(history) = history.as_mut() {
                    history.observe(&event);
                }

                let (kind, uri, position_ms) = match event {
                    PlayerEvent::Playing {
                        track_id,
                        position_ms,
                        ..
                    } => ("playing", uri_string(&track_id), position_ms as i64),
                    PlayerEvent::Paused {
                        track_id,
                        position_ms,
                        ..
                    } => ("paused", uri_string(&track_id), position_ms as i64),
                    PlayerEvent::PositionChanged {
                        track_id,
                        position_ms,
                        ..
                    } => ("position", uri_string(&track_id), position_ms as i64),
                    PlayerEvent::Stopped { track_id, .. } => ("stopped", uri_string(&track_id), 0),
                    PlayerEvent::EndOfTrack { track_id, .. } => {
                        ("end_of_track", uri_string(&track_id), 0)
                    }
                    PlayerEvent::Loading { track_id, .. } => ("loading", uri_string(&track_id), 0),
                    PlayerEvent::Unavailable { track_id, .. } => {
                        ("unavailable", uri_string(&track_id), 0)
                    }
                    // Connect-protocol and volume events are not surfaced yet.
                    _ => continue,
                };

                if let Err(e) = emit(&listener, kind, &uri, position_ms) {
                    log::warn!("dropping event {kind}: {e}");
                }
            }

            // Explicit so the ordering is obvious: the listener must go while
            // this thread is still attached to the JVM.
            drop(listener);
            log::info!("event pump ended");
        })
        .expect("failed to spawn the event pump thread");
}

/// Turns the player's events into the three Spotify wants for a listen.
///
/// Kept beside the pump rather than inside `events` because only here is the
/// order of things known: a track is "finished" when the next one starts, when
/// the player stops, or when it runs off the end, and only one of those is an
/// event that says so.
struct History {
    events: EventService,
    /// Where the in-progress listen is kept, so a killed process loses nothing.
    state_dir: String,
    session_id: String,
    /// The context every listen is attributed to, once one has been started.
    context_uri: String,
    current: Option<Playing>,
    /// Durations seen so far, by track URI.
    ///
    /// `TrackChanged` is the only event carrying one and it arrives *before*
    /// the track starts, so there is nothing to attach it to yet. Held here
    /// until the matching `Playing` shows up. Without this the transition
    /// reported the played time as the track's length — a three-minute song
    /// skipped at forty seconds went to Spotify as a forty-second song played
    /// to the end, and nothing about that was going to be believed.
    durations: std::collections::HashMap<String, u32>,
}

struct Playing {
    uri: String,
    hex: String,
    playback_id: String,
    start_ms: u32,
    position_ms: u32,
    duration_ms: u32,
    started_at: u128,
    reason_start: &'static str,
}

impl History {
    fn new(session: Session, state_dir: String) -> Self {
        let session_id = session.session_id();
        let events = EventService::new(session);
        // Whatever the last run was in the middle of when it went away.
        events::pending::flush(&state_dir, &events);
        History {
            events,
            state_dir,
            session_id: if session_id.is_empty() {
                events::random_id()
            } else {
                session_id
            },
            context_uri: String::new(),
            current: None,
            durations: std::collections::HashMap::new(),
        }
    }

    /// Points the reporting at a new session, keeping the listen in progress.
    ///
    /// The session id changes because Spotify's is per connection, and a report
    /// carrying the dead one's would be filed against a session the account no
    /// longer has. Everything else about the listen is unchanged, which is the
    /// point: the network went away, the music did not.
    fn rebind(&mut self, session: Session) {
        let session_id = session.session_id();
        self.events = EventService::new(session);
        self.session_id = if session_id.is_empty() {
            events::random_id()
        } else {
            session_id
        };
        if !self.context_uri.is_empty() {
            self.events
                .new_session(&self.session_id, &self.context_uri, 1);
        }
    }

    fn observe(&mut self, event: &PlayerEvent) {
        match event {
            PlayerEvent::TrackChanged { audio_item } => {
                // Kept for whenever this track starts; see `durations`. The map
                // is bounded by the queue, and a queue is not unbounded.
                if audio_item.duration_ms > 0 {
                    self.durations
                        .insert(audio_item.uri.clone(), audio_item.duration_ms);
                }
                if let Some(playing) = self.current.as_mut() {
                    if playing.uri == audio_item.uri {
                        playing.duration_ms = audio_item.duration_ms;
                    }
                }
            }

            PlayerEvent::Playing {
                track_id,
                position_ms,
                ..
            } => self.start(uri_string(track_id), *position_ms),

            PlayerEvent::PositionChanged { position_ms, .. }
            | PlayerEvent::Seeked { position_ms, .. } => {
                if let Some(playing) = self.current.as_mut() {
                    playing.position_ms = *position_ms;
                }
                // Once a second, which is what this event arrives at.
                self.remember();
            }

            // A pause ends the stretch that was being listened to.
            //
            // Reported straight away rather than held until the track finishes,
            // because most of the time it never does: the track is paused, the
            // app is left, and the process goes away with the listen still
            // sitting in memory. That is why only one of a session's tracks
            // ever reached the account's history.
            //
            // Resuming opens a *new* playback at the position it resumes from,
            // so what is reported is always a stretch that really was heard —
            // never one invented to make a pause look like a full play.
            PlayerEvent::Paused { position_ms, .. } => {
                if let Some(playing) = self.current.as_mut() {
                    playing.position_ms = *position_ms;
                }
                // Not when the pause is the track running out: the end of a
                // track is reported as one, and reporting it here first would
                // take the completed listen away and leave a stopped one in its
                // place.
                let at_end = self
                    .current
                    .as_ref()
                    .map(|playing| {
                        playing.duration_ms > 0
                            && playing.position_ms + END_MARGIN_MS >= playing.duration_ms
                    })
                    .unwrap_or(false);
                if !at_end {
                    self.finish(EndReason::EndPlay);
                }
            }

            // Ran to the end: the one that counts as a full listen.
            PlayerEvent::EndOfTrack { .. } => self.finish(EndReason::TrackDone),
            PlayerEvent::Stopped { .. } => self.finish(EndReason::EndPlay),
            _ => {}
        }
    }

    fn start(&mut self, uri: String, position_ms: u32) {
        if let Some(playing) = self.current.as_ref() {
            // Un-pausing and seeking both arrive as `Playing` on a track that is
            // already open, and neither is a new listen.
            if playing.uri == uri {
                if let Some(playing) = self.current.as_mut() {
                    playing.position_ms = position_ms;
                }
                return;
            }
            // A different track without an end event: something skipped.
            self.finish(EndReason::Forward);
        }

        let hex = hex_id(&uri);
        if hex.is_empty() {
            return;
        }

        // The playlist or album this queue came from, or the track itself when
        // it came from neither — which is what the official client sends for a
        // track played on its own.
        let context = match current_context() {
            context if context.is_empty() => uri.clone(),
            context => context,
        };
        if self.context_uri != context {
            self.context_uri = context;
            // A context is a session: leaving one and starting another is a new
            // session id, not a continuation of the last.
            self.session_id = events::random_id();
            self.events
                .new_session(&self.session_id, &self.context_uri, 1);
        }

        let playback_id = events::random_id();
        self.events.new_playback(&playback_id, &self.session_id);

        let duration_ms = self.durations.get(&uri).copied().unwrap_or(0);
        self.current = Some(Playing {
            uri,
            hex,
            playback_id,
            start_ms: position_ms,
            position_ms,
            duration_ms,
            started_at: events::now_ms(),
            reason_start: "playbtn",
        });
        self.remember();
    }

    /// Keeps the in-progress listen on disk; see `events::pending`.
    fn remember(&self) {
        let Some(playing) = self.current.as_ref() else {
            return;
        };
        events::pending::write(
            &self.state_dir,
            &events::pending::Pending {
                track_hex: playing.hex.clone(),
                playback_id: playing.playback_id.clone(),
                context_uri: self.context_uri.clone(),
                start_ms: playing.start_ms,
                position_ms: playing.position_ms,
                duration_ms: playing.duration_ms,
                started_at: playing.started_at,
            },
        );
    }

    fn finish(&mut self, reason: EndReason) {
        let Some(playing) = self.current.take() else {
            return;
        };
        // A stretch too short to have been listened to at all — a pause landing
        // in the same instant as the start, a track that failed to open.
        if playing.position_ms.saturating_sub(playing.start_ms) < MIN_LISTEN_MS
            && !matches!(reason, EndReason::TrackDone)
        {
            return;
        }
        // Running off the end means the whole track was heard, whatever the
        // last position report happened to say.
        let end_ms = match reason {
            EndReason::TrackDone if playing.duration_ms > 0 => playing.duration_ms,
            _ => playing.position_ms.max(playing.start_ms),
        };
        let duration_ms = if playing.duration_ms > 0 {
            playing.duration_ms
        } else {
            end_ms
        };

        // Sent, so it must not be sent again on the next start.
        events::pending::clear(&self.state_dir);

        self.events.track_transition(&Listen {
            track_hex: &playing.hex,
            playback_id: &playing.playback_id,
            context_uri: &self.context_uri,
            start_ms: playing.start_ms,
            end_ms,
            duration_ms,
            reason_start: playing.reason_start,
            reason_end: reason,
            started_at: playing.started_at,
        });
    }
}

/// How close to the end counts as the track finishing rather than being paused.
const END_MARGIN_MS: u32 = 2_000;

/// Below this, a stretch is a mis-tap rather than a listen.
const MIN_LISTEN_MS: u32 = 1_000;

/// A track's gid in hex, which is what the events carry — not the base62 id.
fn hex_id(uri: &str) -> String {
    match SpotifyUri::from_uri(uri) {
        Ok(SpotifyUri::Track { id }) => id.to_base16().unwrap_or_default(),
        _ => String::new(),
    }
}

fn uri_string(uri: &SpotifyUri) -> String {
    uri.to_uri().unwrap_or_default()
}

/// Invoke `NativeEvents.onEvent` from a tokio worker thread.
fn emit(listener: &GlobalRef, kind: &str, uri: &str, position_ms: i64) -> Result<(), String> {
    let vm = JAVA_VM.get().ok_or("JNI_OnLoad did not run")?;
    // Tokio worker threads are not known to the VM, so they must attach. The
    // guard detaches on drop, which keeps the thread's local ref table bounded.
    let mut env = vm
        .attach_current_thread()
        .map_err(|e| format!("attach failed: {e}"))?;

    let j_kind = env.new_string(kind).map_err(|e| e.to_string())?;
    let j_uri = env.new_string(uri).map_err(|e| e.to_string())?;

    env.call_method(
        listener,
        "onEvent",
        "(Ljava/lang/String;Ljava/lang/String;J)V",
        &[
            JValue::Object(&j_kind),
            JValue::Object(&j_uri),
            JValue::Long(position_ms),
        ],
    )
    .map_err(|e| e.to_string())?;

    // A Kotlin listener that throws would otherwise leave the exception pending
    // and poison every later JNI call on this thread.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        return Err("listener threw".into());
    }
    Ok(())
}

/// Hands a whole queue to the Connect device.
///
/// A list rather than one track at a time, which is what this did before. Spirc
/// publishes the queue as well as the current item, so loading track by track
/// would show the account a device that never has anything coming up — and it
/// is Spirc, not the caller, that advances at the end of a track.
///
/// `index` selects the starting track; anything out of range starts at the
/// beginning, which is librespot's own behaviour rather than an error.
/// The playlist or album the current queue came from, for the listening events.
///
/// A global rather than a field on the engine: the event pump reads it from its
/// own thread, and it changes whenever a queue is loaded — which is a different
/// lock from the one that owns playback.
static CONTEXT_URI: Mutex<String> = Mutex::new(String::new());

/// The queue as the app handed it over, in play order.
///
/// Kept so the engine can fetch the next track before it is asked for; see
/// [`preload_after`].
static QUEUE: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// How much of the phone the cached audio may take: 512 MB.
const AUDIO_CACHE_LIMIT: u64 = 512 * 1024 * 1024;

/// Deletes the temporary files left behind by downloads that never finished.
///
/// librespot writes each download to a `.tmp…` file beside the cache and
/// renames it when it completes; a skip mid-download leaves the temporary file
/// where it is, several megabytes at a time, for ever.
fn sweep_stale_downloads(dir: &str) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let mut removed = 0;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        if !name.starts_with(".tmp") {
            continue;
        }
        // Only the ones nothing is writing any more. An hour is far longer than
        // any download and short enough that they do not pile up.
        let stale = entry
            .metadata()
            .and_then(|meta| meta.modified())
            .map(|modified| {
                modified
                    .elapsed()
                    .map(|age| age.as_secs() > 3600)
                    .unwrap_or(false)
            })
            .unwrap_or(false);
        if stale && std::fs::remove_file(entry.path()).is_ok() {
            removed += 1;
        }
    }
    if removed > 0 {
        log::info!("swept {removed} unfinished downloads");
    }
}

/// What `start` returns for an account Spotify does not stream to this client.
///
/// Matched by the Kotlin side, so it stays a stable string.
pub const PREMIUM_REQUIRED: &str = "premium account required";

/// How far into a track its successor is fetched, in milliseconds.
const PRELOAD_AFTER_MS: u32 = 5_000;

/// Warms up the track after `uri`, so a skip does not start from nothing.
///
/// librespot preloads on its own, but only in the last thirty seconds of a
/// track — that is for gapless playback, and it does nothing for someone
/// skipping. Between the tap and the first sound there was most of a second of
/// audio key and first chunk; fetched now, while the current track plays, the
/// skip has them already.
fn preload_after(player: &Player, uri: &str) {
    let next = {
        let Ok(queue) = QUEUE.lock() else { return };
        let Some(position) = queue.iter().position(|item| item == uri) else {
            return;
        };
        queue.get(position + 1).cloned()
    };
    let Some(next) = next else { return };
    if let Ok(parsed) = SpotifyUri::from_uri(&next) {
        player.preload(parsed);
    }
}

pub fn current_context() -> String {
    CONTEXT_URI.lock().map(|c| c.clone()).unwrap_or_default()
}

pub fn load_queue(
    uris: Vec<String>,
    index: u32,
    start_playing: bool,
    position_ms: u32,
    context_uri: String,
    play_as_context: bool,
) -> EngineResult<()> {
    if uris.is_empty() {
        return Err("empty queue".into());
    }
    for uri in &uris {
        let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
        if !parsed.is_playable() {
            return Err(format!("{uri} is not a playable item"));
        }
    }

    // Read before the request takes the list: what the load asks for is also
    // what the output waits for after a reconnection.
    let wanted = uris.get(index as usize).cloned().unwrap_or_default();

    let context = context_uri.clone();
    if let Ok(mut stored) = CONTEXT_URI.lock() {
        *stored = context_uri;
    }
    if let Ok(mut stored) = QUEUE.lock() {
        *stored = uris.clone();
    }

    let options = LoadRequestOptions {
        start_playing,
        seek_to: position_ms,
        playing_track: Some(PlayingTrack::Index(index)),
        ..LoadRequestOptions::default()
    };

    // Handed over as the context itself when the queue really is one.
    //
    // A list of URIs is not a place: played that way, the account sees a queue
    // of loose tracks, other devices see no playlist, and the listen has
    // nowhere to be filed. The caller says when the two agree — the rows on
    // screen being the playlist in its own order — because only it can know.
    let request = if play_as_context {
        let start = uris
            .get(index as usize)
            .cloned()
            .map(PlayingTrack::Uri)
            .or(Some(PlayingTrack::Index(index)));
        LoadRequest::from_context_uri(
            context,
            LoadRequestOptions {
                playing_track: start,
                ..options
            },
        )
    } else if !context.is_empty() {
        // The tracks in the order on screen, published under the playlist they
        // came from.
        //
        // Shuffling a playlist, or adding a track to it, makes a queue that is
        // the playlist without being the playlist's order, and upstream has no
        // shape for that: either the context, whose order is Spotify's, or a
        // list of tracks, published as `spotify:web-api`. The second is what
        // this app sent nearly always, and it is a context no other device can
        // resolve: handing playback to one gave it a queue that would not play,
        // and taking it back arrived with a track that could not be found. See
        // native/vendor/README.md.
        LoadRequest::from_tracks_in(context, uris, options)
    } else {
        LoadRequest::from_tracks(uris, options)
    };

    // A queue that nobody asked to hear does not go out while the account is
    // playing somewhere else.
    //
    // A load activates this device, which is right when the listener has just
    // tapped a track and wrong every other time. Opening the app restores the
    // last queue, paused, and that restore was taking the session away from
    // whatever was playing in the other room: Square went silent-but-active and
    // the music stopped mid-track.
    //
    // Refused rather than quietly dropped, so the caller keeps its queue marked
    // as one the engine has never seen and hands it over again the moment the
    // listener really does ask for it.
    if !start_playing && elsewhere_active() {
        return Err("another device has playback".into());
    }

    // Nothing but this track may be heard until it starts; see PLAYBACK_ARMED.
    if !wanted.is_empty() {
        shut(Gate::Expect(wanted.clone()));
    }

    with_bundle(|e| {
        // Without this the device is registered but idle, and a load is
        // ignored: playback belongs to whichever device the account has
        // active, and taking that over is an explicit step.
        e.spirc.activate()?;
        // Shuffle off before the list goes in, every time.
        //
        // The order handed over here is the order the app is showing, shuffled
        // or not: the app draws its own random order and the engine is only
        // asked to play the result. But the Connect state carries a shuffle
        // flag of the account's, which another client may have set and which
        // survives in the state this device restores, and when a context
        // resolves with that flag on librespot shuffles it again for itself.
        // The engine then played its own order while the screen followed
        // another, so a skip stepped somewhere the listener could not see and
        // whole tracks appeared to be missed.
        e.spirc.shuffle(false)?;
        e.spirc.load(request)
    })?
    .inspect_err(|_| {
        // A load has no way around Spirc: choosing what to decode next is the
        // Connect device's job and nothing else can do it. A refused one is a
        // second, weaker sign of the same loss the task watcher above catches
        // properly, kept because it costs nothing.
        SPIRC_LOST.store(true, Ordering::SeqCst);
    })
    .map_err(|e| format!("load failed: {e}"))
}

/// Runs a transport command through Spirc, and through the player if Spirc is gone.
///
/// Spirc and the player are separate things: the first is the Connect device,
/// the second is what decodes audio and writes it to the sink. Every transport
/// command goes through Spirc so the account sees it, but a Spirc command is a
/// message into a task, and when that task has died the channel is closed and
/// the command comes back an error while the player carries on making sound.
///
/// That is the "pause does nothing and only killing the app stops the music"
/// failure. It cannot be fixed by reporting the error, because the audio is
/// still playing either way: something has to reach the thing making it. So the
/// player is told directly, and the account is left with a stale idea of what
/// this device is doing until the engine is rebuilt. Silence under the user's
/// finger is worth more than a tidy Connect state.
fn transport(
    what: &str,
    via_spirc: impl FnOnce(&Bundle) -> Result<(), librespot_core::Error>,
    via_player: impl FnOnce(&Bundle),
) -> EngineResult<()> {
    // Asked before trying, not after failing.
    //
    // A command sent to a Spirc task that has already ended does not come back
    // an error: the channel still accepts the message and nobody ever reads it.
    // Waiting for a failure meant the fallback almost never ran, which is why
    // pause went on doing nothing with the network gone.
    // Another device has the account's playback: this one must not reach for it.
    //
    // Every command here activates the device first, which is right when the
    // listener is asking this phone to play and catastrophic when they are not.
    // A pause sent while a speaker was playing took the session away from the
    // speaker and then stopped it, which is what closing the app did to the
    // music in the other room, and what opening it did on the way in.
    //
    // The player is still told, because the point of a pause is silence here.
    // Spirc is not, so nothing is taken from anyone.
    if elsewhere_active() {
        return with_bundle(|engine| {
            log::info!("{what}: another device has playback, keeping this one to itself");
            via_player(engine);
        });
    }

    if spirc_lost() {
        return with_bundle(|engine| {
            log::warn!("{what}: the connect device is gone, going straight to the player");
            via_player(engine);
        });
    }

    with_bundle(|engine| match {
        // Taken over first, the same way a load does.
        //
        // A device that is registered but not the account's active one drops
        // every transport command on the floor, and says so at warning level:
        // "SpircCommand::Play will be ignored while Not Active". Nothing came
        // back as an error, so the app saw a pause that had been accepted and a
        // player that went on regardless. The load path has taken this step
        // since it was written; the transport path never did.
        let _ = engine.spirc.activate();
        via_spirc(engine)
    } {
        Ok(()) => Ok(()),
        Err(e) => {
            log::warn!("{what}: spirc refused it ({e}), going straight to the player");
            SPIRC_LOST.store(true, Ordering::SeqCst);
            via_player(engine);
            Ok(())
        }
    })?
}

/// Whether this device is decoding audio right now.
///
/// The plainest fact available, and the one that settles the argument when the
/// other two sources disagree; see [`elsewhere_active`].
static LOCAL_PLAYING: AtomicBool = AtomicBool::new(false);

/// Whether the account's playback belongs to another device.
///
/// Asked of librespot rather than of the cluster, because the two disagree for
/// as long as it takes the previous device to let go. After playback is
/// transferred *to* this phone, the account can go on naming the device it came
/// from: trusting that meant refusing to play on a device that was already
/// playing, and every guard in here reading the wrong answer at once.
///
/// The cluster is still the fallback, for the moment before there is a device
/// at all.
pub fn elsewhere_active() -> bool {
    // Sound coming out of this phone settles it.
    //
    // The other two answers can both be wrong at once, and were: after
    // playback was taken back here, the account still named the device it came
    // from and the Connect state had not caught up either, so a pause pressed
    // on a phone that was playing went out to a laptop that was not. Whatever
    // the bookkeeping says, the device making the sound is the device the
    // buttons belong to.
    if LOCAL_PLAYING.load(Ordering::SeqCst) {
        return false;
    }

    match with_bundle(|bundle| bundle.spirc.is_active()) {
        Ok(true) => false,
        Ok(false) => crate::remote::elsewhere_active(),
        Err(_) => crate::remote::elsewhere_active(),
    }
}

/// What the Connect state says this device is playing, as JSON.
///
/// `{"contextUri", "trackUri", "index", "tracks"}`, any of which can be empty.
///
/// The track list is the queue as the account handed it over, which is the
/// only copy of it for a context this app cannot read for itself.
///
/// Asked when another client has driven this device somewhere the app did not
/// send it. The account does not push this device a cluster update about its
/// own playing, so there is nothing to read from the outside: this comes from
/// the Connect state itself, which is the same thing the other devices are
/// shown.
pub fn playing_here() -> EngineResult<String> {
    let playing = with_bundle(|bundle| bundle.spirc.playing())?;
    let escape = |value: &str| serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into());
    let tracks: Vec<String> = playing.tracks.iter().map(|uri| escape(uri)).collect();
    Ok(format!(
        "{{\"contextUri\":{},\"trackUri\":{},\"index\":{},\"tracks\":[{}]}}",
        escape(&playing.context_uri),
        escape(&playing.track_uri),
        playing.index,
        tracks.join(","),
    ))
}

pub fn play() -> EngineResult<()> {
    // Nothing happens here while the music is somewhere else. A play that
    // arrives then is Android's, not the listener's: focus coming back, a
    // service waking. The listener's own play is sent to the device that is
    // actually playing, by the app, and never reaches this.
    if elsewhere_active() {
        log::info!("play: another device has playback, ignoring");
        return Ok(());
    }
    transport("play", |e| e.spirc.play(), |e| e.player.play())
}

pub fn pause() -> EngineResult<()> {
    // The sound stops here, before anything is asked of the network.
    //
    // A pause is a message to the Connect task, and that task also talks to
    // Spotify: while it is waiting on a request it does not read its own
    // channel, so on a weak signal the music went on playing for seconds after
    // the button. The player is the thing making the noise and it is right
    // here, so it is told first and the account is told whenever it can be.
    // Pausing a player that is already paused, or one that was never playing
    // because the music is on another device, costs nothing.
    let _ = with_bundle(|engine| engine.player.pause());

    transport("pause", |e| e.spirc.pause(), |e| e.player.pause())
}

pub fn stop() -> EngineResult<()> {
    // Disconnecting rather than stopping the player: it tells the account this
    // device has given up playback, which is what makes it disappear from the
    // device list instead of lingering as a paused phantom. The fallback does
    // stop the player, because a stop that leaves audio running is not a stop.
    transport("stop", |e| e.spirc.disconnect(true), |e| e.player.stop())
}

pub fn seek(position_ms: u32) -> EngineResult<()> {
    with_bundle(|e| e.spirc.set_position_ms(position_ms))?.map_err(|e| format!("seek failed: {e}"))
}

// Skipping is a Spirc command like the rest, and it was the one left out of
// [`transport`]: with the device gone it was accepted, discarded, and the track
// that was already playing carried on. Stopping is the fallback because there
// is nothing better available here. The player owns no queue, so it cannot pick
// the next track itself, and going on with the current one is the one answer
// that is certainly wrong: the listener asked for something else.
pub fn next() -> EngineResult<()> {
    leaving();
    transport("next", |e| e.spirc.next(), |e| e.player.stop())
}

pub fn previous() -> EngineResult<()> {
    leaving();
    transport("previous", |e| e.spirc.prev(), |e| e.player.stop())
}

/// Shuts the output until the track being played is a different one.
///
/// A skip is a message to the Connect device and the music changes about a
/// second later; the old track is decoded for the whole of that second, and the
/// fade the app wraps a skip in is long over by then. That was the previous song
/// coming back at full volume before the new one cut in.
fn leaving() {
    let current = current_uri();
    if !current.is_empty() {
        shut(Gate::Leave(current));
    }
}

/// Republishes what is playing as the context it came from.
///
/// Only useful in one moment, just before playback is handed to another device.
///
/// A queue that is not a playlist in its own order goes to the engine as a bare
/// list of URIs, and librespot has to invent a context for it: `spotify:web-api`,
/// which is what the logs call `type: Default`. That is fine here, where the
/// order on screen is the order that plays, and useless to anyone else: a device
/// receiving the handover gets a context it cannot resolve, so its bar moves and
/// nothing comes out.
///
/// So the same music is loaded once more as the real playlist, at the same track
/// and the same second, and only then is the handover sent. The cost is that a
/// shuffled queue continues over there in Spotify's order rather than in the one
/// that was on screen: the alternative is handing over something that does not
/// play at all.
pub fn publish_context(position_ms: u32) -> EngineResult<bool> {
    let current = current_uri();
    if current.is_empty() {
        return Ok(false);
    }

    // Nothing to do in the ordinary case, which is what makes a handover quick.
    //
    // Loads now carry the playlist they came from even when the order is this
    // app's own, so the state is already something another device can resolve.
    // Reloading anyway cost a restart of the audio and a wait, on every single
    // change of device, to republish what was published already.
    if !current_context().is_empty() {
        return Ok(false);
    }

    // The track itself, since there is no playlist behind this queue.
    //
    // A track is a context: it is what the official client publishes when a
    // single song is played out of a search, and unlike `spotify:web-api` it is
    // something the other end can resolve. Without this a queue with no
    // playlist behind it, which is most of what a search produces, handed over
    // as a context that answers 400 and left the receiving device with nothing
    // to play.
    let context = current.clone();

    log::info!("republishing {current} as {context} before handing over");
    let request = LoadRequest::from_context_uri(
        context,
        LoadRequestOptions {
            start_playing: true,
            seek_to: position_ms,
            playing_track: Some(PlayingTrack::Uri(current)),
            ..LoadRequestOptions::default()
        },
    );

    with_bundle(|e| {
        e.spirc.activate()?;
        e.spirc.load(request)
    })?
    .map_err(|e| format!("could not republish the context: {e}"))?;
    Ok(true)
}

/// Starts playing here, at the track and second another device was on.
///
/// The whole handover in one call, so it can happen before anything is
/// resolved. The app used to fetch the entire context first, which for a long
/// playlist is a second or two of nothing happening at all, with no way for the
/// listener to tell the request from a request that was lost. The queue on
/// screen catches up afterwards, from the state this load publishes.
pub fn resume_here(context_uri: &str, track_uri: &str, position_ms: u32) -> EngineResult<()> {
    if track_uri.is_empty() {
        return Err("nothing to resume".into());
    }

    let context = if context_uri.is_empty() {
        track_uri.to_string()
    } else {
        context_uri.to_string()
    };
    log::info!("resuming {track_uri} here, from {context} at {position_ms}ms");

    // Anything the previous device was decoding stays out until this track
    // starts; see PLAYBACK_ARMED.
    shut(Gate::Expect(track_uri.to_string()));

    let request = LoadRequest::from_context_uri(
        context,
        LoadRequestOptions {
            start_playing: true,
            seek_to: position_ms,
            playing_track: Some(PlayingTrack::Uri(track_uri.to_string())),
            ..LoadRequestOptions::default()
        },
    );

    with_bundle(|e| {
        e.spirc.activate()?;
        e.spirc.load(request)
    })?
    .map_err(|e| format!("could not resume here: {e}"))
}

/// Takes the account's playback for this device.
///
/// The local half of the device picker. Choosing another device is a request to
/// the server, addressed from here to there; choosing this one cannot be, since
/// a command sent from a device to itself goes out to the access point and
/// comes back refused. Activating is the same thing done directly.
pub fn take_over() -> EngineResult<()> {
    with_bundle(|e| e.spirc.activate())?.map_err(|e| format!("could not take over: {e}"))
}

/// Hands the device a new running order without touching what is playing.
///
/// The app owns the order — it draws its own shuffle — and when the listener
/// turns that on or off the list changes under a track that is still playing.
/// Reloading the queue would say the same thing at the cost of a gap in the
/// song, for a change that is only ever about what comes after it.
pub fn set_queue_order(uris: Vec<String>, index: u32) -> EngineResult<()> {
    if uris.is_empty() {
        return Err("empty queue".into());
    }
    let at = (index as usize).min(uris.len() - 1);
    let prev = uris[..at].to_vec();
    let next = uris[at + 1..].to_vec();

    if let Ok(mut stored) = QUEUE.lock() {
        *stored = uris;
    }

    with_bundle(|e| e.spirc.set_queue_tracks(prev, next))?
        .map_err(|e| format!("queue order failed: {e}"))
}

pub fn set_shuffle(shuffle: bool) -> EngineResult<()> {
    with_bundle(|e| e.spirc.shuffle(shuffle))?.map_err(|e| format!("shuffle failed: {e}"))
}

/// `repeat_track` takes precedence: the two are separate flags in the protocol.
pub fn set_repeat(repeat_context: bool, repeat_track: bool) -> EngineResult<()> {
    with_bundle(|e| {
        e.spirc.repeat_track(repeat_track)?;
        e.spirc.repeat(repeat_context)
    })?
    .map_err(|e| format!("repeat failed: {e}"))
}

/// `volume` is the raw 0..=65535 range librespot uses.
pub fn set_volume(volume: u16) -> EngineResult<()> {
    with_engine(|e| e.mixer.set_volume(volume))
}

pub fn volume() -> EngineResult<u16> {
    with_engine(|e| e.mixer.volume())
}

/// Whether the Connect device is gone and the engine wants rebuilding.
///
/// Two signals, because one of them is late. The flag is authoritative but only
/// rises when the Spirc task has actually finished, and that took ten seconds
/// after a dropped network in the case this was written for: for those ten
/// seconds the device is already unreachable, every command is accepted and
/// discarded, and the listener is pressing skip at a player that will not move.
///
/// An invalid session is the earlier sign of the same thing. It is checked at
/// the moment a command is issued rather than watched, so a connection that
/// drops and comes back on its own between two commands costs nothing: the
/// question is only ever asked when somebody is waiting for an answer.
/// Neither of these takes the engine lock. A reconnection holds it for the whole
/// of a handshake, and both are asked from the playback looper: waiting there
/// would be seconds of a frozen app for a question that has an answer already.
pub fn spirc_lost() -> bool {
    if RECONNECTING.load(Ordering::SeqCst) {
        // True in the strict sense: there is no device at this instant. It is
        // also the useful answer, because a command issued now would find no
        // bundle to run against.
        return true;
    }
    if SPIRC_LOST.load(Ordering::SeqCst) {
        return true;
    }
    session_invalid().unwrap_or(false)
}

pub fn is_connected() -> bool {
    !RECONNECTING.load(Ordering::SeqCst) && session_invalid().map(|bad| !bad) == Some(true)
}

/// Whether the current session has been invalidated, or `None` if asking would
/// have meant waiting.
fn session_invalid() -> Option<bool> {
    let guard = ENGINE.try_lock().ok()?;
    let bundle = guard.as_ref()?.bundle.as_ref()?;
    Some(bundle.session.is_invalid())
}

/// Tear the engine down. Safe to call when it was never started.
pub fn shutdown() {
    let taken = ENGINE.lock().ok().and_then(|mut g| g.take());
    if let Some(engine) = taken {
        // Nothing may report against a bundle from here on, and the pump is
        // about to go with the sender.
        GENERATION.fetch_add(1, Ordering::SeqCst);
        if let Some(bundle) = engine.bundle {
            // Told to the account before the socket goes: a device that
            // vanishes without disconnecting stays in the user's list until it
            // times out.
            let _ = bundle.spirc.disconnect(true);
            let _ = bundle.spirc.shutdown();
            bundle.player.stop();
            bundle.session.shutdown();
        }
        // Dropped so the pump thread's loop ends and the listener is released
        // while that thread is still attached to the JVM.
        drop(engine.events_tx);
        crate::sink::clear_output();
        // Dropping the runtime from a JNI thread is fine: no tokio context here.
        engine.rt.shutdown_timeout(Duration::from_secs(2));
    }
}
