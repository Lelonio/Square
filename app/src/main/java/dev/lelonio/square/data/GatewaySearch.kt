package dev.lelonio.square.data

import dev.lelonio.square.backend.SearchLabels
import org.json.JSONArray
import org.json.JSONObject

/**
 * A search as Spotify's own gateway answers it.
 *
 * The same shape the Web API path produces, from a different and better
 * source: this one reads the words the way the listener meant them, so a line
 * of a song finds the song rather than the handful of tracks that happen to
 * have those words in their titles. See [Gateway.search].
 *
 * Forgiving throughout, like everything else that reads this gateway: a
 * section that is missing, or an item shaped in a way nobody here recognises,
 * is left out rather than treated as a failure. Half an answer is worth
 * showing; there is nothing else to show.
 */
internal object GatewaySearch {

    /**
     * Null only when the gateway did not answer this question at all.
     *
     * An answer with nothing in it is still an answer — somebody searched for
     * something that is not there — and returning null for it would send the
     * caller off to ask the Web API the same question, or tell a listener with
     * no registered application that they have to go and register one.
     */
    fun parse(raw: String, labels: SearchLabels): SearchResults? {
        val search = runCatching {
            JSONObject(raw).getJSONObject("data").getJSONObject("searchV2")
        }.getOrNull() ?: return null

        val matches = search.items("tracksV2")
        val lyricMatches = matches.mapNotNullTo(mutableSetOf()) { wrapper ->
            wrapper.optJSONArray("matchedFields")
                ?.takeIf { fields ->
                    (0 until fields.length()).any { fields.optString(it) == LYRICS }
                }
                ?.let { wrapper.optJSONObject("item")?.optJSONObject("data")?.optString("uri") }
                ?.takeIf { it.isNotEmpty() }
        }

        return SearchResults(
            tracks = matches.mapNotNull { wrapper ->
                // A track sits one level deeper than everything else: the
                // section holds a match, and the match holds the track.
                GatewayTracks.track(wrapper.optJSONObject("item")?.optJSONObject("data"), null)
            },
            artists = search.items("artists").mapNotNull { wrapper ->
                val data = wrapper.optJSONObject("data") ?: return@mapNotNull null
                SearchItem(
                    uri = data.uriOf("spotify:artist:") ?: return@mapNotNull null,
                    title = data.optJSONObject("profile")?.optString("name").orEmpty(),
                    subtitle = labels.artist,
                    artworkUrl = data.optJSONObject("visuals")
                        ?.optJSONObject("avatarImage").best(),
                )
            },
            albums = search.items("albumsV2").mapNotNull { wrapper ->
                val data = wrapper.optJSONObject("data") ?: return@mapNotNull null
                SearchItem(
                    uri = data.uriOf("spotify:album:") ?: return@mapNotNull null,
                    title = data.optString("name"),
                    subtitle = labels.album,
                    artworkUrl = data.optJSONObject("coverArt").best(),
                )
            },
            playlists = search.items("playlists").mapNotNull { wrapper ->
                val data = wrapper.optJSONObject("data") ?: return@mapNotNull null
                SearchItem(
                    uri = data.uriOf("spotify:playlist:") ?: return@mapNotNull null,
                    title = data.optString("name"),
                    subtitle = labels.playlist,
                    artworkUrl = data.optJSONObject("images")
                        ?.optJSONArray("items")?.optJSONObject(0).best(),
                )
            },
            lyricMatches = lyricMatches,
            podcasts = search.items("podcasts").mapNotNull { wrapper ->
                val show = SpotifyPodcasts.showTile(wrapper.optJSONObject("data")) ?: return@mapNotNull null
                SearchItem(
                    uri = show.uri,
                    title = show.name,
                    subtitle = show.subtitle ?: labels.podcast,
                    artworkUrl = wrapper.optJSONObject("data")?.optJSONObject("coverArt").best(),
                )
            },
            episodes = search.items("episodes").mapNotNull { wrapper ->
                SpotifyPodcasts.episode(wrapper.optJSONObject("data"))
            },
        )
    }

    /** What Spotify calls a match on the words inside the song. */
    private const val LYRICS = "LYRICS"

    private fun JSONObject.items(section: String): List<JSONObject> {
        val items: JSONArray = optJSONObject(section)?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull(items::optJSONObject)
    }

    /** Only the kind of thing this app can open; a wrapper can hold anything. */
    private fun JSONObject.uriOf(prefix: String): String? =
        optString("uri").takeIf { it.startsWith(prefix) }

    /**
     * The middle picture, or whatever there is.
     *
     * The sizes come in no particular order, and these rows are thumbnails, so
     * a 640px cover for each of twenty results is a great deal of decoding
     * nobody sees.
     */
    private fun JSONObject?.best(): String? {
        val sources = this?.optJSONArray("sources") ?: return null
        var fallback: String? = null
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val url = source.optString("url").takeIf { it.isNotEmpty() } ?: continue
            if (source.optInt("width") in PREFERRED) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    /** What a row is drawn at, give or take: 300 for covers, 320 for faces. */
    private val PREFERRED = 300..320
}
