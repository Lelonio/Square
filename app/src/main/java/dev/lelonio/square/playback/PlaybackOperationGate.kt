package dev.lelonio.square.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates asynchronous playback operations that may outlive their caller.
 *
 * A service can receive a second command while the first one is suspended on
 * native I/O. The mutex prevents two mutations from entering the player at the
 * same time; the generation lets callers reject work that became obsolete while
 * they were suspended. This is intentionally small and does not own a scope.
 */
class PlaybackOperationGate {
    private val mutex = Mutex()
    private val generation = AtomicLong(0L)

    /** Starts a new logical playback operation and invalidates older callbacks. */
    fun begin(): Long = generation.incrementAndGet()

    /** Returns whether [operation] is still the newest operation. */
    fun isCurrent(operation: Long): Boolean = generation.get() == operation

    /** Invalidates all suspended operations without waiting for them. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** Serializes mutations which must not overlap. Cancellation is propagated. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock(block)
}
