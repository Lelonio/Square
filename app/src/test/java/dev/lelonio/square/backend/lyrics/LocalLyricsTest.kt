package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalLyricsTest {

    @Test fun readsTimedLinesRepeatedStampsAndOffset() {
        val lyrics = LocalLyrics.parse(
            """
            [ar:Someone]
            [offset:+500]
            [00:12.30]First line
            [00:20.00][01:05.5]Chorus
            [00:30]
            """.trimIndent(),
        )!!
        assertTrue(lyrics.synced)
        assertEquals(
            listOf(11_800L to "First line", 19_500L to "Chorus", 29_500L to "", 65_000L to "Chorus"),
            lyrics.lines.map { it.startTimeMs to it.text },
        )
    }

    @Test fun plainWordsWithoutTimesAreKeptUntimed() {
        val lyrics = LocalLyrics.parse("[ti:Song]\n\nOne\nTwo\n")!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("One", "Two"), lyrics.lines.map { it.text })
        assertNull(LocalLyrics.parse("  \n"))
    }

    @Test fun readsAnId3UnsynchronisedLyricsFrame() {
        val text = "[00:01.00]Hello"
        val body = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)
        // A frame before it, to be skipped.
        val title = byteArrayOf(3) + "Title".toByteArray()
        val frames = frame("TIT2", title) + frame("USLT", body)
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(frames.size + 32) + frames + ByteArray(32)
        assertEquals(text, LocalLyrics.embedded(ByteArrayInputStream(tag + ByteArray(100))))
    }

    @Test fun readsAFlacVorbisComment() {
        val comments = listOf("TITLE=Song", "LYRICS=Line one\nLine two")
        val out = ByteArrayOutputStream()
        fun int(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        int(3); out.write("abc".toByteArray())
        int(comments.size)
        comments.forEach { val bytes = it.toByteArray(); int(bytes.size); out.write(bytes) }
        val block = out.toByteArray()
        val streamInfo = byteArrayOf(0, 0, 0, 34) + ByteArray(34)
        val vorbis = byteArrayOf(0x84.toByte(), 0, (block.size shr 8).toByte(), block.size.toByte()) + block
        val file = "fLaC".toByteArray() + streamInfo + vorbis
        assertEquals("Line one\nLine two", LocalLyrics.embedded(ByteArrayInputStream(file)))
    }

    @Test fun theListenersOwnFileBeatsAWordTimedArchive() {
        val order = listOf(LyricsSource.LOCAL_FILE, LyricsSource.APPLE_MUSIC)
        val local = Lyrics(listOf(LyricLine(0, "mine")), synced = true, source = LyricsSource.LOCAL_FILE.name)
        val archive = Lyrics(
            listOf(LyricLine(0, "a", words = listOf(dev.lelonio.square.data.LyricWord(0, 1, "a")))),
            synced = true,
            source = LyricsSource.APPLE_MUSIC.name,
        )
        assertNull(LyricsLibrary.winner(order, mapOf(LyricsSource.APPLE_MUSIC to archive), final = false))
        assertEquals("LOCAL_FILE", LyricsLibrary.winner(order, mapOf(LyricsSource.LOCAL_FILE to local, LyricsSource.APPLE_MUSIC to archive), final = false)?.source)
        assertEquals("APPLE_MUSIC", LyricsLibrary.winner(order, mapOf(LyricsSource.LOCAL_FILE to null, LyricsSource.APPLE_MUSIC to archive), final = false)?.source)
    }

    private fun frame(id: String, body: ByteArray): ByteArray =
        id.toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + byteArrayOf(0, 0) + body

    private fun syncsafe(value: Int): ByteArray = byteArrayOf(
        (value shr 21 and 0x7F).toByte(),
        (value shr 14 and 0x7F).toByte(),
        (value shr 7 and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
