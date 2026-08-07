package dev.lelonio.square.backend.youtube

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether YouTube tracks play as video rather than as sound alone.
 *
 * Process-wide, like [dev.lelonio.square.playback.AudioEffects], and for the
 * same reason: the thing that reads it is the stream resolver, which runs inside
 * the playback service on ExoPlayer's loading thread, while the thing that sets
 * it is a button in the player. There is no session command that carries it.
 *
 * Deliberately not persisted. Video is something you turn on for one song —
 * reopening the app into it, on data, is not what anyone meant.
 */
object YouTubeVideoMode {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Flips the mode and reloads the current track in the new one.
     *
     * The reload is the point: the stream URL is chosen when the source opens,
     * so a track already playing keeps whatever it was given until something
     * makes it open again. Stopping and preparing does that while keeping the
     * queue, and the position is put back by hand because stopping loses it.
     */
    fun toggle(player: Player) {
        _enabled.value = !_enabled.value
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.isPlaying
        player.stop()
        player.seekTo(index, position)
        player.prepare()
        if (wasPlaying) player.play()
    }

    private val _pictureInPicture = MutableStateFlow(false)

    /**
     * Whether the app is currently a floating window.
     *
     * The player screen draws only the picture while it is: a window that small
     * has room for the video and nothing else, and the transport controls are
     * the system's to draw there.
     */
    val pictureInPicture: StateFlow<Boolean> = _pictureInPicture.asStateFlow()

    fun setPictureInPicture(active: Boolean) {
        _pictureInPicture.value = active
    }

    /** Back to sound alone — for when the source or the track changes under it. */
    fun reset() {
        _enabled.value = false
    }
}
