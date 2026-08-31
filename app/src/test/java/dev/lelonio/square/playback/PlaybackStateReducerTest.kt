package dev.lelonio.square.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackStateReducerTest {
    @Test
    fun initialStateIsIdleAndEmpty() {
        val state = PlaybackStateReducer().state

        assertEquals(PlaybackStatus.IDLE, state.status)
        assertEquals(null, state.currentMediaId)
        assertTrue(state.queueMediaIds.isEmpty())
        assertEquals(0, state.repeatMode)
    }

    @Test
    fun queueSelectionBecomesCurrentTrack() {
        val reducer = PlaybackStateReducer()

        reducer.setQueue(listOf("a", "b", "c"), currentIndex = 1)

        assertEquals("b", reducer.state.currentMediaId)
        assertEquals(1, reducer.state.currentIndex)
        assertEquals(PlaybackStatus.LOADING, reducer.state.status)
    }

    @Test
    fun playingAndPausedAreExplicitStates() {
        val reducer = PlaybackStateReducer()
        reducer.setTrack("track")

        reducer.setPlaying(true)
        assertEquals(PlaybackStatus.PLAYING, reducer.state.status)

        reducer.setPlaying(false)
        assertEquals(PlaybackStatus.PAUSED, reducer.state.status)
    }

    @Test
    fun bufferingCanReturnToPlayingOrPaused() {
        val reducer = PlaybackStateReducer()
        reducer.setTrack("track")

        reducer.setBuffering(true)
        assertEquals(PlaybackStatus.BUFFERING, reducer.state.status)

        reducer.setBuffering(false, playingWhenReady = true)
        assertEquals(PlaybackStatus.PLAYING, reducer.state.status)

        reducer.setBuffering(true)
        reducer.setBuffering(false, playingWhenReady = false)
        assertEquals(PlaybackStatus.PAUSED, reducer.state.status)
    }

    @Test
    fun seekAndPositionDoNotChangeTrackIdentity() {
        val reducer = PlaybackStateReducer()
        reducer.setTrack("track", durationMs = 100_000)

        reducer.setSeeking()
        reducer.setPosition(12_345)

        assertEquals("track", reducer.state.currentMediaId)
        assertEquals(12_345, reducer.state.positionMs)
        assertEquals(100_000, reducer.state.durationMs)
        assertEquals(PlaybackStatus.SEEKING, reducer.state.status)
    }

    @Test
    fun repeatShuffleAndBackendAreIndependentState() {
        val reducer = PlaybackStateReducer()

        reducer.setBackend("youtube")
        reducer.setRepeatMode(2)
        reducer.setShuffleEnabled(true)

        assertEquals("youtube", reducer.state.backendId)
        assertEquals(2, reducer.state.repeatMode)
        assertTrue(reducer.state.shuffleEnabled)
    }

    @Test
    fun errorIsTypedAndCanBeClearedToSafePausedState() {
        val reducer = PlaybackStateReducer()
        reducer.setTrack("track")

        reducer.reportError(PlaybackError.TrackUnavailable)
        assertEquals(PlaybackStatus.ERROR, reducer.state.status)
        assertEquals(PlaybackError.TrackUnavailable, reducer.state.error)

        reducer.clearError()
        assertEquals(PlaybackStatus.PAUSED, reducer.state.status)
        assertEquals(null, reducer.state.error)
    }

    @Test
    fun switchingTrackClearsPreviousErrorAndPosition() {
        val reducer = PlaybackStateReducer()
        reducer.setTrack("old", durationMs = 10_000)
        reducer.setPosition(5_000)
        reducer.reportError(PlaybackError.Network)

        reducer.setTrack("new", durationMs = 20_000)

        assertEquals("new", reducer.state.currentMediaId)
        assertEquals(0L, reducer.state.positionMs)
        assertEquals(20_000L, reducer.state.durationMs)
        assertEquals(null, reducer.state.error)
        assertEquals(PlaybackStatus.LOADING, reducer.state.status)
    }

    @Test
    fun resetPreservesSelectedBackendButClearsPlayback() {
        val reducer = PlaybackStateReducer()
        reducer.setBackend("spotify")
        reducer.setQueue(listOf("a", "b"), 1)
        reducer.setPlaying(true)

        reducer.reset()

        assertEquals("spotify", reducer.state.backendId)
        assertEquals(null, reducer.state.currentMediaId)
        assertTrue(reducer.state.queueMediaIds.isEmpty())
        assertEquals(PlaybackStatus.IDLE, reducer.state.status)
    }
}
