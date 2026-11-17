package dev.lelonio.square.playback.soloist

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Soloist itself, from Spotify's CDN.
 *
 * Spotify's binary cannot ship in the APK: it is fetched when Soloist is
 * chosen as the engine, and fetched again when Spotify publishes a new build,
 * which it must, as each build stops working about ninety days after its
 * release. The archive is a plain tar.gz with the binary at its root.
 */
object SoloistDownload {
    private const val TAG = "SoloistDownload"
    const val URL = "https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz"

    /** How often a running Soloist asks whether there is a newer build. */
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    sealed interface Progress {
        data object Idle : Progress
        /** [fraction] is null while the size is not known. */
        data class Running(val fraction: Float?) : Progress
        data class Failed(val message: String) : Progress
        data object Done : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("soloist", Context.MODE_PRIVATE)

    /**
     * Fetches the build when there is none here, or when Spotify has a newer
     * one ([force] asks even when the last check was recent). Returns whether
     * a new binary was put in place.
     */
    suspend fun fetch(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val binary = SoloistRuntime.binary(context)
        val have = binary.exists()
        if (have && !force && System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_EVERY_MS) {
            return@withContext false
        }
        if (_progress.value is Progress.Running) return@withContext false
        _progress.value = Progress.Running(null)
        try {
            val request = Request.Builder().url(URL).apply {
                val since = prefs.getString("last_modified", null)
                if (have && since != null) header("If-Modified-Since", since)
            }.build()
            client.newCall(request).execute().use { response ->
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                if (response.code == 304) {
                    _progress.value = Progress.Done
                    return@withContext false
                }
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty answer")
                val total = body.contentLength().takeIf { it > 0 }
                val dir = SoloistRuntime.home(context).apply { mkdirs() }
                val tmp = File(dir, "soloist.download")
                val counted = Counting(body.byteStream()) { read ->
                    _progress.value = Progress.Running(total?.let { (read.toFloat() / it).coerceIn(0f, 1f) })
                }
                val found = untar(GZIPInputStream(counted), "soloist", tmp)
                if (!found) error("no soloist in the archive")
                tmp.setReadable(true, true)
                tmp.setExecutable(true, true)
                // Renamed over the old one: a Soloist running keeps the file it
                // opened, and the next start takes the new build.
                if (!tmp.renameTo(binary)) error("could not put the binary in place")
                prefs.edit().putString("last_modified", response.header("Last-Modified")).apply()
                Log.i(TAG, "Soloist downloaded (${binary.length()} bytes)")
                SoloistEngine.refresh(context)
            }
            _progress.value = Progress.Done
            true
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: $t")
            _progress.value = Progress.Failed(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    /** Copies the archive's entry named [name] (at its root, any leading ./) to [out]. */
    private fun untar(input: InputStream, name: String, out: File): Boolean {
        val header = ByteArray(512)
        input.use { tar ->
            while (true) {
                if (!readFully(tar, header)) return false
                if (header.all { it == 0.toByte() }) return false
                val entry = String(header, 0, 100).trimEnd('\u0000').removePrefix("./")
                val size = String(header, 124, 12).trim('\u0000', ' ').toLongOrNull(8) ?: 0
                val type = header[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                if (entry == name && (type == '0' || type == '\u0000')) {
                    out.outputStream().use { file ->
                        var left = size
                        val buffer = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (n < 0) return false
                            file.write(buffer, 0, n)
                            left -= n
                        }
                    }
                    return true
                }
                var skip = padded
                while (skip > 0) {
                    val n = tar.skip(skip)
                    if (n <= 0) { if (tar.read() < 0) return false else skip-- } else skip -= n
                }
                if (entry == name) return false
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var at = 0
        while (at < buffer.size) {
            val n = input.read(buffer, at, buffer.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    private class Counting(private val inner: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var read = 0L
        private var told = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }
        override fun close() = inner.close()
        private fun count(n: Int) {
            read += n
            if (read - told > 256 * 1024) {
                told = read
                onRead(read)
            }
        }
    }
}
