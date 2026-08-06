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
    config::{AudioFormat, Bitrate, PlayerConfig},
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

pub fn store_java_vm(vm: JavaVM) {
    let _ = JAVA_VM.set(vm);
}

pub struct Engine {
    rt: Runtime,
    session: Session,
    player: Arc<Player>,
    mixer: Arc<SoftMixer>,
    /// The Connect device. Every transport command goes through it; see [`load`].
    spirc: Spirc,
}

/// Errors are flattened to a string because they cross the JNI boundary and the
/// Kotlin side only ever surfaces them as a message.
pub type EngineResult<T> = Result<T, String>;

fn with_engine<T>(f: impl FnOnce(&Engine) -> T) -> EngineResult<T> {
    let guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_ref().ok_or("engine not started")?;
    Ok(f(engine))
}

/// Borrow the authenticated session, e.g. to clone it for a catalogue request.
pub fn with_session<T>(f: impl FnOnce(&Session) -> T) -> EngineResult<T> {
    with_engine(|engine| f(&engine.session))
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
/// `listener` must implement `dev.emanuele.spot.nativecore.NativeEvents`.
pub fn start(
    client_id: &str,
    device_name: &str,
    access_token: &str,
    credentials_dir: &str,
    cache_dir: &str,
    language: &str,
    listener: GlobalRef,
) -> EngineResult<()> {
    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    if guard.is_some() {
        return Err("engine already started".into());
    }

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("spotcore")
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

    let credentials = Credentials::with_access_token(access_token);

    let mixer = Arc::new(
        SoftMixer::open(MixerConfig::default()).map_err(|e| format!("mixer failed: {e}"))?,
    );

    // SoftMixer::open starts at an attenuation factor of 0.5 — half amplitude,
    // about -6 dB — and nothing else ever sets it, so every track played through
    // this engine was quiet by default. The device's own volume keys are the
    // right place to control loudness, so the software mixer is left wide open.
    mixer.set_volume(u16::MAX);

    let player_config = PlayerConfig {
        bitrate: Bitrate::Bitrate320,
        normalisation: true,
        // Dithering runs per sample — 88200 times a second at 44.1 kHz stereo —
        // for a noise-floor benefit nobody hears on a phone. Off, to leave the
        // decoder more headroom against output underruns.
        ditherer: None,
        // Every update crosses JNI and rebuilds the Media3 state on the main
        // thread; once a second is plenty for a seek bar.
        position_update_interval: Some(Duration::from_secs(1)),
        ..PlayerConfig::default()
    };


    // Taken out before the async block so `mixer` itself stays behind for the
    // engine's volume controls.
    let mixer_volume = mixer.get_soft_volume();

    // Everything below has to run inside the runtime: `Session::new` registers
    // timers and sockets with the reactor, and panics with "there is no reactor
    // running" if constructed outside it. Authenticating here too means the
    // caller gets a definite success or failure before it shows a player UI,
    // which is safe because this JNI call is never made on the main thread.
    // A credentials cache is not an optimisation here, it is required for
    // catalogue access. `connect` persists reusable credentials into it, and
    // login5 then issues the access point's HTTP token as a *stored credential*
    // request, which it signs with the session's client id. Without a cache it
    // falls back to the platform default id instead, which no longer matches the
    // id the OAuth token was minted for, and every spclient call fails with
    // "Login request was denied: BAD_REQUEST".
    let cache = Cache::new(
        Some(std::path::Path::new(credentials_dir)),
        None,
        Some(std::path::Path::new(cache_dir)),
        None,
    )
    .map_err(|e| format!("cache failed: {e}"))?;

    // Cloned before the async block takes ownership of the Arc as a `dyn Mixer`.
    let mixer_for_spirc: Arc<dyn Mixer> = mixer.clone();

    let (session, player, spirc, spirc_task) = rt.block_on(async move {
        // Seed the cache with the OAuth credentials, then hand the cached copy
        // to `connect` with storing switched off. Letting `connect` store
        // instead would overwrite the cache with the handshake's reusable
        // credentials, and that blob is what gets sent as the stored credential
        // when login5 issues the access point's HTTP token.
        cache.save_credentials(&credentials);
        let session = Session::new(session_config, Some(cache));
        let credentials = session
            .cache()
            .and_then(|cache| cache.credentials())
            .unwrap_or(credentials);

        // No `session.connect` here: `Spirc::new` registers its dealer
        // listeners and then connects the session itself. Connecting first
        // authenticated twice and left the second attempt reporting "Session is
        // not connected", which is what this looked like from the outside.

        let player = Player::new(player_config, session.clone(), mixer_volume, move || {
            Box::new(crate::sink::AndroidSink::new(AudioFormat::S16))
        });

        // Spirc is what makes this a Spotify Connect device: it publishes the
        // playback state to the account, which is also the only way plays are
        // recorded in listening history — Spotify counts what a device reports,
        // not what it decodes. That is why nothing appeared in the history
        // before this existed.
        //
        // It owns the player from here on. Driving `player` directly as well
        // would play audio the published state knows nothing about, so every
        // transport command below goes through the Spirc handle instead.
        let connect_config = ConnectConfig {
            name: device_name.to_string(),
            // Smartphone rather than the librespot default of Speaker: the icon
            // in the device list should match what the user is holding.
            device_type: DeviceType::Smartphone,
            // Full scale, matching the mixer. Loudness belongs to the phone's
            // own volume keys.
            initial_volume: u16::MAX,
            ..ConnectConfig::default()
        };

        let (spirc, spirc_task) = Spirc::new(
            connect_config,
            session.clone(),
            credentials.clone(),
            player.clone(),
            mixer_for_spirc,
        )
        .await
        .map_err(|e| format!("connect failed: {e}"))?;

        // Spirc connects with credential storing switched on, which replaces
        // the cached OAuth blob with the handshake's reusable one. login5 signs
        // its stored-credential request with the session's client id, so the
        // two have to agree or every catalogue call comes back BAD_REQUEST —
        // putting the original back is what keeps the access point usable.
        if let Some(cache) = session.cache() {
            cache.save_credentials(&credentials);
        }

        Ok::<_, String>((session, player, spirc, spirc_task))
    })?;

    // The event loop runs for the life of the engine: it is what answers the
    // dealer, so without it the device appears once and then goes stale.
    rt.spawn(async move {
        spirc_task.await;
        log::info!("connect device stopped");
    });

    spawn_event_pump(
        &rt,
        player.clone(),
        listener,
        device_name.to_string(),
        session.clone(),
        cache_dir.to_string(),
    );

    *guard = Some(Engine {
        rt,
        session,
        player,
        mixer,
        spirc,
    });
    Ok(())
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
fn spawn_event_pump(
    rt: &Runtime,
    player: Arc<Player>,
    listener: GlobalRef,
    device_name: String,
    session: Session,
    state_dir: String,
) {
    let mut events = player.get_player_event_channel();
    // Keep the player alive for as long as we are reading its events.
    let _ = rt;

    std::thread::Builder::new()
        .name("spotcore-events".into())
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
            let mut history = History::new(session, state_dir);

            while let Some(event) = events.blocking_recv() {
                history.observe(&event);

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

    let context = context_uri.clone();
    if let Ok(mut stored) = CONTEXT_URI.lock() {
        *stored = context_uri;
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
    } else {
        LoadRequest::from_tracks(uris, options)
    };

    with_engine(|e| {
        // Without this the device is registered but idle, and a load is
        // ignored: playback belongs to whichever device the account has
        // active, and taking that over is an explicit step.
        e.spirc.activate()?;
        e.spirc.load(request)
    })?
    .map_err(|e| format!("load failed: {e}"))
}

pub fn play() -> EngineResult<()> {
    with_engine(|e| e.spirc.play())?.map_err(|e| format!("play failed: {e}"))
}

pub fn pause() -> EngineResult<()> {
    with_engine(|e| e.spirc.pause())?.map_err(|e| format!("pause failed: {e}"))
}

pub fn stop() -> EngineResult<()> {
    // Disconnecting rather than stopping the player: it tells the account this
    // device has given up playback, which is what makes it disappear from the
    // device list instead of lingering as a paused phantom.
    with_engine(|e| e.spirc.disconnect(true))?.map_err(|e| format!("stop failed: {e}"))
}

pub fn seek(position_ms: u32) -> EngineResult<()> {
    with_engine(|e| e.spirc.set_position_ms(position_ms))?.map_err(|e| format!("seek failed: {e}"))
}

pub fn next() -> EngineResult<()> {
    with_engine(|e| e.spirc.next())?.map_err(|e| format!("next failed: {e}"))
}

pub fn previous() -> EngineResult<()> {
    with_engine(|e| e.spirc.prev())?.map_err(|e| format!("previous failed: {e}"))
}

pub fn set_shuffle(shuffle: bool) -> EngineResult<()> {
    with_engine(|e| e.spirc.shuffle(shuffle))?.map_err(|e| format!("shuffle failed: {e}"))
}

/// `repeat_track` takes precedence: the two are separate flags in the protocol.
pub fn set_repeat(repeat_context: bool, repeat_track: bool) -> EngineResult<()> {
    with_engine(|e| {
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

pub fn is_connected() -> bool {
    with_engine(|e| !e.session.is_invalid()).unwrap_or(false)
}

/// Tear the engine down. Safe to call when it was never started.
pub fn shutdown() {
    let taken = ENGINE.lock().ok().and_then(|mut g| g.take());
    if let Some(engine) = taken {
        // Told to the account before the socket goes: a device that vanishes
        // without disconnecting stays in the user's list until it times out.
        let _ = engine.spirc.disconnect(true);
        let _ = engine.spirc.shutdown();
        engine.player.stop();
        engine.session.shutdown();
        crate::sink::clear_output();
        // Dropping the runtime from a JNI thread is fine: no tokio context here.
        engine.rt.shutdown_timeout(Duration::from_secs(2));
    }
}
