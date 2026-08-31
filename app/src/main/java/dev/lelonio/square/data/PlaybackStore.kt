package dev.lelonio.square.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A queue and where playback had got to in it. */
@Serializable
data class SavedPlayback(
    /** In the queue's *original* order, before any shuffle. */
    val tracks: List<CatalogTrack>,
    /**
     * The shuffle permutation in force, or null when not shuffled.
     *
     * Saved as a permutation rather than a boolean because re-enabling shuffle
     * on restore would draw a new random order, losing the queue the user had.
     */
    val shuffleOrder: List<Int>? = null,
    /** Index into the *current* (possibly shuffled) order. */
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int = 0,
    /** The playlist or album the queue came from, when it came from one. */
    val contextUri: String? = null,
    /** Whether the queue is that context in its own order. */
    val contextOrdered: Boolean = false,
    /** What the player shows as the source: "Playlist · Estate 2025". */
    val contextLabel: String = "",
)

/**
 * Persists the playing queue so a restart resumes where the user left off.
 *
 * Deliberately never records that playback was *running*: an app that starts
 * playing music by itself when opened is worse than one that forgets. The
 * restored session is always paused, ready at the right position.
 *
 * This class is persistence only. PlaybackService remains responsible for
 * deciding when a snapshot is authoritative and for applying a restored
 * snapshot to the live player.
 */
class PlaybackStore(context: Context) : PlaybackPersistence {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    override fun save(state: SavedPlayback) {
        if (state.tracks.isEmpty()) {
            clear()
            return
        }
        // Trimmed: a queue can be hundreds of tracks, and writing all of them a
        // few times a minute is a lot of I/O for something only the first screen
        // of which is ever seen again.
        // Trimming a shuffled queue would invalidate the permutation, so the
        // cap only applies when there is no order to keep consistent.
        val trimmed = if (state.shuffleOrder != null || state.tracks.size <= MAX_TRACKS) {
            state
        } else {
            state.copy(
                tracks = state.tracks.take(MAX_TRACKS),
                index = state.index.coerceIn(0, MAX_TRACKS - 1),
            )
        }
        prefs.edit()
            .putString(KEY_STATE, json.encodeToString(SavedPlayback.serializer(), trimmed))
            .apply()
    }

    override fun load(): SavedPlayback? {
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        return try {
            json.decodeFromString(SavedPlayback.serializer(), raw)
                .takeIf { it.tracks.isNotEmpty() }
        } catch (error: Exception) {
            // Corrupt persisted state must not crash service recreation, but it
            // also must not disappear silently. Clear only the invalid snapshot
            // so the next service instance starts from a known empty state.
            Log.e(TAG, "Discarding invalid persisted playback state", error)
            clear()
            null
        }
    }

    override fun clear() = prefs.edit().remove(KEY_STATE).apply()

    private companion object {
        const val TAG = "PlaybackStore"
        const val FILE_NAME = "square_playback"
        const val KEY_STATE = "state"
        const val MAX_TRACKS = 200
    }
}
