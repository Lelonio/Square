package dev.lelonio.square.playback

import org.junit.Assert.*
import org.junit.Test

class PlayQueueTest {
    private fun track(id: String, queued: Boolean = false) =
        PlayQueue.Track("spotify:track:$id", id, "artist", durationMs = 1000, artworkUri = null, queued = queued)

    private fun queueOf(count: Int, current: Int = 0) =
        PlayQueue().apply { replace((0 until count).map { track("$it") }, current) }

    private val PlayQueue.titles get() = items.map { it.title }
    private val PlayQueue.playing get() = items[currentIndex].title

    @Test fun moveDownPlacesTrackWhereMedia3SaysAndKeepsPlaying() {
        val queue = queueOf(6, current = 1)
        queue.move(2, 3, 4)
        assertEquals(listOf("0", "1", "3", "4", "2", "5"), queue.titles)
        assertEquals("1", queue.playing)
    }

    @Test fun moveUpAndMovingThePlayingTrack() {
        val queue = queueOf(6, current = 2)
        queue.move(5, 6, 3)
        assertEquals(listOf("0", "1", "2", "5", "3", "4"), queue.titles)
        assertEquals("2", queue.playing)
        queue.move(2, 3, 4)
        assertEquals("2", queue.playing)
        assertEquals(4, queue.currentIndex)
    }

    @Test fun moveWhileShuffledKeepsTheShuffleAndTheRestOfTheOrder() {
        val queue = queueOf(20, current = 0)
        queue.setShuffled(true)
        val before = queue.titles
        queue.move(7, 8, 2)
        val expected = before.toMutableList().apply { add(2, removeAt(7)) }
        assertEquals(expected, queue.titles)
        assertTrue(queue.isShuffled)
    }

    @Test fun ids_followEntriesThroughMovesAndDuplicates() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("a")), 0)
        val ids = queue.entries.map { it.id }
        assertEquals(3, ids.toSet().size)
        queue.move(0, 1, 2)
        assertEquals(listOf(ids[1], ids[2], ids[0]), queue.entries.map { it.id })
        assertEquals(ids[0], queue.idAt(queue.currentIndex))
    }

    @Test fun removingAroundThePlayingTrackKeepsIt() {
        val queue = queueOf(6, current = 3)
        queue.remove(0, 2)
        assertEquals("3", queue.playing)
        queue.remove(2, 4)
        assertEquals(listOf("2", "3"), queue.titles)
        assertEquals("3", queue.playing)
    }

    @Test fun removingThePlayingTrackStaysAtItsPlace() {
        val queue = queueOf(5, current = 2)
        queue.remove(2, 3)
        assertEquals("3", queue.playing)
    }

    @Test fun addingBeforeThePlayingTrackPushesItDown() {
        val queue = queueOf(3, current = 1)
        queue.add(0, listOf(track("x"), track("y")))
        assertEquals("1", queue.playing)
        queue.add(queue.currentIndex, listOf(track("z")))
        assertEquals("1", queue.playing)
    }

    @Test fun insertNextQueuesInOrderAfterThePlayingTrack() {
        val queue = queueOf(4, current = 1)
        queue.insertNext(listOf(track("x")))
        queue.insertNext(listOf(track("y")))
        assertEquals(listOf("0", "1", "x", "y", "2", "3"), queue.titles)
        assertTrue(queue.items[2].queued && queue.items[3].queued)
    }

    @Test fun shuffleKeepsPlayingTrackFirstAndQueuedRightAfter() {
        val queue = queueOf(10, current = 4)
        queue.insertNext(listOf(track("x")))
        queue.setShuffled(true)
        assertEquals(0, queue.currentIndex)
        assertEquals("4", queue.playing)
        assertEquals("x", queue.items[1].title)
        assertEquals(11, queue.items.size)
    }

    @Test fun unshuffleRestoresOrderAfterEditsMadeWhileShuffled() {
        val queue = queueOf(10, current = 0)
        queue.setShuffled(true)
        val removed = queue.items[3].title
        queue.remove(3, 4)
        queue.move(5, 6, 1)
        queue.insertNext(listOf(track("x")))
        queue.setShuffled(false)
        assertFalse(queue.isShuffled)
        val expected = (0 until 10).map { "$it" }.filter { it != removed }.toMutableList()
            .apply { add(indexOf(queue.playing) + 1, "x") }
        assertEquals(expected, queue.titles)
    }

    @Test fun savedShuffleComesBackAsItWas() {
        val queue = queueOf(12, current = 5)
        queue.setShuffled(true)
        queue.insertNext(listOf(track("x")))
        queue.remove(6, 7)
        val order = queue.shuffleOrder!!
        val tracks = queue.originalTracks
        val shuffled = queue.titles

        val restored = PlayQueue()
        restored.replace(tracks, 0)
        restored.applyShuffleOrder(order)
        assertEquals(shuffled, restored.titles)
        assertTrue(restored.isShuffled)
    }

    @Test fun notShuffledHasNoShuffleOrder() {
        val queue = queueOf(4)
        assertNull(queue.shuffleOrder)
        assertEquals(queue.items, queue.originalTracks)
    }
}
