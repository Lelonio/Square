//! Session + playback engine.
//!
//! One process-wide instance, guarded by a mutex. All librespot work happens on
//! a dedicated multi-thread tokio runtime that outlives individual JNI calls;
//! JNI methods only enqueue commands and return immediately, so the Android main
//! thread is never blocked on network I/O.

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;
use librespot_core::{
    authentication::Credentials, cache::Cache, config::SessionConfig, session::Session,
    spotify_uri::SpotifyUri,
};
use librespot_playback::{
    config::{AudioFormat, Bitrate, PlayerConfig},
    mixer::{softmixer::SoftMixer, Mixer, MixerConfig},
    player::{Player, PlayerEvent},
};
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

    let (session, player) = rt.block_on(async move {
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

        session
            .connect(credentials, false)
            .await
            .map_err(|e| format!("login failed: {e}"))?;

        let player = Player::new(player_config, session.clone(), mixer_volume, move || {
            Box::new(crate::sink::AndroidSink::new(AudioFormat::S16))
        });
        Ok::<_, String>((session, player))
    })?;

    spawn_event_pump(&rt, player.clone(), listener, device_name.to_string());

    *guard = Some(Engine {
        rt,
        session,
        player,
        mixer,
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
fn spawn_event_pump(rt: &Runtime, player: Arc<Player>, listener: GlobalRef, device_name: String) {
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
            while let Some(event) = events.blocking_recv() {
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

pub fn load(uri: &str, start_playing: bool, position_ms: u32) -> EngineResult<()> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
    if !parsed.is_playable() {
        return Err(format!("{uri} is not a playable item"));
    }
    with_engine(|e| e.player.load(parsed, start_playing, position_ms))
}

pub fn play() -> EngineResult<()> {
    with_engine(|e| e.player.play())
}

pub fn pause() -> EngineResult<()> {
    with_engine(|e| e.player.pause())
}

pub fn stop() -> EngineResult<()> {
    with_engine(|e| e.player.stop())
}

pub fn seek(position_ms: u32) -> EngineResult<()> {
    with_engine(|e| e.player.seek(position_ms))
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
        engine.player.stop();
        engine.session.shutdown();
        crate::sink::clear_output();
        // Dropping the runtime from a JNI thread is fine: no tokio context here.
        engine.rt.shutdown_timeout(Duration::from_secs(2));
    }
}
