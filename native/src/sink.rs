//! Audio output through an Android `AudioTrack` on the Kotlin side.
//!
//! librespot ships a rodio/cpal backend that does work on Android, but cpal
//! chooses its own AAudio buffer size and the result underruns audibly. This
//! sink produces no sound itself: it converts each packet to interleaved 16-bit
//! PCM and hands it to Kotlin, where an `AudioTrack` owns the buffer and the
//! real-time thread. `AudioTrack.write` blocks, and that is what paces the
//! decoder — no extra queue is needed on either side.

use jni::objects::{GlobalRef, JValue};
use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::config::AudioFormat;
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::{NUM_CHANNELS, SAMPLE_RATE};
use std::sync::Mutex;

use crate::engine::JAVA_VM;

/// The Kotlin `AudioOutput` this sink drives.
///
/// Global rather than owned by the sink because librespot builds the sink from a
/// `FnOnce` with nowhere to pass extra arguments, and there is only ever one.
static OUTPUT: Mutex<Option<GlobalRef>> = Mutex::new(None);

pub fn set_output(output: GlobalRef) {
    if let Ok(mut guard) = OUTPUT.lock() {
        *guard = Some(output);
    }
}

pub fn clear_output() {
    if let Ok(mut guard) = OUTPUT.lock() {
        *guard = None;
    }
}

/// Stops the Android output and drops whatever it still holds.
///
/// Called when a session is discarded. `AudioTrack` buffers about a second of
/// audio ahead of the speaker, so a player that has already been told to stop
/// has still left a second of the previous song sitting in the device, and it
/// comes out over the beginning of the next one. `AudioOutput.stop` pauses and
/// flushes, which is what throws it away.
pub fn silence() {
    let Some(output) = OUTPUT.lock().ok().and_then(|guard| guard.clone()) else {
        return;
    };
    let Some(vm) = JAVA_VM.get() else { return };
    let Ok(mut env) = vm.attach_current_thread() else {
        return;
    };
    if env.call_method(&output, "stop", "()V", &[]).is_err() {
        log::warn!("could not silence the output");
    }
}

fn output() -> SinkResult<GlobalRef> {
    OUTPUT
        .lock()
        .ok()
        .and_then(|guard| guard.clone())
        .ok_or_else(|| SinkError::NotConnected("no audio output attached".into()))
}

pub struct AndroidSink {
    /// Scratch buffer for interleaved PCM, reused across packets.
    pcm: Vec<u8>,
    /// The bundle this sink belongs to; see [`stale`].
    generation: u64,
}

impl AndroidSink {
    pub fn new(_format: AudioFormat, generation: u64) -> Self {
        Self {
            pcm: Vec::new(),
            generation,
        }
    }

    /// Whether this sink belongs to a session that has been replaced.
    ///
    /// A reconnection builds a new player, and the old one does not stop
    /// decoding the instant it is dropped: its playback thread finishes the
    /// packets it already has. There is one AudioTrack for the whole app, so
    /// those packets came out *after* the new track had started, as a second or
    /// two of the previous song wedged into the beginning of the new one.
    ///
    /// Silently, and only for audio. Refusing with an error would make librespot
    /// treat it as an output failure and log a wall of them on the way down; the
    /// packets simply have nowhere left to go.
    fn stale(&self) -> bool {
        crate::engine::live_generation() != self.generation
            || !crate::engine::playback_armed()
    }

    fn invoke(&self, method: &str) -> SinkResult<()> {
        let output = output()?;
        let vm = JAVA_VM
            .get()
            .ok_or_else(|| SinkError::NotConnected("JNI_OnLoad did not run".into()))?;
        let mut env = vm
            .attach_current_thread_permanently()
            .map_err(|e| SinkError::ConnectionRefused(e.to_string()))?;

        env.call_method(&output, method, "()V", &[])
            .map_err(|e| SinkError::OnWrite(format!("{method} failed: {e}")))?;
        Ok(())
    }
}

impl Sink for AndroidSink {
    fn start(&mut self) -> SinkResult<()> {
        if self.stale() {
            return Ok(());
        }
        self.invoke("start")
    }

    fn stop(&mut self) -> SinkResult<()> {
        // Not gated: a stale sink stopping is the one thing it should still be
        // allowed to do, and the stop flushes whatever the AudioTrack is holding.
        self.invoke("stop")
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        if self.stale() {
            return Ok(());
        }

        // Interleaved little-endian S16, which is how AudioTrack is configured.
        self.pcm.clear();
        match packet {
            AudioPacket::Samples(samples) => {
                self.pcm.reserve(samples.len() * 2);
                for sample in converter.f64_to_s16(&samples) {
                    self.pcm.extend_from_slice(&sample.to_le_bytes());
                }
            }
            AudioPacket::Raw(data) => self.pcm.extend_from_slice(&data),
        }

        if self.pcm.is_empty() {
            return Ok(());
        }

        let output = output()?;
        let vm = JAVA_VM
            .get()
            .ok_or_else(|| SinkError::NotConnected("JNI_OnLoad did not run".into()))?;
        // Permanent: this is librespot's playback thread and it lives as long as
        // the player, so attaching per packet would be pure overhead.
        let mut env = vm
            .attach_current_thread_permanently()
            .map_err(|e| SinkError::ConnectionRefused(e.to_string()))?;

        // A direct buffer over the Rust allocation: AudioTrack can consume it as
        // is, so the PCM is never copied into the Java heap. Built fresh each
        // time because `pcm` may have reallocated since the last packet, and it
        // is only valid for the duration of this blocking call.
        let buffer = unsafe {
            env.new_direct_byte_buffer(self.pcm.as_mut_ptr(), self.pcm.len())
                .map_err(|e| SinkError::OnWrite(e.to_string()))?
        };

        env.call_method(
            &output,
            "write",
            "(Ljava/nio/ByteBuffer;III)V",
            &[
                JValue::Object(&buffer),
                JValue::Int(self.pcm.len() as i32),
                JValue::Int(SAMPLE_RATE as i32),
                JValue::Int(NUM_CHANNELS as i32),
            ],
        )
        .map_err(|e| SinkError::OnWrite(format!("write failed: {e}")))?;

        Ok(())
    }
}
