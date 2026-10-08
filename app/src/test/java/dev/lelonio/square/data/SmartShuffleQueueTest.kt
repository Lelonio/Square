package dev.lelonio.square.data

import dev.lelonio.square.playback.PlayQueue
import org.junit.Assert.*
import org.junit.Test

class SmartShuffleQueueTest {
    private fun track(id: String) = PlayQueue.Track("spotify:track:$id", id, "artist", durationMs = 1000, artworkUri = null)

    @Test fun disablingRestoresOriginalOrderWithoutChangingPlayingTrack() {
        val queue = PlayQueue()
        val tracks = (0..8).map { track("$it") }
        queue.replace(tracks, 4)
        queue.setShuffled(true)
        queue.enableSmartShuffle(listOf(track("new1"), track("new2")))
        assertTrue(queue.smartShuffle)
        assertEquals(tracks[4], queue.items[queue.currentIndex])
        queue.disableSmartShuffle()
        assertEquals(tracks, queue.items)
        assertEquals(tracks[4], queue.items[queue.currentIndex])
        assertFalse(queue.smartShuffle)
    }
    @Test fun disablingKeepsPlayingRecommendationBeforeItsSuccessor() {
        val queue = PlayQueue()
        queue.replace((0..5).map { track("$it") }, 0)
        queue.setShuffled(true)
        queue.enableSmartShuffle(listOf(track("new")))
        queue.currentIndex = queue.items.indexOfFirst { it.recommended }
        val current = queue.items[queue.currentIndex]
        val successor = queue.items[queue.currentIndex + 1]
        queue.disableSmartShuffle()
        assertEquals(current, queue.items[queue.currentIndex])
        assertEquals(successor, queue.items[queue.currentIndex + 1])
    }
}
