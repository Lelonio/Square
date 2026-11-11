package dev.lelonio.square.backend.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which songs are offered the pronunciation, and how each is spelt out. */
class RomanizeTest {

    @Test fun onlySongsInAnotherScriptAreOffered() {
        assertFalse(Romanize.wanted(listOf("Wake me up when it's all over", "", "Perché, 123!")))
        assertTrue(Romanize.wanted(listOf("Intro", "사랑해")))
        assertTrue(Romanize.wanted(listOf("我爱你")))
        assertTrue(Romanize.wanted(listOf("Привет")))
    }

    @Test fun aSongWithKanaIsReadAsJapanese() {
        assertEquals(Romanize.JAPANESE, Romanize.schemeFor(listOf("君の名は", "Hello")))
        assertEquals(Romanize.ANY, Romanize.schemeFor(listOf("我爱你")))
        assertEquals(Romanize.ANY, Romanize.schemeFor(listOf("사랑해")))
    }
}
