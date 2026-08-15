package dev.lelonio.square.data

import android.net.Uri

/**
 * Turns a link somebody shared into the URI the app works in.
 *
 * Spotify hands out the same thing in three shapes: its own `spotify:` scheme,
 * the web address of the page, and the web address with a language in front of
 * it. All three name the same track, and the app only ever speaks the first.
 */
object SpotifyLink {

    /**
     * The `spotify:` URI a link points at, or null if it points at nothing this
     * app can open.
     *
     * Deliberately strict about the kinds it accepts. A share sheet will hand
     * over a search page, a concert listing or somebody's profile just as
     * readily as a track, and an app that answered those with a blank page
     * would be worse at handling links than the browser it took them from.
     */
    fun parse(uri: Uri?): String? {
        val raw = uri?.toString().orEmpty()
        if (raw.isEmpty()) return null

        if (uri?.scheme.equals("spotify", ignoreCase = true)) {
            // Already in hand. Query strings ride along on shared links —
            // `?si=` is on every one of them — and mean nothing here.
            return raw.substringBefore('?').takeIf { it.isKind() }
        }

        val host = uri?.host?.lowercase()
        if (host != "open.spotify.com" && host != "link.tospotify.com") return null

        // "intl-it" and friends sit in front of the path on a shared link, and
        // a user's own playlist link still carries the old `/user/<id>` prefix.
        val parts = uri.pathSegments
            .filter { it.isNotEmpty() && !it.startsWith("intl-") }
        val kind = parts.getOrNull(parts.size - 2) ?: return null
        val id = parts.lastOrNull()?.substringBefore('?') ?: return null
        if (id.isEmpty()) return null

        return "spotify:$kind:$id".takeIf { it.isKind() }
    }

    /** Whether this is one of the pages the app has somewhere to put. */
    private fun String.isKind(): Boolean =
        KINDS.any { startsWith("spotify:$it:") }

    private val KINDS = listOf("track", "album", "playlist", "artist")
}
