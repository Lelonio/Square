package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.SearchItem
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface LyricsRepository {
    suspend fun get(track: CatalogTrack): LyricsResult
    suspend fun search(query: String, labels: SearchLabels): LyricsSearchResponse
    fun offsetMs(trackUri: String, source: String): Long
    fun setOffsetMs(trackUri: String, source: String, offsetMs: Long)
    fun clearCache()
}

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics, val cached: Boolean) : LyricsResult
    data object Unavailable : LyricsResult
    data class Failed(val reason: LyricsFailure) : LyricsResult
}

enum class LyricsFailure { NETWORK, BACKEND_UNAVAILABLE, AUTH_REQUIRED, MALFORMED_RESPONSE }

data class LyricsSearchResult(val track: SearchItem, val source: String)

sealed interface LyricsSearchResponse {
    data class Found(val results: List<LyricsSearchResult>) : LyricsSearchResponse
    data class Failed(val reason: LyricsFailure) : LyricsSearchResponse
}

/** Bounded in-memory TTL cache; key includes backend identity and track URI. */
class DefaultLyricsRepository(
    private val backendProvider: () -> MusicBackend?,
    private val offsetStore: LyricsOffsetStore,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : LyricsRepository {
    private data class Cached(val lyrics: Lyrics, val expiresAtMs: Long)

    private val cache = object : LinkedHashMap<String, Cached>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
            size > CACHE_CAPACITY
    }

    override suspend fun get(track: CatalogTrack): LyricsResult {
        val backend = backendProvider() ?: return LyricsResult.Failed(LyricsFailure.BACKEND_UNAVAILABLE)
        val key = key(backend.id.name, track.uri)
        synchronized(cache) {
            cache[key]?.takeIf { it.expiresAtMs > clockMs() }?.let { return LyricsResult.Found(it.lyrics, true) }
            cache.remove(key)
        }

        val call = withContext(Dispatchers.IO) {
            runCatching { backend.lyrics(track.uri, track.name, track.artist, track.durationMs) }
        }
        val lyrics = call.getOrNull()
        if (call.isFailure) return LyricsResult.Failed(LyricsFailure.NETWORK)
        if (lyrics == null || lyrics.lines.isEmpty()) return LyricsResult.Unavailable
        synchronized(cache) { cache[key] = Cached(lyrics, clockMs() + CACHE_TTL_MS) }
        return LyricsResult.Found(lyrics, false)
    }

    override suspend fun search(query: String, labels: SearchLabels): LyricsSearchResponse {
        val normalized = query.trim()
        if (normalized.isEmpty()) return LyricsSearchResponse.Found(emptyList())
        val backend = backendProvider() ?: return LyricsSearchResponse.Failed(LyricsFailure.BACKEND_UNAVAILABLE)
        val call = withContext(Dispatchers.IO) {
            runCatching { backend.search(normalized, labels) }
        }
        val results = call.getOrNull() ?: return LyricsSearchResponse.Failed(LyricsFailure.NETWORK)
        val matches = results.lyricMatches
        val mapped = results.tracks.asSequence()
            .filter { matches.isEmpty() || matches.contains(it.uri) }
            .take(MAX_SEARCH_RESULTS)
            .map { LyricsSearchResult(SearchItem(it.uri, it.name, it.artist, it.artworkUrl), backend.id.name) }
            .toList()
        return LyricsSearchResponse.Found(mapped)
    }

    override fun offsetMs(trackUri: String, source: String): Long = offsetStore.get(trackUri, source)

    override fun setOffsetMs(trackUri: String, source: String, offsetMs: Long) =
        offsetStore.set(trackUri, source, offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS))

    override fun clearCache() = synchronized(cache) { cache.clear() }

    private fun key(source: String, uri: String): String = "$source|$uri"

    private companion object {
        const val CACHE_CAPACITY = 32
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        const val MAX_SEARCH_RESULTS = 30
        const val MIN_OFFSET_MS = -30_000L
        const val MAX_OFFSET_MS = 30_000L
    }
}

interface LyricsOffsetStore {
    fun get(trackUri: String, source: String): Long
    fun set(trackUri: String, source: String, offsetMs: Long)
}

class SharedPreferencesLyricsOffsetStore(
    private val preferences: android.content.SharedPreferences,
) : LyricsOffsetStore {
    override fun get(trackUri: String, source: String): Long = preferences.getLong(key(trackUri, source), 0L)

    override fun set(trackUri: String, source: String, offsetMs: Long) {
        preferences.edit().putLong(key(trackUri, source), offsetMs).apply()
    }

    private fun key(trackUri: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source|$trackUri".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "lyrics_offset:$digest"
    }
}
