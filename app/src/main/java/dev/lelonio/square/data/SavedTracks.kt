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

    /** Null when the answer was not one of these — a retired query hash, a 401. */
    fun parse(json: String): GatewayPage? = runCatching {
        val tracks = JSONObject(json)
            .getJSONObject("data")
            .getJSONObject("me")
            .getJSONObject("library")
            .getJSONObject("tracks")

        val items = tracks.getJSONArray("items")
        val read = (0 until items.length()).mapNotNull { index ->
            GatewayTracks.track(
                items.optJSONObject(index)?.optJSONObject("track")?.optJSONObject("data"),
                // The gateway does not carry the date a track was saved. The
                // pages arrive newest first, which is what the "recently
                // added" order shows anyway, and sorting by a field that is
                // null throughout leaves that order alone.
                addedAt = null,
            )
        }

        GatewayPage(
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
}
