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

    // "Play next" lands after the song playing and after whatever was queued
    // before it, as on the Spotify side (PlayQueue.insertNext). ExoPlayer on its
    // own appends, which put a queued song behind the rest of the playlist or
    // the radio: queued, and never reached.

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(mutableListOf(mediaItem))

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        val at = if (mediaItems.isNotEmpty() && mediaItems.all(::isQueued)) queueEnd() else mediaItemCount
        super.addMediaItems(at, mediaItems)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, mutableListOf(mediaItem))

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        // The controller asks for the end; the queue knows better where that is.
        val at = if (mediaItems.isNotEmpty() && mediaItems.all(::isQueued)) queueEnd() else index
        super.addMediaItems(at, mediaItems)
    }

    private fun isQueued(item: MediaItem) =
        item.mediaMetadata.extras?.getBoolean(dev.lelonio.square.ui.EXTRA_PLAY_NEXT) == true

    /** Just past the run of queued songs that follows the one playing. */
    private fun queueEnd(): Int {
        if (mediaItemCount == 0) return 0
        var at = currentMediaItemIndex + 1
        while (at < mediaItemCount && isQueued(getMediaItemAt(at))) at++
        return at
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
