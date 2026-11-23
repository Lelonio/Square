package dev.lelonio.square.playback

/**
 * How loud the music going out is, 0 to 1, for what moves with it on screen.
 *
 * The loudest sample of every few in each packet written, from whichever
 * output plays: a glance at the music, not a meter. It falls back by itself
 * when nothing is written (see [now]), so a pause reads as silence.
 */
object AudioLevel {
    @Volatile private var peak = 0f
    @Volatile private var at = 0L

    fun note(value: Float) {
        peak = value.coerceIn(0f, 1f)
        at = android.os.SystemClock.uptimeMillis()
    }

    /** The level now: what was last written, gone to nothing a quarter of a second after the music stops. */
    fun now(): Float {
        val since = android.os.SystemClock.uptimeMillis() - at
        return if (since > 250) 0f else peak
    }

    /** From interleaved float samples, every eighth. */
    fun ofFloats(samples: java.nio.FloatBuffer, count: Int) {
        var most = 0f
        var i = 0
        while (i < count) {
            val v = kotlin.math.abs(samples.get(i))
            if (v > most) most = v
            i += 8
        }
        note(most)
    }

    /** From interleaved 16-bit samples, every eighth. */
    fun ofShorts(samples: java.nio.ShortBuffer, count: Int) {
        var most = 0
        var i = 0
        while (i < count) {
            val v = kotlin.math.abs(samples.get(i).toInt())
            if (v > most) most = v
            i += 8
        }
        note(most / 32768f)
    }
}
