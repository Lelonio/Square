package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricWord
import dev.lelonio.square.data.Lyrics
import org.junit.Assert.*
import org.junit.Test

class LyricsWinnerTest {
    private val order = listOf(LyricsSource.APPLE_MUSIC, LyricsSource.SPOTIFY, LyricsSource.LRCLIB, LyricsSource.KUGOU)
    private fun word(source: LyricsSource) =
        Lyrics(listOf(LyricLine(0, "a b", words = listOf(LyricWord(0, 1, "a")))), synced = true, source = source.name)
    private fun line(source: LyricsSource) = Lyrics(listOf(LyricLine(0, "a b")), synced = true, source = source.name)
    private fun plain(source: LyricsSource) = Lyrics(listOf(LyricLine(null, "a b")), synced = false, source = source.name)

    @Test fun wordTimingFurtherDownBeatsALineTimedAnswerAbove() {
        val all = mapOf(LyricsSource.APPLE_MUSIC to null, LyricsSource.SPOTIFY to line(LyricsSource.SPOTIFY),
            LyricsSource.LRCLIB to line(LyricsSource.LRCLIB), LyricsSource.KUGOU to word(LyricsSource.KUGOU))
        assertEquals("KUGOU", LyricsLibrary.winner(order, all, final = false)?.source)
    }

    @Test fun waitsWhileAnEarlierSourceCouldStillWin() {
        val partial = mapOf(LyricsSource.SPOTIFY to word(LyricsSource.SPOTIFY))
        assertNull(LyricsLibrary.winner(order, partial, final = false))
        assertEquals("SPOTIFY", LyricsLibrary.winner(order, partial, final = true)?.source)
        val earlierAnswered = mapOf(LyricsSource.APPLE_MUSIC to line(LyricsSource.APPLE_MUSIC), LyricsSource.SPOTIFY to word(LyricsSource.SPOTIFY))
        assertEquals("SPOTIFY", LyricsLibrary.winner(order, earlierAnswered, final = false)?.source)
    }

    @Test fun withoutWordTimingTheFirstLineTimedWinsOnceAllAreIn() {
        val all = mapOf(LyricsSource.APPLE_MUSIC to plain(LyricsSource.APPLE_MUSIC), LyricsSource.SPOTIFY to null,
            LyricsSource.LRCLIB to line(LyricsSource.LRCLIB), LyricsSource.KUGOU to line(LyricsSource.KUGOU))
        assertEquals("LRCLIB", LyricsLibrary.winner(order, all, final = false)?.source)
        assertNull(LyricsLibrary.winner(order, all - LyricsSource.KUGOU, final = false))
        assertNull(LyricsLibrary.winner(order, order.associateWith { null }, final = false))
    }

    @Test fun aPreferredSourceIsTheOnlyAnswer() {
        val partial = mapOf(LyricsSource.APPLE_MUSIC to word(LyricsSource.APPLE_MUSIC))
        assertNull(LyricsLibrary.winner(order, partial, final = false, preferred = LyricsSource.LRCLIB))
        val all = partial + mapOf(LyricsSource.SPOTIFY to null, LyricsSource.LRCLIB to plain(LyricsSource.LRCLIB), LyricsSource.KUGOU to null)
        assertEquals("LRCLIB", LyricsLibrary.winner(order, all, final = false, preferred = LyricsSource.LRCLIB)?.source)
        val withoutIt = all + (LyricsSource.LRCLIB to null)
        assertNull(LyricsLibrary.winner(order, withoutIt, final = true, preferred = LyricsSource.LRCLIB))
    }
}
