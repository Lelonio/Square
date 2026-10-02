package dev.lelonio.square.backend.youtube

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Brings every stream to the mixer's rate, and keeps doing it.
 *
 * Sonic resamples, but forgets the rate it was asked for on every reset, and
 * the sink resets its processors whenever the player stops: the first songs
 * reached the mixer at 48 kHz and the ones after a stop went round it at 44.1,
 * crossfade and all. This puts the rate back after each reset. Sonic is final,
 * hence a wrapper rather than a subclass.
 */
@UnstableApi
class MixerRateProcessor : AudioProcessor {

    private val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(PcmMixer.RATE) }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat) = sonic.configure(inputAudioFormat)
    override fun isActive() = sonic.isActive
    override fun queueInput(inputBuffer: ByteBuffer) = sonic.queueInput(inputBuffer)
    override fun queueEndOfStream() = sonic.queueEndOfStream()
    override fun getOutput(): ByteBuffer = sonic.output
    override fun isEnded() = sonic.isEnded
    override fun getDurationAfterProcessorApplied(durationUs: Long) = sonic.getDurationAfterProcessorApplied(durationUs)

    @Suppress("DEPRECATION")
    override fun flush() = sonic.flush()

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) = sonic.flush(streamMetadata)

    override fun reset() {
        sonic.reset()
        sonic.setOutputSampleRateHz(PcmMixer.RATE)
    }
}
