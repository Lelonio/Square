package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Speed and pitch for the players that are not the Spotify engine.
 *
 * Spotify's audio goes through the app's own sink, where a phase vocoder does
 * this work; a file on the phone goes through ExoPlayer, which has its own
 * stretcher built for speech. On music that one smears every attack and adds a
 * metallic edge, which is exactly what the same track sounds like on the two
 * paths at the same setting. This puts the same vocoder in ExoPlayer's chain,
 * so a song is altered the same way whatever it was read from.
 *
 * Sixteen-bit PCM only. Everything the decoders here hand over is that, and a
 * format this cannot take is passed through rather than refused: an untouched
 * song is a much better answer than silence.
 */
@OptIn(UnstableApi::class)
class BungeeAudioProcessor : BaseAudioProcessor() {

    private var stretcher: Stretcher? = null
    private var speed = 1f
    private var pitch = 1f
    private var failureReported = false

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        this.speed = speed
        this.pitch = pitch
    }

    val isAltering: Boolean get() = speed != 1f || pitch != 1f

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // Kept, deliberately. The stretcher retains its short analysis window
        // across parameter changes so a slider update does not create a hole.
    }

    override fun onReset() {
        stretcher?.release()
        stretcher = null
        failureReported = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (!isAltering) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }

        val frames = size / (inputAudioFormat.channelCount * BYTES_PER_SAMPLE)
        val stretcher = stretcher(frames)
        val produced = stretcher?.process(inputBuffer, size, speed, pitch)
        if (produced == null) {
            if (!failureReported) {
                failureReported = true
                android.util.Log.w(TAG, "stretcher unavailable, playing untouched")
            }
            val remaining = inputBuffer.remaining()
            if (remaining > 0) replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        replaceOutputBuffer(produced.remaining()).put(produced).flip()
    }

    private fun stretcher(frames: Int): Stretcher? {
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val existing = stretcher
        if (existing != null && frames <= maxFrames && rate == builtForRate &&
            channels == builtForChannels
        ) {
            return existing
        }
        existing?.release()
        maxFrames = maxOf(frames, maxFrames, MIN_PACKET_FRAMES)
        builtForRate = rate
        builtForChannels = channels
        return Stretcher.create(rate, channels, maxFrames).also { stretcher = it }
    }

    private var maxFrames = 0
    private var builtForRate = 0
    private var builtForChannels = 0

    private companion object {
        const val TAG = "SquareStretch"
        const val BYTES_PER_SAMPLE = 2
        const val MIN_PACKET_FRAMES = 8192
    }
}

/** Vocal removal remains upstream of stretching because it relies on stereo centre extraction. */
@OptIn(UnstableApi::class)
class VocalAudioProcessor : BaseAudioProcessor() {
    private val vocals = CentreExtractor()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val amount = AudioEffects.karaoke.value
        val out = replaceOutputBuffer(size)
        val start = out.position()
        out.put(inputBuffer)
        out.position(start)
        if (amount > 0f) {
            vocals.apply(
                out,
                size,
                inputAudioFormat.channelCount,
                inputAudioFormat.sampleRate,
                amount,
            )
        }
        out.position(start)
        out.limit(start + size)
    }
}

@OptIn(UnstableApi::class)
class BungeeProcessorChain(
    private val bungee: BungeeAudioProcessor = BungeeAudioProcessor(),
    private val advanced: AdvancedDspAudioProcessor = AdvancedDspAudioProcessor(),
) : DefaultAudioSink.AudioProcessorChain {
    private var parameters = PlaybackParameters.DEFAULT
    private val vocals = VocalAudioProcessor()

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(vocals, advanced, bungee)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        parameters = playbackParameters
        bungee.setSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        advanced.setConfiguration(AudioEffects.dsp.value)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        Util.getMediaDurationForPlayoutDuration(playoutDuration, parameters.speed)

    override fun getSkippedOutputFrameCount(): Long = 0
}
