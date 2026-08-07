package dev.lelonio.square.backend.youtube

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Lyrics from LrcLib, for tracks YouTube has none for.
 *
 * YouTube Music does carry lyrics, but only for accounts with a Premium
 * subscription and only through a browse endpoint that answers nothing without
 * one. LrcLib is an open, unauthenticated database of synced lyrics built for
 * exactly this, so it is what this backend asks — and it means the lyrics work
 * whether or not anyone has signed in.
 *
 * A miss is the ordinary case for a lot of the catalogue, so everything here
 * answers null rather than raising.
 */
object LrcLib {

    private val http = OkHttpClient()

    /**
     * @param durationMs used to tell versions apart — a single, an album cut and
     *   a live take share a title and an artist, and the length is what
     *   distinguishes them. LrcLib matches it within a couple of seconds.
     */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val cleaned = title.cleanedTitle()
            val body = get(url(cleaned, artist, durationMs))
            // Retry without the duration before giving up: YouTube's own
            // duration includes any silence at the end of the upload, so an
            // exact-length match misses tracks LrcLib really does have.
                ?: get(url(cleaned, artist, durationMs = null))
                ?: return@withContext null

            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null

            json.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let { synced ->
                return@withContext parseLrc(synced)
            }
            json.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { plain ->
                return@withContext Lyrics(
                    lines = plain.lines()
                        .filter { it.isNotBlank() }
                        .map { LyricLine(startTimeMs = null, text = it.trim()) },
                    synced = false,
                )
            }
            null
        }

    private fun url(title: String, artist: String, durationMs: Long?): String = buildString {
        append("https://lrclib.net/api/get?track_name=")
        append(java.net.URLEncoder.encode(title, "UTF-8"))
        append("&artist_name=")
        append(java.net.URLEncoder.encode(artist, "UTF-8"))
        if (durationMs != null && durationMs > 0) {
            append("&duration=").append(durationMs / 1000)
        }
    }

    /** Null on anything but a 200, which includes LrcLib's 404 for "no match". */
    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            // LrcLib asks clients to identify themselves.
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    /**
     * Trims what YouTube titles carry and song titles do not.
     *
     * Uploads are named for a video — "(Official Video)", "[4K Remaster]",
     * "(Lyrics)" — and searching a lyrics database for any of that finds
     * nothing.
     */
    private fun String.cleanedTitle(): String =
        replace(NOISE, "").replace(WHITESPACE, " ").trim()

    private val NOISE = Regex(
        "\\((?:official|lyric|audio|video|visualizer|hd|4k|mv|m/v)[^)]*\\)" +
            "|\\[[^\\]]*(?:official|lyric|audio|video|remaster|hd|4k)[^\\]]*\\]",
        RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE = Regex("\\s+")

    /**
     * `[mm:ss.xx] words` per line.
     *
     * Lines with no words are kept as timed blanks rather than dropped: they are
     * the instrumental gaps, and without them the view holds the previous line
     * highlighted through a stretch where nothing is being sung.
     */
    private fun parseLrc(raw: String): Lyrics? {
        val lines = raw.lines().mapNotNull { line ->
            val match = LRC_LINE.find(line) ?: return@mapNotNull null
            val (minutes, seconds, fraction, text) = match.destructured
            val startMs = minutes.toLong() * 60_000 +
                seconds.toLong() * 1_000 +
                // Two digits are hundredths, three are milliseconds.
                fraction.padEnd(3, '0').take(3).toLong()
            LyricLine(startTimeMs = startMs, text = text.trim())
        }
        return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, synced = true) }
    }

    private val LRC_LINE = Regex("\\[(\\d{1,2}):(\\d{2})[.:](\\d{2,3})\\](.*)")

    private const val USER_AGENT = "Square (https://github.com/lelonio/square)"
}
