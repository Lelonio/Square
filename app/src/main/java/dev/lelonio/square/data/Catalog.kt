package dev.lelonio.square.data

import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A track as shown in the UI.
 *
 * Deliberately flat and free of Web API shapes: it is produced by the native
 * catalogue, and nothing above this layer should care which transport supplied
 * it.
 */
@Serializable
data class CatalogTrack(
    val uri: String,
    val name: String,
    val artist: String,
    /** The first artist's own uri, when the source gives one; see catalog.rs. */
    val artistUri: String? = null,
    /**
     * Every artist credited, named and addressed.
     *
     * [artist] is these names run together for the places that show one line;
     * this is what makes each of them its own way into the app.
     */
    val artists: List<CatalogArtist> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val artworkUrl: String? = null,
    /**
     * When the track was added to the playlist it was read from, ISO-8601.
     *
     * Only the Web API knows this — the access point's context has no such
     * field — so it is null for anything resolved through the fallback path,
     * and sorting by it then leaves the list in playlist order.
     */
    val addedAt: String? = null,
)

/**
 * One line of lyrics.
 *
 * @param startTimeMs when the line begins, or null for unsynced lyrics — the
 *   distinction decides whether the view can highlight along with playback.
 */
data class LyricLine(val startTimeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/**
 * A track's Canvas.
 *
 * @param isVideo false for the handful of canvases that are a still image; those
 *   are shown as a picture rather than handed to a video surface.
 */
data class CanvasClip(val url: String, val isVideo: Boolean)

/** A playlist owned by the logged-in account. */
@Serializable
data class CatalogPlaylist(
    val uri: String,
    val name: String,
    /** Null when the playlist has no custom cover; the UI draws a tile instead. */
    val artworkUrl: String? = null,
)

/** One credited artist: what to write, and where it leads. */
@Serializable
data class CatalogArtist(val name: String, val uri: String? = null)

/** Bridges a Web API track into the model the UI and the queue already use. */
fun TrackDto.toCatalogTrack(addedAt: String? = null): CatalogTrack = CatalogTrack(
    uri = uri,
    name = name,
    artist = artists.joinToString(", ") { it.name },
    artistUri = artists.firstOrNull()?.uri,
    artists = artists.map { CatalogArtist(it.name, it.uri) },
    album = album?.name.orEmpty(),
    durationMs = durationMs,
    explicit = explicit,
    artworkUrl = album?.images?.firstOrNull()?.url,
    addedAt = addedAt,
)

/**
 * Catalogue reads over the Spotify access point.
 *
 * This exists instead of [SpotifyApi] because `api.spotify.com` meters requests
 * per application, and the client id librespot uses is shared with every other
 * user of it — so the quota runs out regardless of how this app behaves. The
 * access point returns the same data for the logged-in user without that limit.
 *
 * Every call blocks on native code, so all are confined to [Dispatchers.IO].
 */
object Catalog {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun username(): String = withContext(Dispatchers.IO) {
        NativeBridge.username()
    }

    /** Track URIs in a playlist, album, or the account's Liked Songs. */
    suspend fun contextTrackUris(contextUri: String): List<String> = withContext(Dispatchers.IO) {
        json.decodeFromString<List<String>>(NativeBridge.contextTracks(contextUri))
    }

    suspend fun tracks(uris: List<String>): List<CatalogTrack> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()
        val urisJson = json.encodeToString(ListSerializer(String.serializer()), uris)
        json.decodeFromString<List<CatalogTrack>>(NativeBridge.tracksMetadata(urisJson))
    }

    /**
     * Lyrics for a track, or null when Spotify has none.
     *
     * Most of the catalogue has no lyrics, so absence is an ordinary answer
     * rather than a failure; the native side already turns a missing-lyrics
     * response into a null instead of an error.
     */
    suspend fun lyrics(trackUri: String): Lyrics? = withContext(Dispatchers.IO) {
        val raw = NativeBridge.lyrics(trackUri)
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return@withContext null
        val body = root as? JsonObject ?: return@withContext null
        val lyrics = body["lyrics"] as? JsonObject ?: return@withContext null

        // "LINE_SYNCED" means every line carries a timestamp; anything else is
        // a plain block of text.
        val synced = lyrics["syncType"]?.jsonPrimitive?.content == "LINE_SYNCED"

        val lines = (lyrics["lines"] as? JsonArray).orEmpty().mapNotNull { element ->
            val line = element as? JsonObject ?: return@mapNotNull null
            val words = line["words"]?.jsonPrimitive?.content.orEmpty()
            if (words.isBlank()) return@mapNotNull null
            LyricLine(
                startTimeMs = line["startTimeMs"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?.takeIf { synced },
                text = words,
            )
        }

        lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, synced) }
    }

    /**
     * The Canvas clip for a track, or null when there is none.
     *
     * Most of the catalogue has no canvas, so a null here is an ordinary answer
     * and the player falls back to the cover rather than showing an error.
     */
    suspend fun canvas(trackUri: String): CanvasClip? = withContext(Dispatchers.IO) {
        val raw = runCatching { NativeBridge.canvas(trackUri) }.getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val url = root["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        CanvasClip(
            url = url,
            isVideo = root["isVideo"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
        )
    }

    /**
     * Spotify's DJ, which is not a playlist.
     *
     * The access point lists it among them, but it is a generated stream: it has
     * no track list to open, and opening it is the one thing a row in a library
     * promises. The same id for every account.
     */
    private const val DJ_URI = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq"

    /** The account's own playlists. */
    suspend fun playlists(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        json.decodeFromString<List<CatalogPlaylist>>(NativeBridge.rootlist())
            .filterNot { it.uri == DJ_URI }
    }

    /**
     * The cover of one playlist, fetched on its own.
     *
     * Only worth calling for playlists the rootlist gave no artwork for — see
     * the note on the native side. Null both when Spotify has no cover and when
     * the lookup fails: a missing tile falls back to the generated one either
     * way, and a playlist list should not fail over an image.
     */
    suspend fun playlistCover(uri: String): String? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<String?>(NativeBridge.playlistCover(uri)) }.getOrNull()
    }

    /**
     * Tracks of the account's first playlist.
     *
     * A placeholder for a real browse screen, and currently the only listing
     * that works: `spotify:user:{id}:collection` — Liked Songs — is not a valid
     * context for the access point's context endpoint and answers 503, so the
     * collection needs a different call than the one playlists use.
     *
     * @param limit how many tracks to resolve. Each is its own access-point
     *   round trip, so a whole playlist is not something to fetch eagerly.
     */
    suspend fun firstPlaylistTracks(limit: Int = 50): List<CatalogTrack> {
        val first = playlists().firstOrNull() ?: error("nessuna playlist trovata nell'account")
        return tracks(contextTrackUris(first.uri).take(limit))
    }
}
