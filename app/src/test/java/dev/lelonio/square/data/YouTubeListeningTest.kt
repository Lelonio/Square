package dev.lelonio.square.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class YouTubeListeningTest {
    private val today = LocalDate.of(2026, 10, 9)
    private fun track(id: String) = CatalogTrack(uri = "ytmusic:track:$id", name = id, artist = "artist", durationMs = 1000)

    @Test fun headingsAreReadIntoDays() {
        assertEquals(today, YouTubeListening.sectionDay("Today", today))
        assertEquals(today.minusDays(1), YouTubeListening.sectionDay("Yesterday", today))
        assertEquals(today.minusWeeks(1), YouTubeListening.sectionDay("Last week", today))
        assertEquals(LocalDate.of(2026, 8, 15), YouTubeListening.sectionDay("August 2026", today))
        assertEquals(LocalDate.of(2025, 12, 15), YouTubeListening.sectionDay("December", today))
        assertEquals(LocalDate.of(2026, 10, 1), YouTubeListening.sectionDay("October", today))
        assertNull(YouTubeListening.sectionDay("Oggi", today))
    }

    @Test fun replaysAreTheSongsThatCameBackToTheTop() {
        val before = listOf("a", "b", "c", "d")
        assertEquals(emptyList<String>(), YouTubeListening.replays(before, before))
        assertEquals(listOf("x", "y"), YouTubeListening.replays(before, listOf("x", "y", "a", "b", "c", "d")))
        assertEquals(listOf("c", "x"), YouTubeListening.replays(before, listOf("c", "x", "a", "b", "d")))
        // The top song played again, after something new.
        assertEquals(listOf("a", "x"), YouTubeListening.replays(before, listOf("a", "x", "b", "c", "d")))
        // The page is a window: the oldest fall off its end.
        assertEquals(listOf("x"), YouTubeListening.replays(before, listOf("x", "a", "b", "c")))
        assertNull(YouTubeListening.replays(before, listOf("p", "q")))
    }

    @Test fun firstReadingFilesEachSongOnItsHeadingsDay() {
        val now = today.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val plays = YouTubeListening.plays(
            sections = listOf("Today" to listOf(track("a")), "Unknown" to listOf(track("b")), "Last week" to listOf(track("c"))),
            previous = null, previousAt = now, now = now, zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("a", "c"), plays.map { it.track.name })
        assertEquals(today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), plays[0].at)
    }

    @Test fun laterReadingsFileOnlyReplaysWithinTheTimeSince() {
        val plays = YouTubeListening.plays(
            sections = listOf("Today" to listOf(track("x"), track("a"), track("b"))),
            previous = listOf("ytmusic:track:a", "ytmusic:track:b"), previousAt = 1_000, now = 5_000,
        )
        assertEquals(listOf("x"), plays.map { it.track.name })
        assertEquals(5_000L, plays.single().at)
        assertEquals(1_000L..5_000L, plays.single().window)
    }

    @Test fun squaresOwnPlayIsNotCountedTwice() {
        val song = track("a")
        val local = ListeningEvent("local:2000:${song.uri}", song, 2_000, qualified = true)
        val merged = ListeningHistory.mergeRemote(listOf(local), song, 5_000, 1_000L..5_000L)
        assertEquals(1, merged.size)
        assertEquals(2_000L, merged.single().spotifyAt)
        val again = ListeningHistory.mergeRemote(merged, song, 6_000, 5_000L..6_000L)
        assertEquals(2, again.size)
    }
}
