package dev.lelonio.square.widget

import android.content.Context
import dev.lelonio.square.backend.BackendId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The playlists last opened, with what the widget needs to draw them.
 *
 * The library's own recency list keeps addresses only, and the widget is drawn
 * by the launcher with the app possibly not running: the name and the cover
 * have to be on disk already. Kept per source, so the widget never offers a
 * YouTube Music playlist to a Spotify session or the other way round.
 */
class WidgetPlaylists(context: Context) {
    @Serializable
    data class Entry(val uri: String, val name: String, val artworkUrl: String?, val backend: String)

    private val prefs = context.applicationContext.getSharedPreferences("square_widget_playlists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    fun record(uri: String, name: String, artworkUrl: String?, backend: BackendId) {
        // A page opened by address alone has no name yet; it is not one to show.
        if (name.isBlank()) return
        val entry = Entry(uri, name, artworkUrl, backend.name)
        save(backend, listOf(entry) + recent(backend).filterNot { it.uri == uri })
    }

    /**
     * Fills in from what the app already knew before the widget existed: the
     * library's own order of recently opened, with names and covers from the
     * library. Without it the widget started empty and only learned a playlist
     * when one was opened again. True when anything changed.
     */
    fun fill(order: List<String>, known: List<dev.lelonio.square.data.CatalogPlaylist>, backend: BackendId): Boolean {
        val byUri = known.associateBy { it.uri }
        val mine = recent(backend)
        val stored = mine.associateBy { it.uri }
        val fromOrder = order.mapNotNull { uri ->
            byUri[uri]?.takeIf { it.name.isNotBlank() }
                ?.let { Entry(uri, it.name, it.artworkUrl ?: stored[uri]?.artworkUrl, backend.name) }
                ?: stored[uri]
        }
        val updated = (fromOrder + mine.filter { it.uri !in order }).take(LIMIT)
        if (updated == mine) return false
        save(backend, updated)
        return true
    }

    fun recent(backend: BackendId): List<Entry> = all().filter { it.backend == backend.name }

    fun clear() = prefs.edit().remove(KEY).apply()

    /** This source's entries replaced, the other source's kept. */
    private fun save(backend: BackendId, entries: List<Entry>) {
        val updated = all().filter { it.backend != backend.name } + entries.take(LIMIT)
        prefs.edit().putString(KEY, json.encodeToString(serializer, updated)).apply()
    }

    private fun all(): List<Entry> =
        runCatching { json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "entries"
        const val LIMIT = 12
    }
}
