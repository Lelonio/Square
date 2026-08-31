package dev.lelonio.square.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRecoveryPolicyTest {
    private val policy = PlaybackRecoveryPolicy()

    @Test
    fun transientFailuresUseBoundedExponentialBackoff() {
        assertEquals(500L, policy.decide(PlaybackError.Network, 1).delayMs)
        assertEquals(1_000L, policy.decide(PlaybackError.Network, 2).delayMs)
        assertEquals(2_000L, policy.decide(PlaybackError.BackendUnavailable, 3).delayMs)
        assertTrue(policy.decide(PlaybackError.Network, 1).retry)
    }

    @Test
    fun retryBudgetIsFinite() {
        val decision = policy.decide(PlaybackError.Network, 4)

        assertFalse(decision.retry)
        assertTrue(decision.terminal)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun permanentFailuresAreNeverRetried() {
        val errors = listOf(
            PlaybackError.TrackUnavailable,
            PlaybackError.Authentication,
            PlaybackError.Persistence,
            PlaybackError.Unsupported,
            PlaybackError.Unexpected("native_failure"),
        )

        errors.forEach { error ->
            assertFalse(policy.decide(error, 1).retry, "$error must not retry")
        }
    }
}
