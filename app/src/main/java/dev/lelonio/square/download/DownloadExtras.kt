package dev.lelonio.square.download

import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Everything about a downloaded song that is not the song.
 *
 * The words, the Canvas, the cover and what the artist is. Offline these are
 * the difference between a player that works and a player that looks broken:
 * the audio plays either way, but a blank cover over a track with no lyrics and
 * an empty artist page reads as an app that has lost its connection to itself.
 *
 * ### Where it plugs in
 *
 * Inside the one funnel each of these already goes through — the lyrics chain
 * in [dev.lelonio.square.backend.lyrics.SpotifyLyrics], the download queue for
 * the pictures and the Canvas. A successful answer is written down on the way
 * past; offline the same call reads it back, so no screen grew an offline
 * branch of its own.
 *
 * ### What is kept, and what is not
 *
 * Only for tracks that are actually downloaded — see [isKept], which the
 * container wires to the download index. Caching the lyrics of everything ever
 * opened would be a second, unbounded store with no owner and nothing to sweep
 * it, which is exactly the shape of thing that quietly fills a phone.
 *
 * A stored answer is a fallback and never a preference. Online the request is
 * still made and its answer still wins: lyrics get corrected, Canvases get
 * replaced, and a cache that shadowed them would keep showing yesterday's.
 * The one exception is the cover, where the bytes cannot differ — the URL names
 * the image — so a local copy is used straight away and saves the round trip.
 */
object DownloadExtras {

    /**
     * Where the other catalogue's answers are filed, and how they are retired.
     *
     * The name carries a number because the answers are only as good as the
     * matching that produced them: a song matched against the wrong release
     * keeps wearing that release's sleeve for as long as the file is here, and
     * there is nothing in the file itself to say so. Bumping this asks every
     * downloaded song again with the matching as it stands now, and the old
     * directory is swept below. Bumped again when the pictures changed format:
     * the answers name jpg files that are still on the phone, so nothing would
     * have gone looking for the smaller ones.
     */
    private const val ART = "apple4"

    private var root: File? = null

    /** Whether a track is downloaded, and therefore worth keeping extras for. */
    private var kept: (String) -> Boolean = { false }

    fun attach(
        downloadsRoot: File,
        baseClient: okhttp3.OkHttpClient? = null,
        isKept: (String) -> Boolean,
    ) {
        root = File(downloadsRoot, "extras")
        kept = isKept
        customHttpClient = baseClient?.newBuilder()
            ?.connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            ?.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            ?.followRedirects(true)
            ?.build()
        forgetArtAnswers()
        // Answers filed by a matching this build no longer trusts; see ART.
        root?.let { here ->
            here.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("apple") && it.name != ART }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    fun attach(downloadsRoot: File, isKept: (String) -> Boolean) =
        attach(downloadsRoot, null, isKept)

    // ------------------------------------------------------------ the answers

    fun rememberLyrics(trackUri: String, raw: String) = remember("lyrics", trackUri, raw)

    fun lyrics(trackUri: String): String? = recall("lyrics", trackUri)

    fun rememberCanvas(trackUri: String, raw: String) = remember("canvas", trackUri, raw)

    fun canvas(trackUri: String): String? = recall("canvas", trackUri)

    /**
     * The Canvas as it can actually be played offline: the stored answer with
     * its url swapped for the copy on this phone.
     *
     * The answer names a CDN url, and offline that url is nothing. The video
     * beside it is the same clip, so this is the answer the player wanted.
     * Null when either half is missing — a Canvas that was noted but never
     * fetched is not one that can be shown.
     */
    fun localCanvas(trackUri: String): dev.lelonio.square.data.CanvasClip? {
        val raw = canvas(trackUri) ?: return null
        val answer = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val url = answer.optString("url").takeIf { it.isNotBlank() } ?: return null
        val video = answer.optBoolean("isVideo", true)
        val file = fileOf(url, if (video) "video" else "art") ?: return null
        return dev.lelonio.square.data.CanvasClip(
            url = android.net.Uri.fromFile(file).toString(),
            isVideo = video,
        )
    }

    /**
     * The other catalogue's pictures of the record a downloaded song is on.
     *
     * Kept per track rather than per record because a track is what the index
     * knows: the download store holds songs, and the record they belong to is a
     * name on each of them. Both urls may be absent — the answer is filed all
     * the same, and its presence is what says this song has been asked about.
     */
    fun rememberArt(trackUri: String, heroUrl: String?, coverUrl: String?) {
        val answer = JSONObject()
            .put("hero", heroUrl.orEmpty())
            .put("cover", coverUrl.orEmpty())
        remember(ART, trackUri, answer.toString())
    }

    /** The tall picture and the sleeve kept for a track, either of them null. */
    fun art(trackUri: String): Pair<String?, String?>? {
        val raw = recall(ART, trackUri) ?: return null
        val answer = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return answer.optString("hero").takeIf { it.isNotBlank() } to
            answer.optString("cover").takeIf { it.isNotBlank() }
    }

    /**
     * Files that this song has been asked about, and had nothing.
     *
     * Most of the catalogue has no lyrics and no Canvas, and without a note of
     * having asked, every one of those songs would be asked about again on
     * every run of the queue — a library's worth of requests, for ever, to
     * learn the same nothing. The note is the empty file itself: [recall] reads
     * a blank one as no answer, so nothing else has to know it is there.
     */
    fun note(kind: String, uri: String) = remember(kind, uri, "")

    /** Whether this song has been asked about at all, answer or not. */
    fun asked(kind: String, uri: String): Boolean = look(kind, uri)?.exists() == true

    /**
     * Artist descriptions are kept for anyone with a downloaded track, so the
     * page behind a song is not empty offline. Not gated on [isKept]: the URI
     * here is the artist's, and the track it was reached from is long out of
     * scope by the time this is called.
     */
    fun rememberArtist(artistUri: String, raw: String) {
        val file = fileFor("artist", artistUri) ?: return
        runCatching { file.parentFile?.mkdirs(); file.writeText(raw) }
    }

    fun artist(artistUri: String): String? = recall("artist", artistUri)

    private fun remember(kind: String, uri: String, raw: String) {
        if (!kept(uri)) return
        val file = fileFor(kind, uri) ?: return
        runCatching { file.parentFile?.mkdirs(); file.writeText(raw) }
    }

    private fun recall(kind: String, uri: String): String? {
        val file = look(kind, uri) ?: return null
        return runCatching { file.takeIf(File::exists)?.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------- the files

    /**
     * The local copy of an image or a Canvas, if there is one.
     *
     * Named after the URL rather than the track, because both are addressed
     * that way and the same cover is shared by every song on a record: keying
     * on the track would store one album's art a dozen times.
     */
    fun fileOf(url: String, kind: String): File? =
        if (kind == "art") artCache.computeIfAbsent(url) { Kept(look(kind, it)) }.value
        else look(kind, url)

    private fun look(kind: String, key: String): File? {
        val file = fileFor(kind, key) ?: return null
        if (file.exists()) return file
        val old = oldFileFor(kind, key)
        if (old != null && old.exists()) {
            if (old.renameTo(file)) {
                return file
            }
            return old
        }
        return null
    }

    /**
     * Answers about covers, remembered.
     *
     * This one is asked from inside composition — every artwork in every row
     * checks whether it has a local copy before deciding what to load — and the
     * answer costs a stat on the filesystem. On a list being flung that is disk
     * I/O on the frame's own thread, dozens of times a second, for an answer
     * that changes when a download finishes and at no other moment.
     *
     * A wrapper rather than a nullable value, because a map cannot remember
     * that the answer was "there is none".
     */
    private class Kept(val value: File?)

    private val artCache = java.util.concurrent.ConcurrentHashMap<String, Kept>()

    /** Called whenever a cover appears or goes, so the answers stay true. */
    private fun forgetArtAnswers() = artCache.clear()

    private var customHttpClient: okhttp3.OkHttpClient? = null

    private val httpClient: okhttp3.OkHttpClient
        get() = customHttpClient ?: defaultHttpClient

    private val defaultHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Fetches and keeps one file. Answers with what is already there.
     *
     * Uses OkHttp for HTTP/2 connection reuse and pooled connections.
     *
     * For artwork ([kind] == "art") the image is decoded, scaled down to at most
     * [MAX_ART_PX] on each side, and re-encoded as WEBP_LOSSY quality
     * [ART_WEBP_QUALITY] before being written. This typically reduces each cover
     * from ~150 KB (640 px JPEG from Spotify/Apple CDN) to ~30–40 KB with no
     * visible difference at any size the app uses, saving bandwidth every time a
     * cover is downloaded and disk space for the lifetime of the library.
     *
     * The file is still named .jpg: Android BitmapFactory and Coil both detect
     * the format from the file's magic bytes, not from the extension, so the
     * rename is not needed and avoiding it means existing cached files remain
     * valid without a version bump.
     */
    suspend fun keep(url: String, kind: String): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val file = fileFor(kind, url) ?: return@withContext null
        if (file.exists() && file.length() > 0) return@withContext file

        runCatching {
            file.parentFile?.mkdirs()
            // Through a part file, so an interrupted fetch never leaves
            // something half-written where a reader would take it for whole.
            val part = File(file.parentFile, "${file.name}.part")
            val request = okhttp3.Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body ?: return@runCatching null

                if (kind == "art") {
                    // Decode → scale → WebP-compress in memory, then write once.
                    //
                    // Reading the full body into a ByteArray costs one allocation
                    // of ~150 KB; the alternative (streaming into BitmapFactory)
                    // requires two passes over the stream, which OkHttp does not
                    // support without buffering it anyway.
                    val bytes = body.bytes()
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = false }
                    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: return@runCatching null  // unreadable image

                    val scaled = scaleBitmapDown(raw, MAX_ART_PX)
                    // raw and scaled may be the same object when no scaling was needed.
                    if (scaled !== raw) raw.recycle()

                    part.outputStream().use { out ->
                        @Suppress("DEPRECATION") // WEBP is fine on API 26+; WEBP_LOSSY needs API 30
                        val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            Bitmap.CompressFormat.WEBP
                        }
                        scaled.compress(format, ART_WEBP_QUALITY, out)
                    }
                    scaled.recycle()
                } else {
                    // Videos and other binary files: stream directly with a
                    // 32 KB buffer so large Canvas clips don't sit in memory.
                    part.outputStream().buffered(32 * 1024).use { output ->
                        body.byteStream().buffered(32 * 1024).use { input ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            if (!part.renameTo(file)) {
                part.delete()
                return@runCatching null
            }
            if (kind == "art") forgetArtAnswers()
            file
        }.getOrNull()
    }

    /**
     * Scales a bitmap down so neither dimension exceeds [maxPx].
     *
     * Returns the original bitmap unchanged when it is already within the limit,
     * so the caller can tell whether a recycle is needed.
     */
    private fun scaleBitmapDown(src: Bitmap, maxPx: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxPx && h <= maxPx) return src
        val scale = maxPx.toFloat() / maxOf(w, h)
        val dstW = (w * scale).toInt().coerceAtLeast(1)
        val dstH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dstW, dstH, /* filter= */ true)
    }

    /**
     * Maximum side length for a stored cover, in pixels.
     *
     * High enough never to touch the prints this app asks for, and here only to
     * bound what a pathological image could take.
     *
     * It arrived as 512, on the reasoning that a cover is never drawn wider
     * than the screen. Two of the pictures kept here are not covers in that
     * sense: the catalogue's tall artwork is fetched at 1600x2134 and the
     * player draws it full-bleed, so on a 1080-wide phone 512 is a picture
     * stretched to twice its size. The point of keeping it at all is that a
     * downloaded song looks the same offline as it does online.
     *
     * The re-encode below is the part worth having: WebP at 80 saves most of
     * what the scaling saved and loses nothing anybody can see.
     */
    private const val MAX_ART_PX = 2400

    /**
     * WEBP quality for stored covers.
     *
     * 80 is indistinguishable from lossless at any size the app renders
     * covers at, and reduces file size by roughly 60–70 % compared to the
     * JPEG that arrives from Spotify's CDN.
     */
    private const val ART_WEBP_QUALITY = 80

    /**
     * What a cover URL is really about, so every size of it lands on one file.
     *
     * A Spotify image id is forty hex characters: sixteen that say what size it
     * is, then twenty-four that say which picture. `ab67616d00001e02…` is the
     * 300px print and `ab67616d0000b273…` the 640px one, and everything after
     * those first sixteen is identical.
     *
     * Keyed on the whole URL, the row thumbnail and the player cover would be
     * two files for one picture, and neither would be found by code holding the
     * other spelling — which is exactly what happened: the covers were saved
     * under the large URL and looked up under the small one, so offline the
     * notification had no picture at all. Keyed on the tail, one download
     * answers for every size of it.
     */
    private fun coverKey(url: String): String {
        val id = url.substringAfterLast('/')
        return if (id.length == ID_LENGTH && id.all { it.isDigit() || it in 'a'..'f' }) {
            id.takeLast(ID_LENGTH - SIZE_PREFIX)
        } else {
            url
        }
    }

    /** A Spotify image id, and how much of the front of it is the size. */
    private const val ID_LENGTH = 40
    private const val SIZE_PREFIX = 16

    /**
     * The cover as a `file://` for anything that wants a URI rather than a
     * file — the media session's metadata, above all, which is what draws the
     * picture in the notification and the quick settings panel.
     */
    fun artworkUri(url: String?): android.net.Uri? {
        val kept = url?.let { fileOf(it, "art") } ?: return null
        return android.net.Uri.fromFile(kept)
    }

    /** Drops everything kept for a track that is no longer downloaded. */
    fun forget(trackUri: String) {
        listOf("lyrics", "canvas", ART).forEach { kind ->
            fileFor(kind, trackUri)?.let { runCatching { it.delete() } }
            oldFileFor(kind, trackUri)?.let { runCatching { it.delete() } }
        }
    }

    /**
     * What the extras take up, in bytes.
     *
     * Worth counting separately from the audio: the Canvases are video, and a
     * few hundred songs' worth of them is not a rounding error next to the
     * music. Walks the directory, so it belongs off the main thread.
     */
    fun bytes(): Long =
        root?.walkTopDown()?.filter(File::isFile)?.sumOf(File::length) ?: 0L

    /**
     * Deletes what belongs to songs that are no longer downloaded.
     *
     * The audio has an index to be reconciled against; the extras have only
     * their file names, which are hashes and cannot be read backwards. So the
     * set of names that *should* exist is rebuilt from the tracks that survive,
     * and anything else in the directory goes.
     *
     * It matters most for the Canvases. A cover is tens of kilobytes and a
     * Canvas is a few megabytes of video, so a library that has been added to
     * and removed from a few times leaves far more behind here than the index
     * ever accounted for — measured on the test phone at a hundred and thirty
     * megabytes for songs that were long gone.
     *
     * Artist descriptions are left alone: they are keyed by artist rather than
     * by track, they are a few kilobytes each, and an artist with nothing
     * downloaded today may well have something tomorrow.
     */
    fun sweep(trackUris: Collection<String>, coverUrls: Collection<String>) {
        if (root == null) return

        val keepLyrics = trackUris.flatMapTo(mutableSetOf()) { uri ->
            look("lyrics", uri)
            listOfNotNull(fileFor("lyrics", uri)?.name, oldFileFor("lyrics", uri)?.name)
        }
        val keepCanvas = trackUris.flatMapTo(mutableSetOf()) { uri ->
            look("canvas", uri)
            listOfNotNull(fileFor("canvas", uri)?.name, oldFileFor("canvas", uri)?.name)
        }
        val keepApple = trackUris.flatMapTo(mutableSetOf()) { uri ->
            look(ART, uri)
            listOfNotNull(fileFor(ART, uri)?.name, oldFileFor(ART, uri)?.name)
        }
        val keepArt = coverUrls.flatMapTo(mutableSetOf()) { url ->
            look("art", url)
            listOfNotNull(fileFor("art", url)?.name, oldFileFor("art", url)?.name)
        }

        // The tall pictures are named by the answers that point at them, the
        // same way the Canvas videos are: read while those answers are still
        // here to be read.
        trackUris.forEach { uri ->
            art(uri)?.toList()?.filterNotNull()?.forEach { url ->
                look("art", url)
                fileFor("art", url)?.let { keepArt += it.name }
                oldFileFor("art", url)?.let { keepArt += it.name }
            }
        }

        // Read before the Canvas answers are pruned: a video is named after the
        // URL inside the answer that points at it, so the answers are the only
        // way to know which videos are still wanted.
        val keepVideo = trackUris.flatMapTo(mutableSetOf()) { uri ->
            val url = recall("canvas", uri)
                ?.let { raw -> runCatching { JSONObject(raw).optString("url") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (url != null) {
                look("video", url)
                listOfNotNull(fileFor("video", url)?.name, oldFileFor("video", url)?.name)
            } else {
                emptyList()
            }
        }

        forgetArtAnswers()
        prune("lyrics", keepLyrics)
        prune("canvas", keepCanvas)
        prune(ART, keepApple)
        prune("art", keepArt)
        prune("video", keepVideo)
    }

    private fun prune(kind: String, keep: Set<String>) {
        val dir = root?.let { File(it, kind) } ?: return
        dir.listFiles()?.forEach { file ->
            if (file.name !in keep) runCatching { file.delete() }
        }
    }

    /** Everything goes, when the downloads do. */
    fun clear() {
        forgetArtAnswers()
        root?.let { runCatching { it.deleteRecursively() } }
    }

    private fun fileFor(kind: String, key: String): File? {
        val root = root ?: return null
        // Hashed rather than sanitised: a URI is not a legal file name, and any
        // escaping scheme would have to survive the characters it escapes.
        //
        // A 64-bit FNV-1a hash rather than String.hashCode() (32-bit): the 32-bit
        // space has ~4 billion values, and the birthday-paradox collision probability
        // for a 10,000-item library is already ~1 %. At 64 bits the probability is
        // negligible across any library size the app will encounter in practice.
        val name = fnv1a64((if (kind == "art") coverKey(key) else key)).toString(16)
        val extension = when (kind) {
            "art"   -> "jpg"
            "video" -> "mp4"
            else    -> "json"
        }
        return File(File(root, kind), "$name.$extension")
    }

    private fun oldFileFor(kind: String, key: String): File? {
        val root = root ?: return null
        val name = (if (kind == "art") coverKey(key) else key).hashCode().toUInt().toString(16)
        val extension = when (kind) {
            "art"   -> "jpg"
            "video" -> "mp4"
            else    -> "json"
        }
        return File(File(root, kind), "$name.$extension")
    }

    /**
     * 64-bit FNV-1a hash of a string, encoded as its UTF-8 bytes.
     *
     * Non-cryptographic, fast, and well-distributed for short keys like URIs and
     * image URLs. Returns an unsigned Long so the hex representation is always
     * positive and always 16 characters wide.
     */
    private fun fnv1a64(input: String): ULong {
        var hash = FNV_OFFSET_BASIS
        for (byte in input.encodeToByteArray()) {
            hash = hash xor byte.toULong()
            hash *= FNV_PRIME
        }
        return hash
    }

}

/** FNV-1a 64-bit offset basis. */
private val FNV_OFFSET_BASIS = 14695981039346656037UL

/** FNV-1a 64-bit prime. */
private const val FNV_PRIME = 1099511628211UL
