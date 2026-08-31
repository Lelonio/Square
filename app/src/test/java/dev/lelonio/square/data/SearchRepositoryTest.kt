package dev.lelonio.square.data

import androidx.media3.common.Player
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.backend.SearchLabels
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRepositoryTest {
    private class FakeBackend(
        override val id: BackendId,
        private val responseDelayMs: Long = 0,
    ) : MusicBackend {
        override val authState = MutableStateFlow<BackendAuthState>(BackendAuthState.LoggedIn(id.name))
        override val isReady = true
        var calls = 0
        override suspend fun refreshAuth() = Unit
        override suspend fun logOut() = Unit
        override suspend fun search(query: String, labels: SearchLabels): SearchResults {
            calls++
            if (responseDelayMs > 0) delay(responseDelayMs)
            return SearchResults()
        }
        override suspend fun playlists() = emptyList<CatalogPlaylist>()
        override suspend fun tracksOf(uri: String) = emptyList<CatalogTrack>()
        override fun owns(uri: String) = false
        override fun createPlayer(host: PlaybackHost): Player = error("not used")
    }

    @Test
    fun normalizedQueriesShareCacheEntry() = runTest {
        val backend = FakeBackend(BackendId.SPOTIFY)
        val repository = SearchRepository({ backend }, { SearchLabels("a", "b", "p") })
        repository.search("  Daft   Punk ")
        repository.search("daft punk")
        assertEquals(1, backend.calls)
    }

    @Test
    fun backendIdentitySeparatesProviderCaches() = runTest {
        val spotify = FakeBackend(BackendId.SPOTIFY)
        val youtube = FakeBackend(BackendId.YOUTUBE_MUSIC)
        var active: MusicBackend = spotify
        val repository = SearchRepository({ active }, { SearchLabels("a", "b", "p") })
        repository.search("music")
        active = youtube
        repository.search("music")
        assertEquals(1, spotify.calls)
        assertEquals(1, youtube.calls)
    }

    @Test
    fun concurrentIdenticalSearchesShareOneBackendRequest() = runTest {
        val backend = FakeBackend(BackendId.SPOTIFY, responseDelayMs = 100)
        val repository = SearchRepository({ backend }, { SearchLabels("a", "b", "p") })

        listOf("music", " MUSIC ", "music").map { query ->
            async { repository.search(query) }
        }.awaitAll()

        assertEquals(1, backend.calls)
    }
}
