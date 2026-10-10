package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricWord
import dev.lelonio.square.data.Lyrics
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Word-timed lyrics from KuGou, strongest on Chinese, Korean and Japanese
 * music, which the other sources hold little of.
 *
 * Two requests: a search by artist, title and length that names candidates
 * with a key each, then the lyrics of the closest one, in KRC. A KRC file is
 * encrypted the same way by every client: base64, a "krc1" header, a fixed
 * sixteen-byte XOR, then zlib. Inside, each line is `[start,length]` and each
 * word `<offset,length,0>text`, the offsets counted from the line's start.
 *
 * The first lines of a KRC are credits ("Lyrics by: …"), sung by nobody and
 * timed as if they were; they are left out.
 */
object KuGou {

    private val http = OkHttpClient()

    suspend fun lyrics(title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val cleaned = title.songTitle()
            if (cleaned.isBlank()) return@withContext null
            val candidate = search(cleaned, artist.primaryArtist(), durationMs) ?: return@withContext null
            val krc = download(candidate.first, candidate.second) ?: return@withContext null
            parse(krc, cleaned)?.also {
                android.util.Log.i(TAG, "$cleaned: ${it.lines.size} lines, ${it.lines.count { l -> l.words.isNotEmpty() }} timed by word")
            }
        }

    /** The id and key of the candidate closest in length, or null. */
    private fun search(title: String, artist: String, durationMs: Long): Pair<String, String>? {
        val url = buildString {
            append(SEARCH_URL).append("?ver=1&man=yes&client=pc&hash=")
            append("&keyword=").append(encode("$artist - $title"))
            if (durationMs > 0) append("&duration=").append(durationMs)
        }
        val body = get(url) ?: return null
        val candidates = runCatching { JSONObject(body).optJSONArray("candidates") }.getOrNull() ?: return null
        val best = (0 until candidates.length())
            .mapNotNull { candidates.optJSONObject(it) }
            .filter { it.optString("id").isNotEmpty() && it.optString("accesskey").isNotEmpty() }
            // Of a length that can be the same recording; the search answers
            // something for nearly everything, and a cover or a live take timed
            // to a different master would sing along out of step.
            .filter { durationMs <= 0 || abs(it.optLong("duration") - durationMs) <= MAX_DRIFT_MS }
            .minByOrNull { if (durationMs > 0) abs(it.optLong("duration") - durationMs) else 0L }
            ?: return null
        return best.optString("id") to best.optString("accesskey")
    }

    private fun download(id: String, key: String): String? {
        val url = "$DOWNLOAD_URL?ver=1&client=pc&fmt=krc&charset=utf8&id=${encode(id)}&accesskey=${encode(key)}"
        val body = get(url) ?: return null
        val content = runCatching { JSONObject(body).optString("content") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { decrypt(android.util.Base64.decode(content, android.util.Base64.DEFAULT)) }.getOrNull()
    }

    /** KRC's own encryption, the same in every client that reads it. */
    internal fun decrypt(raw: ByteArray): String? {
        if (raw.size <= 4 || String(raw, 0, 4, Charsets.US_ASCII) != "krc1") return null
        val body = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KEY[i % KEY.size]).toByte() }
        val inflater = java.util.zip.Inflater()
        inflater.setInput(body)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }

    /**
     * The lines and their words, credits left out.
     *
     * A credit is any line before the first real lyric that names the song
     * itself or carries a colon (including the full-width one Chinese text
     * uses): "Lyrics by：", "作词：".
     */
    internal fun parse(krc: String, title: String): Lyrics? {
        val lines = krc.lineSequence().mapNotNull { line ->
            val head = LINE.find(line) ?: return@mapNotNull null
            val start = head.groupValues[1].toLong()
            val pieces = WORD.findAll(line.substring(head.range.last + 1)).toList()
            // The line keeps the spaces between the pieces; the words do not,
            // as TTML's do not either: the view finds each word in the line.
            val text = pieces.joinToString("") { it.groupValues[3] }.trim()
            val words = pieces.mapNotNull { word ->
                val offset = word.groupValues[1].toLong()
                val length = word.groupValues[2].toLong()
                word.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    ?.let { LyricWord(start + offset, start + offset + length, it) }
            }
            if (text.isEmpty()) null else LyricLine(startTimeMs = start, text = text, words = words)
        }.toList()
        val firstSung = lines.indexOfFirst { line ->
            val text = line.text
            !(text.contains('：') || text.contains(':') ||
                text.startsWith(title, ignoreCase = true) && text.contains(" - "))
        }
        val sung = if (firstSung < 0) emptyList() else lines.drop(firstSung)
        return sung.takeIf { it.isNotEmpty() }?.let { Lyrics(lines = it, synced = true) }
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val LINE = Regex("""^\[(\d+),(\d+)]""")
    private val WORD = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    private val KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    /** Further apart than this, the candidate is another recording. */
    private const val MAX_DRIFT_MS = 4_000L

    private const val TAG = "KuGou"
    private const val SEARCH_URL = "https://lyrics.kugou.com/search"
    private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
}
