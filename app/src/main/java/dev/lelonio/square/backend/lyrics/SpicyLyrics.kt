package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricWord
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.LyricsCredit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lyrics from Spicy Lyrics, for listeners who have a key of their own.
 *
 * Optional, and off until a key is entered in the settings: the API asks each
 * person running an app to have their own key, which they get from Square's
 * page in Spicy Lyrics' catalogue. Asked by the Spotify track id, which is the
 * only thing it takes, so only for Spotify's tracks.
 *
 * What it answers is timed to the syllable more often than anything else here,
 * and much of it is synced by its community; who did it is shown under the
 * words, which the API makes a condition of using it. See [LyricsCredit].
 */
object SpicyLyrics {

    private val http = OkHttpClient()

    /** The listener's key, or blank when there is none; set by LyricsLibrary.attach. */
    @Volatile
    var key: () -> String = { "" }

    /** Until when the API asked not to be asked, after a 429; see RateLimit-Reset. */
    @Volatile
    private var quietUntil = 0L

    val enabled: Boolean get() = key().isNotBlank()

    suspend fun lyrics(trackUri: String): Lyrics? = withContext(Dispatchers.IO) {
        val secret = key().trim()
        val id = trackUri.removePrefix("spotify:track:")
        if (secret.isEmpty() || id == trackUri || System.currentTimeMillis() < quietUntil) return@withContext null
        val request = Request.Builder()
            .url("$API_URL/$id")
            .header("Authorization", "Bearer $secret")
            // The key handed out through Square's catalogue page allows this
            // origin; a phone has none of its own to send.
            .header("Origin", ORIGIN)
            .header("Accept", "application/json")
            .build()
        val body = runCatching {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 429 -> {
                        val wait = response.header("RateLimit-Reset")?.toLongOrNull() ?: 60L
                        quietUntil = System.currentTimeMillis() + wait * 1000
                        android.util.Log.w(TAG, "rate limited for ${wait}s")
                        null
                    }
                    !response.isSuccessful -> {
                        // The API says why in `error` (key_not_found,
                        // origin_not_allowed, ...): logged, never the key.
                        if (response.code != 404) {
                            val reason = runCatching {
                                JSONObject(response.body?.string().orEmpty()).optJSONObject("Body")?.optString("error")
                            }.getOrNull()
                            android.util.Log.w(TAG, "HTTP ${response.code} for $id: $reason")
                        }
                        null
                    }
                    else -> response.body?.string()
                }
            }
        }.getOrNull() ?: return@withContext null
        runCatching { parse(JSONObject(body)) }.getOrNull()
    }

    /**
     * The lyrics in an answer. They come inside an envelope, under `Body`
     * beside `Status` and `Type`: read at the top, where `Type` is "object",
     * every song came back with no lyrics.
     */
    internal fun parse(answer: JSONObject): Lyrics? {
        val json = answer.optJSONObject("Body") ?: answer
        val credit = credit(json)
        val lines = when (json.optString("Type")) {
            "Syllable" -> json.optJSONArray("Content").objects().mapNotNull { vocal ->
                val lead = vocal.optJSONObject("Lead") ?: return@mapNotNull null
                syllableLine(lead)
            }
            "Line" -> json.optJSONArray("Content").objects().mapNotNull { line ->
                val text = line.optString("Text").trim()
                if (text.isEmpty()) return@mapNotNull null
                LyricLine(
                    startTimeMs = seconds(line, "StartTime"),
                    text = text,
                    translation = line.optString("TranslatedText").takeIf { it.isNotBlank() },
                )
            }
            "Static" -> json.optJSONArray("Lines").objects().mapNotNull { line ->
                val text = line.optString("Text").trim()
                if (text.isEmpty()) null else LyricLine(startTimeMs = null, text = text)
            }
            else -> emptyList()
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines = lines, synced = json.optString("Type") != "Static", credit = credit)
    }

    /**
     * A sung line from its syllables: the text with a space after each one that
     * ends a word, and one timed piece per syllable, as TTML gives them.
     */
    private fun syllableLine(lead: JSONObject): LyricLine? {
        val syllables = lead.optJSONArray("Syllables").objects()
        if (syllables.isEmpty()) return null
        val text = StringBuilder()
        val words = syllables.mapNotNull { syllable ->
            val piece = syllable.optString("Text")
            text.append(piece)
            if (!syllable.optBoolean("IsPartOfWord")) text.append(' ')
            val start = seconds(syllable, "StartTime") ?: return@mapNotNull null
            piece.trim().takeIf { it.isNotEmpty() }
                ?.let { LyricWord(start, seconds(syllable, "EndTime") ?: start, it) }
        }
        val line = text.toString().replace(SPACES, " ").trim()
        if (line.isEmpty()) return null
        return LyricLine(
            startTimeMs = seconds(lead, "StartTime") ?: words.firstOrNull()?.startMs,
            text = line,
            words = words,
            translation = lead.optString("TranslatedText").takeIf { it.isNotBlank() },
        )
    }

    /** Who to thank, as the API wants it said; see LyricsCredit. */
    private fun credit(json: JSONObject): LyricsCredit {
        val attribution = json.optJSONObject("UploadAttribution")
        val uploader = attribution?.optJSONObject("Uploader")
        val maker = attribution?.optJSONObject("Maker")
        return LyricsCredit(
            provider = PROVIDER,
            uploader = uploader?.optString("username")?.takeIf { it.isNotBlank() },
            uploaderUrl = uploader?.optString("url")?.takeIf { it.startsWith("http") },
            maker = maker?.optString("username")?.takeIf { it.isNotBlank() },
            makerUrl = maker?.optString("url")?.takeIf { it.startsWith("http") },
        )
    }

    private fun seconds(json: JSONObject, key: String): Long? =
        if (json.has(key)) (json.optDouble(key) * 1000).toLong() else null

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private val SPACES = Regex("\\s+")

    private const val TAG = "SpicyLyrics"
    private const val PROVIDER = "Spicy Lyrics"
    private const val API_URL = "https://api.spicylyrics.org/v1/lyrics"

    /**
     * The origin the template allows; see the note on the request. An origin
     * is a scheme and a host only, and this one is the developer's own
     * GitHub Pages host rather than github.com, which everyone shares.
     */
    const val ORIGIN = "https://lelonio.github.io"

    /** Where a listener gets a key: Square's page in the catalogue, once it has one. */
    const val KEY_PAGE = "https://developers.spicylyrics.org/catalog"
}
