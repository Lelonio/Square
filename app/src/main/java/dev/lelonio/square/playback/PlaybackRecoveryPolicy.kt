package dev.lelonio.square.playback

/**
 * Bounded retry policy for transient playback failures.
 *
 * This class is deliberately pure: it does not perform delays or retry a player
 * itself. The service owns cancellation and decides which backend operation is
 * safe to repeat. Keeping the decision separate prevents retry storms and makes
 * recovery behaviour deterministic in tests.
 */
data class PlaybackRetryDecision(
    val retry: Boolean,
    val delayMs: Long,
    val attempt: Int,
) {
    val terminal: Boolean get() = !retry
}

class PlaybackRecoveryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must be non-negative" }
        require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
    }

    /**
     * Returns the next recovery action for a one-based retry [attempt].
     *
     * Authentication, malformed data and unsupported operations are not
     * transient and must never be retried automatically. Network/backend
     * availability failures are bounded by [maxAttempts].
     */
    fun decide(error: PlaybackError, attempt: Int): PlaybackRetryDecision {
        require(attempt >= 1) { "attempt must be one-based" }

        if (!isTransient(error) || attempt > maxAttempts) {
            return PlaybackRetryDecision(retry = false, delayMs = 0L, attempt = attempt)
        }

        val exponent = (attempt - 1).coerceAtMost(30)
        val multiplier = 1L shl exponent
        val delay = if (initialDelayMs > maxDelayMs / multiplier) {
            maxDelayMs
        } else {
            (initialDelayMs * multiplier).coerceAtMost(maxDelayMs)
        }
        return PlaybackRetryDecision(retry = true, delayMs = delay, attempt = attempt)
    }

    private fun isTransient(error: PlaybackError): Boolean = when (error) {
        PlaybackError.Network,
        PlaybackError.BackendUnavailable,
        -> true
        PlaybackError.TrackUnavailable,
        PlaybackError.Authentication,
        PlaybackError.Persistence,
        PlaybackError.Unsupported,
        is PlaybackError.Unexpected,
        -> false
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INITIAL_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
    }
}
