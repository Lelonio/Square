package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Skips what is not music in a YouTube track, from SponsorBlock's database.
 *
 * A song on YouTube Music is very often its video: an intro before the first
 * note, a sketch in the middle, a minute of talking after the last chord.
 * Trimming silence does nothing for those, because none of them is silent.
 * SponsorBlock keeps a category for exactly this, `music_offtopic`, filled in
 * by the people who watch the videos; this asks for it once per track and
 * jumps over each segment as playback reaches it (#30).
 *
 * Only the video id leaves the phone, to sponsor.ajay.app, and only for the
 * YouTube source. A segment is skipped once per play: someone who seeks back
 * into it means to hear it.
 */
class SponsorBlock(
    private val player: ExoPlayer,
    private val enabled: () -> Boolean,
) : Player.Listener {

    private data class Segment(val startMs: Long, val endMs: Long)

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var fetch: Job? = null

    /** Answers already had, by video id, for the life of the player. */
    private val known = HashMap<String, List<Segment>>()

    /** The segments of what is playing, and which of them have been jumped. */
    private var segments: List<Segment> = emptyList()
    private val skipped = HashSet<Segment>()

    init {
        player.addListener(this)
        load(player.currentMediaItem)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = load(mediaItem)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) follow() else watch?.cancel()
    }

    private fun load(item: MediaItem?) {
        segments = emptyList()
        skipped.clear()
        fetch?.cancel()
        val id = item?.mediaId?.let(::videoIdOf) ?: return
        if (!enabled()) return
        known[id]?.let {
            segments = it
            follow()
            return
        }
        fetch = scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { ask(id) }.getOrDefault(emptyList()) }
            known[id] = found
            if (videoIdOf(player.currentMediaItem?.mediaId.orEmpty()) == id) {
                segments = found
                if (found.isNotEmpty()) {
                    android.util.Log.i(TAG, "$id: ${found.size} segment(s) to skip")
                }
                follow()
            }
        }
    }

    /** Checks the position a few times a second while there is anything to skip. */
    private fun follow() {
        watch?.cancel()
        if (segments.isEmpty() || !player.isPlaying) return
        watch = scope.launch {
            while (isActive) {
                val at = player.currentPosition
                val inside = segments.firstOrNull { at >= it.startMs && at < it.endMs - END_SLACK_MS }
                if (inside != null && skipped.add(inside) && enabled()) {
                    val length = player.duration
                    // A segment that runs to the end of the song is the song
                    // being over: move on rather than seek to its last frame.
                    if (length > 0 && inside.endMs >= length - END_SLACK_MS) {
                        android.util.Log.i(TAG, "skipping the rest of the track at ${at}ms")
                        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
                    } else {
                        android.util.Log.i(TAG, "skipping ${inside.startMs}..${inside.endMs}ms")
                        player.seekTo(inside.endMs)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun ask(videoId: String): List<Segment> {
        val categories = URLEncoder.encode("[\"music_offtopic\"]", "UTF-8")
        val url = URL("$API?videoID=${URLEncoder.encode(videoId, "UTF-8")}&categories=$categories")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Square")
        }
        return try {
            // 404 is SponsorBlock saying it has nothing for this video.
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.getJSONObject(index)
                if (entry.optString("actionType", "skip") != "skip") return@mapNotNull null
                val span = entry.getJSONArray("segment")
                val start = (span.getDouble(0) * 1000).toLong()
                val end = (span.getDouble(1) * 1000).toLong()
                // Anything shorter than a second is not worth the jump it costs.
                if (end - start < MIN_SEGMENT_MS) null else Segment(start, end)
            }.sortedBy { it.startMs }
        } finally {
            connection.disconnect()
        }
    }

    private fun videoIdOf(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(YouTubeBackend.TRACK_PREFIX) }
            ?.removePrefix(YouTubeBackend.TRACK_PREFIX)
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "SquareSponsorBlock"
        const val API = "https://sponsor.ajay.app/api/skipSegments"
        const val TICK_MS = 250L
        const val TIMEOUT_MS = 6_000
        const val MIN_SEGMENT_MS = 1_000L

        /** How close to a segment's end the position counts as past it. */
        const val END_SLACK_MS = 400L
    }
}
