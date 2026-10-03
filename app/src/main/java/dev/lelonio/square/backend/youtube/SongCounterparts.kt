package dev.lelonio.square.backend.youtube

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The song a music video is a video of.
 *
 * A video saved to a list plays its own soundtrack: the intro, the skit in the
 * middle, the sound effects, and a title no lyrics site knows. YouTube Music
 * has the record as a separate entry, found here the way a listener would find
 * it: the same title by the same artist, among the songs, and about as long.
 *
 * Shared by the lists, which show the song where they can, and by the stream
 * resolver, which plays the song whatever the list had time to show — so a
 * tap on a row is the right song even before its row has caught up. What is
 * found is kept on disk: a playlist opened once opens converted ever after.
 */
object SongCounterparts {

    private class Found(val song: SongItem?)

    /** By video id. A null song is "looked, there is none", kept for this run only. */
    private val found = ConcurrentHashMap<String, Found>()

    /** One search per video at a time, whoever asks: a list and the resolver can ask at once. */
    private val searching = ConcurrentHashMap<String, Mutex>()

    /** The videos the lists have shown, by id: what the resolver needs to look for their songs. */
    private val shown = ConcurrentHashMap<String, SongItem>()

    private val _resolved = MutableSharedFlow<Pair<String, SongItem>>(extraBufferCapacity = 256)

    /** Every song found, as (video id, song), as it is found. */
    val resolved: SharedFlow<Pair<String, SongItem>> = _resolved.asSharedFlow()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private val saveLock = Any()

    fun init(dir: File) {
        if (file != null) return
        file = File(dir, FILE_NAME)
        io.launch { load() }
    }

    fun isVideo(item: SongItem) =
        !item.isEpisode && item.uploadEntityId == null &&
            (item.musicVideoType == MUSIC_VIDEO_TYPE_OMV || item.musicVideoType == MUSIC_VIDEO_TYPE_UGC)

    /** The song already found for [videoId], if any. */
    fun known(videoId: String): SongItem? = found[videoId]?.song

    /** The video [songId] was found for, if it was. */
    fun videoOf(songId: String): String? = found.entries.firstOrNull { it.value.song?.id == songId }?.key

    /**
     * Whether [videoId] should play as its song: only a video one of the
     * listener's own lists showed. The same video found anywhere else — a chart
     * of videos, a search — is meant as the video, song known or not.
     */
    fun wanted(videoId: String): Boolean = shown.containsKey(videoId)

    /** Remembers [items] as shown, so the resolver can find their songs if they are played. */
    fun remember(items: List<SongItem>) {
        items.filter(::isVideo).forEach { shown[it.id] = it }
    }

    suspend fun find(video: SongItem): SongItem? {
        found[video.id]?.let { return it.song }
        val lock = searching.computeIfAbsent(video.id) { Mutex() }
        return lock.withLock {
            found[video.id]?.let { return@withLock it.song }
            search(video)
        }
    }

    /** Blocking, for the resolver on its loading thread: [videoId] must have been [remember]ed. */
    fun findBlocking(videoId: String): SongItem? {
        found[videoId]?.let { return it.song }
        val video = shown[videoId] ?: return null
        return runBlocking { find(video) }
    }

    private suspend fun search(video: SongItem): SongItem? {
        val artist = video.artists.firstOrNull()?.name.orEmpty()
        val title = songTitleOf(video.title, artist)
        var answered = false
        val match = withTimeoutOrNull(TIMEOUT_MS) {
            val results = YouTube.search("$title $artist".trim(), YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()?.items
            answered = results != null
            results.orEmpty()
                .filterIsInstance<SongItem>()
                .take(CANDIDATES)
                .firstOrNull { isSongOf(video, title, it) }
        }
        // A timeout is not an answer: asked again next time.
        if (match != null || answered) found[video.id] = Found(match)
        if (match != null) {
            _resolved.tryEmit(video.id to match)
            save()
        } else if (answered) {
            android.util.Log.i(TAG, "no song for video ${video.id} \"${video.title}\"")
        }
        return match
    }

    /** A video's title as a song's: no "(Official Video)", no "Artist - " in front. */
    private fun songTitleOf(title: String, artist: String): String {
        var clean = title.replace(VIDEO_TAG, " ")
        if (artist.isNotEmpty()) {
            clean = clean.replace(Regex("^\\s*${Regex.escape(artist)}\\s*[-–—:|]\\s*", RegexOption.IGNORE_CASE), "")
        }
        return clean.replace(Regex("\\s+"), " ").trim().ifEmpty { title }
    }

    private fun isSongOf(video: SongItem, title: String, song: SongItem): Boolean {
        if (song.musicVideoType != null && song.musicVideoType != MUSIC_VIDEO_TYPE_ATV) return false
        val wanted = squash(title)
        val got = squash(song.title)
        if (wanted.isEmpty() || got.isEmpty()) return false
        val sameTitle = wanted == got ||
            (kotlin.math.min(wanted.length, got.length) >= 4 && (wanted.contains(got) || got.contains(wanted)))
        if (!sameTitle) return false
        val channel = video.artists.map { squash(it.name).replace(CHANNEL_NOISE, "") }
        val sameArtist = song.artists.any { credited ->
            val name = squash(credited.name)
            name.isNotEmpty() && (channel.any { it == name || it.contains(name) } || squash(video.title).contains(name))
        }
        if (!sameArtist) return false
        val a = video.duration
        val b = song.duration
        return a == null || b == null || kotlin.math.abs(a - b) <= DURATION_SLACK_S
    }

    private fun squash(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun load() {
        val source = file?.takeIf { it.exists() } ?: return
        runCatching {
            val entries = JSONArray(source.readText())
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val video = entry.getString("v")
                found.putIfAbsent(video, Found(songOf(entry.getJSONObject("s"))))
            }
        }.onFailure { android.util.Log.w(TAG, "counterparts unreadable", it) }
    }

    private fun save() {
        val target = file ?: return
        io.launch {
            synchronized(saveLock) {
                runCatching {
                    val entries = JSONArray()
                    found.forEach { (video, entry) ->
                        val song = entry.song ?: return@forEach
                        entries.put(JSONObject().put("v", video).put("s", jsonOf(song)))
                    }
                    val temp = File(target.parentFile, "${target.name}.tmp")
                    temp.writeText(entries.toString())
                    temp.renameTo(target)
                }.onFailure { android.util.Log.w(TAG, "counterparts not saved", it) }
            }
        }
    }

    private fun jsonOf(song: SongItem) = JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artists", JSONArray().apply {
            song.artists.forEach { put(JSONObject().put("n", it.name).put("i", it.id ?: "")) }
        })
        .put("album", song.album?.let { JSONObject().put("n", it.name).put("i", it.id) } ?: JSONObject.NULL)
        .put("duration", song.duration ?: -1)
        .put("thumb", song.thumbnail)
        .put("explicit", song.explicit)

    private fun songOf(json: JSONObject): SongItem {
        val artists = json.getJSONArray("artists")
        val album = json.optJSONObject("album")
        return SongItem(
            id = json.getString("id"),
            title = json.getString("title"),
            artists = (0 until artists.length()).map { i ->
                val artist = artists.getJSONObject(i)
                Artist(artist.getString("n"), artist.optString("i").takeIf { it.isNotEmpty() })
            },
            album = album?.let { Album(it.getString("n"), it.getString("i")) },
            duration = json.optInt("duration", -1).takeIf { it > 0 },
            musicVideoType = MUSIC_VIDEO_TYPE_ATV,
            thumbnail = json.getString("thumb"),
            explicit = json.optBoolean("explicit"),
        )
    }

    private const val TAG = "SquareYouTube"
    private const val FILE_NAME = "yt_counterparts.json"
    private const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
    private const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"
    private const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
    private const val TIMEOUT_MS = 5_000L
    private const val CANDIDATES = 5

    /** An intro or an outro the record does not have, at most. */
    private const val DURATION_SLACK_S = 75
    private val VIDEO_TAG = Regex(
        "[(\\[][^)\\]]*(official|video|lyric|audio|visuali[sz]er|\\bmv\\b|\\bhd\\b|\\b4k\\b|clip)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val CHANNEL_NOISE = Regex("vevo|topic|official|music|tv$")
}
