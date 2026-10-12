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
    SPICY_LYRICS("Spicy Lyrics"),
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

    fun attach(context: Context, spicyKey: () -> String = { "" }, preferred: () -> String = { "" }) {
        directory = File(context.applicationContext.cacheDir, "lyrics").apply { mkdirs() }
        SpicyLyrics.key = spicyKey
        this.preferred = preferred
    }

    /** The source picked in the settings by name, or blank for the best one; see [preferredFor]. */
    private var preferred: () -> String = { "" }

    /**
     * The one source the listener wants words from, where it can be asked
     * about this song. Only its words are shown: a song it has nothing for
     * shows none, however much the others have, unless the listener picks
     * another source for that song themselves.
     */
    fun preferredFor(uri: String): LyricsSource? =
        LyricsSource.entries.firstOrNull { it.name == preferred() }?.takeIf { it in sourcesFor(uri) }

    /** Whether these words may be shown for the song, by the source the listener prefers. */
    fun shows(uri: String, lyrics: Lyrics): Boolean =
        preferredFor(uri)?.let { it.name == lyrics.source } ?: true

    /**
     * Whether words already in hand are worth asking the sources about again:
     * not timed to the word, not saying where they came from, or not from the
     * source the listener prefers.
     */
    fun worthAskingAgain(uri: String, lyrics: Lyrics): Boolean =
        !isWordTimed(lyrics) || lyrics.source == null ||
            preferredFor(uri)?.let { it.name != lyrics.source } == true

    /** Asked in this order when nobody has chosen; the first answer wins. */
    fun sourcesFor(uri: String): List<LyricsSource> =
        if (uri.startsWith("spotify:track:")) {
            // AMLL is keyed on the Spotify id, and Spotify's own words exist
            // only for its own tracks.
            // Spicy Lyrics only for a listener who has entered a key for it.
            listOfNotNull(
                LyricsSource.AMLL, LyricsSource.SPICY_LYRICS.takeIf { SpicyLyrics.enabled },
                LyricsSource.APPLE_MUSIC, LyricsSource.BETTER_LYRICS,
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
            LyricsSource.SPICY_LYRICS -> SpicyLyrics.lyrics(query.uri)
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
     * Every source asked about a song, once, together: shared by the choice
     * below and the panel listing the sources, so opening the panel shows what
     * is already known instead of asking all over again. Kept for the last
     * few songs of the session.
     */
    private val surveys = android.util.LruCache<String, Map<LyricsSource, kotlinx.coroutines.Deferred<Lyrics?>>>(16)

    fun survey(query: LyricsQuery): Map<LyricsSource, kotlinx.coroutines.Deferred<Lyrics?>> = synchronized(surveys) {
        // By the song and the names it was asked by: most sources search by
        // title, and a song asked about under the one before it's title (the
        // two do not always change in the same instant) has a survey of its
        // own rather than the answers for the other song.
        val key = "${query.uri}\n${same(query.title)}\n${same(query.artist)}"
        surveys.get(key) ?: sourcesFor(query.uri).associateWith { source ->
            scope.async { withTimeoutOrNull(ASK_TIMEOUT_MS) { fetch(source, query) } }
        }.also { surveys.put(key, it) }
    }

    /**
     * The words to show, from what the sources have answered so far.
     *
     * The first source in the order with words timed to the word wins, as
     * soon as every source before it has answered without any. With none
     * timed to the word, the first timed to the line, then the first at all.
     * Null while a source further up could still change the answer, unless
     * [final], when the best of what is in is taken.
     */
    internal fun winner(
        order: List<LyricsSource>,
        answered: Map<LyricsSource, Lyrics?>,
        final: Boolean,
        preferred: LyricsSource? = null,
    ): Lyrics? {
        // The listener's own pick, and nothing else: what it has, or nothing.
        if (preferred != null) return answered[preferred]
        for (source in order) {
            if (source !in answered) {
                if (final) continue else return null
            }
            val lyrics = answered[source] ?: continue
            if (rank(lyrics) == 2) return lyrics
        }
        val present = order.mapNotNull { answered[it] }
        val best = present.maxOfOrNull(::rank) ?: return null
        return present.first { rank(it) == best }
    }

    private fun answeredSoFar(survey: Map<LyricsSource, kotlinx.coroutines.Deferred<Lyrics?>>): Map<LyricsSource, Lyrics?> =
        survey.filterValues { it.isCompleted }.mapValues { (_, answer) -> runCatching { answer.getCompleted() }.getOrNull() }

    /**
     * The words for a song: what was kept for it, or the best the sources have.
     *
     * Every source is asked at once, and the order decides between them: the
     * first with words timed to the word wins. Waited for up to a few seconds;
     * past that, the best answer in by then is shown, and anything better that
     * arrives later replaces it through [upgrades]. Asking one source at a time
     * and stopping at the first line-timed answer showed a song line by line
     * when a source further down had it word by word.
     */
    suspend fun lyrics(query: LyricsQuery): Lyrics? {
        cached(query.uri, query.title)?.takeIf { it.chosen || shows(query.uri, it.lyrics) }?.let { entry ->
            // Picked by an older rule, or before a source existed: looked at
            // again once a session, unless the listener chose it.
            // Kept with no source named, from before sources were: the same
            // look tells which one it was.
            val unnamed = entry.lyrics.source == null
            val preferred = preferredFor(query.uri)
            val stale = unnamed || (preferred == null && !entry.chosen && rank(entry.lyrics) < 2)
            if (stale && rechecked.add("${query.uri}|${preferred?.name}")) {
                improveLater(query, entry.lyrics)
            }
            return entry.lyrics
        }
        val order = sourcesFor(query.uri)
        val preferred = preferredFor(query.uri)
        val survey = survey(query)
        // One source to wait for, and its answer is the answer.
        if (preferred != null) {
            val found = survey[preferred]?.let { runCatching { it.await() }.getOrNull() }
            found?.let { keep(query.uri, it, chosen = false, title = query.title) }
            return found
        }
        val decided = withTimeoutOrNull(DECIDE_MS) {
            var found: Lyrics? = null
            var settled = false
            while (!settled) {
                val answered = answeredSoFar(survey)
                val all = answered.size == survey.size
                found = winner(order, answered, final = all, preferred = preferred)
                settled = found != null || all
                if (!settled) kotlinx.coroutines.delay(POLL_MS)
            }
            found
        }
        // Past the wait, the best of what is in; and with nothing in yet, the
        // rest is waited for rather than answering "no lyrics" for a song
        // whose words are still on their way.
        var shown = decided ?: winner(order, answeredSoFar(survey), final = true, preferred = preferred)
        if (shown == null) {
            survey.values.forEach { runCatching { it.await() } }
            shown = winner(order, answeredSoFar(survey), final = true, preferred = preferred)
        }
        val all = answeredSoFar(survey).size == survey.size
        shown?.let { keep(query.uri, it, chosen = false, title = query.title) }
        if (!all) improveLater(query, shown)
        return shown
    }

    /**
     * Waits for every source and offers something better than [current], if
     * there is one: words timed more finely, or any words where there were
     * none. Never over a choice the listener has made.
     */
    fun improveLater(query: LyricsQuery, current: Lyrics?) {
        scope.launch {
            val survey = survey(query)
            val answered = survey.mapValues { (_, answer) -> runCatching { answer.await() }.getOrNull() }
            val preferred = preferredFor(query.uri)
            val best = winner(sourcesFor(query.uri), answered, final = true, preferred = preferred)
            val free = cached(query.uri, query.title)?.chosen != true
            // The preferred source's words are not replaced by finer ones,
            // and replace anything that is not theirs.
            val holdsPreferred = preferred != null && current?.source == preferred.name
            val bringsPreferred = preferred != null && best?.source == preferred.name && !holdsPreferred
            val better = best != null && free && !holdsPreferred &&
                (current == null || bringsPreferred || rank(best) > rank(current))
            // Words that say nothing of their source, and that no source
            // answers word for word any more: the order picks again, as it
            // would for a song never shown, rather than leaving the source
            // button with nothing to name.
            val named = current?.source != null || current == null || name(query, current, answered)
            if (better || (!named && best != null && free)) {
                keep(query.uri, best!!, chosen = false, title = query.title)
                // A download's copy too, which is read before anything else:
                // left as it was, it would be the one shown on the next play.
                if (keptForGood(best)) withContext(Dispatchers.IO) {
                    runCatching {
                        val download = dev.lelonio.square.download.DownloadExtras
                        if (download.lyrics(query.uri) != null) {
                            download.rememberLyrics(query.uri, json.encodeToString(Lyrics.serializer(), best))
                        }
                    }
                }
                android.util.Log.i("LyricsLibrary", "better lyrics for ${query.uri} from ${best.source}")
                _upgrades.tryEmit(query.uri to best)
            }
        }
    }

    /**
     * Words kept before their source was written down, given its name.
     *
     * A download's copy and the words this phone kept from before sources
     * were recorded say nothing of where they came from, and the player's
     * source button could only read "Sources" for them while it named every
     * other song's. The source is whichever one answers the same lines now;
     * the copies are written again with its name, so this is asked once.
     */
    private suspend fun name(query: LyricsQuery, current: Lyrics, answered: Map<LyricsSource, Lyrics?>): Boolean {
        val uri = query.uri
        val text = lines(current)
        val source = sourcesFor(uri).firstOrNull { answered[it]?.let(::lines) == text } ?: return false
        android.util.Log.i("LyricsLibrary", "kept lyrics for $uri named ${source.name}")
        val named = current.copy(source = source.name)
        cached(uri)?.takeIf { lines(it.lyrics) == text }?.let { keep(uri, named, it.chosen, query.title) }
        withContext(Dispatchers.IO) {
            runCatching {
                val download = dev.lelonio.square.download.DownloadExtras
                if (download.lyrics(uri) != null && keptForGood(named)) {
                    download.rememberLyrics(uri, json.encodeToString(Lyrics.serializer(), named))
                }
            }
        }
        _upgrades.tryEmit(uri to named)
        return true
    }

    private fun same(name: String): String = name.trim().lowercase()

    private fun lines(lyrics: Lyrics): List<String> =
        lyrics.lines.map { it.text.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Whether the words are timed to the word, which nothing else here beats. */
    fun isWordTimed(lyrics: Lyrics): Boolean = rank(lyrics) == 2

    /** Whether these words may be kept beside a download, with no end date. */
    fun keptForGood(lyrics: Lyrics): Boolean = lyrics.source != LyricsSource.SPICY_LYRICS.name

    /** Only what is kept, asking nobody: for offline, where the network is not there. */
    suspend fun cachedLyrics(uri: String): Lyrics? =
        cached(uri)?.takeIf { it.chosen || shows(uri, it.lyrics) }?.lyrics

    /** What every source has for the song, from the song's one survey, in the usual order. */
    suspend fun everySource(query: LyricsQuery): List<Pair<LyricsSource, Lyrics?>> =
        survey(query).map { (source, answer) -> source to runCatching { answer.await() }.getOrNull() }

    /**
     * The listener's choice for this song, kept for good: here, and beside a
     * download, whose own copy is what is read first for a downloaded song.
     */
    suspend fun choose(uri: String, lyrics: Lyrics) {
        keep(uri, lyrics, chosen = true)
        // Not beside a download, which keeps its words for good; see cached.
        if (!keptForGood(lyrics)) return
        withContext(Dispatchers.IO) {
            runCatching {
                dev.lelonio.square.download.DownloadExtras.rememberLyrics(uri, json.encodeToString(Lyrics.serializer(), lyrics))
            }
        }
    }

    @Serializable
    private data class Entry(
        val savedAt: Long,
        val chosen: Boolean,
        val lyrics: Lyrics,
        /** The title the song was asked by; null for entries kept before this was. */
        val title: String? = null,
    )

    private val memory = android.util.LruCache<String, Entry>(64)

    /**
     * What is kept for the song, if it is still to be believed.
     *
     * With [title], words the order picked are only taken when they were
     * found under that title. A song once asked about under the title of the
     * one before it kept that song's words as its own, for a month, and no
     * later answer could replace them; such an entry, and one from before the
     * title was written down, is asked again instead. A listener's choice is
     * theirs, and kept whatever it was asked by.
     */
    private suspend fun cached(uri: String, title: String? = null): Entry? {
        val entry = memory.get(uri) ?: withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<Entry>(file(uri)?.readText() ?: return@withContext null) }.getOrNull()
        } ?: return null
        if (title != null && !entry.chosen && (entry.title == null || same(entry.title) != same(title))) return null
        // A month at most for anything picked by the order, and for Spicy
        // Lyrics' answers even when chosen: its terms let a copy be kept for
        // thirty days and no longer.
        val expires = !entry.chosen || entry.lyrics.source == LyricsSource.SPICY_LYRICS.name
        if (expires && System.currentTimeMillis() - entry.savedAt !in 0..MAX_AGE_MS) return null
        memory.put(uri, entry)
        return entry
    }

    private suspend fun keep(uri: String, lyrics: Lyrics, chosen: Boolean, title: String? = null) {
        val entry = Entry(System.currentTimeMillis(), chosen, lyrics, title)
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

    /** How long the choice waits for the sources further up the order. */
    private const val DECIDE_MS = 4_000L

    /** How often it looks at what has come in meanwhile. */
    private const val POLL_MS = 100L

    /** A source that has not answered by then is shown as having nothing. */
    private const val ASK_TIMEOUT_MS = 12_000L

    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
}
