//! JNI entry points for the native Spotify playback core.
//!
//! The Kotlin side owns OAuth and the UI; this crate owns the librespot session,
//! the audio pipeline and the player state machine. Everything crossing the JNI
//! boundary is either a primitive or a UTF-8 string, so there is no shared
//! ownership to reason about.

mod catalog;
mod engine;
mod events;
mod ffi;
mod sink;

use jni::sys::{jint, JNI_VERSION_1_6};
use jni::JavaVM;
use std::ffi::c_void;

/// Called by the Android runtime when `System.loadLibrary` maps this object.
///
/// cpal's AAudio host needs a `JavaVM` + application `Context` to resolve the
/// output device, and it reads both from `ndk_context`. Nothing else in the
/// crate may touch audio before this has run.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("spotcore"),
        );
    }

    // The context object itself is attached later from Kotlin via
    // `nativeInit`, because JNI_OnLoad has no Context to hand us.
    engine::store_java_vm(vm);
    JNI_VERSION_1_6
}
