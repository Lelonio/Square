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
        val queued = mediaItems.isNotEmpty() && mediaItems.all(::isQueued)
        val at = if (queued) queueEnd() else mediaItemCount
        super.addMediaItems(at, mediaItems)
        if (queued) playNextInShuffle(at, mediaItems.size)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, mutableListOf(mediaItem))

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        // The controller asks for the end; the queue knows better where that is.
        val queued = mediaItems.isNotEmpty() && mediaItems.all(::isQueued)
        val at = if (queued) queueEnd() else index
        super.addMediaItems(at, mediaItems)
        if (queued) playNextInShuffle(at, mediaItems.size)
    }

    /**
     * Shuffled, the songs just queued are played next too.
     *
     * Placing them after the song playing in the list is not enough with
     * shuffle on: ExoPlayer plays its own shuffled order, and gave the new
     * songs a random place in it, so "play next" played a random song next.
     * The order is rewritten with them right after the song playing and after
     * anything queued before them, and everything else as it was.
     */
    private fun playNextInShuffle(at: Int, count: Int) {
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer ?: return
        if (!shuffleModeEnabled || count <= 0) return
        val timeline = currentTimeline
        if (timeline.isEmpty) return
        val added = (at until at + count).toSet()
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != androidx.media3.common.C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        order.removeAll(added)
        var place = order.indexOf(currentMediaItemIndex) + 1
        while (place in order.indices && isQueued(getMediaItemAt(order[place]))) place++
        order.addAll(place.coerceIn(0, order.size), (at until at + count).toList())
        exo.setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()),
        )
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
