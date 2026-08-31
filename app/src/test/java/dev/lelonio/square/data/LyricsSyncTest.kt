package dev.lelonio.square.data

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsSyncTest {
    private val lyrics = Lyrics(
        lines = listOf(
            LyricLine(0, "first", listOf(LyricWord(0, 500, "first"))),
            LyricLine(1_000, "second", listOf(LyricWord(1_000, 1_400, "second"))),
            LyricLine(2_000, "third", listOf(LyricWord(2_000, 2_500, "third"))),
        ),
        synced = true,
    )

    @Test
    fun line_index_tracks_latest_started_line() {
        assertEquals(0, LyricsSync.lineIndex(lyrics, 200))
        assertEquals(1, LyricsSync.lineIndex(lyrics, 1_500))
        assertEquals(2, LyricsSync.lineIndex(lyrics, 9_000))
    }

    @Test
    fun offset_moves_sync_without_mutating_playback_position() {
        assertEquals(0, LyricsSync.lineIndex(lyrics, 700, 200))
        assertEquals(1, LyricsSync.lineIndex(lyrics, 900, 200))
    }

    @Test
    fun word_index_respects_word_end() {
        val line = lyrics.lines[1]
        assertEquals(0, LyricsSync.wordIndex(line, 1_100))
        assertEquals(-1, LyricsSync.wordIndex(line, 1_500))
    }
}
