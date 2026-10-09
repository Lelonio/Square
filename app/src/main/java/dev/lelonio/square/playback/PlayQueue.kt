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
 * Every place in it is an [Entry] with an id of its own, and every change is
 * made to entries: see [Entry] for why not to indices.
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
        /** Every credited artist with a page of their own, in credit order. */
        val artists: List<dev.lelonio.square.data.CatalogArtist> = emptyList(),
        /**
         * The record it is on, and its address where the source gave one.
         *
         * Carried here because the queue rebuilds every item it holds — see
         * toMediaItemData — and whatever is not carried is lost to everything
         * that reads the player. The album's name went that way, which is why
         * the player could not say what record was playing.
         */
        val album: String = "",
        val albumUri: String? = null,
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
        /**
         * Whether the source rates the track as explicit.
         *
         * Carried for the same reason the album is: the queue rebuilds every
         * item it holds, and what it does not carry never reaches the player.
         */
        val explicit: Boolean = false,
        val recommended: Boolean = false,
    )

    /**
     * One place in the queue: a track, and an id that is this place's alone.
     *
     * The id is what everything else holds on to. A track can sit in the queue
     * twice, and an index stops meaning anything the moment something is added,
     * taken out or dragged above it; the old queue followed its tracks by index
     * and redid that arithmetic in every operation, for the list, the playing
     * track and the shuffle each, and every mistake in it was a queue that
     * rearranged itself.
     */
    class Entry(val id: Long, val track: Track)

    private var nextId = 1L
    private val _entries = mutableListOf<Entry>()
    val entries: List<Entry> get() = _entries

    private var cachedItems: List<Track>? = null

    /** The tracks in the order they play. */
    val items: List<Track>
        get() = cachedItems ?: _entries.map { it.track }.also { cachedItems = it }

    /**
     * Where playback is in [items].
     *
     * Set freely from outside, as the engine moves on. Every change made here
     * puts it back on the entry that was playing, wherever that entry went.
     */
    var currentIndex: Int = 0
    var smartShuffle: Boolean = false

    /**
     * The order shuffling was turned on in, by entry, or null when not shuffled.
     *
     * Nothing has to keep this in step. Entries taken out since are skipped when
     * it is read, and entries put in since are not in it at all: they keep the
     * place they were given relative to their neighbours, see [unshuffledOrder].
     */
    private var contextOrder: List<Long>? = null

    val isShuffled: Boolean get() = contextOrder != null

    /** The queue as it was before shuffling, which is what gets persisted. */
    val originalTracks: List<Track> get() = unshuffledOrder().map { it.track }

    /** For each place in [items], its index in [originalTracks]; null when not shuffled. */
    val shuffleOrder: List<Int>?
        get() {
            if (contextOrder == null) return null
            val at = unshuffledOrder().withIndex().associate { (index, entry) -> entry.id to index }
            return _entries.map { at.getValue(it.id) }
        }

    fun idAt(index: Int): Long? = _entries.getOrNull(index)?.id

    private fun entryOf(track: Track) = Entry(nextId++, track)

    /**
     * Runs a change and puts [currentIndex] back on the entry that was playing.
     *
     * When that entry is gone, playback stands where it was, at what moved up
     * into its place.
     */
    private inline fun edit(change: () -> Unit) {
        val playing = _entries.getOrNull(currentIndex)?.id
        val was = currentIndex
        change()
        cachedItems = null
        val found = playing?.let { id -> _entries.indexOfFirst { it.id == id } } ?: -1
        currentIndex = (if (found >= 0) found else was).coerceIn(0, maxOf(0, _entries.lastIndex))
    }

    /**
     * Re-applies a previously saved permutation.
     *
     * Restoring a shuffled queue cannot simply re-enable shuffle: that would
     * draw a *new* random order, and the queue the user left would be gone.
     */
    fun applyShuffleOrder(order: List<Int>) {
        val original = _entries.toList()
        if (order.size != original.size || order.toSet() != original.indices.toSet()) return
        edit {
            _entries.clear()
            _entries.addAll(order.map(original::get))
        }
        contextOrder = original.map { it.id }
    }

    fun replace(tracks: List<Track>, startIndex: Int) {
        smartShuffle = false
        contextOrder = null
        _entries.clear()
        _entries.addAll(tracks.map(::entryOf))
        cachedItems = null
        currentIndex = startIndex.coerceIn(0, maxOf(0, _entries.lastIndex))
    }

    /**
     * Shuffles the queue, or restores the original order.
     *
     * The current track moves to the front rather than staying in place: it is
     * still playing, and anything before it in a freshly shuffled order would be
     * a "previous" the listener never heard. Tracks queued by hand stay right
     * after it either way: they were asked for next, and shuffle is about the
     * playlist, not about them.
     */
    fun setShuffled(shuffled: Boolean) {
        if (shuffled == isShuffled || _entries.isEmpty()) return
        if (shuffled) {
            val order = _entries.map { it.id }
            val current = _entries[currentIndex.coerceIn(0, _entries.lastIndex)]
            val queued = queuedRun()
            val rest = _entries.filter { it !== current && it !in queued }.shuffled()
            edit {
                _entries.clear()
                _entries.add(current)
                _entries.addAll(queued)
                _entries.addAll(rest)
            }
            contextOrder = order
        } else {
            val restored = unshuffledOrder()
            edit {
                _entries.clear()
                _entries.addAll(restored)
            }
            contextOrder = null
        }
    }

    /** The hand-queued entries right after the playing one. */
    private fun queuedRun(): List<Entry> {
        val run = mutableListOf<Entry>()
        var at = currentIndex + 1
        while (at < _entries.size && _entries[at].track.queued) run += _entries[at++]
        return run
    }

    /**
     * What turning shuffle off gives: the order shuffle was turned on in.
     *
     * Entries added while shuffled were not there then. Each follows whatever
     * came before it in the shuffled order, so a track queued after the playing
     * one is still right after it once the playlist is back in its own order.
     */
    private fun unshuffledOrder(): List<Entry> {
        val order = contextOrder ?: return _entries.toList()
        val present = _entries.associateBy { it.id }
        val known = order.toSet()
        val result = order.mapNotNullTo(mutableListOf()) { present[it] }
        _entries.forEachIndexed { index, entry ->
            if (entry.id in known) return@forEachIndexed
            // After its predecessor, wherever that has gone; at the start when
            // it has none.
            val before = _entries.getOrNull(index - 1)
            val at = if (before == null) 0 else result.indexOf(before) + 1
            result.add(at, entry)
        }
        return result
    }

    fun enableSmartShuffle(recommendations: List<Track>) {
        val excluded = items.mapTo(mutableSetOf()) { it.uri }
        val candidates = recommendations.filter { it.uri !in excluded }.distinctBy { it.uri }
        val points = dev.lelonio.square.data.SmartShuffle.insertionPoints(items.map { it.queued }, currentIndex, candidates.size)
        points.zip(candidates).asReversed().forEach { (at, track) ->
            add(at, listOf(track.copy(recommended = true, queued = false)))
        }
        smartShuffle = points.isNotEmpty()
        if (smartShuffle) contextIsOrdered = false
    }

    fun disableSmartShuffle() {
        val current = _entries.getOrNull(currentIndex)
        val nextOriginal = _entries.drop(currentIndex + 1).firstOrNull { !it.track.recommended && !it.track.queued }
        edit {
            _entries.removeAll { it.track.recommended && !it.track.queued && it !== current }
        }
        smartShuffle = false
        setShuffled(false)
        // A recommended song already playing remains immediately before its successor.
        if (current?.track?.recommended == true && nextOriginal != null) {
            edit {
                _entries.remove(current)
                _entries.add(_entries.indexOf(nextOriginal).coerceAtLeast(0), current)
            }
        }
    }

    fun add(index: Int, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        edit { _entries.addAll(index.coerceIn(0, _entries.size), tracks.map(::entryOf)) }
    }

    /**
     * Inserts tracks to play right after the current one.
     *
     * Queueing two tracks in a row has to play them in the order they were
     * queued, so the second goes *after* the first rather than in front of it:
     * the insertion point is the end of the run of already-queued tracks
     * following the current one, which is why [Track.queued] exists.
     */
    fun insertNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val at = nextInsertIndex()
        edit { _entries.addAll(at, tracks.map { entryOf(it.copy(queued = true)) }) }

        // The queue is no longer the context in its own order, so it must not
        // be handed to the engine as one: played that way Spotify supplies the
        // playlist's own track list and the inserted track is nowhere in it.
        contextIsOrdered = false
    }

    /** Just past the queued run that follows the current track. */
    fun nextInsertIndex(): Int {
        if (_entries.isEmpty()) return 0
        var at = (currentIndex + 1).coerceAtMost(_entries.size)
        while (at < _entries.size && _entries[at].track.queued) at++
        return at
    }

    fun remove(fromIndex: Int, toIndex: Int) {
        val from = fromIndex.coerceIn(0, _entries.size)
        val to = toIndex.coerceIn(from, _entries.size)
        if (from == to) return
        edit { _entries.subList(from, to).clear() }
    }

    /**
     * Moves `[fromIndex, toIndex)` so that it starts at [newIndex] of the
     * result, which is how Media3 counts it.
     *
     * Shuffled, the order shuffle will go back to is left alone: the drag
     * placed a track in this shuffled order, not in the playlist's.
     */
    fun move(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val from = fromIndex.coerceIn(0, _entries.size)
        val to = toIndex.coerceIn(from, _entries.size)
        if (from == to) return
        edit {
            val moved = ArrayList(_entries.subList(from, to))
            _entries.subList(from, to).clear()
            _entries.addAll(newIndex.coerceIn(0, _entries.size), moved)
        }
        // No longer the context in its own order; see [insertNext].
        contextIsOrdered = false
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
        // The index has to be carried across the filter, not handed over as
        // it arrived.
        //
        // Items without a Spotify uri are dropped — a local file, an advert,
        // anything the engine cannot be asked to play — and every drop above
        // the chosen track pulls it one place further down a list that has not
        // moved on screen. Tapping the third song and hearing the fifth is
        // that, and it is consistent per playlist because the same rows are
        // dropped every time.
        val tracks = ArrayList<Track>(mediaItems.size)
        var chosen = 0
        mediaItems.forEachIndexed { position, item ->
            if (position == startIndex) chosen = tracks.size
            toTrack(item)?.let(tracks::add)
        }
        replace(tracks, chosen)
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

    /** The two parallel lists put back together; see EXTRA_ARTIST_NAMES. */
    private fun creditedArtists(
        extras: android.os.Bundle?,
    ): List<dev.lelonio.square.data.CatalogArtist> {
        val names = extras?.getStringArrayList(dev.lelonio.square.ui.EXTRA_ARTIST_NAMES)
        val uris = extras?.getStringArrayList(dev.lelonio.square.ui.EXTRA_ARTIST_URIS)
        if (names == null || uris == null) return emptyList()
        return names.zip(uris) { name, uri -> dev.lelonio.square.data.CatalogArtist(name, uri) }
    }

    /**
     * Where a uri is in this queue, counted from where the queue already is.
     *
     * A song can be in a playlist twice, and albums repeat across a queue built
     * from several of them. Asked for the first match, a queue told "the engine
     * is playing X" would jump to whichever X came first — which is what made
     * the screen walk backwards to a song played twenty minutes ago while the
     * speaker carried on. The nearest one is the one it means.
     *
     * Returns -1 when the uri is not here at all, which is a real answer: it
     * means another device chose something outside this queue.
     */
    fun nearestIndexOf(uri: String, from: Int): Int {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        items.forEachIndexed { index, track ->
            if (track.uri != uri) return@forEachIndexed
            val distance = kotlin.math.abs(index - from)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    private fun toTrack(item: MediaItem): Track? {
        val uri = item.mediaId.takeIf { it.startsWith("spotify:") } ?: return null
        val metadata = item.mediaMetadata
        return Track(
            uri = uri,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            artistUri = metadata.extras?.getString(dev.lelonio.square.ui.EXTRA_ARTIST_URI),
            artists = creditedArtists(metadata.extras),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumUri = metadata.extras?.getString(dev.lelonio.square.ui.EXTRA_ALBUM_URI),
            durationMs = metadata.durationMs ?: 0L,
            artworkUri = metadata.artworkUri,
            queued = metadata.extras?.getBoolean(EXTRA_PLAY_NEXT) == true,
            explicit = metadata.extras?.getBoolean(dev.lelonio.square.ui.EXTRA_EXPLICIT) == true,
            recommended = metadata.extras?.getBoolean(dev.lelonio.square.data.SmartShuffle.RECOMMENDED) == true,
        )
    }
}
