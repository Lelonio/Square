//! Raw JNI exports for `dev.emanuele.spot.nativecore.NativeBridge`.
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

fn read_string(env: &mut JNIEnv, s: &JString) -> engine::EngineResult<String> {
    env.get_string(s)
        .map(|v| v.into())
        .map_err(|e| format!("invalid string argument: {e}"))
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeInitContext(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
) {
    let result = engine::init_android_context(&context);
    or_throw(&mut env, result);
}

/// Register the Kotlin `AudioOutput` the sink writes PCM to.
///
/// Must be called before playback starts; without it the sink has nowhere to
/// send audio and every write fails.
#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeSetAudioOutput(
    mut env: JNIEnv,
    _class: JClass,
    output: JObject,
) {
    let result = env
        .new_global_ref(&output)
        .map_err(|e| format!("failed to pin the audio output: {e}"))
        .map(sink::set_output);
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    client_id: JString,
    device_name: JString,
    access_token: JString,
    credentials_dir: JString,
    cache_dir: JString,
    language: JString,
    listener: JObject,
) {
    let result = (|| {
        let client_id = read_string(&mut env, &client_id)?;
        let device_name = read_string(&mut env, &device_name)?;
        let access_token = read_string(&mut env, &access_token)?;
        let credentials_dir = read_string(&mut env, &credentials_dir)?;
        let cache_dir = read_string(&mut env, &cache_dir)?;
        let language = read_string(&mut env, &language).unwrap_or_default();
        let listener = env
            .new_global_ref(&listener)
            .map_err(|e| format!("failed to pin listener: {e}"))?;
        engine::start(
            &client_id,
            &device_name,
            &access_token,
            &credentials_dir,
            &cache_dir,
            &language,
            listener,
        )
    })();
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeLoadQueue(
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
    let result = read_string(&mut env, &uris_json)
        .and_then(|raw| {
            serde_json::from_str::<Vec<String>>(&raw).map_err(|e| format!("bad queue: {e}"))
        })
        .and_then(|uris| {
            engine::load_queue(
                uris,
                index.max(0) as u32,
                start_playing == JNI_TRUE,
                position_ms.max(0) as u32,
                context,
                play_as_context == JNI_TRUE,
            )
        });
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeNext(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = engine::next();
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativePrevious(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = engine::previous();
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeSetShuffle(
    mut env: JNIEnv,
    _class: JClass,
    shuffle: jboolean,
) {
    let result = engine::set_shuffle(shuffle == JNI_TRUE);
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeSetRepeat(
    mut env: JNIEnv,
    _class: JClass,
    repeat_context: jboolean,
    repeat_track: jboolean,
) {
    let result = engine::set_repeat(repeat_context == JNI_TRUE, repeat_track == JNI_TRUE);
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativePlay(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = engine::play();
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativePause(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = engine::pause();
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeStop(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = engine::stop();
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeSeek(
    mut env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) {
    let result = engine::seek(position_ms.clamp(0, u32::MAX as jlong) as u32);
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeSetVolume(
    mut env: JNIEnv,
    _class: JClass,
    volume: jint,
) {
    let result = engine::set_volume(volume.clamp(0, u16::MAX as jint) as u16);
    or_throw(&mut env, result);
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeVolume(
    mut env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = engine::volume().map(|v| v as jint);
    or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeIsConnected(
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
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeUsername(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = catalog::username();
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeCollectionUri(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = catalog::collection_uri();
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeRootlist(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = catalog::rootlist();
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeContextTracks(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let result = read_string(&mut env, &uri).and_then(|uri| catalog::context_tracks(&uri));
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativePlaylistCover(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let result = read_string(&mut env, &uri).and_then(|uri| catalog::playlist_cover(&uri));
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeCanvas(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let result = read_string(&mut env, &track_uri).and_then(|uri| catalog::canvas(&uri));
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeLyrics(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let result = read_string(&mut env, &track_uri).and_then(|uri| catalog::lyrics(&uri));
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeTracksMetadata(
    mut env: JNIEnv,
    _class: JClass,
    uris_json: JString,
) -> jstring {
    let result = read_string(&mut env, &uris_json).and_then(|json| catalog::tracks_metadata(&json));
    string_or_throw(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_emanuele_spot_nativecore_NativeBridge_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    engine::shutdown();
}
