package dev.lelonio.square.data

import org.json.JSONObject

/**
 * Spotify's own browse pages, read as rows.
 *
 * The personalised home answers "what should this account see when it opens the
 * app". This answers a different and much larger question — what the catalogue
 * has to offer right now — and it is where the client's own Browse gets the new
 * releases chosen for a listener, the editors' playlists, the charts and the
 * daily mixes. The two overlap in places, which is why the pages that use this
 * drop anything the home already showed.
 *
 * Read exactly as forgivingly as [SpotifyHome], and for the same reason: this
 * is a private interface, so an unrecognised row is skipped rather than treated
 * as a failure.
 */
object SpotifyBrowse {

    /**
     * The pages worth asking for, by the uri Spotify addresses them with.
     *
     * These are stable identifiers — the same ones the web client puts in its
     * own links — and they name a page rather than its contents, so what comes
     * back is whatever the catalogue holds today.
     */
    object Pages {
        /** New releases: the albums out now, and the ones picked for this account. */
        const val NEW_RELEASES = "spotify:page:0JQ5DAqbMKFz6FAsUtgAab"

        /** Made for you: the daily mixes, the DJ, the day's own list, on repeat. */
        const val MADE_FOR_YOU = "spotify:page:0JQ5DAt0tbjZptfcdMSKl3"

        /** Charts: what is being played most, by country and worldwide. */
        const val CHARTS = "spotify:page:0JQ5DAudkNjCgYMM0TZXDw"

        /** Music: the front page of browse, which leads with discovery. */
        const val MUSIC = "spotify:page:0JQ5DAqbMKFSi39LMRT0Cy"
    }

    fun parse(json: String): List<HomeShelf> = runCatching {
        val sections = JSONObject(json)
            .getJSONObject("data")
            .getJSONObject("browse")
            .getJSONObject("sections")
            .getJSONArray("items")

        (0 until sections.length()).mapNotNull { index ->
            shelf(sections.getJSONObject(index))
        }
    }.getOrDefault(emptyList())

    private fun shelf(section: JSONObject): HomeShelf? {
        val data = section.optJSONObject("data") ?: return null
        val title = data.optJSONObject("title")?.optString("transformedLabel").orEmpty()
        if (title.isEmpty()) return null

        val items = section.optJSONObject("sectionItems")?.optJSONArray("items") ?: return null
        val entries = (0 until items.length()).mapNotNull { index ->
            entry(items.getJSONObject(index))
        }
        return if (entries.isEmpty()) null else HomeShelf(title, entries)
    }

    /**
     * One card of a row.
     *
     * Playlists and albums only. A browse page also carries links to other
     * browse pages — the grid of categories — and those are a page this app has
     * no screen for; a tile that cannot be opened is worse than a gap.
     */
    private fun entry(item: JSONObject): CatalogPlaylist? {
        val uri = item.optString("uri")
        if (uri.isEmpty()) return null
        if (uri == Catalog.DJ_URI && !dev.lelonio.square.backend.spotify.SpotifyDj.enabled) return null
        val data = item.optJSONObject("content")?.optJSONObject("data") ?: return null

        return when (data.optString("__typename")) {
            "Playlist" -> CatalogPlaylist(
                uri = uri,
                name = data.optString("name"),
                artworkUrl = firstSource(data.optJSONObject("images")),
            )

            "Album" -> CatalogPlaylist(
                uri = uri,
                name = data.optString("name"),
                artworkUrl = sources(data.optJSONObject("coverArt")),
            )

            else -> null
        }
    }

    /** The `images { items { sources } }` shape a playlist's artwork arrives in. */
    private fun firstSource(images: JSONObject?): String? {
        val items = images?.optJSONArray("items") ?: return null
        val first = items.optJSONObject(0) ?: return null
        return sources(first)
    }

    /**
     * The largest of the sizes offered, or the only one.
     *
     * A cover arrives as three prints of the same picture and the tiles here
     * are drawn at a hundred and fifty dp on a screen three times that dense,
     * so the small print is a blur and the large one is what to ask for.
     */
    private fun sources(holder: JSONObject?): String? {
        val list = holder?.optJSONArray("sources") ?: return null
        var best: String? = null
        var bestWidth = -1
        for (index in 0 until list.length()) {
            val source = list.optJSONObject(index) ?: continue
            val url = source.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val width = source.optInt("width", 0)
            if (best == null || width > bestWidth) {
                best = url
                bestWidth = width
            }
        }
        return best
    }
}
