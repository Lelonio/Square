package dev.lelonio.square.backend.youtube

import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.pages.LibraryContinuationPage
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.collectAllItems
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPaginationTest {

    @Test
    fun collectsEveryPageInOrderAndRemovesDuplicateItems() = runBlocking {
        val requests = mutableListOf<String>()
        val first = LibraryPage(
            items = listOf(playlist("one"), playlist("two")),
            continuation = "page-2",
        )

        val result = first.collectAllItems { token ->
            requests += token
            when (token) {
                "page-2" -> Result.success(
                    LibraryContinuationPage(
                        items = listOf(playlist("two"), playlist("three")),
                        continuation = "page-3",
                    ),
                )
                "page-3" -> Result.success(
                    LibraryContinuationPage(
                        items = listOf(playlist("four")),
                        continuation = null,
                    ),
                )
                else -> error("Unexpected continuation: $token")
            }
        }

        assertEquals(listOf("page-2", "page-3"), requests)
        assertEquals(listOf("one", "two", "three", "four"), result.map { it.id })
    }

    @Test
    fun keepsAlreadyReadItemsWhenAContinuationRequestFails() = runBlocking {
        val result = LibraryPage(
            items = listOf(playlist("one")),
            continuation = "unavailable",
        ).collectAllItems {
            Result.failure(IllegalStateException("temporary network failure"))
        }

        assertEquals(listOf("one"), result.map { it.id })
    }

    @Test
    fun stopsWhenTheServiceRepeatsAContinuationToken() = runBlocking {
        val requests = mutableListOf<String>()
        val result = LibraryPage(
            items = listOf(playlist("one")),
            continuation = "same-token",
        ).collectAllItems { token ->
            requests += token
            Result.success(
                LibraryContinuationPage(
                    items = listOf(playlist("two")),
                    continuation = "same-token",
                ),
            )
        }

        assertEquals(listOf("same-token"), requests)
        assertEquals(listOf("one", "two"), result.map { it.id })
    }

    @Test
    fun enforcesTheContinuationPageSafetyLimit() = runBlocking {
        val requests = mutableListOf<String>()
        val result = LibraryPage(
            items = listOf(playlist("one")),
            continuation = "page-1",
        ).collectAllItems(
            fetchContinuation = { token ->
                requests += token
                val next = "page-${requests.size + 1}"
                Result.success(LibraryContinuationPage(listOf(playlist("item-${requests.size}")), next))
            },
            maxPages = 2,
        )

        assertEquals(listOf("page-1", "page-2"), requests)
        assertEquals(listOf("one", "item-1", "item-2"), result.map { it.id })
    }

    private fun playlist(id: String) = PlaylistItem(
        id = id,
        title = id,
        author = null,
        songCountText = null,
        thumbnail = null,
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null,
    )
}
