package dev.lelonio.square.backend.spotify

import android.content.Context
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spotify's DJ: an endless session of tracks, with a voice between them.
 *
 * The DJ is listed among the account's playlists, and asked for as one it has
 * nothing in it. What it plays comes a few tracks at a time from its own
 * session service, each page naming the next, and some tracks come with a line
 * to say before or after them, which Spotify synthesises on request. The engine
 * does the asking, the synthesising and the saying (native/src/dj.rs); this is
 * the app's side: starting a session, keeping the queue fed as it plays, and
 * whether the listener wants any of it.
 *
 * Played as a queue of loose tracks filed under the DJ, so the account still
 * records the listens against it and other devices name it.
 */
object SpotifyDj {

    /** The setting, mirrored here so the pages that list playlists can ask. */
    @Volatile
    var enabled = true

    fun isDj(contextUri: String?) = contextUri == Catalog.DJ_URI

    /** The page after the last one read, where the session's next tracks are. */
    @Volatile
    private var nextPage = ""

    private val _speaking = MutableStateFlow(false)

    /**
     * True while the DJ is talking, from the moment its voice reaches the
     * output to the first packet of music after it. The player shows the DJ
     * then instead of the song, which has not started.
     */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** How loud the DJ is right now, 0 to 1, for the picture that moves with it. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Close to the DJ's own: a green, a blue, a violet. */
    private val DEFAULT_COLORS = listOf(0xFF1ED760.toInt(), 0xFF2D46B9.toInt(), 0xFF8D67AB.toInt())

    private val _colors = MutableStateFlow(DEFAULT_COLORS)

    /** The DJ's own colours, top to bottom, as its session gives them. */
    val colors: StateFlow<List<Int>> = _colors.asStateFlow()

    /** Called by the output; see AudioOutput.writeVoice. */
    fun setSpeaking(on: Boolean) {
        _speaking.value = on
        if (!on) _level.value = 0f
    }

    /**
     * Measures a packet of the DJ's voice: 16-bit little-endian PCM, read
     * without moving the buffer the output is about to write.
     */
    fun hear(data: ByteBuffer, sizeInBytes: Int) {
        val pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = sizeInBytes / 2
        if (samples == 0) return
        var sum = 0.0
        var i = 0
        while (i < samples) {
            val value = pcm.getShort(i * 2) / 32768.0
            sum += value * value
            i += 2 // one channel is enough to see a voice move
        }
        val rms = kotlin.math.sqrt(sum / (samples / 2).coerceAtLeast(1))
        _level.value = (rms * 4).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The session's newest tracks, ready to be played: the ones not heard yet.
     *
     * The session answers with everything it has played since it began and a
     * few new tracks at the end, so starting from the top played the same
     * songs every time the DJ was opened. Waits a moment for the line that
     * opens the first, which otherwise could never be ready in time: a line
     * takes seconds to synthesise and the track would start without it.
     */
    suspend fun start(context: Context): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val session = session(context)
        val heard = heard(context)
        // A session never met before is heard but for its newest part.
        val unheard = if (heard.isEmpty() && session.size > SEGMENT) {
            session.takeLast(SEGMENT)
        } else {
            session.filter { it.uri !in heard }.ifEmpty { session.takeLast(SEGMENT) }
        }
        prepare(context, unheard)
        unheard.firstOrNull()?.let { NativeBridge.djAwaitOpening(it.uri, OPENING_WAIT_MS) }
        unheard
    }

    /**
     * The tracks the session has added since it was last asked: the ones not
     * heard yet. The session answers with its whole history every time, and
     * leaving that to the queue to sort out put the songs heard at the start
     * back at the end of it. The caller hands them to [prepare].
     */
    suspend fun more(context: Context): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val heard = heard(context)
        // The next page is what makes the session go on: asked for again,
        // the session itself answers with the same tracks.
        session(context, nextPage).filter { it.uri !in heard }
    }

    /** Has the lines of [tracks] made, and remembers them as heard. */
    fun prepare(context: Context, tracks: List<CatalogTrack>) {
        if (tracks.isEmpty()) return
        NativeBridge.djPrepare(org.json.JSONArray(tracks.map { it.uri }).toString())
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val heard = (heard(context) + tracks.map { it.uri }).toList().takeLast(HEARD_CAP)
        store.edit().putString(KEY_HEARD, org.json.JSONArray(heard).toString()).apply()
    }

    private fun heard(context: Context): Set<String> {
        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY_HEARD, null)
            ?: return emptySet()
        return runCatching {
            val list = org.json.JSONArray(raw)
            (0 until list.length()).map { list.getString(it) }.toCollection(LinkedHashSet())
        }.getOrDefault(emptySet())
    }

    private suspend fun session(context: Context, page: String = ""): List<CatalogTrack> {
        val app = context.applicationContext as SquareApplication
        val answer = JSONObject(
            NativeBridge.djSession(page, app.language.language(), app.pathfinderKeys.appVersion),
        )
        nextPage = answer.optString("next")
        answer.optJSONObject("colors")?.let { colors ->
            val parsed = listOf("top", "mid", "bottom").mapNotNull { parseColor(colors.optString(it)) }
            if (parsed.size == 3) _colors.value = parsed
        }
        val uris = answer.optJSONArray("tracks")?.let { list ->
            (0 until list.length()).map { list.getString(it) }
        }.orEmpty()
        if (uris.isEmpty()) return emptyList()
        // In the session's order: the lines were written for it.
        val byUri = Catalog.tracks(uris).associateBy { it.uri }
        return uris.mapNotNull { byUri[it] }
    }

    /** `#RRGGBB`, `#AARRGGBB` or `#RRGGBBAA`, whichever the session uses. */
    private fun parseColor(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> android.graphics.Color.parseColor("#$hex")
                8 -> {
                    // Read as RRGGBBAA when the last pair looks like an alpha
                    // and the first does not; Android's own reading otherwise.
                    val asCss = (hex.substring(6, 8).toInt(16) shl 24) or hex.substring(0, 6).toInt(16)
                    if (hex.startsWith("FF", ignoreCase = true)) android.graphics.Color.parseColor("#$hex") else asCss
                }
                else -> null
            }
        }.getOrNull()
    }

    private const val STORE = "spotify_dj"
    private const val KEY_HEARD = "heard"

    /** The tracks a session adds at a time. */
    private const val SEGMENT = 5

    /** How many heard tracks are remembered: far more than a session repeats. */
    private const val HEARD_CAP = 500

    /** How long a new session waits for its first line before playing without it. */
    private const val OPENING_WAIT_MS = 8_000L
}
