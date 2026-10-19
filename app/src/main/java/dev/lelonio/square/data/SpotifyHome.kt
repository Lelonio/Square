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
    /**
     * The section's address, "spotify:section:…", where it came with one.
     *
     * The same for every account and in every language, which is what tells
     * the row of what was being played from the rest without reading a title;
     * see TabContents.
     */
    val sectionUri: String = "",
    /**
     * Gathered out of the single-tile sections at the foot of Spotify's home
     * rather than given as a row; see [SpotifyHome.parse].
     */
    val gathered: Boolean = false,
    /** The artist the row is about, "For fans of", "More like", where it said. */
    val artist: String? = null,
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

    /**
     * @param podcasts whether shows and episodes are tiles too. Off for the
     *   music home, where they used to be dropped; on for the Podcast chip,
     *   which is nothing but them.
     */
    fun parse(json: String, podcasts: Boolean = false): List<HomeShelf> = runCatching {
        val sections = JSONObject(json)
            .getJSONObject("data")
            .getJSONObject("home")
            .getJSONObject("sectionContainer")
            .getJSONObject("sections")
            .getJSONArray("items")

        val rows = mutableListOf<HomeShelf>()
        val gathered = LinkedHashMap<String, HomeShelf>()
        for (index in 0 until sections.length()) {
            val section = sections.getJSONObject(index)
            val baseline = section.optJSONObject("data")?.optString("__typename") == BASELINE
            if (!baseline) {
                shelf(section, podcasts)?.let(rows::add)
                continue
            }
            // The endless feed at the bottom of Spotify's own home: dozens of
            // sections holding a single tile each, several of them titled the
            // same. One tile is not a row, so they are put back together: by
            // the artist a section is about when it names one ("For fans of
            // Geolier" twice is one row), and by title when it does not ("Just
            // for you" five times is one row).
            val one = shelf(section, podcasts) ?: continue
            val artist = section.optJSONObject("data")
                ?.optJSONObject("headerEntity")
                ?.optJSONObject("data")
                ?.optString("uri")
                ?.takeIf { it.startsWith("spotify:artist:") }
            val key = artist ?: "title:${one.title}"
            val so = gathered[key]
            gathered[key] = so?.copy(items = (so.items + one.items).distinctBy { it.uri })
                ?: one.copy(sectionUri = "", gathered = true, artist = artist)
        }
        rows + gathered.values
    }.getOrDefault(emptyList())

    private fun shelf(section: JSONObject, podcasts: Boolean): HomeShelf? {
        val data = section.optJSONObject("data") ?: return null

        val title = data.optJSONObject("title")?.optString("transformedLabel").orEmpty()
        if (title.isEmpty()) return null

        val items = section.optJSONObject("sectionItems")?.optJSONArray("items")
            ?: return null
        val entries = (0 until items.length()).mapNotNull { index ->
            entry(items.getJSONObject(index), podcasts)
        }
        return if (entries.isEmpty()) null else HomeShelf(title, entries, section.optString("uri"))
    }

    private fun entry(item: JSONObject, podcasts: Boolean): CatalogPlaylist? {
        val uri = item.optString("uri")
        if (uri.isEmpty()) return null
        // The DJ is a stream Spotify generates as you listen, not a list: it has
        // no tracks to resolve, so opening it as a page only ever showed a
        // blank one. With the DJ on it is played as a session instead (see
        // SpotifyDj); off, it is kept out as before.
        if (uri == Catalog.DJ_URI && !dev.lelonio.square.backend.spotify.SpotifyDj.enabled) return null
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

            // Shows open as their list of episodes and an episode plays; both
            // only on the podcast page, where they are what was asked for.
            "Podcast" -> if (podcasts) SpotifyPodcasts.showTile(data) else null
            "Episode" -> if (podcasts) SpotifyPodcasts.episodeTile(data) else null

            // Audiobooks come down the same pipe, and a tile that cannot be
            // opened is worse than a gap.
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
