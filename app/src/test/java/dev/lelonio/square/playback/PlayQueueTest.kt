package dev.lelonio.square.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayQueueTest {
    private fun track(id: String, queued: Boolean = false) = PlayQueue.Track(
        uri = "spotify:track:$id",
        title = id,
        artist = "artist",
        durationMs = 1_000L,
        artworkUri = null,
        queued = queued,
    )

    @Test
    fun addKeepsCurrentIndexStableWhenAppending() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b")), startIndex = 0)

        queue.add(2, listOf(track("c")))

        assertEquals(listOf("a", "b", "c"), queue.items.map { it.uri.substringAfterLast(':') })
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun insertNextPreservesQueueOrder() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("c")), startIndex = 0)

        queue.insertNext(listOf(track("x"), track("y")))

        assertEquals(listOf("a", "x", "y", "b", "c"), queue.items.map { it.uri.substringAfterLast(':') })
        assertEquals(listOf("x", "y"), queue.items.subList(1, 3).map { it.uri.substringAfterLast(':') })
        assertTrue(queue.items[1].queued && queue.items[2].queued)
    }

    @Test
    fun removeAdjustsCurrentIndexWithoutSearchingByTrackId() {
        val duplicateA = track("a")
        val queue = PlayQueue()
        queue.replace(listOf(duplicateA, track("b"), duplicateA, track("c")), startIndex = 2)

        queue.remove(0, 1)

        assertEquals(listOf("b", "a", "c"), queue.items.map { it.uri.substringAfterLast(':') })
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun movePreservesThePlayingItemByIndex() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("c"), track("d")), startIndex = 2)

        queue.move(0, 1, 3)

        assertEquals(listOf("b", "c", "d", "a"), queue.items.map { it.uri.substringAfterLast(':') })
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun shuffleCanBeDisabledWithoutLosingOriginalOrder() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("c"), track("d")), startIndex = 2)

        queue.setShuffled(true)
        assertTrue(queue.isShuffled)
        assertEquals("c", queue.items.first().uri.substringAfterLast(':'))
        assertEquals(setOf("a", "b", "c", "d"), queue.items.map { it.uri.substringAfterLast(':') }.toSet())

        queue.setShuffled(false)

        assertEquals(listOf("a", "b", "c", "d"), queue.items.map { it.uri.substringAfterLast(':') })
        assertEquals(2, queue.currentIndex)
        assertTrue(!queue.isShuffled)
    }
}
