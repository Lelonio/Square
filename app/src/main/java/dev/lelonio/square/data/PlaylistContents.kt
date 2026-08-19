package dev.lelonio.square.data

import org.json.JSONObject

/**
 * A playlist's tracks, read from Spotify's own gateway.
 *
 * The same list the Web API answers for, from the endpoint Spotify's own web
 * player uses, and the difference is who pays: the Web API is metered against
 * an application the listener has to register for themselves, while this is
 * answered for anyone who is logged in. It also comes two hundred at a time
 * rather than a hundred, and unlike the access point's context it arrives with
 * the whole track in it rather than a URI to look up one by one.
 *
 * Forgiving in the same way [SavedTracks] is, and for the same reason.
 */
object PlaylistContents {

    /** Null when the answer was not one of these — a retired query hash, a 401. */
    fun parse(json: String): GatewayPage? = runCatching {
        val content = JSONObject(json)
            .getJSONObject("data")
            .getJSONObject("playlistV2")
            .getJSONObject("content")

        val items = content.getJSONArray("items")
        val read = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            // Episodes come through here too, wrapped in a different type, and
            // GatewayTracks turns down anything without a track URI anyway.
            GatewayTracks.track(
                item.optJSONObject("itemV2")?.optJSONObject("data"),
                addedAt = item.optJSONObject("addedAt")
                    ?.optString("isoString")
                    ?.takeIf { it.isNotEmpty() },
            )
        }

        val paging = content.optJSONObject("pagingInfo")
        val offset = paging?.optInt("offset") ?: 0
        val total = content.optInt("totalCount", offset + items.length())
        val next = offset + items.length()

        GatewayPage(
            tracks = read,
            // Counted rather than announced: this page says where it starts
            // and how long the list is, and nothing about what follows it. An
            // empty page ends the read whatever the total claims, or a playlist
            // whose count is wrong would be read for ever.
            nextOffset = next.takeIf { items.length() > 0 && it < total },
            total = total,
        )
    }.getOrNull()
}
