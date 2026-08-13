//! Raw JNI exports for `dev.lelonio.square.nativecore.NativeBridge`.
//!
//! Every function follows the same contract: do the work, and on failure throw
//! `java.lang.IllegalStateException` with the engine's error message. Kotlin
//! therefore never has to check return codes.

use crate::{catalog, engine, sink};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

const EXCEPTION: &str = "java/lang/IllegalStateException";

/// Return a `String` result to Java, or throw and return null.
///
/// Null is only ever seen by the JVM while an exception is already pending, so
/// Kotlin's non-null return types are not actually violated.
fn string_or_throw(env: &mut JNIEnv, result: engine::EngineResult<String>) -> jstring {
    match result.and_then(|value| {
        env.new_string(value)
            .map_err(|e| format!("could not allocate a Java string: {e}"))
    }) {
        Ok(s) => s.into_raw(),
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            JObject::null().into_raw() as jstring
        }
    }
}

/// Convert an engine result into either a value or a pending Java exception.
///
/// The returned `T` is meaningless once an exception is pending; callers return
/// it anyway because the JVM discards the value as soon as it unwinds.
fn or_throw<T: Default>(env: &mut JNIEnv, result: engine::EngineResult<T>) -> T {
    match result {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            T::default()
        }
    }
}


/// Runs `f`, turning a panic into a Java exception rather than an abort.
///
/// The Rust side owns audio decoding, the Connect state machine and a good deal
/// of reverse-engineered parsing, and a panic in any of it used to end the
/// process: the crash was a track change away and left nothing to read. A
/// panic is still a bug, but it should cost the action, not the app.
fn guard<T: Default>(
    env: &mut JNIEnv,
    what: &str,
    f: impl FnOnce() -> engine::EngineResult<T>,
) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(result) => or_throw(env, result),
        Err(_) => {
            // The payload is not logged: a panic message can carry whatever was
            // being processed, and that is the user's listening.
            log::error!("panic in {what}");
            let _ = env.throw_new(EXCEPTION, format!("{what} failed"));
            T::default()
        }
    }
}

/// [guard] for the calls that answer with a string.
fn guard_string(
    env: &mut JNIEnv,
    what: &str,
    f: impl FnOnce() -> engine::EngineResult<String>,
) -> jstring {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(result) => string_or_throw(env, result),
        Err(_) => {
            log::error!("panic in {what}");
            let _ = env.throw_new(EXCEPTION, format!("{what} failed"));
            JObject::null().into_raw() as jstring
        }
    }
}

fn read_string(env: &mut JNIEnv, s: &JString) -> engine::EngineResult<String> {
    env.get_string(s)
        .map(|v| v.into())
        .map_err(|e| format!("invalid string argument: {e}"))
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeInitContext(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
) {
    guard(&mut env, "InitContext", || engine::init_android_context(&context));
}

/// Register the Kotlin `AudioOutput` the sink writes PCM to.
///
/// Must be called before playback starts; without it the sink has nowhere to
/// send audio and every write fails.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSetAudioOutput(
    mut env: JNIEnv,
    _class: JClass,
    output: JObject,
) {
    // Read outside the guard: these borrow `env`, and the guard borrows it too.
    let output = env
        .new_global_ref(&output)
        .map_err(|e| format!("failed to pin the audio output: {e}"));
    guard(&mut env, "SetAudioOutput", || output.map(sink::set_output));
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    client_id: JString,
    device_name: JString,
    device_id: JString,
    access_token: JString,
    credentials_dir: JString,
    cache_dir: JString,
    language: JString,
    bitrate_kbps: jni::sys::jint,
    crossfade_ms: jni::sys::jint,
    listener: JObject,
) {
    // Every argument is read before the guard: reading borrows `env`, and so
    // does the guard itself.
    let arguments: engine::EngineResult<(
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        jni::objects::GlobalRef,
    )> = (|| {
        Ok((
            read_string(&mut env, &client_id)?,
            read_string(&mut env, &device_name)?,
            read_string(&mut env, &device_id).unwrap_or_default(),
            read_string(&mut env, &access_token)?,
            read_string(&mut env, &credentials_dir)?,
            read_string(&mut env, &cache_dir)?,
            read_string(&mut env, &language).unwrap_or_default(),
            env.new_global_ref(&listener)
                .map_err(|e| format!("failed to pin listener: {e}"))?,
        ))
    })();

    guard(&mut env, "Start", || {
        let (
            client_id,
            device_name,
            device_id,
            access_token,
            credentials_dir,
            cache_dir,
            language,
            listener,
        ) = arguments?;
        engine::start(
            &client_id,
            &device_name,
            &device_id,
            &access_token,
            &credentials_dir,
            &cache_dir,
            &language,
            bitrate_kbps,
            crossfade_ms,
            listener,
        )
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeLoadQueue(
    mut env: JNIEnv,
    _class: JClass,
    uris_json: JString,
    index: jint,
    start_playing: jboolean,
    position_ms: jint,
    context_uri: JString,
    play_as_context: jboolean,
) {
    // JSON rather than a jobjectArray: the Kotlin side already serialises track
    // lists for the catalogue calls, and one decoder is easier to keep correct
    // than two ways of crossing the same boundary.
    let context = read_string(&mut env, &context_uri).unwrap_or_default();
    let raw = match read_string(&mut env, &uris_json) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return;
        }
    };
    guard(&mut env, "LoadQueue", || {
        serde_json::from_str::<Vec<String>>(&raw)
            .map_err(|e| format!("bad queue: {e}"))
            .and_then(|uris| {
            engine::load_queue(
                uris,
                index.max(0) as u32,
                start_playing == JNI_TRUE,
                position_ms.max(0) as u32,
                context,
                play_as_context == JNI_TRUE,
            )
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeNext(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "Next", || engine::next());
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePrevious(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "Previous", || engine::previous());
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSetShuffle(
    mut env: JNIEnv,
    _class: JClass,
    shuffle: jboolean,
) {
    guard(&mut env, "SetShuffle", || engine::set_shuffle(shuffle == JNI_TRUE));
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSetRepeat(
    mut env: JNIEnv,
    _class: JClass,
    repeat_context: jboolean,
    repeat_track: jboolean,
) {
    guard(&mut env, "SetRepeat", || engine::set_repeat(repeat_context == JNI_TRUE, repeat_track == JNI_TRUE));
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePlay(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "Play", || engine::play());
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePause(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "Pause", || engine::pause());
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeStop(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "Stop", || engine::stop());
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSeek(
    mut env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) {
    guard(&mut env, "Seek", || engine::seek(position_ms.clamp(0, u32::MAX as jlong) as u32));
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSetVolume(
    mut env: JNIEnv,
    _class: JClass,
    volume: jint,
) {
    guard(&mut env, "SetVolume", || engine::set_volume(volume.clamp(0, u16::MAX as jint) as u16));
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeVolume(
    mut env: JNIEnv,
    _class: JClass,
) -> jint {
    guard(&mut env, "Volume", || engine::volume().map(|v| v as jint))
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeIsConnected(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine::is_connected() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// --- Catalogue lookups, all over the access point rather than the Web API ---

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeUsername(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "Username", || catalog::username())

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeCollectionUri(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "CollectionUri", || catalog::collection_uri())

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeRootlist(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "Rootlist", || catalog::rootlist())

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeContextTracks(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let uri = match read_string(&mut env, &uri) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return JObject::null().into_raw() as jstring;
        }
    };
    guard_string(&mut env, "ContextTracks", || catalog::context_tracks(&uri))

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePlaylistCover(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let uri = match read_string(&mut env, &uri) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return JObject::null().into_raw() as jstring;
        }
    };
    guard_string(&mut env, "PlaylistCover", || catalog::playlist_cover(&uri))

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeCanvas(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let uri = match read_string(&mut env, &track_uri) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return JObject::null().into_raw() as jstring;
        }
    };
    guard_string(&mut env, "Canvas", || catalog::canvas(&uri))

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeLyrics(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let uri = match read_string(&mut env, &track_uri) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return JObject::null().into_raw() as jstring;
        }
    };
    guard_string(&mut env, "Lyrics", || catalog::lyrics(&uri))

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeTracksMetadata(
    mut env: JNIEnv,
    _class: JClass,
    uris_json: JString,
) -> jstring {
    let json = match read_string(&mut env, &uris_json) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return JObject::null().into_raw() as jstring;
        }
    };
    guard_string(&mut env, "TracksMetadata", || catalog::tracks_metadata(&json))

}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSpircLost(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    engine::spirc_lost() as jboolean
}

/// Rebuilds the player with a new bitrate and crossfade.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSetQuality(
    mut env: JNIEnv,
    _class: JClass,
    bitrate_kbps: jint,
    crossfade_ms: jint,
) {
    guard(&mut env, "SetQuality", || {
        engine::set_quality(bitrate_kbps, crossfade_ms)
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeReconnect(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "Reconnect", engine::reconnect);
}

/// Whether the account is playing on a device that is not this one.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePlaybackElsewhere(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    engine::elsewhere_active() as jboolean
}

/// Republishes the queue as its real context, before handing playback over.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePublishContext(
    mut env: JNIEnv,
    _class: JClass,
    position_ms: jint,
) -> jboolean {
    guard(&mut env, "PublishContext", || {
        engine::publish_context(position_ms.max(0) as u32)
    }) as jboolean
}

/// Starts playing here what another device was playing.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeResumeHere(
    mut env: JNIEnv,
    _class: JClass,
    context_uri: JString,
    track_uri: JString,
    position_ms: jint,
) {
    let context = read_string(&mut env, &context_uri).unwrap_or_default();
    let track = read_string(&mut env, &track_uri);
    guard(&mut env, "ResumeHere", || {
        engine::resume_here(&context, &track?, position_ms.max(0) as u32)
    });
}

/// Takes the account's playback for this device.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeTakeOver(
    mut env: JNIEnv,
    _class: JClass,
) {
    guard(&mut env, "TakeOver", engine::take_over);
}

/// The account's personalised home, as raw JSON from Spotify's gateway.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeHomeFeed(
    mut env: JNIEnv,
    _class: JClass,
    time_zone: JString,
    language: JString,
    hash: JString,
    app_version: JString,
) -> jstring {
    let zone = read_string(&mut env, &time_zone).unwrap_or_default();
    let language = read_string(&mut env, &language).unwrap_or_default();
    let hash = read_string(&mut env, &hash).unwrap_or_default();
    let app_version = read_string(&mut env, &app_version).unwrap_or_default();
    guard_string(&mut env, "HomeFeed", || {
        crate::pathfinder::home(&zone, &language, &hash, &app_version)
    })
}

/// This device's own Connect id.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeDeviceId(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "DeviceId", crate::remote::device_id)
}

/// A new running order for the queue already loaded; see `engine::set_queue_order`.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeSetQueueOrder(
    mut env: JNIEnv,
    _class: JClass,
    uris_json: JString,
    index: jint,
) {
    // JSON, like the load above and for the same reason.
    let raw = match read_string(&mut env, &uris_json) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new(EXCEPTION, message);
            return;
        }
    };
    guard(&mut env, "SetQueueOrder", || {
        serde_json::from_str::<Vec<String>>(&raw)
            .map_err(|e| format!("bad queue: {e}"))
            .and_then(|uris| engine::set_queue_order(uris, index.max(0) as u32))
    });
}

/// What this device itself is playing, straight from its Connect state.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativePlayingHere(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "PlayingHere", engine::playing_here)
}

/// What the account is playing, wherever it is playing it.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeRemoteState(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "RemoteState", || Ok(crate::remote::state_json()))
}

/// Every Connect device the account can see, this one included.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeRemoteDevices(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_string(&mut env, "RemoteDevices", || {
        Ok(crate::remote::devices_json())
    })
}

/// Sends one command, as Spotify's own clients word it, to another device.
#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeRemoteCommand(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
    body: JString,
) {
    let device_id = read_string(&mut env, &device_id);
    let body = read_string(&mut env, &body);
    guard(&mut env, "RemoteCommand", || {
        crate::remote::command(&device_id?, body?)
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeRemoteVolume(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
    volume: jint,
) {
    let device_id = read_string(&mut env, &device_id);
    guard(&mut env, "RemoteVolume", || {
        crate::remote::set_volume(&device_id?, volume.clamp(0, u16::MAX as i32) as u16)
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_lelonio_square_nativecore_NativeBridge_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(engine::shutdown);
}
