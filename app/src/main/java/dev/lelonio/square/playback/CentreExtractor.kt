package dev.lelonio.square.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Takes the singing out of a finished stereo mix, one frequency at a time.
 *
 * The idea is the one DUET is built on: in a two-channel recording, each source
 * has its own place on the stage, and that place shows up as a pair of numbers
 * between the channels — how much louder it is on one side, and how much later
 * it arrives there. Cut the sound into short overlapping frames and look at
 * every frequency of every frame separately, and each of those little cells
 * belongs almost entirely to whichever source is loudest there. A lead voice
 * sits dead centre: equal in both channels, no delay. So the cells that look
 * centred can be turned down while everything to the sides is left alone.
 *
 * The earlier attempt here subtracted one channel from the other across the
 * whole signal at once, which is the same idea with no ears: everything centred
 * went, everything else stayed, and a record is not built in those two boxes.
 * Working per cell is what makes the difference audible — a guitar panned three
 * degrees off centre survives, cymbals survive, and so does most of what the
 * voice's reverb throws to the sides.
 *
 * References: Rickard, "The DUET blind source separation algorithm" (2007);
 * Yilmaz & Rickard, "Blind separation of speech mixtures via time-frequency
 * masking" (2004). The masking here is soft rather than binary, because a
 * listener asked for a slider rather than a switch.
 *
 * What it is not: the separation Apple ships, which is not separation at all —
 * those are the label's own multitrack stems. Nothing a player can compute from
 * a finished mix comes close to a recording that never had the voice in it.
 */
class CentreExtractor {

    private var rate = 0
    private var input = FloatArray(0)
    private var overlap = FloatArray(0)
    private var done = FloatArray(0)
    private var pending = 0
    private var ready = 0

    private val real = FloatArray(WINDOW)
    private val imaginary = FloatArray(WINDOW)
    private val leftReal = FloatArray(WINDOW)
    private val leftImaginary = FloatArray(WINDOW)
    private val rightReal = FloatArray(WINDOW)
    private val rightImaginary = FloatArray(WINDOW)

    private val window = FloatArray(WINDOW) { i ->
        // Hann, and at three-quarters overlap it sums to a constant, so the
        // frames can be added back together without the level breathing.
        (0.5f - 0.5f * cos(2.0 * PI * i / WINDOW).toFloat())
    }

    /** Delay this adds, in frames: one window, since a frame is only whole once. */
    val latencyFrames: Int get() = WINDOW

    fun reset() {
        input = FloatArray(0)
        overlap = FloatArray(0)
        done = FloatArray(0)
        pending = 0
        ready = 0
        rate = 0
    }

    /**
     * The same, for audio arriving as bytes.
     *
     * Interleaved 16-bit stereo only: there is nothing to compare in one
     * channel, and no other depth reaches either of this app's outputs.
     */
    fun apply(
        data: java.nio.ByteBuffer,
        sizeInBytes: Int,
        channels: Int,
        sampleRate: Int,
        amount: Float,
    ) {
        if (amount <= 0f || channels != 2 || sizeInBytes < 4) return
        val frames = sizeInBytes / 4
        if (scratch.size < frames * 2) scratch = ShortArray(frames * 2)

        val at = data.position()
        val shorts = data.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        shorts.get(scratch, 0, frames * 2)
        process(scratch, frames, sampleRate, amount)
        shorts.position(0)
        shorts.put(scratch, 0, frames * 2)
        data.position(at)
    }

    private var scratch = ShortArray(0)

    /**
     * Rewrites [pcm] in place: interleaved stereo, 16-bit, [frames] frames.
     *
     * The audio comes back a window late — a frame cannot be judged until it is
     * whole — so the first ninety milliseconds after this is switched on are
     * the tail of what came before it. Nothing else in the app is delayed with
     * it, which is why the position on screen is left alone: ninety
     * milliseconds is under the threshold where a lyric line looks early.
     */
    fun process(pcm: ShortArray, frames: Int, sampleRate: Int, amount: Float) {
        if (frames <= 0) return
        val samples = frames * 2
        if (sampleRate != rate) {
            rate = sampleRate
            pending = 0
            ready = 0
            java.util.Arrays.fill(overlap, 0f)
        }

        grow(samples)

        // Everything arriving joins what was left over from last time.
        for (i in 0 until samples) input[pending + i] = pcm[i] / SCALE
        pending += samples

        // Every whole frame that can be built is built.
        var consumed = 0
        while (pending - consumed >= WINDOW * 2) {
            transformFrame(consumed, amount)
            consumed += HOP * 2
        }

        // What the overlap has finished with — everything before the last frame
        // start — moves to the queue waiting to be played.
        if (consumed > 0) {
            grow(consumed)
            System.arraycopy(overlap, 0, done, ready, consumed)
            ready += consumed
            System.arraycopy(overlap, consumed, overlap, 0, overlap.size - consumed)
            java.util.Arrays.fill(overlap, overlap.size - consumed, overlap.size, 0f)
            System.arraycopy(input, consumed, input, 0, pending - consumed)
            pending -= consumed
        }

        // And exactly as much as came in goes out — a window later than it
        // arrived, which is the price of judging a frame only once it is whole.
        if (ready >= samples) {
            for (i in 0 until samples) pcm[i] = clamp(done[i])
            System.arraycopy(done, samples, done, 0, ready - samples)
            ready -= samples
        } else {
            // Only while the pipeline fills, at the very start.
            java.util.Arrays.fill(pcm, 0, samples, 0)
        }
    }

    /** Keeps the three working buffers big enough for what is being asked. */
    private fun grow(extra: Int) {
        val wanted = pending + extra + WINDOW * 4
        if (input.size < wanted) input = input.copyOf(wanted)
        if (overlap.size < wanted) overlap = overlap.copyOf(wanted)
        if (done.size < ready + extra + WINDOW * 4) done = done.copyOf(ready + extra + WINDOW * 4)
    }

    /** One frame: window it, look at every bin, put it back. */
    private fun transformFrame(at: Int, amount: Float) {
        for (i in 0 until WINDOW) {
            val w = window[i]
            leftReal[i] = input[at + i * 2] * w
            leftImaginary[i] = 0f
            rightReal[i] = input[at + i * 2 + 1] * w
            rightImaginary[i] = 0f
        }
        fft(leftReal, leftImaginary, false)
        fft(rightReal, rightImaginary, false)

        val lowBin = (KEEP_BELOW_HZ * WINDOW / rate).toInt()
        val highBin = (KEEP_ABOVE_HZ * WINDOW / rate).toInt()

        for (bin in 0 until BINS) {
            val lr = leftReal[bin]
            val li = leftImaginary[bin]
            val rr = rightReal[bin]
            val ri = rightImaginary[bin]

            val gain = if (bin < lowBin || bin > highBin) {
                1f
            } else {
                centreGain(lr, li, rr, ri, amount)
            }

            leftReal[bin] = lr * gain
            leftImaginary[bin] = li * gain
            rightReal[bin] = rr * gain
            rightImaginary[bin] = ri * gain

            // The upper half of the spectrum is the mirror of the lower one.
            if (bin in 1 until BINS - 1) {
                val mirror = WINDOW - bin
                leftReal[mirror] = leftReal[bin]
                leftImaginary[mirror] = -leftImaginary[bin]
                rightReal[mirror] = rightReal[bin]
                rightImaginary[mirror] = -rightImaginary[bin]
            }
        }

        fft(leftReal, leftImaginary, true)
        fft(rightReal, rightImaginary, true)

        for (i in 0 until WINDOW) {
            val w = window[i] * NORMALISE
            overlap[at + i * 2] += leftReal[i] * w
            overlap[at + i * 2 + 1] += rightReal[i] * w
        }
    }

    /**
     * How much of this cell to keep, given where it sits on the stage.
     *
     * Two measurements, both DUET's: how far apart the two channels are in
     * level, and how far apart in phase. A cell in the middle scores near zero
     * on both, and the further it is from there the less of the slider applies
     * to it.
     */
    private fun centreGain(lr: Float, li: Float, rr: Float, ri: Float, amount: Float): Float {
        val leftSize = hypot(lr, li)
        val rightSize = hypot(rr, ri)
        if (leftSize < TINY && rightSize < TINY) return 1f

        // Symmetric, so a voice pushed slightly left scores the same as one
        // pushed slightly right.
        val level = abs(leftSize - rightSize) / (leftSize + rightSize)

        // The angle between the two channels at this frequency.
        val crossReal = lr * rr + li * ri
        val crossImaginary = li * rr - lr * ri
        val phase = abs(atan2(crossImaginary, crossReal))

        val centred = exp(
            -(level * level) / (LEVEL_WIDTH * LEVEL_WIDTH) -
                (phase * phase) / (PHASE_WIDTH * PHASE_WIDTH),
        )
        return 1f - amount * centred
    }

    /**
     * Radix-2, in place, iterative.
     *
     * Written out rather than pulled in: this runs on the audio thread a
     * hundred and seventy times a second and the app has no other use for a
     * transform, so a dependency would be all cost.
     */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        var j = 0
        for (i in 1 until WINDOW) {
            var bit = WINDOW shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var length = 2
        while (length <= WINDOW) {
            val angle = (if (inverse) 2.0 * PI else -2.0 * PI) / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            var i = 0
            while (i < WINDOW) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until length / 2) {
                    val ar = re[i + k]
                    val ai = im[i + k]
                    val br = re[i + k + length / 2] * wr - im[i + k + length / 2] * wi
                    val bi = re[i + k + length / 2] * wi + im[i + k + length / 2] * wr
                    re[i + k] = ar + br
                    im[i + k] = ai + bi
                    re[i + k + length / 2] = ar - br
                    im[i + k + length / 2] = ai - bi
                    val nextR = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = nextR
                }
                i += length
            }
            length = length shl 1
        }

        if (inverse) {
            for (i in 0 until WINDOW) {
                re[i] /= WINDOW
                im[i] /= WINDOW
            }
        }
    }

    private fun clamp(value: Float): Short {
        val scaled = value * SCALE
        return when {
            scaled > Short.MAX_VALUE -> Short.MAX_VALUE
            scaled < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    private companion object {
        /** ~93 ms at 44.1 kHz: long enough to tell a voice from a snare. */
        const val WINDOW = 4096
        const val HOP = WINDOW / 4
        const val BINS = WINDOW / 2 + 1

        /**
         * How near the middle a cell has to be before it counts as the voice.
         *
         * In the two numbers DUET works in: how far the level may differ
         * between the channels, and how far the phase may. Both are generous —
         * a voice is never mathematically centred once a room has been added to
         * it — and the mask falls away smoothly rather than at an edge, which
         * is what keeps this from sounding like a gate.
         */
        const val LEVEL_WIDTH = 0.28f
        const val PHASE_WIDTH = 0.55f

        /** Below this the record keeps its bass and its kick, centred or not. */
        const val KEEP_BELOW_HZ = 120f

        /** And above this the air, where a voice has only its sibilance. */
        const val KEEP_ABOVE_HZ = 12_000f

        const val SCALE = 32768f
        const val TINY = 1e-7f

        /** Hann at three-quarters overlap sums to 1.5; this takes that back out. */
        const val NORMALISE = 2f / 3f
    }
}
