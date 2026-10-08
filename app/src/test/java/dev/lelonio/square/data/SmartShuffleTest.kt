package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class SmartShuffleTest {
    @Test fun excludesPlaylistSongsDuplicatesAndLocalFiles() {
        assertEquals(listOf("spotify:track:new"), SmartShuffle.candidates(
            listOf("spotify:track:old", "spotify:local:file", "spotify:track:new", "spotify:track:new"),
            setOf("spotify:track:old"),
        ))
    }
    @Test fun preservesExplicitPlayNextEntries() {
        assertEquals(listOf(5), SmartShuffle.insertionPoints(listOf(false, true, true, false, false), 0, 3))
    }
    @Test fun spacesRecommendationsAmongOriginalSongs() {
        assertEquals(listOf(3, 6), SmartShuffle.insertionPoints(List(7) { false }, 0, 2))
    }
    @Test fun emptyQueueCannotEnableSmartShuffle() {
        assertTrue(SmartShuffle.insertionPoints(emptyList(), 0, 4).isEmpty())
        assertTrue(SmartShuffle.insertionPoints(listOf(false), 0, 0).isEmpty())
    }
}
