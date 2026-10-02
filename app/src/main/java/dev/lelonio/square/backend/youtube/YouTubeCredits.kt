package dev.lelonio.square.backend.youtube

import dev.lelonio.square.backend.spotify.SpotifyCredits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who made a YouTube Music song, from the same page as "View song credits"
 * there: performed by, written by, produced by, and whose metadata it is.
 *
 * The page answers without an account, so it is asked as YouTube Music's web
 * client asks it, and handed to the panel in the shape the Spotify credits
 * already have.
 */
object YouTubeCredits {

    private val http = OkHttpClient()

    /** Null when the song has none or anything goes wrong: a panel, not a feature. */
    suspend fun of(trackUri: String): SpotifyCredits.Credits? = withContext(Dispatchers.IO) {
        val videoId = YouTubeBackend.videoIdOfUri(trackUri).takeIf { it.isNotEmpty() }
            ?: return@withContext null
        runCatching {
            val body = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB_REMIX")
                            .put("clientVersion", com.metrolist.innertube.models.YouTubeClient.WEB_REMIX.clientVersion)
                            .put("hl", java.util.Locale.getDefault().language),
                    ),
                )
                .put("browseId", "MPTC$videoId")
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .header("Origin", "https://music.youtube.com")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val text = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            } ?: return@withContext null
            parse(JSONObject(text))
        }.onFailure { android.util.Log.w(TAG, "credits for $videoId", it) }.getOrNull()
    }

    private fun parse(root: JSONObject): SpotifyCredits.Credits? {
        val dialog = find(root, "dismissableDialogRenderer") ?: return null
        val title = dialog.optJSONObject("metadata")
            ?.optJSONObject("musicMultiRowListItemRenderer")
            ?.optJSONObject("title")
            ?.let(::text)
            .orEmpty()
        val roles = mutableListOf<SpotifyCredits.Role>()
        val sources = mutableListOf<String>()
        val sections = dialog.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i)
                ?.optJSONObject("dismissableDialogContentSectionRenderer") ?: continue
            val heading = section.optJSONObject("title")?.let(::text).orEmpty()
            val runs = section.optJSONObject("subtitle")?.optJSONArray("runs") ?: continue
            val people = mutableListOf<SpotifyCredits.Person>()
            for (r in 0 until runs.length()) {
                val run = runs.optJSONObject(r) ?: continue
                // One name per line; the lines between them are runs of their own.
                val name = run.optString("text").trim().takeIf { it.isNotEmpty() } ?: continue
                val artistId = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                    ?.takeIf { it.startsWith("UC") }
                people += SpotifyCredits.Person(
                    name = name,
                    subroles = emptyList(),
                    uri = artistId?.let { "${YouTubeBackend.ARTIST_PREFIX}$it" },
                )
            }
            // The last section names who supplied the data, which is the label.
            if (heading.contains("provided by", ignoreCase = true) || i == sections.length() - 1 &&
                heading.contains("metadata", ignoreCase = true)
            ) {
                sources += people.map { it.name }
            } else if (people.isNotEmpty()) {
                roles += SpotifyCredits.Role(heading, people)
            }
        }
        return SpotifyCredits.Credits(title, roles, sources).takeUnless { it.isEmpty }
    }

    private fun text(runs: JSONObject): String {
        val array = runs.optJSONArray("runs") ?: return runs.optString("simpleText")
        return (0 until array.length()).joinToString("") { array.optJSONObject(it)?.optString("text").orEmpty() }
    }

    /** The first object under [key], however deep: the page nests it in an action. */
    private fun find(node: Any?, key: String): JSONObject? = when (node) {
        is JSONObject -> node.optJSONObject(key) ?: node.keys().asSequence()
            .firstNotNullOfOrNull { find(node.opt(it), key) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { find(node.opt(it), key) }
        else -> null
    }

    private const val TAG = "SquareYTCredits"
}
