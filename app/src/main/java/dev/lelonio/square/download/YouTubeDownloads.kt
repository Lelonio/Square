package dev.lelonio.square.download

import android.content.Context
import dev.lelonio.square.backend.youtube.YouTubeBackend
import dev.lelonio.square.backend.youtube.YouTubeStreams
import dev.lelonio.square.data.DownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * YouTube Music songs kept on the phone.
 *
 * The other source keeps its songs inside its engine; this one has no engine,
 * so a kept song is a file of its own: the same audio the player would stream,
 * fetched once and named after the video. The store that says who wants which
 * song is the same for both, which is what lets a playlist of either keep its
 * songs, share them with another and let them go.
 */
object YouTubeDownloads {

    /** Takes a kept song off the phone, whichever source it belongs to. */
    fun forget(context: Context, trackUri: String) {
        if (trackUri.startsWith(YouTubeBackend.TRACK_PREFIX)) {
            remove(context, trackUri)
        } else {
            dev.lelonio.square.nativecore.NativeBridge.removeDownload(trackUri)
        }
    }

    /** Stops a song that is still coming, whichever source it belongs to. */
    fun stop(trackUri: String) {
        if (trackUri.startsWith(YouTubeBackend.TRACK_PREFIX)) {
            cancel(trackUri)
        } else {
            dev.lelonio.square.nativecore.NativeBridge.cancelDownload(trackUri)
        }
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Songs a removal asked to stop while they were coming. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /**
     * Inside the store's own folder, so emptying the downloads empties these
     * with the rest; see DownloadStore.clearAll.
     */
    private fun directory(context: Context): File =
        File(File(context.applicationContext.filesDir, DownloadStore.DIR_NAME), DownloadStore.YOUTUBE_DIR)
            .apply { mkdirs() }

    /** The finished file for a song, or null when it is not on the phone. */
    fun fileFor(context: Context, trackUri: String): File? {
        if (!trackUri.startsWith(YouTubeBackend.TRACK_PREFIX)) return null
        val id = YouTubeBackend.videoIdOfUri(trackUri)
        return directory(context).listFiles()?.firstOrNull {
            it.nameWithoutExtension == id && it.extension != PARTIAL
        }
    }

    fun cancel(trackUri: String) {
        cancelled.add(trackUri)
    }

    /** Takes the song off the phone, finished or not. */
    fun remove(context: Context, trackUri: String) {
        if (!trackUri.startsWith(YouTubeBackend.TRACK_PREFIX)) return
        val id = YouTubeBackend.videoIdOfUri(trackUri)
        directory(context).listFiles()
            ?.filter { it.name.startsWith("$id.") }
            ?.forEach { it.delete() }
    }

    /**
     * Fetches a song to the phone.
     *
     * The best audio the video has, as the player picks it. In pieces rather
     * than one request: YouTube slows a single long read of a stream down to
     * the speed it plays at, and asks by range are served at full speed.
     * Written beside its final name and renamed at the end, so a file that is
     * there is a file that is whole.
     *
     * @throws IOException for anything the network did, which is worth trying
     *   again; anything else is about the song.
     */
    suspend fun download(
        context: Context,
        trackUri: String,
        onProgress: (Float) -> Unit,
    ): DownloadStore.FileRecord = withContext(Dispatchers.IO) {
        cancelled.remove(trackUri)
        YouTubeStreams.ensureNewPipe()
        val id = YouTubeBackend.videoIdOfUri(trackUri)
        // Asked without an account first, and as the account where YouTube
        // wants one, as the player does. Without the second, a network
        // YouTube had stopped trusting ("confirm you're not a bot") could not
        // download anything, signed in or not (#81).
        val source = try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeStreams.WATCH_URL + id)
            val stream = info.audioStreams
                .filter { !it.content.isNullOrEmpty() }
                .maxByOrNull { it.averageBitrate }
                ?: error("no audio for $id")
            Source(stream.content, stream.format?.suffix ?: "m4a", stream.averageBitrate.takeIf { it > 0 } ?: 0)
        } catch (error: Exception) {
            if (!dev.lelonio.square.backend.youtube.SignedInStreams.needsAccount(error)) throw error
            val url = dev.lelonio.square.backend.youtube.SignedInStreams.url(
                context, id, dev.lelonio.square.backend.youtube.SignedInStreams.Kind.AUDIO, lowRate = false,
            )
            Source(url, if ("mime=audio%2Fwebm" in url) "webm" else "m4a", 0)
        }
        val extension = source.extension

        val dir = directory(context)
        val part = File(dir, "$id.$extension.$PARTIAL")
        val done = File(dir, "$id.$extension")
        var offset = 0L
        var total = -1L
        try {
            FileOutputStream(part, false).use { out ->
                while (total < 0 || offset < total) {
                    if (trackUri in cancelled) throw kotlinx.coroutines.CancellationException("removed")
                    val request = Request.Builder()
                        .url(source.url)
                        .header("Range", "bytes=$offset-${offset + CHUNK_BYTES - 1}")
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (response.code != 200 && response.code != 206) {
                            throw IOException("HTTP ${response.code}")
                        }
                        if (total < 0) {
                            total = response.header("Content-Range")
                                ?.substringAfterLast('/')
                                ?.toLongOrNull()
                                ?: -1L
                        }
                        val body = response.body ?: throw IOException("empty response")
                        val before = offset
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                offset += read
                            }
                        }
                        // The whole file in one answer, or a piece shorter than
                        // asked for with no total to go by: either way there is
                        // nothing left to ask for.
                        if (response.code == 200 ||
                            (total < 0 && offset - before < CHUNK_BYTES)
                        ) {
                            total = offset
                        }
                    }
                    if (total > 0) onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (!part.renameTo(done)) throw IOException("cannot keep $id")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }

        DownloadStore.FileRecord(
            trackId = id,
            format = extension,
            bytes = done.length(),
            kbps = source.kbps,
            downloadedAt = System.currentTimeMillis(),
        )
    }

    /** Where a song's audio is fetched from, and what it is. */
    private class Source(val url: String, val extension: String, val kbps: Int)

    private const val PARTIAL = "part"
    private const val READ_TIMEOUT_SECONDS = 30L

    /** Under the size YouTube starts slowing a single read down at. */
    private const val CHUNK_BYTES = 9L * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024
}
