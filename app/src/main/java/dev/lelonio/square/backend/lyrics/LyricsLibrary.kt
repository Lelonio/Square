package dev.lelonio.square.backend.lyrics

import android.content.Context
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.Lyrics
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a song's words can come from; see [LyricsLibrary]. */
enum class LyricsSource(val label: String) {
    AMLL("AMLL"),
    APPLE_MUSIC("Apple Music"),
    BETTER_LYRICS("Better Lyrics"),
    SPOTIFY("Spotify"),
    LRCLIB("LRCLIB"),
    NETEASE("NetEase"),
    KUGOU("KuGou"),
    LYRICS_OVH("Lyrics.ovh"),
}

/** The song the words are asked for. */
data class LyricsQuery(val uri: String, val title: String, val artist: String, val durationMs: Long)

/**
 * Every source of lyrics, in one place, and what they answered before.
 *
 * Each backend used to write its own chain by hand, and a source added to one
 * was missing from the other. Here is the one list: the order a song is asked
 * in when nobody has chosen, every source at once when the listener wants to
 * see what else there is, and a copy on disk of what was shown.
 *
 * The copy is kept per song with the source it came from. A source the
 * listener picked stays picked; one the order arrived at is asked again after
 * a month, since archives fill up and a line-timed answer may by then have a
 * word-timed one.
 */
object LyricsLibrary {

    private val json = Json { ignoreUnknownKeys = true }
    private var directory: File? = null

    fun attach(context: Context) {
        directory = File(context.applicationContext.cacheDir, "lyrics").apply { mkdirs() }
    }

    /** Asked in this order when nobody has chosen; the first answer wins. */
    fun sourcesFor(uri: String): List<LyricsSource> =
        if (uri.startsWith("spotify:track:")) {
            // AMLL is keyed on the Spotify id, and Spotify's own words exist
            // only for its own tracks.
            listOf(
                LyricsSource.AMLL, LyricsSource.APPLE_MUSIC, LyricsSource.BETTER_LYRICS,
                LyricsSource.SPOTIFY, LyricsSource.LRCLIB, LyricsSource.NETEASE,
                LyricsSource.KUGOU, LyricsSource.LYRICS_OVH,
            )
        } else {
            listOf(
                LyricsSource.APPLE_MUSIC, LyricsSource.BETTER_LYRICS, LyricsSource.LRCLIB,
                LyricsSource.NETEASE, LyricsSource.KUGOU, LyricsSource.LYRICS_OVH,
            )
        }

    /** One source's answer, marked with where it came from. */
    suspend fun fetch(source: LyricsSource, query: LyricsQuery): Lyrics? = runCatching {
        val (_, title, artist, duration) = query
        when (source) {
            LyricsSource.AMLL -> Amll.lyrics(query.uri)
            LyricsSource.APPLE_MUSIC -> Lossless.lyrics(title, artist, duration)
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, duration)
            LyricsSource.SPOTIFY -> Catalog.lyrics(query.uri)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, duration)
            LyricsSource.NETEASE -> NetEase.lyrics(title, artist, duration)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, duration)
            LyricsSource.LYRICS_OVH -> LyricsOvh.lyrics(title, artist, duration)
        }
    }.getOrNull()?.takeIf { it.lines.isNotEmpty() }?.copy(source = source.name)

    /**
     * Words timed to the word, from any source, are better than words timed
     * to the line from the first one: the order says who to ask first, this
     * says what is worth waiting for.
     */
    private fun rank(lyrics: Lyrics): Int = when {
        lyrics.lines.any { it.words.isNotEmpty() } -> 2
        lyrics.synced -> 1
        else -> 0
    }

    private val rechecked = java.util.Collections.synchronizedSet(HashSet<String>())

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val _upgrades = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Lyrics>>(extraBufferCapacity = 4)

    /**
     * Better words found for a song after it was shown some: the uri and the
     * words. The player swaps them in if that song is still the one playing.
     */
    val upgrades: kotlinx.coroutines.flow.SharedFlow<Pair<String, Lyrics>> = _upgrades

    /**
     * The words for a song: what was kept for it, or the best the sources have.
     *
     * Asked in the order, and the first answer timed to the word is the one.
     * An answer timed only to the line is shown at once rather than held back,
     * and the sources after it are asked in the background for one timed to
     * the word; when one has it, it is kept and offered through [upgrades].
     * A song whose word-timed lyrics only one archive held used to be shown
     * line by line from the first source that answered at all.
     */
    suspend fun lyrics(query: LyricsQuery): Lyrics? {
        val sources = sourcesFor(query.uri)
        cached(query.uri)?.let { entry ->
            // Kept from before word timing was looked for everywhere: asked
            // again in the background, once a session, unless it was chosen.
            if (!entry.chosen && rank(entry.lyrics) < 2 && rechecked.add(query.uri)) {
                scope.launch { lookForWordTiming(query, sources.filter { it.name != entry.lyrics.source }) }
            }
            return entry.lyrics
        }
        var best: Lyrics? = null
        for ((index, source) in sources.withIndex()) {
            val found = fetch(source, query) ?: continue
            if (best == null || rank(found) > rank(best)) best = found
            if (rank(found) == 2) break
            // Timed to the line: good enough to show now, and the rest are
            // asked meanwhile.
            if (rank(found) == 1) {
                keep(query.uri, found, chosen = false)
                val rest = sources.drop(index + 1)
                scope.launch { lookForWordTiming(query, rest) }
                return found
            }
        }
        best?.let { keep(query.uri, it, chosen = false) }
        return best
    }

    private suspend fun lookForWordTiming(query: LyricsQuery, sources: List<LyricsSource>) {
        if (sources.isEmpty()) return
        val answers = coroutineScope {
            sources.map { source -> async { withTimeoutOrNull(ASK_TIMEOUT_MS) { fetch(source, query) } } }.awaitAll()
        }
        val better = answers.firstOrNull { it != null && rank(it) == 2 } ?: return
        // Not over a choice the listener made in the meantime.
        if (cached(query.uri)?.chosen == true) return
        keep(query.uri, better, chosen = false)
        android.util.Log.i("LyricsLibrary", "word-timed lyrics for ${query.uri} from ${better.source}")
        _upgrades.tryEmit(query.uri to better)
    }

    /** Only what is kept, asking nobody: for offline, where the network is not there. */
    suspend fun cachedLyrics(uri: String): Lyrics? = cached(uri)?.lyrics

    /** What every source has for the song, asked together, in the usual order. */
    suspend fun everySource(query: LyricsQuery): List<Pair<LyricsSource, Lyrics?>> = coroutineScope {
        sourcesFor(query.uri).map { source ->
            async { source to withTimeoutOrNull(ASK_TIMEOUT_MS) { fetch(source, query) } }
        }.awaitAll()
    }

    /**
     * The listener's choice for this song, kept for good: here, and beside a
     * download, whose own copy is what is read first for a downloaded song.
     */
    suspend fun choose(uri: String, lyrics: Lyrics) {
        keep(uri, lyrics, chosen = true)
        withContext(Dispatchers.IO) {
            runCatching {
                dev.lelonio.square.download.DownloadExtras.rememberLyrics(uri, json.encodeToString(Lyrics.serializer(), lyrics))
            }
        }
    }

    @Serializable
    private data class Entry(val savedAt: Long, val chosen: Boolean, val lyrics: Lyrics)

    private val memory = android.util.LruCache<String, Entry>(64)

    private suspend fun cached(uri: String): Entry? {
        memory.get(uri)?.let { return it }
        val entry = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<Entry>(file(uri)?.readText() ?: return@withContext null) }.getOrNull()
        } ?: return null
        if (!entry.chosen && System.currentTimeMillis() - entry.savedAt !in 0..MAX_AGE_MS) return null
        memory.put(uri, entry)
        return entry
    }

    private suspend fun keep(uri: String, lyrics: Lyrics, chosen: Boolean) {
        val entry = Entry(System.currentTimeMillis(), chosen, lyrics)
        memory.put(uri, entry)
        withContext(Dispatchers.IO) {
            runCatching { file(uri)?.writeText(json.encodeToString(Entry.serializer(), entry)) }
        }
    }

    private fun file(uri: String): File? {
        val dir = directory ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    /** A source that has not answered by then is shown as having nothing. */
    private const val ASK_TIMEOUT_MS = 12_000L

    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
}
