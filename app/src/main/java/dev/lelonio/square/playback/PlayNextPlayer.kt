package dev.lelonio.square.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * An ExoPlayer whose "play next" plays next.
 *
 * "Play next" lands after the song playing and after whatever was queued
 * before it, as on the Spotify side (PlayQueue.insertNext). ExoPlayer on its
 * own appends, which put a queued song behind the rest of the playlist, the
 * folder or the radio: queued, and never reached (#180).
 *
 * The YouTube player and the player for the phone's own files both go through
 * this; see SkipFadePlayer and LocalPlayerFactory.
 *
 * Shuffle is done here too, the way PlayQueue does it on the Spotify side: by
 * reordering the list itself, with ExoPlayer's own order left as the list's.
 * ExoPlayer's shuffle kept the list as it was and played a hidden order
 * through it, so the queue on screen, its drag handles and "play next" all
 * spoke of the list while the music followed something else: the queue showed
 * the playlist's next song, not the one coming (#190, #180).
 */
@UnstableApi
open class PlayNextPlayer(player: Player) : ForwardingPlayer(player) {

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(mutableListOf(mediaItem))

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        val queued = mediaItems.isNotEmpty() && mediaItems.all(::isQueued)
        val at = if (queued) queueEnd() else mediaItemCount
        super.addMediaItems(at, mediaItems)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, mutableListOf(mediaItem))

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        // The controller asks for the end; the queue knows better where that is.
        val queued = mediaItems.isNotEmpty() && mediaItems.all(::isQueued)
        val at = if (queued) queueEnd() else index
        super.addMediaItems(at, mediaItems)
    }

    /** The list as it was when shuffle went on, to go back to; see [unshuffledOrder]. */
    private var original: List<MediaItem>? = null

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == this.shuffleModeEnabled) return
        if (shuffleModeEnabled) {
            val items = items()
            original = items
            if (items.size > 1) {
                // The playing song first, then what was queued by hand after
                // it, then the rest in any order; as PlayQueue.setShuffled.
                val current = items[currentMediaItemIndex.coerceIn(items.indices)]
                val queued = queuedRun()
                reorderAround(listOf(current) + queued + items.filter { it !== current && queued.none { q -> q === it } }.shuffled())
            }
            playListOrder()
            super.setShuffleModeEnabled(true)
        } else {
            super.setShuffleModeEnabled(false)
            val restored = unshuffledOrder()
            original = null
            if (restored.size > 1) reorderAround(restored)
        }
    }

    // A new queue arriving while shuffled is shuffled on the way in, the song
    // asked for first. ExoPlayer would otherwise play it in order: its own
    // order is the list's, see above.

    // Each goes straight to the player below rather than through another of
    // these: SkipFadePlayer wraps each in a fade, and one calling another
    // faded twice.

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        original = mediaItems.toList()
        if (!shuffleModeEnabled || mediaItems.size < 2) return super.setMediaItems(mediaItems)
        playListOrder()
        super.setMediaItems(mediaItems.shuffled().toMutableList())
        playListOrder()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        original = mediaItems.toList()
        if (!shuffleModeEnabled || mediaItems.size < 2) return super.setMediaItems(mediaItems, resetPosition)
        playListOrder()
        super.setMediaItems(mediaItems.shuffled().toMutableList(), resetPosition)
        playListOrder()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        original = mediaItems.toList()
        if (!shuffleModeEnabled || mediaItems.size < 2) {
            return super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
        playListOrder()
        if (startIndex !in mediaItems.indices) {
            super.setMediaItems(mediaItems.shuffled().toMutableList(), startIndex, startPositionMs)
        } else {
            val first = mediaItems[startIndex]
            val rest = mediaItems.filterIndexed { index, _ -> index != startIndex }.shuffled()
            super.setMediaItems((listOf(first) + rest).toMutableList(), 0, startPositionMs)
        }
        playListOrder()
    }

    /** ExoPlayer's own shuffle order made the list's, so it plays what is shown. */
    private fun playListOrder() {
        val exo = wrappedPlayer as? ExoPlayer ?: return
        exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(exo.mediaItemCount))
    }

    private fun items(): List<MediaItem> = (0 until mediaItemCount).map(::getMediaItemAt)

    /** The hand-queued songs right after the playing one. */
    private fun queuedRun(): List<MediaItem> {
        val run = mutableListOf<MediaItem>()
        var at = currentMediaItemIndex + 1
        while (at < mediaItemCount && isQueued(getMediaItemAt(at))) run += getMediaItemAt(at++)
        return run
    }

    /**
     * What turning shuffle off gives: the order shuffle was turned on in.
     *
     * Songs added while shuffled were not there then. Each follows whatever
     * came before it in the shuffled order, so a song queued after the playing
     * one is still right after it once the list is back in its own order; as
     * PlayQueue.unshuffledOrder.
     */
    private fun unshuffledOrder(): List<MediaItem> {
        val items = items()
        val order = original ?: return items
        // By song rather than by object: the player may hold a copy of the
        // item it was given. The same song twice is told apart by which time
        // it is.
        val present = keysOf(items).zip(items).toMap()
        val known = keysOf(order).toSet()
        val result = keysOf(order).mapNotNullTo(mutableListOf()) { present[it] }
        keysOf(items).forEachIndexed { index, key ->
            if (key in known) return@forEachIndexed
            val before = items.getOrNull(index - 1)
            val at = if (before == null) 0 else result.indexOfFirst { it === before } + 1
            result.add(at, items[index])
        }
        return result
    }

    private fun keysOf(items: List<MediaItem>): List<String> {
        val seen = HashMap<String, Int>()
        return items.map { item ->
            val n = seen.merge(item.mediaId, 1, Int::plus)!!
            "${item.mediaId}#$n"
        }
    }

    /**
     * Puts the list in [target]'s order without touching the playing song.
     *
     * Everything around it is taken out and put back, which is four changes
     * whatever the length; moving songs one at a time was one change each,
     * and every change goes out to the screen, the notification and the car.
     */
    private fun reorderAround(target: List<MediaItem>) {
        val player = wrappedPlayer
        val count = player.mediaItemCount
        if (count == 0) return
        val at = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val current = player.getMediaItemAt(at)
        val k = target.indexOfFirst { it === current }
        if (k < 0) return
        if (at + 1 < count) player.removeMediaItems(at + 1, count)
        if (at > 0) player.removeMediaItems(0, at)
        if (k > 0) player.addMediaItems(0, target.subList(0, k))
        if (k + 1 < target.size) player.addMediaItems(k + 1, target.subList(k + 1, target.size))
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
}
