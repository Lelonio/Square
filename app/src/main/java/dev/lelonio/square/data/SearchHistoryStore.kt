package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What was found by searching and then played, kept locally.
 *
 * Songs rather than the words typed to find them, which is the part worth
 * keeping: half-typed terms and misspellings say nothing a week later, and the
 * song they led to is the thing anyone actually wants back. It also means the
 * list can be tapped — every row is a track, and playing it again is a tap
 * rather than a search run a second time.
 *
 * Local, like [RecentStore], and for the same reason: nothing is sent anywhere
 * and no quota is spent to keep it.
 */
class SearchHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CatalogTrack.serializer())
    private val mutex = Mutex()

    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<CatalogTrack>> = _tracks.asStateFlow()

    /** Records a play, moving a repeat to the front rather than adding it twice. */
    suspend fun record(track: CatalogTrack) = withContext(Dispatchers.IO) {
        mutex.withLock {
            save((listOf(track) + _tracks.value.filterNot { it.uri == track.uri }).take(LIMIT))
        }
    }

    /** Takes one song out, for a search somebody would rather not keep. */
    suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        mutex.withLock { save(_tracks.value.filterNot { it.uri == uri }) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { save(emptyList()) }
    }

    private fun save(tracks: List<CatalogTrack>) {
        _tracks.value = tracks
        prefs.edit().putString(KEY_TRACKS, json.encodeToString(serializer, tracks)).apply()
    }

    private fun load(): List<CatalogTrack> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE_NAME = "square_search_history"
        const val KEY_TRACKS = "tracks"

        /** A screenful and a half; older than that and it is a different day. */
        const val LIMIT = 20
    }
}
