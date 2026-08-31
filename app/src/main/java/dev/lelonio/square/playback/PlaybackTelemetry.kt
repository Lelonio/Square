package dev.lelonio.square.playback

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight playback timing instrumentation.
 *
 * Measurements use elapsedRealtime so wall-clock changes cannot distort them.
 * The class only records explicit lifecycle points; it never samples CPU,
 * memory, or battery continuously and therefore adds negligible playback work.
 */
class PlaybackTelemetry(
    private val tag: String = "PlaybackTelemetry",
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val sequence = AtomicLong(0L)

    fun mark(name: String, mediaId: String? = null): Marker {
        val id = sequence.incrementAndGet()
        val startedAt = clockMs()
        Log.d(tag, "start id=$id event=$name media=${mediaId.orEmpty()}")
        return Marker(id, name, mediaId, startedAt, clockMs)
    }

    class Marker internal constructor(
        private val id: Long,
        private val name: String,
        private val mediaId: String?,
        private val startedAtMs: Long,
        private val clockMs: () -> Long,
    ) {
        fun complete(outcome: String = "ok"): Long {
            val elapsed = (clockMs() - startedAtMs).coerceAtLeast(0L)
            Log.d(
                "PlaybackTelemetry",
                "complete id=$id event=$name durationMs=$elapsed outcome=$outcome media=${mediaId.orEmpty()}",
            )
            return elapsed
        }
    }
}
