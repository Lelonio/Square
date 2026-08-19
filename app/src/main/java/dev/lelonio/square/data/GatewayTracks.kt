package dev.lelonio.square.data

import org.json.JSONObject

/**
 * One track as Spotify's gateway writes it, whatever list it came out of.
 *
 * The library's saved tracks and a playlist's contents are different queries
 * answering different shapes, but the track inside them is the same object with
 * the same fields, so reading it belongs in one place.
 *
 * Forgiving on purpose, like everything else that reads this gateway: an item
 * with a shape nobody recognises is skipped rather than treated as a failure.
 */
internal object GatewayTracks {

    /**
     * Null for anything that is not a playable track: a podcast episode, a
     * local file, something this market has no licensed copy of. Keeping those
     * would put items in the queue that the engine could only skip.
     */
    fun track(data: JSONObject?, addedAt: String?): CatalogTrack? {
        if (data == null) return null
        val uri = data.optString("uri").takeIf { it.startsWith("spotify:track:") } ?: return null
        if (data.optJSONObject("playability")?.optBoolean("playable", true) == false) return null

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
            // The library calls it `duration` and a playlist calls it
            // `trackDuration`, for the same number.
            durationMs = (data.optJSONObject("duration") ?: data.optJSONObject("trackDuration"))
                ?.optLong("totalMilliseconds") ?: 0L,
            explicit = data.optJSONObject("contentRating")?.optString("label") == "EXPLICIT",
            artworkUrl = cover(album),
            addedAt = addedAt,
        )
    }

    /**
     * The middle cover, or whatever there is.
     *
     * Three sizes come back, in no particular order. A row shows a thumbnail,
     * so this takes the 300px one when it is there rather than the first named.
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

/**
 * A page of a list read from the gateway.
 *
 * @param nextOffset where the next page starts, or null when this was the last.
 * @param total how many the list holds, which is what says whether what is on
 *   screen is all of it.
 */
data class GatewayPage(
    val tracks: List<CatalogTrack>,
    val nextOffset: Int?,
    val total: Int,
)
