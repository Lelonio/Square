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
}

impl AndroidSink {
    pub fn new(_format: AudioFormat) -> Self {
        Self { pcm: Vec::new() }
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
        self.invoke("start")
    }

    fn stop(&mut self) -> SinkResult<()> {
        self.invoke("stop")
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
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
