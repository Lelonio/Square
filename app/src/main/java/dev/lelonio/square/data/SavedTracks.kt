package dev.lelonio.square.data

import org.json.JSONObject

/**
 * The account's saved tracks, read from Spotify's own gateway.
 *
 * Liked Songs is the one list with nowhere ordinary to come from. The access
 * point refuses it as a context, and the Web API will only answer for a
 * listener who has registered an application of their own — and then fifty at
 * a time, against a quota. The gateway the personalised home already comes
 * from answers the same question with the whole track in it, two hundred at a
 * time, for anyone who is logged in.
 *
 * Forgiving in the same way [SpotifyHome] is, and for the same reason: this
 * reads a private interface that changes without notice, so an item nobody
 * recognises is skipped rather than treated as a failure. What it cannot do is
 * pretend to have read a page it did not, hence the null.
 */
object SavedTracks {

    /**
     * @param nextOffset where the next page starts, or null at the end.
     * @param total how many the account has, which is what says whether the
     *   list on screen is all of them.
     */
    data class Page(
        val tracks: List<CatalogTrack>,
        val nextOffset: Int?,
        val total: Int,
    )

    /** Null when the answer was not one of these — a retired query hash, a 401. */
    fun parse(json: String): Page? = runCatching {
        val tracks = JSONObject(json)
            .getJSONObject("data")
            .getJSONObject("me")
            .getJSONObject("library")
            .getJSONObject("tracks")

        val items = tracks.getJSONArray("items")
        val read = (0 until items.length()).mapNotNull { index ->
            track(items.optJSONObject(index) ?: return@mapNotNull null)
        }

        Page(
            tracks = read,
            // Null on the last page, and null is how the caller knows it is
            // the last. Present-but-null, at that: the key is always there, so
            // asking whether it exists says nothing.
            nextOffset = tracks.optJSONObject("pagingInfo")
                ?.takeIf { !it.isNull("nextOffset") }
                ?.optInt("nextOffset"),
            total = tracks.optInt("totalCount", read.size),
        )
    }.getOrNull()

    private fun track(item: JSONObject): CatalogTrack? {
        val data = item.optJSONObject("track")?.optJSONObject("data") ?: return null
        val uri = data.optString("uri").takeIf { it.startsWith("spotify:track:") } ?: return null

        // Same rule as every other list: something the engine could only skip
        // has no business being in the queue.
        val playable = data.optJSONObject("playability")?.optBoolean("playable", true) ?: true
        if (!playable) return null

        val artists = data.optJSONObject("artists")?.optJSONArray("items")
        val credited = buildList {
            for (index in 0 until (artists?.length() ?: 0)) {
                val artist = artists?.optJSONObject(index) ?: continue
                val name = artist.optJSONObject("profile")?.optString("name").orEmpty()
                if (name.isEmpty()) continue
                add(CatalogArtist(name, artist.optString("uri").takeIf { it.isNotEmpty() }))
            }
        }

        val album = data.optJSONObject("albumOfTrack")

        return CatalogTrack(
            uri = uri,
            name = data.optString("name"),
            artist = credited.joinToString(", ") { it.name },
            artistUri = credited.firstOrNull()?.uri,
            artists = credited,
            album = album?.optString("name").orEmpty(),
            durationMs = data.optJSONObject("duration")?.optLong("totalMilliseconds") ?: 0L,
            explicit = data.optJSONObject("contentRating")?.optString("label") == "EXPLICIT",
            artworkUrl = cover(album),
            // The gateway does not carry the date a track was saved. The pages
            // arrive newest first, which is what the "recently added" order
            // shows anyway, and sorting by a field that is null throughout
            // leaves that order alone.
            addedAt = null,
        )
    }

    /**
     * The middle cover, or whatever there is.
     *
     * Three sizes come back, in no particular order. A row shows a thumbnail
     * and the sheet behind the player wants something bigger, so this takes the
     * 300px one when it is there rather than the first named.
     */
    private fun cover(album: JSONObject?): String? {
        val sources = album?.optJSONObject("coverArt")?.optJSONArray("sources") ?: return null
        var fallback: String? = null
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val url = source.optString("url").takeIf { it.isNotEmpty() } ?: continue
            if (source.optInt("width") == PREFERRED_COVER) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    /** What the rows are drawn at; see [cover]. */
    private const val PREFERRED_COVER = 300
}
