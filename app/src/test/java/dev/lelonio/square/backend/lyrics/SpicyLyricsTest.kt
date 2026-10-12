package dev.lelonio.square.backend.lyrics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpicyLyricsTest {
    @Test fun syllablesBecomeWordsAndTheCreditIsKept() {
        val json = JSONObject(
            """
            {"Type":"Syllable","source":"spicy_lyrics",
             "UploadAttribution":{"Uploader":{"username":"alice","url":"https://spicylyrics.org/u/alice"}},
             "Content":[{"Type":"Vocal","OppositeAligned":false,"Lead":{
               "StartTime":1.5,"EndTime":3.0,"TranslatedText":"Ciao bellissima",
               "Syllables":[
                 {"Text":"Hel","StartTime":1.5,"EndTime":1.7,"IsPartOfWord":true},
                 {"Text":"lo","StartTime":1.7,"EndTime":2.0,"IsPartOfWord":false},
                 {"Text":"beau","StartTime":2.0,"EndTime":2.3,"IsPartOfWord":true},
                 {"Text":"ti","StartTime":2.3,"EndTime":2.5,"IsPartOfWord":true},
                 {"Text":"ful","StartTime":2.5,"EndTime":3.0,"IsPartOfWord":false}]}}]}
            """.trimIndent(),
        )
        val lyrics = SpicyLyrics.parse(json)!!
        val line = lyrics.lines.single()
        assertEquals("Hello beautiful", line.text)
        assertEquals(1500L, line.startTimeMs)
        assertEquals(listOf("Hel", "lo", "beau", "ti", "ful"), line.words.map { it.text })
        assertEquals(2300L, line.words[3].startMs)
        assertEquals("Ciao bellissima", line.translation)
        assertTrue(lyrics.synced)
        assertEquals("Spicy Lyrics", lyrics.credit?.provider)
        assertEquals("alice", lyrics.credit?.uploader)
        assertNull(lyrics.credit?.maker)
    }

    @Test fun lineAndStaticLyrics() {
        val line = SpicyLyrics.parse(
            JSONObject("""{"Type":"Line","source":"apple_music","Content":[{"Type":"Vocal","Text":"A line","StartTime":4.25,"EndTime":6}]}"""),
        )!!
        assertEquals(4250L, line.lines.single().startTimeMs)
        assertNull(line.credit?.uploader)

        val static = SpicyLyrics.parse(JSONObject("""{"Type":"Static","Lines":[{"Text":"One"},{"Text":""},{"Text":"Two"}]}"""))!!
        assertEquals(listOf("One", "Two"), static.lines.map { it.text })
        assertFalse(static.synced)
    }
}
