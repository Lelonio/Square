package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The order playlists were last opened in, kept locally.
 *
 * Spotify's own rootlist comes back in the order the account created or added
 * them, which for an account of any age is close to arbitrary — the playlist
 * used every day can sit thirtieth. This records what was actually opened on
 * this device and puts those first.
 *
 * A list of URIs rather than a map of timestamps: order is the only thing being
 * asked of it, and a list needs no comparison to answer that.
 */
class PlaylistOrderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _order = MutableStateFlow(load())

    /** Most recently opened first. Playlists never opened are absent. */
    val order: StateFlow<List<String>> = _order.asStateFlow()

    fun record(uri: String) {
        val updated = (listOf(uri) + _order.value.filterNot { it == uri }).take(LIMIT)
        _order.value = updated
        prefs.edit().putString(KEY_ORDER, updated.joinToString(SEPARATOR)).apply()
    }

    fun clear() {
        _order.value = emptyList()
        prefs.edit().remove(KEY_ORDER).apply()
    }

    private fun load(): List<String> =
        prefs.getString(KEY_ORDER, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private companion object {
        const val FILE_NAME = "square_playlist_order"
        const val KEY_ORDER = "order"

        /** A URI contains no whitespace, so this can never appear inside one. */
        const val SEPARATOR = "\n"
        const val LIMIT = 100
    }
}

/**
 * Sorts by [order], keeping everything else in its original relative position.
 *
 * A stable partition rather than a full sort: playlists that have never been
 * opened must not be shuffled among themselves, or the tail of the list would
 * rearrange itself every time one of them was touched.
 */
fun List<CatalogPlaylist>.sortedByRecentlyOpened(order: List<String>): List<CatalogPlaylist> {
    if (order.isEmpty()) return this
    val rank = order.withIndex().associate { (index, uri) -> uri to index }
    val (opened, rest) = partition { rank.containsKey(it.uri) }
    return opened.sortedBy { rank.getValue(it.uri) } + rest
}
