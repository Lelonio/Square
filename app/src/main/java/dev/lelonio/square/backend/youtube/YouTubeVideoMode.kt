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
        val onVideo = player.currentMediaItem?.mediaId?.startsWith(YouTubeBackend.VIDEO_PREFIX) == true
        _enabled.value = !_enabled.value
        // On a video the button means "this one as sound", and the mode goes
        // back to following the queue once it moves on.
        audioOnly = onVideo && !_enabled.value
        byItem = onVideo && _enabled.value
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.isPlaying
        player.stop()
        // A new source for the item, so it is built for the mode it is now in:
        // the picture comes as its own stream; see VideoAwareSourceFactory.
        player.currentMediaItem?.let { player.replaceMediaItem(index, it) }
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
        byItem = false
        audioOnly = false
    }

    /**
     * Whether the mode is on because the item playing is a video, rather than
     * because somebody pressed the button: it then goes off again with the item.
     */
    @Volatile
    var byItem = false
        private set

    /** Why a song's official video could not be shown in its place. */
    enum class Unavailable { AGE_RESTRICTED, OTHER }

    private val _unavailable = MutableStateFlow<Map<String, Unavailable>>(emptyMap())

    /** By video id of the song: the songs showing their own upload instead of their video. */
    val unavailable: StateFlow<Map<String, Unavailable>> = _unavailable.asStateFlow()

    fun markUnavailable(songId: String, reason: Unavailable) {
        _unavailable.value = _unavailable.value + (songId to reason)
    }

    /** Set when the listener asked for the video playing to be sound alone. */
    @Volatile
    private var audioOnly = false

    /** Follows the queue: on for a video, off again for the song after it. */
    fun onItem(mediaId: String?) {
        audioOnly = false
        val video = mediaId?.startsWith(YouTubeBackend.VIDEO_PREFIX) == true
        if (video && !_enabled.value) {
            byItem = true
            _enabled.value = true
        } else if (!video && byItem) {
            byItem = false
            _enabled.value = false
        }
    }

    /** Whether [uri] should open as video: a video always, a song when the button says so. */
    fun wantsVideo(uri: String): Boolean =
        if (uri.startsWith(YouTubeBackend.VIDEO_PREFIX)) !audioOnly else _enabled.value && !byItem
}
