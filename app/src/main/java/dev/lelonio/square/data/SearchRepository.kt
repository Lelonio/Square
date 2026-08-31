package dev.lelonio.square.data

import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.SearchLabels
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page of backend-independent search data. */
data class SearchPage(
    val page: Int,
    val results: SearchResults,
    val hasMore: Boolean,
)

/** Stable state used by presentation code; backend models never cross this boundary. */
sealed interface SearchRepositoryResult {
    data object Idle : SearchRepositoryResult
    data object Loading : SearchRepositoryResult
    data class Success(val page: SearchPage, val cached: Boolean = false) : SearchRepositoryResult
    data class Failure(val message: String) : SearchRepositoryResult
}

/**
 * Thin repository boundary around the existing MusicBackend.
 *
 * The current backends expose a complete SearchResults response rather than a
 * cursor. This repository therefore provides safe page identity and bounded
 * in-memory caching without pretending that either provider has cursor support.
 */
class SearchRepository(
    private val backendProvider: () -> MusicBackend,
    private val labelsProvider: () -> SearchLabels,
    private val maxEntries: Int = 24,
) {
    private data class Key(val backend: String, val query: String, val page: Int)
    private val cache = LinkedHashMap<Key, SearchPage>(maxEntries, 0.75f, true)
    private val mutex = Mutex()

    suspend fun search(query: String, page: Int = 0): SearchPage {
        require(page >= 0)
        val backend = backendProvider()
        val normalized = normalize(query)
        require(normalized.isNotEmpty())
        val key = Key(backend.id.name, normalized, page)

        mutex.withLock { cache[key]?.let { return it } }

        // MusicBackend currently exposes a first-page search operation. Keep
        // page identity explicit so pagination can be added per backend later
        // without leaking provider cursors into Compose.
        check(page == 0) { "Pagination is not supported by ${backend.id}" }
        val result = backend.search(normalized, labelsProvider())
        val value = SearchPage(page = 0, results = result, hasMore = false)
        mutex.withLock {
            cache[key] = value
            while (cache.size > maxEntries) cache.remove(cache.entries.first().key)
        }
        return value
    }

    suspend fun invalidateBackend(backendId: String) {
        mutex.withLock { cache.keys.removeAll { it.backend == backendId } }
    }

    suspend fun clear() = mutex.withLock { cache.clear() }

    private fun normalize(query: String): String = query.trim().replace(Regex("\\s+"), " ").lowercase()
}
