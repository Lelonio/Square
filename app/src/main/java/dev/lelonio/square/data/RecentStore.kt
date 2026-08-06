package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Recently played tracks, kept locally.
 *
 * Spotify does expose listening history, but only through the Web API, whose
 * quota is shared across every user of this client id and is the reason the rest
 * of the app reads from the access point instead. Recording locally gives the
 * same thing for this device without spending that budget — and it is honest
 * data rather than a section filled with something that only looks personal.
 */
class RecentStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CatalogTrack.serializer())

    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<CatalogTrack>> = _tracks.asStateFlow()

    /**
     * Records a play, moving it to the front if it is already there.
     *
     * De-duplicating by URI keeps a repeated track from filling the whole list,
     * which is what makes the section useless in practice.
     */
    suspend fun record(track: CatalogTrack) = withContext(Dispatchers.IO) {
        val updated = (listOf(track) + _tracks.value.filterNot { it.uri == track.uri })
            .take(LIMIT)
        _tracks.value = updated
        prefs.edit()
            .putString(KEY_TRACKS, json.encodeToString(serializer, updated))
            .apply()
    }

    fun clear() {
        _tracks.value = emptyList()
        prefs.edit().remove(KEY_TRACKS).apply()
    }

    private fun load(): List<CatalogTrack> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE_NAME = "square_recent"
        const val KEY_TRACKS = "tracks"
        const val LIMIT = 20
    }
}
