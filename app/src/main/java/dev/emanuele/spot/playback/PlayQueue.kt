package dev.emanuele.spot.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import dev.emanuele.spot.ui.EXTRA_CONTEXT_ORDERED
import dev.emanuele.spot.ui.EXTRA_CONTEXT_URI

/**
 * The queue the engine plays through.
 *
 * librespot loads one track at a time and has no notion of a playlist, so the
 * queue lives here and [LibrespotPlayer] advances it on `end_of_track`.
 *
 * Shuffle is implemented by reordering the list itself rather than keeping a
 * separate play order. `SimpleBasePlayer`'s timeline does not implement shuffle
 * ("TODO: Support shuffle order" upstream), so its next/previous would keep
 * walking the list linearly while end-of-track followed a shuffled order —
 * leaving the two disagreeing. Reordering the queue keeps one order for both.
 *
 * Not thread-safe by design: only the player's application looper touches it.
 */
class PlayQueue {

    data class Track(
        /** Spotify URI, e.g. `spotify:track:4cOdK2wGLETKBW3PvgPWqT`. */
        val uri: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val artworkUri: Uri?,
    )

    private val _items = mutableListOf<Track>()
    val items: List<Track> get() = _items

    var currentIndex: Int = 0

    /** Unshuffled queue, kept so the order can be restored exactly. */
    private var originalOrder: List<Track>? = null

    /** For each current position, its index in [originalOrder]. */
    private var originalIndices: List<Int>? = null

    val isShuffled: Boolean get() = originalOrder != null

    /** The queue as it was before shuffling, which is what gets persisted. */
    val originalTracks: List<Track> get() = originalOrder ?: _items

    /** The permutation currently applied, or null when not shuffled. */
    val shuffleOrder: List<Int>? get() = originalIndices

    /**
     * Re-applies a previously saved permutation.
     *
     * Restoring a shuffled queue cannot simply re-enable shuffle: that would
     * draw a *new* random order, and the queue the user left would be gone.
     */
    fun applyShuffleOrder(order: List<Int>) {
        val original = _items.toList()
        if (order.size != original.size || order.toSet() != original.indices.toSet()) return

        _items.clear()
        _items.addAll(order.map(original::get))
        originalOrder = original
        originalIndices = order
    }

    fun replace(tracks: List<Track>, startIndex: Int) {
        _items.clear()
        _items.addAll(tracks)
        currentIndex = startIndex.coerceIn(0, maxOf(0, _items.lastIndex))
        clearShuffle()
    }

    /**
     * Shuffles the queue, or restores the original order.
     *
     * The current track moves to the front rather than staying in place: it is
     * still playing, and anything before it in a freshly shuffled order would be
     * a "previous" the listener never heard.
     */
    fun setShuffled(shuffled: Boolean) {
        if (shuffled == isShuffled || _items.isEmpty()) return

        if (shuffled) {
            val original = _items.toList()
            val rest = original.indices.filter { it != currentIndex }.shuffled()
            val order = listOf(currentIndex) + rest

            _items.clear()
            _items.addAll(order.map(original::get))
            originalOrder = original
            originalIndices = order
            currentIndex = 0
        } else {
            val original = originalOrder ?: return
            // Index, not track identity: a playlist may hold the same track more
            // than once, and searching would land on the wrong copy.
            val restored = originalIndices?.getOrNull(currentIndex) ?: 0
            _items.clear()
            _items.addAll(original)
            currentIndex = restored.coerceIn(0, maxOf(0, _items.lastIndex))
            clearShuffle()
        }
    }

    private fun clearShuffle() {
        originalOrder = null
        originalIndices = null
    }

    fun add(index: Int, tracks: List<Track>) {
        val at = index.coerceIn(0, _items.size)
        _items.addAll(at, tracks)
        if (at <= currentIndex) currentIndex += tracks.size
        // The saved order no longer describes this queue.
        clearShuffle()
    }

    fun remove(fromIndex: Int, toIndex: Int) {
        val from = fromIndex.coerceIn(0, _items.size)
        val to = toIndex.coerceIn(from, _items.size)
        if (from == to) return

        _items.subList(from, to).clear()
        currentIndex = when {
            currentIndex >= to -> currentIndex - (to - from)
            currentIndex >= from -> from
            else -> currentIndex
        }.coerceIn(0, maxOf(0, _items.lastIndex))
        clearShuffle()
    }

    fun move(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val from = fromIndex.coerceIn(0, _items.size)
        val to = toIndex.coerceIn(from, _items.size)
        if (from == to) return

        val moved = ArrayList(_items.subList(from, to))
        val target = newIndex.coerceIn(0, _items.size - moved.size)
        val current = currentIndex

        _items.subList(from, to).clear()
        _items.addAll(target, moved)

        // Recomputed arithmetically rather than by searching for the playing
        // track: a playlist may hold the same track twice, and indexOf would
        // latch onto the wrong copy.
        currentIndex = if (current in from until to) {
            target + (current - from)
        } else {
            var shifted = if (current >= to) current - moved.size else current
            if (shifted >= target) shifted += moved.size
            shifted
        }.coerceIn(0, maxOf(0, _items.lastIndex))
        clearShuffle()
    }

    /**
     * Rebuilds the queue from Media3 items handed over by a controller.
     *
     * Anything a controller sends must already carry a Spotify URI as its media
     * id — there is no way to play an arbitrary [MediaItem] here — so items
     * without one are dropped rather than queued and skipped later.
     */
    fun replaceFromMediaItems(mediaItems: List<MediaItem>, startIndex: Int) {
        contextUri = mediaItems.firstNotNullOfOrNull {
            it.mediaMetadata.extras?.getString(EXTRA_CONTEXT_URI)
        }
        contextIsOrdered = mediaItems.firstOrNull()
            ?.mediaMetadata?.extras?.getBoolean(EXTRA_CONTEXT_ORDERED) == true
        replace(mediaItems.mapNotNull(::toTrack), startIndex)
    }

    /**
     * The playlist or album this queue came from, when it came from one.
     *
     * Carried for the listening history: Spotify files a play under the context
     * it happened in, and a queue of loose tracks is filed under nothing.
     */
    var contextUri: String? = null
        private set

    /** True when this queue is that context in its own order; see LibrespotPlayer. */
    var contextIsOrdered: Boolean = false
        private set

    /** Insert controller-supplied items; see [replaceFromMediaItems] for the id rule. */
    fun addFromMediaItems(index: Int, mediaItems: List<MediaItem>) {
        add(index, mediaItems.mapNotNull(::toTrack))
    }

    private fun toTrack(item: MediaItem): Track? {
        val uri = item.mediaId.takeIf { it.startsWith("spotify:") } ?: return null
        val metadata = item.mediaMetadata
        return Track(
            uri = uri,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            durationMs = metadata.durationMs ?: 0L,
            artworkUri = metadata.artworkUri,
        )
    }
}
