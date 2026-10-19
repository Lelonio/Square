package dev.lelonio.square.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Podcasts, read from Spotify's own gateway.
 *
 * A show is opened as a list of its episodes, on the same page a playlist
 * opens on, and an episode is a [CatalogTrack] with the few things only an
 * episode has filled in: the day it came out, how far in the listener got, and
 * whether it has a picture as well as sound. Everything that plays a track
 * plays an episode too — the engine takes `spotify:episode:` addresses as it
 * takes `spotify:track:` ones.
 *
 * Forgiving in the same way [SpotifyHome] is, and for the same reason: this is
 * a private interface, and a show with one odd episode should still open.
 */
object SpotifyPodcasts {

    /** A show's own page, without its episodes. */
    data class Show(
        val uri: String,
        val name: String,
        val publisher: String,
        val description: String,
        val artworkUrl: String?,
        /** Whether the account follows it. */
        val saved: Boolean?,
    )

    fun isShow(uri: String): Boolean = uri.startsWith(SHOW)

    fun isEpisode(uri: String): Boolean = uri.startsWith(EPISODE)

    /** Null when the answer was not a show: a retired hash, a removed show. */
    fun parseShow(json: String): Show? = runCatching {
        val data = JSONObject(json).getJSONObject("data").getJSONObject("podcastUnionV2")
        if (data.optString("__typename") != "Podcast") return null
        Show(
            uri = data.optString("uri"),
            name = data.optString("name"),
            publisher = data.optJSONObject("publisher")?.optString("name").orEmpty(),
            description = data.optString("description").trim(),
            artworkUrl = cover(data.optJSONObject("coverArt"), LARGE),
            saved = if (data.has("saved")) data.optBoolean("saved") else null,
        )
    }.getOrNull()

    /**
     * A page of a show's episodes.
     *
     * The show's name is passed in rather than read from each episode: the
     * episodes of one page all belong to it, and the list under its title does
     * not need it said again on every row — the player does, and gets it here.
     */
    fun parseEpisodes(json: String, showName: String): GatewayPage? = runCatching {
        val page = JSONObject(json).getJSONObject("data")
            .getJSONObject("podcastUnionV2")
            .getJSONObject("episodesV2")
        val items = page.getJSONArray("items")
        val read = (0 until items.length()).mapNotNull { index ->
            episode(
                items.optJSONObject(index)?.optJSONObject("entity")?.optJSONObject("data"),
                showName,
            )
        }
        val total = page.optInt("totalCount", items.length())
        GatewayPage(
            tracks = read,
            nextOffset = page.optJSONObject("pagingInfo")
                ?.takeIf { it.has("nextOffset") && !it.isNull("nextOffset") }
                ?.optInt("nextOffset")
                ?.takeIf { items.length() > 0 },
            total = total,
        )
    }.getOrNull()

    /** The account's podcasts, as tiles; null when the library would not answer. */
    fun parseFollowed(json: String): List<CatalogPlaylist>? = runCatching {
        val library = JSONObject(json).getJSONObject("data")
            .getJSONObject("me")
            .getJSONObject("libraryV3")
        if (library.optString("__typename") != "LibraryPage") {
            android.util.Log.i(TAG, "library answered ${library.optString("__typename")}")
            return null
        }
        val items = library.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { index ->
            val data = items.optJSONObject(index)?.optJSONObject("item")?.optJSONObject("data")
                ?: return@mapNotNull null
            if (data.optString("__typename") != "Podcast") return@mapNotNull null
            CatalogPlaylist(
                uri = data.optString("uri").takeIf(::isShow) ?: return@mapNotNull null,
                name = data.optString("name"),
                artworkUrl = cover(data.optJSONObject("coverArt"), LARGE),
                subtitle = data.optJSONObject("publisher")?.optString("name")?.takeIf { it.isNotEmpty() },
            )
        }
    }.getOrNull()

    /**
     * One episode, wherever it was found: a show's page, a playlist, a home
     * shelf, a search. Null for anything else, and for an episode this market
     * cannot play.
     */
    fun episode(data: JSONObject?, showName: String? = null): CatalogTrack? {
        if (data == null || data.optString("__typename") != "Episode") return null
        val uri = data.optString("uri").takeIf(::isEpisode) ?: return null
        if (data.optJSONObject("playability")?.optBoolean("playable", true) == false) return null

        val show = data.optJSONObject("podcastV2")?.optJSONObject("data")
        val name = show?.optString("name")?.takeIf { it.isNotEmpty() } ?: showName.orEmpty()
        val played = data.optJSONObject("playedState")
        val media = data.optJSONArray("mediaTypes")
        val released = data.optJSONObject("releaseDate")?.optString("isoString")?.takeIf { it.isNotEmpty() }
        return CatalogTrack(
            uri = uri,
            name = data.optString("name"),
            artist = name,
            album = name,
            albumUri = show?.optString("uri")?.takeIf(::isShow),
            durationMs = data.optJSONObject("duration")?.optLong("totalMilliseconds") ?: 0L,
            explicit = data.optJSONObject("contentRating")?.optString("label") == "EXPLICIT",
            artworkUrl = cover(data.optJSONObject("coverArt"), ROW)
                ?: cover(show?.optJSONObject("coverArt"), ROW),
            year = released?.take(4).orEmpty(),
            releasedAt = released,
            resumeMs = played?.optLong("playPositionMilliseconds") ?: 0L,
            played = played?.optString("state") == "COMPLETED",
            hasVideo = media != null && (0 until media.length()).any { media.optString(it) == "VIDEO" },
            summary = summaryOf(data),
        )
    }

    /**
     * What the show says about an episode, as one paragraph of plain text.
     *
     * From the HTML where there is some: the plain field is the same text with
     * the line breaks simply removed, which runs the end of one sentence into
     * the start of the next ("driver.Thank you").
     */
    private fun summaryOf(data: JSONObject): String {
        val html = data.optString("htmlDescription")
        val text = if (html.isNotBlank()) {
            androidx.core.text.HtmlCompat
                .fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
        } else {
            data.optString("description")
        }
        return text.replace(Regex("\\s+"), " ").trim().take(SUMMARY_LENGTH)
    }

    /** A show as a tile, from wherever one was found. */
    fun showTile(data: JSONObject?): CatalogPlaylist? {
        if (data == null || data.optString("__typename") != "Podcast") return null
        return CatalogPlaylist(
            uri = data.optString("uri").takeIf(::isShow) ?: return null,
            name = data.optString("name"),
            artworkUrl = cover(data.optJSONObject("coverArt"), LARGE),
            subtitle = data.optJSONObject("publisher")?.optString("name")?.takeIf { it.isNotEmpty() },
        )
    }

    /** An episode as a tile: a home shelf shows it like a record. */
    fun episodeTile(data: JSONObject?): CatalogPlaylist? {
        val episode = episode(data) ?: return null
        return CatalogPlaylist(
            uri = episode.uri,
            name = episode.name,
            artworkUrl = cover(data?.optJSONObject("coverArt"), LARGE) ?: episode.artworkUrl,
            subtitle = episode.artist.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The picture nearest [width], or whatever there is.
     *
     * Three sizes come back in no particular order; a row wants the small one
     * and a page the large.
     */
    private fun cover(holder: JSONObject?, width: Int): String? {
        val sources = holder?.optJSONArray("sources") ?: return null
        var best: String? = null
        var distance = Int.MAX_VALUE
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val url = source.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val gap = kotlin.math.abs(source.optInt("width", width) - width)
            if (gap < distance) {
                best = url
                distance = gap
            }
        }
        return best
    }

    private const val TAG = "SquarePodcasts"
    private const val SHOW = "spotify:show:"
    private const val EPISODE = "spotify:episode:"
    private const val ROW = 300
    private const val LARGE = 640

    /** Enough to say what an episode is about; show notes run to pages. */
    private const val SUMMARY_LENGTH = 600
}
