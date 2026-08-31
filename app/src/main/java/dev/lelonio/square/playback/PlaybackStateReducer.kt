package dev.lelonio.square.playback

import androidx.media3.common.Player

/**
 * Pure state transitions used by the playback layer.
 *
 * The reducer has no Android lifecycle, coroutine, or backend dependency. It is
 * intentionally not a replacement for Media3/PlaybackService; it provides a
 * deterministic contract for state mutation and a small seam for concurrency
 * and lifecycle tests.
 */
class PlaybackStateReducer(initial: PlaybackStateSnapshot = PlaybackStateSnapshot()) {
    var state: PlaybackStateSnapshot = initial
        private set

    fun setBackend(backendId: String?) {
        state = state.copy(backendId = backendId, error = null)
    }

    fun setQueue(mediaIds: List<String>, currentIndex: Int = 0) {
        val safeIndex = currentIndex.coerceIn(0, mediaIds.lastIndex.coerceAtLeast(0))
        state = state.copy(
            queueMediaIds = mediaIds.toList(),
            currentIndex = safeIndex,
            currentMediaId = mediaIds.getOrNull(safeIndex),
            positionMs = 0L,
            durationMs = 0L,
            error = null,
            status = if (mediaIds.isEmpty()) PlaybackStatus.IDLE else PlaybackStatus.LOADING,
        )
    }

    fun setTrack(mediaId: String?, durationMs: Long = 0L) {
        state = state.copy(
            currentMediaId = mediaId,
            durationMs = durationMs.coerceAtLeast(0L),
            positionMs = 0L,
            error = null,
            status = if (mediaId == null) PlaybackStatus.IDLE else PlaybackStatus.LOADING,
        )
    }

    fun setPlaying(playing: Boolean) {
        if (state.currentMediaId == null) return
        state = state.copy(
            status = if (playing) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
            error = null,
        )
    }

    fun setBuffering(buffering: Boolean) {
        if (state.currentMediaId == null) return
        state = state.copy(status = if (buffering) PlaybackStatus.BUFFERING else state.status)
    }

    fun setSeeking() {
        if (state.currentMediaId != null) state = state.copy(status = PlaybackStatus.SEEKING)
    }

    fun setPosition(positionMs: Long) {
        state = state.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    fun setDuration(durationMs: Long) {
        state = state.copy(durationMs = durationMs.coerceAtLeast(0L))
    }

    fun setRepeatMode(repeatMode: Int) {
        state = state.copy(repeatMode = repeatMode)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        state = state.copy(shuffleEnabled = enabled)
    }

    fun setCurrentIndex(index: Int) {
        if (state.queueMediaIds.isEmpty()) return
        val safeIndex = index.coerceIn(0, state.queueMediaIds.lastIndex)
        state = state.copy(
            currentIndex = safeIndex,
            currentMediaId = state.queueMediaIds[safeIndex],
            positionMs = 0L,
            error = null,
            status = PlaybackStatus.LOADING,
        )
    }

    fun reportError(error: PlaybackError) {
        state = state.copy(status = PlaybackStatus.ERROR, error = error)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun reset() {
        state = PlaybackStateSnapshot(
            repeatMode = Player.REPEAT_MODE_OFF,
            backendId = state.backendId,
        )
    }
}
