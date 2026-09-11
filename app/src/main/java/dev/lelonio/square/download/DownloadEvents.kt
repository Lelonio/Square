package dev.lelonio.square.download

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Progress ticks on their way out of the engine.
 *
 * The engine has one listener and one permanent JVM attachment, so a download
 * says how far along it is on the same channel the player says where it is.
 * That is an implementation detail of the boundary, not a relationship between
 * the two, and this is where the two are separated again: [accept] takes the
 * download events off the wire before the player ever sees them.
 *
 * An object rather than something injected, for the same reason
 * [dev.lelonio.square.data.RemoteConnect] is one: there is a single engine
 * behind it, and the listener it pushes into belongs to the player.
 */
object DownloadEvents {

    data class Progress(val uri: String, val fraction: Float)

    private val _progress = MutableSharedFlow<Progress>(
        extraBufferCapacity = 64,
        // A tick nobody read is a ring that did not move by a percent. Dropping
        // the oldest keeps the newest, which is the only one worth drawing.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val progress: SharedFlow<Progress> = _progress.asSharedFlow()

    private val lastEmitMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastFraction = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /**
     * Takes a download event off the engine's event channel.
     *
     * @return true when the event was a download's and the player should not
     *   see it.
     */
    fun accept(type: String, uri: String, value: Long): Boolean = when (type) {
        PROGRESS -> {
            // Per mille on the wire: the event carries one number, and a
            // fraction would not have survived the trip as a long.
            // Throttled to prevent flooding Compose with dozens of recompositions/sec.
            val fraction = (value / 1000f).coerceIn(0f, 1f)
            val now = System.currentTimeMillis()
            val last = lastEmitMs[uri] ?: 0L
            val prev = lastFraction[uri] ?: 0f
            if (now - last >= 150L || (fraction - prev) >= 0.02f || fraction >= 0.99f) {
                lastEmitMs[uri] = now
                lastFraction[uri] = fraction
                _progress.tryEmit(Progress(uri, fraction))
            }
            true
        }

        // The queue already knows a track finished — `downloadTrack` returned to
        // it — but the ring should reach the end before the row changes shape,
        // and the last progress tick lands a chunk short of the whole.
        DONE -> {
            lastEmitMs.remove(uri)
            lastFraction.remove(uri)
            _progress.tryEmit(Progress(uri, 1f))
            true
        }

        else -> false
    }

    private const val PROGRESS = "download_progress"
    private const val DONE = "download_done"
}
