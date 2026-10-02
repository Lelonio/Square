package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer

/** Keeps the public queue on the main player while its outgoing audio overlaps the next song. */
@UnstableApi
class SkipFadePlayer(
    player: Player,
    private val fades: YouTubeFadeController,
    /** Zero while the listener has the setting off; see PreferencesStore. */
    private val fadeMs: () -> Int,
) : ForwardingPlayer(player) {

    private fun changing(change: () -> Unit) = fades.changeWith(fadeMs(), change)

    override fun release() {
        fades.release()
        super.release()
        fades.releaseMixer()
    }

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
