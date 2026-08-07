package dev.lelonio.square.backend.youtube

/**
 * Asks Google's image host for a cover at a usable size.
 *
 * The URLs YouTube hands out carry the size in the path — `=w60-h60` and
 * similar — and what it hands out by default is a thumbnail meant for a list
 * row on a phone. Handed to a full-screen player it is upscaled, which is what
 * made every cover look soft.
 *
 * Rewriting the size is the whole fix: the same URL with `=w544-h544` comes
 * back sharp. Both hosts are matched because YouTube moved album art from
 * `lh3` to `yt3` and a pattern that only knows the old one silently does
 * nothing on the new.
 *
 * Adapted from Metrolist (GPL-3.0), which worked this out first.
 */
fun String.atSize(width: Int, height: Int = width): String {
    GOOGLE_USER_CONTENT.matchEntire(this)?.groupValues?.let {
        return "${substringBefore("=w")}=w$width-h$height-p-l90-rj"
    }
    // The other shape Google uses, for channel and artist pictures.
    if (GGPHT.matches(this)) return "$this-s$width"
    // Video thumbnails carry no size to rewrite: the size *is* the file name,
    // and the one YouTube hands out is `hqdefault` at 480x360. `sddefault` is
    // the largest one every upload is guaranteed to have — `maxresdefault`
    // exists only for videos uploaded above 720p and 404s for the rest.
    YTIMG.matchEntire(this)?.groupValues?.let { (_, id) ->
        return "https://i.ytimg.com/vi/$id/sddefault.jpg"
    }
    return this
}

private val GOOGLE_USER_CONTENT =
    Regex("https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*")

private val GGPHT = Regex("https://yt3\\.ggpht\\.com/.*=s(\\d+)")

private val YTIMG = Regex("https://i\\.ytimg\\.com/vi/([^/]+)/.*")

/**
 * What a cover is asked for at, when it is going to fill a tile or a player.
 *
 * Sized for the player rather than the tiles: it is the same URL either way, and
 * the one place a soft cover shows is the full-screen one.
 */
const val COVER_SIZE = 720
