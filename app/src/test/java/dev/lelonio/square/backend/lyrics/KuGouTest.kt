package dev.lelonio.square.backend.lyrics

import org.junit.Assert.*
import org.junit.Test

class KuGouTest {
    private val krc = """
        [ti:Blinding Lights]
        [ar:The Weeknd]
        [0,1756]<0,439,0>Blinding <439,439,0>Lights - <878,439,0>The <1317,439,0>Weeknd
        [1757,4829]<0,439,0>Lyrics <439,439,0>by：<878,439,0>Max <1317,439,0>Martin
        [14075,13597]<0,177,0>Yeah
        [27672,2784]<0,200,0>I've <200,144,0>been <344,176,0>tryna <520,632,0>call
    """.trimIndent()

    @Test fun readsLinesAndWordsAndLeavesOutTheCredits() {
        val lyrics = KuGou.parse(krc, "Blinding Lights")!!
        assertEquals(listOf("Yeah", "I've been tryna call"), lyrics.lines.map { it.text })
        val call = lyrics.lines[1]
        assertEquals(27672L, call.startTimeMs)
        assertEquals(listOf("I've", "been", "tryna", "call"), call.words.map { it.text })
        assertEquals(27672L + 520, call.words[3].startMs)
        assertEquals(27672L + 520 + 632, call.words[3].endMs)
        assertTrue(lyrics.synced)
    }

    @Test fun decryptsWhatKuGouEncrypts() {
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val deflater = java.util.zip.Deflater().apply { setInput(krc.toByteArray()); finish() }
        val packed = ByteArray(4096).let { buffer -> buffer.copyOf(deflater.deflate(buffer)) }
        val body = ByteArray(packed.size) { i -> (packed[i].toInt() xor key[i % key.size]).toByte() }
        val raw = "krc1".toByteArray() + body
        assertEquals(krc, KuGou.decrypt(raw))
        assertNull(KuGou.decrypt("nope".toByteArray()))
    }
}
