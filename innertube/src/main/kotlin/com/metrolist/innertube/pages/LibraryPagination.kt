package com.metrolist.innertube.pages

import com.metrolist.innertube.models.YTItem
import timber.log.Timber

/**
 * Reads every page exposed by a library browse response.
 *
 * The first page is already represented by this [LibraryPage]. Continuations
 * are fetched one at a time because each token is issued by the preceding
 * response. A repeated token or a failed request stops pagination and keeps
 * the items already read, rather than hanging or discarding a usable library.
 *
 * IDs are unique in the result while the service's original ordering is kept.
 */
suspend fun LibraryPage.collectAllItems(
    fetchContinuation: suspend (String) -> Result<LibraryContinuationPage>,
    maxPages: Int = MAX_LIBRARY_CONTINUATION_PAGES,
): List<YTItem> {
    require(maxPages > 0) { "maxPages must be greater than zero" }

    val collected = linkedMapOf<String, YTItem>()
    items.forEach { item -> collected.putIfAbsent(item.id, item) }

    val visitedTokens = mutableSetOf<String>()
    var nextToken = continuation
    var pagesRead = 0

    while (pagesRead < maxPages) {
        val token = nextToken?.takeIf { it.isNotBlank() } ?: break
        if (!visitedTokens.add(token)) {
            Timber.w("Library pagination returned a repeated continuation token; stopping")
            break
        }

        val result = fetchContinuation(token)
        if (result.isFailure) {
            Timber.w(
                result.exceptionOrNull(),
                "Library continuation failed after %d pages; retaining %d items",
                pagesRead,
                collected.size,
            )
            break
        }

        val page = result.getOrThrow()
        page.items.forEach { item -> collected.putIfAbsent(item.id, item) }
        nextToken = page.continuation
        pagesRead++
    }

    if (!nextToken.isNullOrBlank() && pagesRead >= maxPages) {
        Timber.w("Library pagination reached its %d-page safety limit", maxPages)
    }

    return collected.values.toList()
}

private const val MAX_LIBRARY_CONTINUATION_PAGES = 100
