package dev.lelonio.square.playback

import dev.lelonio.square.data.Quality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BandwidthWatchTest {

    @Test
    fun slowLoadStepsDownAndEmitsMeasuredLatency() {
        val measurements = mutableListOf<BandwidthWatch.Measurement>()
        val watch = BandwidthWatch(
            onStep = {},
            onMeasurement = measurements::add,
        )
        watch.start(Quality.High.kbps)

        watch.loading("spotify:track:a", now = 1_000L)
        watch.playing("spotify:track:a", now = 7_001L)

        assertEquals(Quality.Medium.kbps, watch.current)
        assertTrue(measurements.any {
            it.reason == BandwidthWatch.Reason.SLOW_LOAD && it.loadLatencyMs == 6_001L
        })
    }

    @Test
    fun fourFastTracksAreRequiredBeforeSteppingBackUp() {
        val steps = mutableListOf<Int>()
        val watch = BandwidthWatch(onStep = steps::add)
        watch.start(Quality.Low.kbps)

        repeat(3) { index ->
            val start = index * 2_000L + 1_000L
            watch.loading("spotify:track:$index", now = start)
            watch.playing("spotify:track:$index", now = start + 500L)
        }
        assertEquals(Quality.Low.kbps, watch.current)

        watch.loading("spotify:track:3", now = 7_000L)
        watch.playing("spotify:track:3", now = 7_500L)

        assertEquals(Quality.Medium.kbps, watch.current)
        assertEquals(listOf(Quality.Medium.kbps), steps)
    }

    @Test
    fun duplicateLoadingForSameTrackDoesNotResetMeasurement() {
        val measurements = mutableListOf<BandwidthWatch.Measurement>()
        val watch = BandwidthWatch(onStep = {}, onMeasurement = measurements::add)

        watch.loading("spotify:track:a", now = 1_000L)
        watch.loading("spotify:track:a", now = 2_000L)
        watch.playing("spotify:track:a", now = 1_500L)

        assertEquals(1, measurements.size)
        assertEquals(500L, measurements.single().loadLatencyMs)
    }
}
