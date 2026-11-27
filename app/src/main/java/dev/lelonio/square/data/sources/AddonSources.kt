package dev.lelonio.square.data.sources

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A user-configured HTTP music addon. No addon code is downloaded or executed. */
@Serializable
data class AddonSourceConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String,
    val enabled: Boolean = true,
    /** Optional setting sent to addons which accept a quality query parameter. */
    val quality: String = "",
)

@Serializable
data class AddonSourceList(val sources: List<AddonSourceConfig> = emptyList())

data class AddonTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val streamUrl: String? = null,
)

data class AddonStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
)

/**
 * Persists addon endpoints and exposes changes to the service/UI. URLs are
 * configuration, not executable plugins. Keep secrets in the addon itself;
 * this deliberately has no token/password field.
 */
class AddonSourceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("square_addon_sources", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _sources = MutableStateFlow(read())
    val sources: StateFlow<List<AddonSourceConfig>> = _sources.asStateFlow()

    @Synchronized private fun read(): List<AddonSourceConfig> = runCatching {
        json.decodeFromString(AddonSourceList.serializer(), prefs.getString(KEY, null) ?: "{}").sources
    }.getOrDefault(emptyList())

    @Synchronized private fun commit(value: List<AddonSourceConfig>) {
        val safe = value.distinctBy { it.id }.take(MAX_SOURCES)
        prefs.edit().putString(KEY, json.encodeToString(AddonSourceList.serializer(), AddonSourceList(safe))).apply()
        _sources.value = safe
    }

    fun add(baseUrl: String, name: String = ""): AddonSourceConfig {
        val canonical = canonicalBaseUrl(baseUrl)
        require(_sources.value.none { canonicalBaseUrl(it.baseUrl) == canonical }) { "That addon is already added" }
        val item = AddonSourceConfig(name = name.trim(), baseUrl = canonical)
        commit(_sources.value + item)
        return item
    }

    fun update(source: AddonSourceConfig) {
        require(canonicalBaseUrl(source.baseUrl) == source.baseUrl) { "Invalid addon URL" }
        commit(_sources.value.map { if (it.id == source.id) source else it })
    }

    fun remove(id: String) = commit(_sources.value.filterNot { it.id == id })

    private companion object {
        const val KEY = "sources_v1"
        const val MAX_SOURCES = 20
    }
}

/**
 * HTTP client for the BitChord-style addon contract: GET /manifest.json,
 * GET /search?q=... and GET /stream/{trackId}. Responses are parsed leniently
 * so optional metadata does not make an otherwise playable result unusable.
 */
class AddonSourceClient(
    private val source: AddonSourceConfig,
    private val client: OkHttpClient = defaultClient,
) {
    private val base: HttpUrl = canonicalBaseUrl(source.baseUrl).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Addon URL must be HTTPS (or local HTTP)")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun manifest(): AddonManifest = withContext(Dispatchers.IO) {
        val root = getJson("manifest.json") as? JsonObject ?: error("Invalid addon manifest")
        AddonManifest(
            id = root.text("id"), name = root.text("name"), version = root.text("version"),
            resources = root.array("resources").mapNotNull { it.asText() },
        )
    }

    suspend fun search(query: String, limit: Int = 10): List<AddonTrack> = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        val url = endpoint("search").newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("limit", limit.coerceIn(1, 25).toString())
            .apply { if (source.quality.isNotBlank()) addQueryParameter("quality", source.quality) }
            .build()
        val root = requestJson(url) as? JsonObject ?: return@withContext emptyList()
        val list = root["tracks"] as? JsonArray ?: return@withContext emptyList()
        list.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.text("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = row.text("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            AddonTrack(
                id = id, title = title, artist = row.text("artist"), album = row.text("album"),
                durationMs = row.number("duration")?.let { (it * 1000).toLong() },
                artworkUrl = row.text("artworkURL").ifBlank { row.text("albumArtworkURL") }.ifBlank { null },
                streamUrl = row.text("streamURL").ifBlank { null },
            )
        }
    }

    suspend fun stream(track: AddonTrack): AddonStream? = withContext(Dispatchers.IO) {
        val raw = track.streamUrl?.let { JsonObject(mapOf("url" to kotlinx.serialization.json.JsonPrimitive(it))) }
            ?: requestJson(endpoint("stream", track.id)) as? JsonObject
            ?: return@withContext null
        val urlText = raw.text("url").ifBlank { return@withContext null }
        val streamUrl = urlText.toHttpUrlOrNull() ?: return@withContext null
        require(streamUrl.isHttps || isLocalHost(streamUrl.host)) { "Addon returned an unsafe stream URL" }
        val headers = (raw["headers"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            val text = value.asText()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            key.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) }?.let { it to text.take(2048) }
        }?.toMap().orEmpty()
        AddonStream(streamUrl.toString(), headers, raw.text("mimeType").ifBlank { null })
    }

    private fun getJson(path: String): JsonElement = requestJson(endpoint(path))

    private fun requestJson(url: HttpUrl): JsonElement {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Addon returned HTTP ${response.code}")
            val body = response.body?.string()?.takeIf { it.length <= MAX_RESPONSE_CHARS }
                ?: throw IOException("Addon response was empty or too large")
            return json.parseToJsonElement(body)
        }
    }

    private fun endpoint(vararg path: String): HttpUrl {
        val builder = base.newBuilder()
        path.forEach { segment -> builder.addPathSegment(segment) }
        return builder.build()
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 2_000_000
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        /** HTTPS is required except for loopback/private LAN addon servers. */
        fun canonicalBaseUrl(raw: String): String {
            val parsed = raw.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid addon URL")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Do not put credentials in the URL" }
            require(parsed.encodedPath.isEmpty() || parsed.encodedPath == "/") { "Enter the addon server root URL" }
            require(parsed.isHttps || isLocalHost(parsed.host)) { "Use HTTPS, or HTTP only for a local addon" }
            return parsed.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
        }

        private fun isLocalHost(host: String): Boolean =
            host == "localhost" || host == "127.0.0.1" || host == "::1" ||
                host.startsWith("10.") || host.startsWith("192.168.") ||
                host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
    }
}

/** The current BitChord-compatible manifest fields used by Square. */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val resources: List<String>,
)

/** Finds the best exact catalogue match, rejecting unrelated versions/remixes. */
object AddonTrackMatcher {
    fun bestMatch(
        candidates: List<AddonTrack>,
        title: String,
        artist: String,
        durationMs: Long? = null,
    ): AddonTrack? {
        val wantedTitle = normalize(title)
        val wantedArtists = artist.split(',', '&', ';').map(::normalize).filter(String::isNotBlank)
        if (wantedTitle.isBlank() || wantedArtists.isEmpty()) return null
        return candidates.asSequence()
            .filter { normalize(it.title) == wantedTitle }
            .filter { candidate ->
                val candidateArtists = candidate.artist.split(',', '&', ';').map(::normalize)
                candidateArtists.any { it in wantedArtists || wantedArtists.any { wanted -> it == wanted } }
            }
            .filter { candidate ->
                val actual = candidate.durationMs
                durationMs == null || actual == null || kotlin.math.abs(actual - durationMs) <= 3_000
            }
            .firstOrNull()
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
}

private fun JsonObject.text(name: String): String = this[name]?.asText().orEmpty()
private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray)?.toList().orEmpty()
private fun JsonObject.number(name: String): Double? = this[name]?.asText()?.toDoubleOrNull()?.takeIf { it > 0 }
private fun JsonElement.asText(): String? = runCatching { jsonPrimitive.content }.getOrNull()
