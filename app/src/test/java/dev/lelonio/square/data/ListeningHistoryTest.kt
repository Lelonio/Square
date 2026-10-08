package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class ListeningHistoryTest {
    private val song = CatalogTrack("spotify:track:a", "Song", "Artist", durationMs = 60_000)
    private val time = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli()

    @Test fun importsAreIdempotentAndRepeatedSongsRemainSeparate() {
        val once = ListeningHistory.merge(emptyList(), song, time)
        val twice = ListeningHistory.merge(once, song, time)
        assertEquals(once, twice)
        assertEquals(2, ListeningHistory.merge(twice, song, time + 60_000).size)
    }
    @Test fun importedListenReconcilesWithOneLocalOccurrence() {
        val local = ListeningEvent("local:1", song, time, time + 60_000, 30_000, true)
        val matched = ListeningHistory.merge(listOf(local), song, time + 40_000)
        assertEquals(1, matched.size)
        assertEquals(30_000, matched.single().listenedMs)
        assertEquals(2, ListeningHistory.merge(matched, song, time + 50_000).size)
    }
    @Test fun pauseAndLongSamplingGapsDoNotAddTime() {
        val sampler = ListeningSampler()
        sampler.sample(song, true, time, 0)
        assertEquals(1_000, sampler.sample(song, true, time + 1_000, 1_000)!!.listenedMs)
        sampler.sample(song, false, time + 2_000, 2_000)
        sampler.sample(song, false, time + 62_000, 62_000)
        sampler.sample(song, true, time + 63_000, 63_000)
        assertEquals(2_000, sampler.sample(song, true, time + 64_000, 64_000)!!.listenedMs)
        assertEquals(4_000, sampler.sample(song, true, time + 100_000, 100_000)!!.listenedMs)
    }
    @Test fun qualifiedLocalPlaySurvivesPauseWithoutCountingTwice() {
        val sampler = ListeningSampler()
        sampler.sample(song, true, time, 0)
        var event: ListeningEvent? = null
        for (second in 1..30) event = sampler.sample(song, true, time + second * 1_000, second * 1_000L)
        assertTrue(event!!.qualified)
        val id = event!!.id
        sampler.sample(song, false, time + 31_000, 31_000)
        assertEquals(id, sampler.sample(song, true, time + 60_000, 60_000)!!.id)
    }
    @Test fun monthBoundaryUsesLocalTimeAndRemoteMinutesAreUnknown() {
        val at = Instant.parse("2026-09-30T23:30:00Z").toEpochMilli()
        val events = ListeningHistory.merge(emptyList(), song, at)
        val summary = ListeningHistory.summarize(events, YearMonth.of(2026, 10), ZoneId.of("Europe/Rome"))
        assertEquals(1, summary.plays)
        assertEquals(0, summary.minutes)
        assertEquals(0, ListeningHistory.summarize(events, YearMonth.of(2026, 9), ZoneId.of("Europe/Rome")).plays)
    }
    @Test fun openingAPausedQueueDoesNotStartAListen() {
        val sampler = ListeningSampler()
        assertNull(sampler.sample(song, false, time, 0))
        assertNull(sampler.sample(song, false, time + 60_000, 60_000))
        assertNull(sampler.sample(song, true, time + 61_000, 61_000))
        assertEquals(time + 61_000, sampler.sample(song, true, time + 62_000, 62_000)!!.startedAt)
    }
    @Test fun measuredMinutesAreSplitAtTheMonthBoundary() {
        val sampler = ListeningSampler()
        val boundary = YearMonth.of(2026, 10).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        sampler.sample(song, true, boundary - 500, 0)
        val event = sampler.sample(song, true, boundary + 500, 1_000)!!
        assertEquals(500L, event.measuredMonths["2026-09"])
        assertEquals(500L, event.measuredMonths["2026-10"])
    }

    @Test fun importBeforeLocalFlushIsReconciledAndOlderSnapshotsCannotRemoveMinutes() {
        val imported = ListeningHistory.merge(emptyList(), song, time)
        val local = ListeningEvent("local:1", song, time, time + 60_000, 60_000, true,
            measuredMonths = mapOf("2026-10" to 60_000))
        val recorded = ListeningHistory.record(imported, local)
        assertEquals(1, recorded.size)
        assertEquals(time, recorded.single().spotifyAt)
        assertEquals(1, ListeningHistory.summarize(recorded, YearMonth.of(2026, 10), ZoneId.of("UTC")).plays)
        assertEquals(1L, ListeningHistory.summarize(recorded, YearMonth.of(2026, 10), ZoneId.of("UTC")).minutes)
        assertEquals(recorded, ListeningHistory.record(recorded, local.copy(listenedMs = 30_000)))
    }

}
