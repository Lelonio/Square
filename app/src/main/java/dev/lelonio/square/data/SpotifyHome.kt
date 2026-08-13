package dev.lelonio.square.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One row of the personalised home: a title, and the things under it.
 *
 * Spotify's own shelves, which is what makes this worth having. The account's
 * playlists are already readable through the access point; "Made for you", the
 * daily mixes and the rest are assembled per listener and exist nowhere else.
 */
data class HomeShelf(
    val title: String,
    val items: List<CatalogPlaylist>,
)

/**
 * Turns the gateway's answer into shelves; see native/src/pathfinder.rs.
 *
 * Forgiving on purpose. This reads a private interface that changes without
 * notice, so anything unrecognised is skipped rather than treated as an error:
 * a home with four rows out of eight is worth more than a page that refuses to
 * draw because one item had a shape nobody expected.
 */
object SpotifyHome {

    /** Sections whose items are one-per-row filler; see the note in [parse]. */
    private const val BASELINE = "HomeFeedBaselineSectionData"

    fun parse(json: String): List<HomeShelf> = runCatching {
        val sections = JSONObject(json)
            .getJSONObject("data")
            .getJSONObject("home")
            .getJSONObject("sectionContainer")
            .getJSONObject("sections")
            .getJSONArray("items")

        (0 until sections.length()).mapNotNull { index ->
            shelf(sections.getJSONObject(index))
        }
    }.getOrDefault(emptyList())

    private fun shelf(section: JSONObject): HomeShelf? {
        val data = section.optJSONObject("data") ?: return null

        // The endless feed at the bottom of Spotify's own home: dozens of
        // sections holding a single item each, several of them titled the same.
        // A carousel of one tile is not a row, and repeating "Made for you"
        // eight times down a page is not a home.
        if (data.optString("__typename") == BASELINE) return null

        val title = data.optJSONObject("title")?.optString("transformedLabel").orEmpty()
        if (title.isEmpty()) return null

        val items = section.optJSONObject("sectionItems")?.optJSONArray("items")
            ?: return null
        val entries = (0 until items.length()).mapNotNull { index ->
            entry(items.getJSONObject(index))
        }
        return if (entries.isEmpty()) null else HomeShelf(title, entries)
    }

    private fun entry(item: JSONObject): CatalogPlaylist? {
        val uri = item.optString("uri")
        if (uri.isEmpty()) return null
        // The DJ is a stream Spotify generates as you listen, not a list: it has
        // no tracks to resolve, so opening it here only ever showed a blank
        // page. It is kept out of the library for the same reason.
        if (uri == Catalog.DJ_URI) return null
        val content = item.optJSONObject("content") ?: return null
        val data = content.optJSONObject("data") ?: return null

        return when (data.optString("__typename")) {
            "Playlist" -> CatalogPlaylist(
                uri = uri,
                name = data.optString("name"),
                artworkUrl = firstImage(data.optJSONObject("images")),
            )

            "Album" -> CatalogPlaylist(
                uri = uri,
                name = data.optString("name"),
                artworkUrl = sourceUrl(data.optJSONObject("coverArt")),
            )

            "Artist" -> CatalogPlaylist(
                uri = uri,
                name = data.optJSONObject("profile")?.optString("name").orEmpty(),
                artworkUrl = sourceUrl(
                    data.optJSONObject("visuals")?.optJSONObject("avatarImage"),
                ),
            )

            // Podcasts and audiobooks come down the same pipe. This app plays
            // music, and a tile that cannot be opened is worse than a gap.
            else -> null
        }
    }

    /** The `images { items { sources } }` shape playlists use. */
    private fun firstImage(images: JSONObject?): String? {
        val items = images?.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        return sourceUrl(items.optJSONObject(0))
    }

    /**
     * The largest picture on offer, or the only one.
     *
     * Sizes are not always given: playlist covers arrive with null dimensions
     * and a single source, album art with three. Sorting by width and taking
     * the end covers both without a special case.
     */
    private fun sourceUrl(holder: JSONObject?): String? {
        val sources: JSONArray = holder?.optJSONArray("sources") ?: return null
        var best: String? = null
        var width = -1
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val url = source.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val size = source.optInt("width", 0)
            if (best == null || size > width) {
                best = url
                width = size
            }
        }
        return best
    }
}
