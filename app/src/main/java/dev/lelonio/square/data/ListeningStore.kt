package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What was listened to, kept apart per source.
 *
 * One history for the signed-in Spotify account and one for YouTube Music.
 * There used to be a single history, for Spotify alone: YouTube Music was not
 * recorded at all, and a history that belonged to one account at a time could
 * only have been shared by throwing the other's away at every switch.
 * [events] is the history of the source on screen; see [show].
 */
class ListeningStore(context: Context) {
    enum class Source { SPOTIFY, YOUTUBE_MUSIC }

    private val prefs = context.getSharedPreferences("square_listening", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ListeningEvent.serializer())
    private val lock = Mutex()
    private val cache = HashMap<String, List<ListeningEvent>>()
    private var source = Source.SPOTIFY

    init {
        // The single history from before, which was the Spotify account's.
        val legacy = prefs.getString("events", null)
        val owner = account
        if (legacy != null) {
            prefs.edit().apply { if (owner != null) putString(key(owner), legacy) }.remove("events").apply()
        }
    }

    /** The Spotify account whose history is kept. */
    val account get() = prefs.getString("account", null)

    private val mutable = MutableStateFlow(account?.let(::read).orEmpty())
    val events = mutable.asStateFlow()

    /** Who a source's listens are filed under; null while Spotify has no account. */
    fun ownerOf(source: Source): String? = when (source) {
        Source.SPOTIFY -> account
        Source.YOUTUBE_MUSIC -> YOUTUBE_OWNER
    }

    /** Puts this source's history in [events]. */
    suspend fun show(source: Source) = withContext(Dispatchers.IO) { lock.withLock {
        this@ListeningStore.source = source
        mutable.value = ownerOf(source)?.let(::read).orEmpty()
    } }

    /** A different Spotify account signed in: the last one's history goes. */
    suspend fun useAccount(id: String) = withContext(Dispatchers.IO) { lock.withLock {
        val old = account
        if (old == id) return@withLock
        prefs.edit().putString("account", id).apply { if (old != null) remove(key(old)) }.apply()
        old?.let(cache::remove)
        if (source == Source.SPOTIFY) mutable.value = read(id)
    } }

    suspend fun record(event: ListeningEvent, owner: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (!known(owner)) return@withLock
        save(owner, ListeningHistory.record(read(owner), event))
    } }

    suspend fun import(plays: List<Pair<CatalogTrack, Long>>, owner: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (!known(owner)) return@withLock
        save(owner, plays.fold(read(owner)) { events, (track, at) -> ListeningHistory.merge(events, track, at) })
    } }

    /** Plays read from YouTube Music's history; see [YouTubeListening.plays]. */
    suspend fun importRemote(plays: List<YouTubeListening.RemotePlay>, owner: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (!known(owner)) return@withLock
        save(owner, plays.fold(read(owner)) { events, play ->
            ListeningHistory.mergeRemote(events, play.track, play.at, play.window)
        })
    } }

    /** The songs YouTube Music's history listed last time, newest first, and when. */
    fun youtubeReading(): Pair<List<String>, Long>? {
        val at = prefs.getLong("youtube-read-at", 0L).takeIf { it > 0 } ?: return null
        val songs = prefs.getString("youtube-read", null)?.split('\n')?.filter { it.isNotEmpty() } ?: return null
        return songs to at
    }

    fun keepYoutubeReading(songs: List<String>, at: Long) {
        prefs.edit().putString("youtube-read", songs.joinToString("\n")).putLong("youtube-read-at", at).apply()
    }

    /** Signing out of Spotify: its history goes, YouTube Music's stays. */
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        val old = account
        prefs.edit().remove("account").apply { if (old != null) remove(key(old)) }.apply()
        old?.let(cache::remove)
        if (source == Source.SPOTIFY) mutable.value = emptyList()
    } }

    private fun known(owner: String) = owner == YOUTUBE_OWNER || owner == account

    private fun key(owner: String) = "events:$owner"

    private fun read(owner: String): List<ListeningEvent> = cache.getOrPut(owner) {
        runCatching { json.decodeFromString(serializer, prefs.getString(key(owner), null) ?: "[]") }
            .getOrDefault(emptyList())
    }

    private fun save(owner: String, events: List<ListeningEvent>) {
        if (events == read(owner)) return
        cache[owner] = events
        prefs.edit().putString(key(owner), json.encodeToString(serializer, events)).apply()
        if (ownerOf(source) == owner) mutable.value = events
    }

    companion object {
        const val YOUTUBE_OWNER = "youtube-music"
    }
}
