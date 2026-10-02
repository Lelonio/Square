package dev.lelonio.square.backend.youtube

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * The official music video of a song, for the video button.
 *
 * A song on YouTube Music is its own upload, and its picture is the cover held
 * still for three minutes. The video people mean is a separate upload, found
 * here the way a listener would find it: the same title by the same artist,
 * among the videos, and about as long. Played whole, sound included, because
 * a video's intro and ending are not the record's.
 */
object OfficialVideos {

    private class Found(val videoId: String?)

    private val found = java.util.concurrent.ConcurrentHashMap<String, Found>()

    /** Blocking: called from the stream resolver, on a loading thread. */
    fun forSong(songId: String, song: StreamInfo): String? {
        found[songId]?.let { return it.videoId }
        val title = song.name.orEmpty()
        val artist = song.uploaderName.orEmpty().removeSuffix(" - Topic").trim()
        val seconds = song.duration.toInt()
        var answered = false
        val match = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                val results = YouTube.search("$title $artist".trim(), YouTube.SearchFilter.FILTER_VIDEO)
                    .getOrNull()?.items
                answered = results != null
                results.orEmpty()
                    .filterIsInstance<SongItem>()
                    .take(CANDIDATES)
                    .firstOrNull { isVideoOf(it, title, artist, seconds) }
            }
        }
        if (match != null || answered) found[songId] = Found(match?.id)
        if (match == null && answered) android.util.Log.i(TAG, "no official video for $songId \"$title\"")
        return match?.id
    }

    private fun isVideoOf(video: SongItem, title: String, artist: String, seconds: Int): Boolean {
        if (video.musicVideoType != "MUSIC_VIDEO_TYPE_OMV") return false
        val wanted = squash(title)
        if (wanted.isEmpty() || !squash(video.title).contains(wanted)) return false
        val name = squash(artist)
        val sameArtist = name.isEmpty() ||
            video.artists.any { squash(it.name).let { credited -> credited == name || credited.contains(name) } } ||
            squash(video.title).contains(name)
        if (!sameArtist) return false
        val length = video.duration ?: return true
        // A video runs longer than its record, by an intro or a scene, not shorter.
        return seconds <= 0 || length in (seconds - 15)..(seconds + 180)
    }

    private fun squash(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private const val TIMEOUT_MS = 6_000L
    private const val CANDIDATES = 6
    private const val TAG = "SquareYTVideo"
}
