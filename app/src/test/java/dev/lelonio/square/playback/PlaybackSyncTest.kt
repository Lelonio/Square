package dev.lelonio.square.playback

import dev.lelonio.square.playback.soloist.SoloistEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM checks of the playback position, buffering and Soloist state sync
 * logic: which statuses count as playing, how a held or running anchor reads
 * the position, and that the queue keeps the playing track when duplicates
 * and edits are involved.
 */
class PlaybackSyncTest {

    // ---- SoloistEngine.State: buffering vs. actual playback ----------------

    private fun state(status: String) = SoloistEngine.State(status = status)

    @Test fun playingStatusIsPlayingAndActuallyPlaying() {
        val s = state("playing")
        assertTrue(s.playing)
        assertTrue(s.isActuallyPlaying)
        assertFalse(s.isBuffering)
    }

    @Test fun bufferingStatusIsPlayingButNotActuallyPlaying() {
        val s = state("buffering")
        assertTrue(s.playing)
        assertTrue(s.isBuffering)
        assertFalse(s.isActuallyPlaying)
    }

    @Test fun pausedStatusIsNotPlayingBufferingOrActuallyPlaying() {
        val s = state("paused")
        assertFalse(s.playing)
        assertFalse(s.isBuffering)
        assertFalse(s.isActuallyPlaying)
    }

    @Test fun idleStatusIsNotPlayingBufferingOrActuallyPlaying() {
        val s = state("idle")
        assertFalse(s.playing)
        assertFalse(s.isBuffering)
        assertFalse(s.isActuallyPlaying)
    }

    @Test fun defaultStateIsIdle() {
        val s = SoloistEngine.State()
        assertEquals("idle", s.status)
        assertFalse(s.playing)
        assertFalse(s.isBuffering)
        assertFalse(s.isActuallyPlaying)
    }

    // ---- SoloistEngine.Anchor: held and running ----------------------------

    @Test fun anchorAtZeroSpeedStaysFrozenAtItsPosition() {
        val anchor = SoloistEngine.Anchor(positionMs = 42_000L, atMs = System.currentTimeMillis(), speed = 0.0)
        assertEquals(42_000L, anchor.now())
        Thread.sleep(20)
        assertEquals(42_000L, anchor.now())
    }

    @Test fun heldFreezesSpeedToZero() {
        val anchor = SoloistEngine.Anchor(positionMs = 5_000L, atMs = System.currentTimeMillis(), speed = 1.0)
        val held = anchor.held()
        assertEquals(0.0, held.speed, 0.0)
        assertTrue(held.positionMs >= 5_000L)
        // Frozen: the held position does not move with the clock.
        val first = held.now()
        Thread.sleep(20)
        assertEquals(first, held.now())
        assertEquals(held.positionMs, held.now())
    }

    @Test fun holdingAHeldAnchorReturnsItUnchanged() {
        val held = SoloistEngine.Anchor(positionMs = 7_000L, atMs = System.currentTimeMillis(), speed = 0.0)
        assertSame(held, held.held())
    }

    @Test fun runningRestoresSpeedToOne() {
        val held = SoloistEngine.Anchor(positionMs = 9_000L, atMs = System.currentTimeMillis(), speed = 0.0)
        val running = held.running()
        assertEquals(1.0, running.speed, 0.0)
        assertEquals(9_000L, running.positionMs)
        assertTrue(running.now() >= 9_000L)
    }

    @Test fun runningAnAnchorThatIsAlreadyRunningReturnsItUnchanged() {
        val running = SoloistEngine.Anchor(positionMs = 3_000L, atMs = System.currentTimeMillis(), speed = 1.0)
        assertSame(running, running.running())
    }

    @Test fun heldThenRunningKeepsThePositionItWasHeldAt() {
        val anchor = SoloistEngine.Anchor(positionMs = 11_000L, atMs = System.currentTimeMillis(), speed = 1.0)
        val held = anchor.held()
        val resumed = held.running()
        assertEquals(1.0, resumed.speed, 0.0)
        assertEquals(held.positionMs, resumed.positionMs)
        assertTrue(resumed.now() >= held.positionMs)
    }

    // ---- PlayQueue: duplicates, rapid skips and edits ----------------------

    private fun track(id: String) =
        PlayQueue.Track("spotify:track:$id", id, "artist", durationMs = 1000, artworkUri = null)

    private val PlayQueue.titles get() = items.map { it.title }
    private val PlayQueue.playingTitle get() = items[currentIndex].title

    @Test fun nearestIndexOfPicksTheDuplicateNearestTheStartingPlace() {
        val queue = PlayQueue()
        // a at 0, 2, 4
        queue.replace(listOf(track("a"), track("b"), track("a"), track("c"), track("a"), track("d")), 0)
        assertEquals(0, queue.nearestIndexOf("spotify:track:a", 0))
        assertEquals(2, queue.nearestIndexOf("spotify:track:a", 2))
        assertEquals(4, queue.nearestIndexOf("spotify:track:a", 4))
        assertEquals(4, queue.nearestIndexOf("spotify:track:a", 5))
    }

    @Test fun nearestIndexOfReturnsMinusOneWhenTheUriIsAbsent() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b")), 0)
        assertEquals(-1, queue.nearestIndexOf("spotify:track:z", 0))
    }

    @Test fun nearestIndexOfFromThePlayingTrackFindsThePlayingPlaceFirst() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("a"), track("c"), track("a")), 2)
        assertEquals(queue.currentIndex, queue.nearestIndexOf("spotify:track:a", queue.currentIndex))
    }

    @Test fun reportedDuplicateNearThePlayingPlaceIsNotTheFarOne() {
        val queue = PlayQueue()
        val tracks = (0 until 20).map { if (it == 0 || it == 19) track("a") else track("x$it") }
        queue.replace(tracks, 18)
        assertEquals(19, queue.nearestIndexOf("spotify:track:a", queue.currentIndex))
        queue.currentIndex = 1
        assertEquals(0, queue.nearestIndexOf("spotify:track:a", queue.currentIndex))
    }

    @Test fun rapidForwardAndBackwardSkipsKeepCurrentConsistentWithDuplicates() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("a"), track("b"), track("a"), track("c")), 0)
        val expectedTitles = listOf("a", "b", "a", "b", "a", "c")
        // Skip forward and back in a burst, as a user tapping next/previous would.
        val moves = listOf(1, 1, 1, -1, 1, 1, -1, -1, 1, 1, 1, 1, -1)
        var index = 0
        for (step in moves) {
            index = (index + step).coerceIn(0, expectedTitles.lastIndex)
            queue.currentIndex = index
            assertEquals(expectedTitles[index], queue.playingTitle)
            // Soloist reporting the playing uri resolves to the place playing.
            assertEquals(index, queue.nearestIndexOf(queue.items[index].uri, queue.currentIndex))
        }
    }

    @Test fun removingAnEarlierDuplicateKeepsThePlayingEntry() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("a"), track("c")), 2)
        val playingId = queue.idAt(queue.currentIndex)
        queue.remove(0, 1)
        assertEquals(listOf("b", "a", "c"), queue.titles)
        assertEquals(1, queue.currentIndex)
        assertEquals(playingId, queue.idAt(queue.currentIndex))
        assertEquals("spotify:track:a", queue.items[queue.currentIndex].uri)
    }

    @Test fun movingAnEarlierTrackAroundDuplicatesKeepsThePlayingEntry() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("a"), track("c")), 2)
        val playingId = queue.idAt(queue.currentIndex)
        queue.remove(0, 1)
        queue.move(0, 1, 2)
        assertEquals(listOf("a", "c", "b"), queue.titles)
        assertEquals(0, queue.currentIndex)
        assertEquals(playingId, queue.idAt(queue.currentIndex))
    }

    @Test fun removingThePlayingDuplicateLeavesAValidCurrentIndex() {
        val queue = PlayQueue()
        queue.replace(listOf(track("a"), track("b"), track("a"), track("c")), 2)
        queue.remove(2, 3)
        assertEquals(listOf("a", "b", "c"), queue.titles)
        assertTrue(queue.currentIndex in queue.items.indices)
    }

    @Test fun editsWithDuplicatesNeverMoveThePlayingEntry() {
        val queue = PlayQueue()
        val tracks = (0 until 8).map { track("t${it % 3}") }
        queue.replace(tracks, 4)
        val playingId = queue.idAt(4)!!

        fun assertStillPlaying(step: String) {
            assertTrue("$step: current out of range", queue.currentIndex in queue.items.indices)
            assertEquals("$step: playing entry moved", playingId, queue.idAt(queue.currentIndex))
        }

        queue.add(0, listOf(track("t0"), track("t1")))
        assertStillPlaying("add before")
        queue.move(1, 3, 6)
        assertStillPlaying("move")
        queue.remove(0, 2)
        assertStillPlaying("remove before")
        queue.insertNext(listOf(track("t0")))
        assertStillPlaying("insertNext")
        queue.setShuffled(true)
        assertStillPlaying("shuffle")
        queue.setShuffled(false)
        assertStillPlaying("unshuffle")
    }
}
