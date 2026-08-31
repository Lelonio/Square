package dev.lelonio.square.data

import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.backend.SearchLabels
import androidx.media3.common.Player
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class Phase5SearchDiscoveryTest {
    private fun backend(id: BackendId, result: SearchResults) = object : MusicBackend {
        override val id = id
        override val authState = MutableStateFlow<BackendAuthState>(BackendAuthState.LoggedIn(id.name))
        override val isReady = true
        var calls = 0
        override suspend fun refreshAuth() = Unit
        override suspend fun logOut() = Unit
        override suspend fun search(query: String, labels: SearchLabels): SearchResults {
            calls++
            return result
        }
        override suspend fun playlists(): List<CatalogPlaylist> = emptyList()
        override suspend fun tracksOf(uri: String): List<CatalogTrack> = emptyList()
        override fun owns(uri: String) = false
        override fun createPlayer(host: PlaybackHost): Player = error("not used")
    }

    @Test
    fun repositoryNormalizesAndCachesIdenticalQuery() = runTest {
        val b = backend(BackendId.SPOTIFY, SearchResults())
        val repository = SearchRepository({ b }, { SearchLabels("artist", "album", "playlist") })
        repository.search("  Daft   Punk  ")
        repository.search("daft punk")
        assertEquals(1, b.calls)
    }

    @Test
    fun laterPageIsRejectedUntilBackendExposesPagination() = runTest {
        val b = backend(BackendId.YOUTUBE_MUSIC, SearchResults())
        val repository = SearchRepository({ b }, { SearchLabels("artist", "album", "playlist") })
        repository.search("music")
        try {
            repository.search("music", page = 1)
            assertTrue("expected unsupported pagination", false)
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Pagination"))
        }
    }

    @Test
    fun compositeDiscoveryKeepsOtherSourcesWhenOneFails() = runTest {
        val good = object : RecommendationSource {
            override val id = "good"
            override suspend fun sections() = listOf(RecommendationSection("good:1", "Good", listOf()))
        }
        val bad = object : RecommendationSource {
            override val id = "bad"
            override suspend fun sections(): List<RecommendationSection> = error("offline")
        }
        val sections = CompositeRecommendationRepository(listOf(good, bad)).sections()
        assertEquals("good:1", sections.first().id)
        assertEquals("bad:unavailable", sections.last().id)
    }
}
