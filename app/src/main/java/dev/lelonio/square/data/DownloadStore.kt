package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File

/**
 * What is downloaded, and who asked for it.
 *
 * The audio itself is the engine's business — `native/src/downloads.rs` writes
 * one encrypted file and one sidecar per track, and the player finds them
 * without ever asking this class. What lives here is everything the engine has
 * no opinion about: which playlists wanted a track, what a track is called so
 * it can be drawn with no connection, and which downloads have yet to happen.
 *
 * ### Owners, and why a track is not simply "downloaded"
 *
 * The requirement this whole class exists for is that a song in two downloaded
 * playlists is one file that both playlists know about. So nothing records "the
 * listener downloaded this track". What is recorded is a set of *owners* — a
 * playlist, an album, an artist, the loose collection of singles, the liked
 * songs — each holding the tracks it asked for, in order.
 *
 * Everything follows from that. Downloading a playlist that shares tracks with
 * one already downloaded costs no bytes. Removing a playlist removes an owner,
 * and the file goes only once no owner is left ([pruneOrphans]). A track shows
 * as downloaded inside the playlist it belongs to because that is the question
 * being asked, not because of a flag on the track.
 *
 * ### The queue is not stored
 *
 * There is no list of pending downloads on disk. What is pending is whatever an
 * owner asked for and has no file yet ([pending]), which means an app killed
 * mid-playlist works out what it still owes the moment it starts, and a
 * download interrupted halfway resumes from the `.part` file the engine left.
 * A queue file would have been a second truth to keep in step with this one.
 */
class DownloadStore(context: Context) {

    /**
     * A file that is actually on disk, as the engine reported it.
     *
     * Present only for completed downloads: the entry is written after
     * `downloadTrack` returns, so an entry here and a sidecar there mean the
     * same thing.
     */
    @Serializable
    data class FileRecord(
        val trackId: String,
        val format: String,
        val bytes: Long,
        /** The quality actually stored, so a re-download at 320 knows to run. */
        val kbps: Int,
        val downloadedAt: Long,
    )

    /** Enough to draw a downloaded playlist with no connection. */
    @Serializable
    data class OwnerLabel(
        val name: String,
        val artworkUrl: String? = null,
        val kind: String = KIND_PLAYLIST,
    )

    @Serializable
    data class Failure(val reason: String, val attempts: Int, val at: Long) {
        /**
         * When this track is worth trying again: two seconds, then four, then
         * eight, up to five minutes.
         *
         * The first failure of a batch is almost always the connection going,
         * and the whole batch recovers together — so the early waits are short
         * enough that the listener never sees the pause, and the late ones long
         * enough that a track Spotify genuinely will not serve stops costing
         * anything.
         */
        fun readyAt(): Long =
            at + (BASE_BACKOFF_MS shl (attempts - 1).coerceIn(0, 8)).coerceAtMost(MAX_BACKOFF_MS)

        private companion object {
            const val BASE_BACKOFF_MS = 2_000L
            const val MAX_BACKOFF_MS = 5L * 60 * 1000
        }
    }

    @Serializable
    private data class Index(
        val version: Int = 1,
        val files: Map<String, FileRecord> = emptyMap(),
        val owners: Map<String, List<String>> = emptyMap(),
        /**
         * Metadata for every track any owner asked for.
         *
         * Kept here rather than read from [ContextCacheStore] because that
         * cache expires after a week and is trimmed to forty contexts, and a
         * download is meant to outlive both. Offline, this is what the rows are
         * drawn from.
         */
        val tracks: Map<String, CatalogTrack> = emptyMap(),
        val labels: Map<String, OwnerLabel> = emptyMap(),
        val failures: Map<String, Failure> = emptyMap(),
    )

    /** Where the engine keeps the audio. Handed to it once, at startup. */
    val root: File = File(context.applicationContext.filesDir, DIR_NAME)

    private val indexFile = File(root, INDEX_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val loadLock = Mutex()

    private val _index = MutableStateFlow(Index())
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** What is on disk, by track URI. */
    val files: StateFlow<Map<String, FileRecord>>
        get() = _files.asStateFlow()
    private val _files = MutableStateFlow<Map<String, FileRecord>>(emptyMap())

    /** Who asked for what, by owner URI, in the order the owner lists them. */
    val owners: StateFlow<Map<String, List<String>>>
        get() = _owners.asStateFlow()
    private val _owners = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Downloads in flight, as a fraction. Never persisted. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    val failures: StateFlow<Map<String, Failure>>
        get() = _failures.asStateFlow()
    private val _failures = MutableStateFlow<Map<String, Failure>>(emptyMap())

    /**
     * [ownerState] for every owner, ready for a screen to read.
     *
     * A flow, so a page redraws its button as the queue moves. Only the owners
     * are computed this way — there are tens of them. The per-track answer is
     * left to the caller, which reads [files] and [progress] and asks about the
     * rows it is actually drawing: building a map of several thousand tracks
     * several times a second, to mark the dozen on screen, is the version of
     * this that makes a playlist scroll badly.
     */
    val ownerStates: StateFlow<Map<String, OwnerState>> =
        combine(_owners, _files, _progress, _failures) { owners, _, _, _ ->
            owners.keys.associateWith(::ownerState)
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            ensureLoaded()
            reconcile()
        }
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loadLock.withLock {
            if (!loaded) loadLocked()
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Reads the index. Safe to call more than once; the second call is a no-op.
     *
     * A missing or unreadable index is an empty one rather than a failure. The
     * files on disk are still there and [reconcile] will find them, which is a
     * better answer than refusing to start because a JSON file got truncated.
     */
    suspend fun load() = ensureLoaded()

    private fun loadLocked() {
        root.mkdirs()
        val stored = runCatching {
            if (indexFile.exists()) json.decodeFromString(Index.serializer(), indexFile.readText())
            else Index()
        }.getOrElse { Index() }
        // Published before the flag goes up: [ensureLoaded] reads the flag
        // without the lock, and a caller that sees it set must find the index
        // already there rather than the empty one it started with.
        publish(stored)
        loaded = true
    }

    @Volatile
    private var loaded = false

    /**
     * Drops index entries whose audio has gone, and forgets tracks and labels
     * nothing points at any more.
     *
     * The two halves of this can drift for reasons neither side controls: the
     * listener can clear the app's storage from Android's settings, which takes
     * the audio and leaves the index, and a crash between the rename and the
     * index write leaves a file nothing claims. Cheap enough to run at startup.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            val surviving = current.files.filterKeys { uri ->
                val record = current.files[uri] ?: return@filterKeys false
                audioFile(record.trackId).exists()
            }
            if (surviving.size != current.files.size) {
                publish(current.copy(files = surviving))
                save()
            }
        }
    }

    // ------------------------------------------------------------- questions

    fun isDownloaded(trackUri: String): Boolean = _files.value.containsKey(trackUri)

    /**
     * How a single row should draw itself.
     *
     * [DownloadState.None] for anything the listener has not asked for, which
     * is deliberately indistinguishable from "not started": a row shows nothing
     * at all until a download for it exists, so an undownloaded playlist looks
     * exactly as it did before this feature.
     */
    fun stateOf(trackUri: String): DownloadState = when {
        _files.value.containsKey(trackUri) -> DownloadState.Done
        _progress.value.containsKey(trackUri) ->
            DownloadState.Running(_progress.value.getValue(trackUri))
        _failures.value.containsKey(trackUri) ->
            DownloadState.Failed(_failures.value.getValue(trackUri).reason)
        isWanted(trackUri) -> DownloadState.Queued
        else -> DownloadState.None
    }

    /** Whether any owner still asks for this track. */
    fun isWanted(trackUri: String): Boolean =
        _owners.value.values.any { tracks -> tracks.contains(trackUri) }

    /**
     * How a playlist, album or artist page should draw its button.
     *
     * `Partial` is not a failure. Some tracks are not available on the account's
     * market and never will be, and a playlist that says "38 of 40" is telling
     * the truth about something the app cannot fix.
     */
    fun ownerState(ownerUri: String): OwnerState {
        val wanted = _owners.value[ownerUri] ?: return OwnerState.None
        if (wanted.isEmpty()) return OwnerState.None

        val files = _files.value
        val progress = _progress.value
        val failures = _failures.value

        val done = wanted.count(files::containsKey)
        if (done == wanted.size) return OwnerState.Complete

        val running = wanted.filter(progress::containsKey)
        val outstanding = wanted.filterNot(files::containsKey)
        val stuck = outstanding.count(failures::containsKey)

        // Everything left has already failed as far as it is going to, so the
        // owner is as complete as it will get. Saying "downloading" for ever
        // would be the alternative, and the ring would never stop turning.
        if (stuck == outstanding.size) return OwnerState.Partial(done, wanted.size)

        // The share of the whole, not of what is left: a ring that restarts
        // every time a track finishes is worse than one that creeps.
        val fraction =
            (done + running.sumOf { progress[it]?.toDouble() ?: 0.0 }) / wanted.size
        return OwnerState.Running(fraction.toFloat().coerceIn(0f, 1f), done, wanted.size)
    }

    /**
     * Tracks an owner asked for that have no file yet and are worth trying now,
     * in the order their owners list them.
     *
     * Failures are held back rather than dropped: a track that failed once is
     * offered again after a wait that doubles each time, and only stops being
     * offered after [MAX_ATTEMPTS]. Without the wait a track Spotify does not
     * serve on this account would be retried in a tight loop for as long as the
     * queue ran; without the ceiling it would be retried for ever, on every
     * launch, and the queue would never report itself finished.
     */
    fun pending(now: Long = System.currentTimeMillis()): List<String> {
        val files = _files.value
        val failures = _failures.value
        val ordered = LinkedHashSet<String>()
        for (tracks in _owners.value.values) {
            for (uri in tracks) {
                if (files.containsKey(uri)) continue
                val failure = failures[uri]
                if (failure != null && failure.attempts >= MAX_ATTEMPTS) continue
                if (failure != null && failure.readyAt() > now) continue
                ordered.add(uri)
            }
        }
        return ordered.toList()
    }

    /**
     * When the next track held back by a failure becomes worth trying again, or
     * null when nothing is waiting.
     *
     * The queue sleeps until this rather than polling, so a phone with one
     * stubborn track in a playlist is not woken every few seconds for it.
     */
    fun retryAt(): Long? {
        val files = _files.value
        val claimed = _owners.value.values.flatten().toSet()
        return _failures.value
            .filterKeys { it in claimed && !files.containsKey(it) }
            .values
            .filter { it.attempts < MAX_ATTEMPTS }
            .minOfOrNull { it.readyAt() }
    }

    /** Tracks that will not be tried again unless the listener asks. */
    fun givenUp(): List<String> {
        val claimed = _owners.value.values.flatten().toSet()
        val files = _files.value
        return _failures.value
            .filter { (uri, failure) ->
                uri in claimed && !files.containsKey(uri) && failure.attempts >= MAX_ATTEMPTS
            }
            .keys
            .toList()
    }

    /** Metadata for a downloaded track, for drawing it with no connection. */
    fun trackOf(trackUri: String): CatalogTrack? = _index.value.tracks[trackUri]

    fun labelOf(ownerUri: String): OwnerLabel? = _index.value.labels[ownerUri]

    fun totalBytes(): Long = _files.value.values.sumOf { it.bytes }

    fun bytesOf(ownerUri: String): Long {
        val files = _files.value
        return _owners.value[ownerUri].orEmpty().sumOf { files[it]?.bytes ?: 0L }
    }

    // ------------------------------------------------------------ what to own

    /**
     * Records that [ownerUri] wants these tracks, replacing whatever it wanted
     * before.
     *
     * Replacing rather than merging is what keeps a downloaded playlist honest
     * as it changes: a track removed from the playlist upstream stops being
     * wanted here on the next sync, and its file goes once nothing else claims
     * it.
     */
    suspend fun setOwner(
        ownerUri: String,
        tracks: List<CatalogTrack>,
        label: OwnerLabel?,
    ) = writeLock.withLock {
        ensureLoaded()
        val current = _index.value
        val existingFiles = current.files.toMutableMap()
        for (track in tracks) {
            if (!existingFiles.containsKey(track.uri)) {
                val sidecar = runCatching {
                    dev.lelonio.square.nativecore.NativeBridge.downloadState(track.uri)
                }.getOrNull()
                if (!sidecar.isNullOrBlank()) {
                    val rootObj = runCatching { JSONObject(sidecar) }.getOrNull()
                    if (rootObj != null) {
                        val trackId = rootObj.optString("id").takeIf { it.isNotBlank() } ?: track.uri.substringAfterLast(':')
                        if (audioFile(trackId).exists()) {
                            existingFiles[track.uri] = FileRecord(
                                trackId = trackId,
                                format = rootObj.optString("format").ifEmpty { "OGG_VORBIS_320" },
                                bytes = rootObj.optLong("bytes"),
                                kbps = rootObj.optInt("kbps").takeIf { it > 0 } ?: 320,
                                downloadedAt = rootObj.optLong("downloadedAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
        }
        publish(
            current.copy(
                files = existingFiles,
                owners = current.owners + (ownerUri to tracks.map { it.uri }),
                tracks = current.tracks + tracks.associateBy { it.uri },
                labels = label?.let { current.labels + (ownerUri to it) } ?: current.labels,
                // Asking again is the listener saying "try this", so the record
                // of past failures for these tracks goes.
                failures = current.failures - tracks.map { it.uri }.toSet(),
            ),
        )
        save()
    }

    /** Adds one track to the loose collection of singles. */
    suspend fun addSingle(track: CatalogTrack) {
        val existing = _owners.value[SINGLES].orEmpty()
        if (existing.contains(track.uri)) return
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            publish(
                current.copy(
                    owners = current.owners + (SINGLES to existing + track.uri),
                    tracks = current.tracks + (track.uri to track),
                    failures = current.failures - track.uri,
                ),
            )
            save()
        }
    }

    suspend fun removeSingle(trackUri: String) {
        val existing = _owners.value[SINGLES].orEmpty()
        if (!existing.contains(trackUri)) return
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            publish(current.copy(owners = current.owners + (SINGLES to existing - trackUri)))
            save()
        }
    }

    /** Adds one track to the automatic download of liked songs. */
    suspend fun addLiked(track: CatalogTrack) {
        val existing = _owners.value[LIKED].orEmpty()
        if (existing.contains(track.uri)) return
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            publish(
                current.copy(
                    owners = current.owners + (LIKED to existing + track.uri),
                    tracks = current.tracks + (track.uri to track),
                    failures = current.failures - track.uri,
                ),
            )
            save()
        }
    }

    /** Removes one track from the automatic download of liked songs. */
    suspend fun removeLiked(trackUri: String) {
        val existing = _owners.value[LIKED].orEmpty()
        if (!existing.contains(trackUri)) return
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            publish(current.copy(owners = current.owners + (LIKED to existing - trackUri)))
            save()
        }
    }

    /**
     * Forgets an owner. What it was alone in wanting is left for [pruneOrphans].
     *
     * Deliberately two steps. Pruning here would drop the files from the index
     * with nobody left holding a list of what to delete, and the audio would
     * stay on disk for good — several hundred megabytes that nothing in the app
     * could ever see again, let alone remove.
     */
    suspend fun removeOwner(ownerUri: String) = writeLock.withLock {
        ensureLoaded()
        val current = _index.value
        publish(
            current.copy(
                owners = current.owners - ownerUri,
                labels = current.labels - ownerUri,
            ),
        )
        save()
    }

    // ---------------------------------------------------- what the queue says

    fun onProgress(trackUri: String, fraction: Float) {
        val current = _progress.value[trackUri]
        val clamped = fraction.coerceIn(0f, 1f)
        if (current != null && Math.abs(clamped - current) < 0.01f && clamped < 1f) return
        _progress.value = _progress.value + (trackUri to clamped)
    }

    /**
     * Forgets that a track was in flight, without recording anything against
     * it. For a download that stopped for a reason the track had no part in —
     * the engine going, or the queue being told to stop.
     */
    fun clearProgress(trackUri: String) {
        if (!_progress.value.containsKey(trackUri)) return
        _progress.value = _progress.value - trackUri
    }

    suspend fun onCompleted(trackUri: String, record: FileRecord) {
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            publish(
                current.copy(
                    files = current.files + (trackUri to record),
                    failures = current.failures - trackUri,
                ),
            )
            _progress.value = _progress.value - trackUri
            save()
        }
    }

    suspend fun onFailed(trackUri: String, reason: String) {
        writeLock.withLock {
            ensureLoaded()
            val current = _index.value
            val before = current.failures[trackUri]
            publish(
                current.copy(
                    failures = current.failures + (
                        trackUri to Failure(
                            reason = reason,
                            attempts = (before?.attempts ?: 0) + 1,
                            at = System.currentTimeMillis(),
                        )
                        ),
                ),
            )
            _progress.value = _progress.value - trackUri
            save()
        }
    }

    /** Forgets past failures so [pending] offers those tracks again. */
    suspend fun retryFailed() = writeLock.withLock {
        ensureLoaded()
        publish(_index.value.copy(failures = emptyMap()))
        save()
    }

    /**
     * Deletes files, metadata and labels nothing claims any more.
     *
     * This is the other half of the owner model: [removeOwner] only forgets who
     * wanted a track, and this is what notices that nobody does. Deliberately
     * separate, so removing one playlist never deletes a file another playlist
     * is still holding.
     *
     * Returns the tracks whose audio was deleted, so the caller can tell the
     * engine to drop them.
     */
    suspend fun pruneOrphans(): List<String> = writeLock.withLock {
        ensureLoaded()
        val current = _index.value
        val claimed = current.owners.values.flatten().toSet()

        val orphaned = current.files.keys.filterNot(claimed::contains)
        val strandedTracks = current.tracks.keys.filterNot { claimed.contains(it) || current.files.containsKey(it) }
        if (orphaned.isEmpty() && strandedTracks.isEmpty()) return@withLock emptyList()

        publish(
            current.copy(
                files = current.files - orphaned.toSet(),
                tracks = current.tracks - strandedTracks.toSet(),
                failures = current.failures - strandedTracks.toSet(),
            ),
        )
        save()
        orphaned
    }

    /** Everything goes: the index, and the whole store the engine writes into. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            ensureLoaded()
            publish(Index())
            _progress.value = emptyMap()
            runCatching { root.deleteRecursively() }
            root.mkdirs()
            // The extras live under the same root and go with it; this is only
            // to drop whatever the object is holding on to.
            dev.lelonio.square.download.DownloadExtras.clear()
            save()
        }
    }

    // ------------------------------------------------------------- internals

    private fun audioFile(trackId: String): File =
        File(File(root, "audio"), trackId.take(2)).resolve(trackId.drop(2))

    private fun publish(index: Index) {
        _index.value = index
        _files.value = index.files
        _owners.value = index.owners
        _failures.value = index.failures
    }

    /**
     * Writes the index through a temporary file.
     *
     * Not debounced: every caller here is a whole-playlist change or the end of
     * a download, which is at most one write every few seconds even with the
     * queue running flat out. Progress ticks, which arrive several times a
     * second, never reach this — they live only in [progress].
     */
    private fun save() {
        val snapshot = _index.value
        scope.launch {
            loadLock.withLock {
                runCatching {
                    root.mkdirs()
                    val tmp = File(root, "$INDEX_NAME.tmp")
                    tmp.writeText(json.encodeToString(Index.serializer(), snapshot))
                    if (!tmp.renameTo(indexFile)) {
                        indexFile.delete()
                        tmp.renameTo(indexFile)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * How many times one track is tried before it is left alone.
         *
         * Reached, it is not an error state to recover from: some tracks are
         * simply not served to this account, and a playlist that stops at 38 of
         * 40 is telling the truth. The listener can ask again from the
         * downloads panel, which is what [retryFailed] is for.
         */
        const val MAX_ATTEMPTS = 5

        const val DIR_NAME = "downloads"
        private const val INDEX_NAME = "index.json"

        /** The owner every single track downloaded on its own belongs to. */
        const val SINGLES = "downloads:tracks"

        /** The owner the automatic download of the liked songs writes to. */
        const val LIKED = "downloads:liked"

        const val KIND_PLAYLIST = "playlist"
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
    }
}

/** How one track should draw itself. */
sealed interface DownloadState {
    /** Not asked for. Draws nothing at all — see [DownloadStore.stateOf]. */
    data object None : DownloadState
    data object Queued : DownloadState
    data class Running(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/** How a playlist, album or artist should draw its button. */
sealed interface OwnerState {
    data object None : OwnerState
    data class Running(val progress: Float, val done: Int, val total: Int) : OwnerState
    data object Complete : OwnerState
    /** Everything that could be fetched was; the rest is not available here. */
    data class Partial(val done: Int, val total: Int) : OwnerState
}
