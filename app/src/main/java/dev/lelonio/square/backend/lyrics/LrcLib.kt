package dev.lelonio.square.backend.lyrics

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

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * @param durationMs used to tell versions apart — a single, an album cut and
     *   a live take share a title and an artist, and the length is what
     *   distinguishes them. LrcLib matches it within a couple of seconds.
     */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val cleaned = title.songTitle()
            val primary = artist.primaryArtist()

            // 1. Exact match with cleaned title, full artist and duration
            var body = get(url(cleaned, artist, durationMs))

            // 2. Exact match with primary artist and duration
            if (body == null && primary != artist) {
                body = get(url(cleaned, primary, durationMs))
            }

            // 3. Exact match with primary artist without duration
            if (body == null) {
                body = get(url(cleaned, primary, durationMs = null))
            }

            if (body != null) {
                parseJson(body, durationMs)?.let { return@withContext it }
            }

            // 4. Fallback search via /api/search when exact lookup misses
            search(cleaned, primary, durationMs)
        }

    private fun parseJson(body: String, durationMs: Long): Lyrics? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        json.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let { synced ->
            return parseLrc(synced)
        }
        json.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { plain ->
            return LyricsEstimator.estimate(plain, durationMs)
        }
        return null
    }

    private fun search(title: String, artist: String, durationMs: Long): Lyrics? {
        val query = "$title $artist".trim()
        val searchUrl = "https://lrclib.net/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val raw = get(searchUrl) ?: return null
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        if (array.length() == 0) return null

        val targetSec = durationMs / 1000.0
        var bestSynced: Lyrics? = null
        var bestPlain: Lyrics? = null
        var minDiff = Double.MAX_VALUE

        for (i in 0 until array.length().coerceAtMost(10)) {
            val item = array.optJSONObject(i) ?: continue
            val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plain = item.optString("plainLyrics").takeIf { it.isNotBlank() }
            if (synced == null && plain == null) continue

            val itemDuration = item.optDouble("duration", 0.0)
            val diff = if (targetSec > 0 && itemDuration > 0) kotlin.math.abs(itemDuration - targetSec) else 0.0

            if (synced != null) {
                if (diff <= 4.0 && diff < minDiff) {
                    minDiff = diff
                    bestSynced = parseLrc(synced)
                } else if (bestSynced == null) {
                    bestSynced = parseLrc(synced)
                }
            } else if (plain != null && bestPlain == null && diff <= 5.0) {
                bestPlain = LyricsEstimator.estimate(plain, durationMs)
            }
        }

        return bestSynced ?: bestPlain
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
            if (!response.isSuccessful) null else response.body.string()
        }
    }.getOrNull()

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
