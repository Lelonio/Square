package dev.emanuele.spot.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Time stretching and pitch shifting, backed by Bungee.
 *
 * This replaces `AudioTrack.setPlaybackParams`. That path was free, but it runs
 * the platform's Sonic implementation, which is built for speech: on music it
 * smears transients and adds a metallic ring at anything beyond a few percent.
 * Bungee is a phase vocoder and holds together across the range this app offers.
 *
 * Not thread-safe: the native stretcher carries streaming state between calls,
 * so every method must be called from the one thread that writes audio.
 */
class Stretcher private constructor(
    private var handle: Long,
    private val channels: Int,
    private val maxInputFrames: Int,
    maxOutputFrames: Int,
) {

    /**
     * Direct buffers so the native side can read and write them without a copy.
     * Reused across calls: allocating one per packet would mean a few hundred
     * direct allocations a second.
     */
    private val input: ByteBuffer = ByteBuffer
        .allocateDirect(maxInputFrames * channels * BYTES_PER_SAMPLE)
        .order(ByteOrder.nativeOrder())

    private val output: ByteBuffer = ByteBuffer
        .allocateDirect(maxOutputFrames * channels * BYTES_PER_SAMPLE)
        .order(ByteOrder.nativeOrder())

    /**
     * Stretches one packet of interleaved 16-bit PCM.
     *
     * @param source read from its current position; fully consumed
     * @param sizeInBytes bytes to take from [source]
     * @return a buffer positioned at zero and limited to the bytes produced, or
     *   null when the packet could not be processed — the caller should then
     *   pass the audio through untouched rather than drop it.
     */
    fun process(source: ByteBuffer, sizeInBytes: Int, speed: Float, pitch: Float): ByteBuffer? {
        if (handle == 0L) return null

        val frames = sizeInBytes / (channels * BYTES_PER_SAMPLE)
        if (frames <= 0 || frames > maxInputFrames) return null

        input.clear()
        val sourceLimit = source.limit()
        source.limit(source.position() + sizeInBytes)
        input.put(source)
        source.limit(sourceLimit)

        val produced = nativeProcess(handle, input, frames, speed, pitch, output)
        if (produced <= 0) return null

        output.limit(produced * channels * BYTES_PER_SAMPLE)
        output.position(0)
        return output
    }

    fun release() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2

        /**
         * Slowest speed the stretcher is sized for. Output is longer than input
         * below 1.0, and the output buffer has to cover the worst case.
         */
        private const val MIN_SPEED = 0.25f

        init {
            System.loadLibrary("spotstretch")
        }

        /** Returns null when the native stretcher cannot be created. */
        fun create(sampleRate: Int, channels: Int, maxInputFrames: Int): Stretcher? {
            val maxOutputFrames = (maxInputFrames / MIN_SPEED).toInt() + 1
            val handle = nativeCreate(sampleRate, channels, maxInputFrames, maxOutputFrames)
            if (handle == 0L) return null
            return Stretcher(handle, channels, maxInputFrames, maxOutputFrames)
        }

        @JvmStatic
        private external fun nativeCreate(
            sampleRate: Int,
            channels: Int,
            maxInputFrames: Int,
            maxOutputFrames: Int,
        ): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeProcess(
            handle: Long,
            input: ByteBuffer,
            inputFrames: Int,
            speed: Float,
            pitch: Float,
            output: ByteBuffer,
        ): Int
    }
}
