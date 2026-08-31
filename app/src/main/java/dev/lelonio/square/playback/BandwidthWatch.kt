package dev.lelonio.square.playback

import android.util.Log
import dev.lelonio.square.data.Quality

/**
 * What the connection can actually carry, judged by listening rather than asking.
 *
 * The system's own estimate answers a different question: it reports the speed
 * of the radio link, which stays high behind a router that is throttling, a
 * captive network that is shaping, or a plan that has run out of fast data.
 *
 * This watcher is deliberately small and stateful, and is owned by the active
 * Spotify player. It is not a second playback state authority: it only decides
 * which bitrate the next track should request and records connection evidence.
 */
class BandwidthWatch(
    private val onStep: (Int) -> Unit,
    private val onMeasurement: (Measurement) -> Unit = {},
) {
    /** The step in use, in kbps: one of [Quality]'s three fixed values. */
    var current: Int = Quality.High.kbps
        private set

    private var loadStartedAt = 0L
    private var loadingUri: String? = null
    private var goodRun = 0
    private var loadSequence = 0L
    private var stalls = 0

    /** Immutable diagnostic evidence for playback telemetry/tests. */
    data class Measurement(
        val sequence: Long,
        val uri: String?,
        val loadLatencyMs: Long?,
        val stallCount: Int,
        val selectedBitrateKbps: Int,
        val reason: Reason,
    )

    enum class Reason { LOAD_COMPLETED, SLOW_LOAD, STALL, BITRATE_CHANGED }

    /** Where the ladder starts, when playback begins with no history. */
    fun start(from: Int) {
        current = from
        goodRun = 0
        loadStartedAt = 0L
        loadingUri = null
        stalls = 0
    }

    /** The engine has begun loading [uri]. */
    fun loading(uri: String, now: Long = System.currentTimeMillis()) {
        if (loadingUri == uri) return
        loadingUri = uri
        loadStartedAt = now
        loadSequence++
    }

    /** [uri] is now playing: whatever it took to get here is the measurement. */
    fun playing(uri: String, now: Long = System.currentTimeMillis()) {
        val started = loadStartedAt
        if (loadingUri != uri || started == 0L) return
        loadingUri = null
        val took = (now - started).coerceAtLeast(0L)
        if (took >= SLOW_LOAD_MS) {
            Log.i(TAG, "load took ${took}ms, stepping down")
            emit(Measurement(loadSequence, uri, took, stalls, current, Reason.SLOW_LOAD))
            stepDown()
        } else {
            goodRun++
            emit(Measurement(loadSequence, uri, took, stalls, current, Reason.LOAD_COMPLETED))
            if (goodRun >= GOOD_TRACKS_BEFORE_RISING) stepUp()
        }
    }

    /**
     * The music stopped while it meant to be playing.
     *
     * Reported by the player position watcher rather than measured here: the
     * player is the only thing that knows the difference between a buffer that
     * ran dry and a listener who pressed pause.
     */
    fun stalled() {
        stalls++
        Log.i(TAG, "the buffer ran dry, stepping down")
        emit(Measurement(loadSequence, loadingUri, null, stalls, current, Reason.STALL))
        stepDown()
    }

    private fun stepDown() {
        goodRun = 0
        val next = when (current) {
            Quality.High.kbps -> Quality.Medium.kbps
            Quality.Medium.kbps -> Quality.Low.kbps
            else -> return
        }
        apply(next)
    }

    private fun stepUp() {
        goodRun = 0
        val next = when (current) {
            Quality.Low.kbps -> Quality.Medium.kbps
            Quality.Medium.kbps -> Quality.High.kbps
            else -> return
        }
        apply(next)
    }

    private fun apply(kbps: Int) {
        if (kbps == current) return
        current = kbps
        Log.i(TAG, "asking for $kbps kbps from the next track")
        emit(Measurement(loadSequence, loadingUri, null, stalls, current, Reason.BITRATE_CHANGED))
        onStep(kbps)
    }

    private fun emit(measurement: Measurement) {
        runCatching { onMeasurement(measurement) }
            .onFailure { Log.w(TAG, "playback telemetry callback failed", it) }
    }

    private companion object {
        const val TAG = "SquareBandwidth"

        /** A load slower than this is evidence that the current quality is too high. */
        const val SLOW_LOAD_MS = 6_000L

        /** How many quick, unbroken tracks it takes to try the step above. */
        const val GOOD_TRACKS_BEFORE_RISING = 4
    }
}
