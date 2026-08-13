package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playlists the listener has pinned to the top of their library.
 *
 * Kept here rather than on the account: Spotify has no such concept, so there
 * is nothing to send and nothing to read back. That also means the pins belong
 * to this device, which is the honest behaviour for something the service does
 * not know about.
 *
 * Ordered, oldest pin first, so the shelf at the top does not rearrange itself
 * every time another playlist is pinned.
 */
class PinnedPlaylistStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(load())

    /** URIs in the order they were pinned. */
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    fun isPinned(uri: String): Boolean = uri in _pinned.value

    fun toggle(uri: String) {
        val current = _pinned.value
        val updated = if (uri in current) {
            current - uri
        } else {
            (current + uri).takeLast(LIMIT)
        }
        _pinned.value = updated
        prefs.edit().putString(KEY_PINNED, updated.joinToString(SEPARATOR)).commit()
    }

    private fun load(): List<String> =
        prefs.getString(KEY_PINNED, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private companion object {
        const val FILE_NAME = "square_pinned_playlists"
        const val KEY_PINNED = "pinned"

        /** A URI contains no whitespace, so this can never appear inside one. */
        const val SEPARATOR = "\n"

        /** Enough that nobody meets it; a guard against a runaway list. */
        const val LIMIT = 50
    }
}

/**
 * Lifts the pinned playlists to the front, whatever the sort underneath.
 *
 * The sort the listener chose still applies: this only says which rows come
 * first, and pinning is an instruction the sort has no business overruling.
 */
fun List<CatalogPlaylist>.withPinnedFirst(pinned: List<String>): List<CatalogPlaylist> {
    if (pinned.isEmpty()) return this
    val rank = pinned.withIndex().associate { (index, uri) -> uri to index }
    val (top, rest) = partition { rank.containsKey(it.uri) }
    return top.sortedBy { rank.getValue(it.uri) } + rest
}
