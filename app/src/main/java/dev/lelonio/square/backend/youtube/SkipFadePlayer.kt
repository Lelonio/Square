package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer

/**
 * The YouTube Music player, with the song being left behind faded out first.
 *
 * Everything that changes what is playing arrives here: the skip buttons, a
 * song picked from a list, a queue handed over whole. Each of them used to cut
 * the music dead and start the next one at full volume, which beside the
 * Spotify source, where the engine dissolves the two into each other, sounded
 * like the app changing its mind rather than changing song.
 *
 * One player holds one song, so the two halves cannot overlap the way the
 * engine's do; see [YouTubeFadeController.changeWith], which owns the volume
 * and runs them one after the other.
 *
 * A seek inside the song that is playing is not a change of song and is passed
 * straight through: dragging the progress bar is not something to dissolve.
 */
@UnstableApi
class SkipFadePlayer(
    player: Player,
    private val fades: YouTubeFadeController,
    /** Zero while the listener has the setting off; see PreferencesStore. */
    private val fadeMs: () -> Int,
) : ForwardingPlayer(player) {

    private fun changing(change: () -> Unit) = fades.changeWith(fadeMs(), change)

    override fun seekToNext() = changing { super.seekToNext() }

    override fun seekToNextMediaItem() = changing { super.seekToNextMediaItem() }

    override fun seekToPrevious() = changing { super.seekToPrevious() }

    override fun seekToPreviousMediaItem() = changing { super.seekToPreviousMediaItem() }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
        } else {
            changing { super.seekTo(mediaItemIndex, positionMs) }
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == currentMediaItemIndex) {
            super.seekToDefaultPosition(mediaItemIndex)
        } else {
            changing { super.seekToDefaultPosition(mediaItemIndex) }
        }
    }

    override fun setMediaItem(mediaItem: MediaItem) = changing { super.setMediaItem(mediaItem) }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        changing { super.setMediaItem(mediaItem, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        changing { super.setMediaItem(mediaItem, resetPosition) }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) =
        changing { super.setMediaItems(mediaItems) }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
        changing { super.setMediaItems(mediaItems, resetPosition) }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = changing { super.setMediaItems(mediaItems, startIndex, startPositionMs) }
}
