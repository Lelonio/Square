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

    /** Logged once rather than per packet, which would be several a second. */
    private var failureReported = false

    /**
     * Set from the sink's chain when the listener moves either slider.
     *
     * The stretcher takes them per packet, so nothing has to be rebuilt: what
     * is playing changes on the next few milliseconds of audio.
     */
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
        // Kept, deliberately.
        //
        // The sink flushes its processors whenever the parameters change, and
        // throwing the vocoder away here meant the next packet arrived at a
        // stretcher with an empty window: it has to fill one before it can
        // resynthesise anything, and that silence is the hole heard when the
        // speed slider moves. What it carries over instead is a few tens of
        // milliseconds of the audio before the change, which after a seek is
        // the only cost — and one nobody can hear.
        //
        // The format is a different matter: a stretcher is built for a sample
        // rate and a channel count, and a track with either different needs a
        // new one. That is what [stretcher] checks.
    }

    override fun onReset() {
        stretcher?.release()
        stretcher = null
        failureReported = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        // Nothing to do at rest, and doing nothing is worth the check: this
        // runs on the audio thread for every packet of every track.
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
            // Position is already past what was read on the failed attempt for
            // a partial read; take what is left rather than dropping the packet.
            val remaining = inputBuffer.remaining()
            if (remaining > 0) replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        replaceOutputBuffer(produced.remaining()).put(produced).flip()
    }

    /** One stretcher per format, sized for the largest packet seen so far. */
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

        /** A decoder's packet, with room to spare, so the first one fits. */
        const val MIN_PACKET_FRAMES = 8192
    }
}

/**
 * The sink's chain, with the vocoder in it instead of the platform's stretcher.
 *
 * ExoPlayer asks the chain to apply speed and pitch, and asks it afterwards how
 * long the audio it has played was in media time. Both answers have to come
 * from whoever is doing the stretching, or the position on screen runs at a
 * different rate from the sound.
 */
@OptIn(UnstableApi::class)
class BungeeProcessorChain(
    private val bungee: BungeeAudioProcessor = BungeeAudioProcessor(),
) : DefaultAudioSink.AudioProcessorChain {

    private var parameters = PlaybackParameters.DEFAULT

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(bungee)

    override fun applyPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): PlaybackParameters {
        parameters = playbackParameters
        bungee.setSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    /** How much media a stretch of playing covers, which is the speed itself. */
    override fun getMediaDuration(playoutDuration: Long): Long =
        Util.getMediaDurationForPlayoutDuration(playoutDuration, parameters.speed)

    /** Nothing is dropped here; silence is skipped by nobody in this chain. */
    override fun getSkippedOutputFrameCount(): Long = 0
}
