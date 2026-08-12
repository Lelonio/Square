package dev.lelonio.square.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL
import dev.lelonio.square.ui.EXTRA_CONTEXT_ORDERED
import dev.lelonio.square.ui.EXTRA_CONTEXT_URI
import dev.lelonio.square.ui.EXTRA_PLAY_NEXT

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
        /** The first artist's uri, when it is known; see EXTRA_ARTIST_URI. */
        val artistUri: String? = null,
        val durationMs: Long,
        val artworkUri: Uri?,
        /**
         * Put here by "add to queue" rather than by the list this came from.
         *
         * Kept on the track so the insertion point never has to be remembered:
         * a queued run is simply the tracks marked this way sitting right after
         * the current one, and playing past them removes them from it by
         * itself. Not persisted — a queue survives a restart, the distinction
         * between "queued" and "in the playlist" does not.
         */
        val queued: Boolean = false,
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

    /**
     * Inserts tracks to play right after the current one.
     *
     * Queueing two tracks in a row has to play them in the order they were
     * queued, so the second goes *after* the first rather than in front of it:
     * the insertion point is the end of the run of already-queued tracks
     * following the current one, which is why [Track.queued] exists.
     *
     * Unlike [add] this keeps a shuffled queue shuffled. Dropping the
     * permutation would make the caller re-shuffle, and the track just placed
     * behind the current one would land somewhere random — the one thing the
     * gesture promises not to do.
     */
    fun insertNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val marked = tracks.map { it.copy(queued = true) }
        val at = nextInsertIndex()

        val order = originalIndices
        val original = originalOrder
        if (order != null && original != null) {
            // Appended to the pre-shuffle order rather than inserted into it:
            // every existing index in the permutation then stays valid.
            originalOrder = original + marked
            originalIndices = order.toMutableList().apply {
                addAll(at, (original.size until original.size + marked.size).toList())
            }
        }

        _items.addAll(at, marked)

        // The queue is no longer the context in its own order, so it must not
        // be handed to the engine as one: played that way Spotify supplies the
        // playlist's own track list and the inserted track is nowhere in it.
        contextIsOrdered = false
    }

    /** Just past the queued run that follows the current track. */
    fun nextInsertIndex(): Int {
        if (_items.isEmpty()) return 0
        var at = (currentIndex + 1).coerceAtMost(_items.size)
        while (at < _items.size && _items[at].queued) at++
        return at
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
        contextLabel = mediaItems.firstNotNullOfOrNull {
            it.mediaMetadata.extras?.getString(EXTRA_CONTEXT_LABEL)
        }.orEmpty()
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

    /**
     * What the player shows as the source: "Playlist · Estate 2025".
     *
     * Kept here and put back on every item the player publishes. The items that
     * arrive from a controller carry it in their extras, but the ones handed
     * *back* are rebuilt from this queue, and a rebuilt item has whatever is put
     * into it — which is how the label reached the service and never came out
     * the other side.
     */
    var contextLabel: String = ""
        private set

    /**
     * Puts back a context read from disk.
     *
     * The queue survives a restart and the context did not, so a paused song
     * came back belonging to nothing: no source in the player, and a listen
     * that Spotify would file outside the playlist it actually came from.
     */
    fun restoreContext(uri: String?, ordered: Boolean, label: String) {
        contextUri = uri
        contextIsOrdered = ordered
        contextLabel = label
    }

    /**
     * Insert controller-supplied items; see [replaceFromMediaItems] for the id
     * rule.
     *
     * An item asking to play next ignores the index the controller gave: a
     * `MediaController` lives in another process and cannot know where the
     * queued run ends, so it states the intent and the queue decides the place.
     */
    fun addFromMediaItems(index: Int, mediaItems: List<MediaItem>) {
        val tracks = mediaItems.mapNotNull(::toTrack)
        if (tracks.isNotEmpty() && tracks.all { it.queued }) insertNext(tracks)
        else add(index, tracks)
    }

    private fun toTrack(item: MediaItem): Track? {
        val uri = item.mediaId.takeIf { it.startsWith("spotify:") } ?: return null
        val metadata = item.mediaMetadata
        return Track(
            uri = uri,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            artistUri = metadata.extras?.getString(dev.lelonio.square.ui.EXTRA_ARTIST_URI),
            durationMs = metadata.durationMs ?: 0L,
            artworkUri = metadata.artworkUri,
            queued = metadata.extras?.getBoolean(EXTRA_PLAY_NEXT) == true,
        )
    }
}
