package dev.lelonio.square.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlaybackStateRecoveryTest {
    @Test
    fun recoveringPreservesTrackAndPosition() {
        val reducer = PlaybackStateReducer().apply {
            setTrack("spotify:track:a", durationMs = 200_000)
            setPosition(12_345)
            setPlaying(true)
            setRecovering(PlaybackError.Network)
        }

        assertEquals(PlaybackStatus.RECOVERING, reducer.state.status)
        assertEquals("spotify:track:a", reducer.state.currentMediaId)
        assertEquals(12_345L, reducer.state.positionMs)
        assertSame(PlaybackError.Network, reducer.state.error)
    }

    @Test
    fun completedIsDistinctFromTerminalError() {
        val reducer = PlaybackStateReducer().apply {
            setTrack("spotify:track:a")
            setEnded()
        }

        assertEquals(PlaybackStatus.ENDED, reducer.state.status)
        assertEquals(null, reducer.state.error)
    }
}
