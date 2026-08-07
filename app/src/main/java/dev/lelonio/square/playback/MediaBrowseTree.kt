package dev.lelonio.square.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.toCatalogTrack
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL
import dev.lelonio.square.ui.EXTRA_CONTEXT_ORDERED
import dev.lelonio.square.ui.EXTRA_CONTEXT_URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The browse tree Android Auto (and any other `MediaBrowser`) reads.
 *
 * Auto never draws the app's own UI: it asks the session for a root, walks the
 * children it answers with, and plays whatever item is tapped. So everything
 * the car can reach has to exist here rather than in Compose.
 *
 * Two things make this more than a listing:
 *
 * - **The engine may not be running.** The car can bind the service with the
 *   app never having been opened, so every read waits for the native session to
 *   finish connecting instead of answering with an empty shelf.
 * - **A tapped track is not a queue.** Auto sends back exactly the one item it
 *   was given, which would play a single song and stop. The id of every track
 *   carries the node it was listed under, and [onSetMediaItems] expands it back
 *   into the whole playlist positioned at that track — which is what tapping
 *   the third song in a playlist means everywhere else.
 */
@UnstableApi
class MediaBrowseTree(
    context: Context,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    private val app = context.applicationContext as SquareApplication

    /**
     * Resources read in the app's own language.
     *
     * A service is not re-created when the language changes and below Android 13
     * its configuration is the phone's, not the app's — the same reason the
     * engine asks [SquareApplication.language] rather than its own resources.
     */
    private val strings: Context = app.language.wrap(app)

    /** Track lists already resolved, keyed by the node they were listed under. */
    private val trackCache = mutableMapOf<String, List<CatalogTrack>>()

    /** Playlist names, so a queue built from the car is labelled like one built in the app. */
    private val nodeLabels = mutableMapOf<String, String>()

    /** One catalogue read at a time: the car fires children requests in parallel. */
    private val lock = Mutex()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(
            browsable(ID_ROOT, strings.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            params,
        ),
    )

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val item = when {
            mediaId == ID_ROOT ->
                browsable(ID_ROOT, strings.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            mediaId == ID_PLAYLISTS ->
                browsable(ID_PLAYLISTS, strings.getString(R.string.playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            mediaId == ID_RECENT ->
                browsable(ID_RECENT, strings.getString(R.string.play_again), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            mediaId.startsWith(TRACK_PREFIX) -> {
                val (parentId, trackUri) = splitTrackId(mediaId) ?: return@future notFound()
                val track = tracksOf(parentId).firstOrNull { it.uri == trackUri } ?: return@future notFound()
                playable(parentId, track)
            }
            else -> browsable(mediaId, nodeLabels[mediaId].orEmpty(), MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
        LibraryResult.ofItem(item, null)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children = runCatching { childrenOf(parentId) }
            .onFailure { android.util.Log.w(TAG, "children of $parentId failed: $it") }
            .getOrElse { emptyList() }

        // Media3 hands the page through untouched; a browser that asks for one
        // and gets the whole list shows the first page repeated forever.
        val from = (page * pageSize).coerceAtMost(children.size)
        val to = (from + pageSize).coerceAtMost(children.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ID_ROOT -> listOf(
            browsable(ID_PLAYLISTS, strings.getString(R.string.playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            browsable(ID_RECENT, strings.getString(R.string.play_again), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )

        parentId == ID_PLAYLISTS -> {
            if (!engineReady()) emptyList() else lock.withLock {
                Catalog.playlists().map { playlist ->
                    nodeLabels[playlist.uri] = playlist.name
                    browsable(
                        playlist.uri,
                        playlist.name,
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        playlist.artworkUrl?.let(Uri::parse),
                    )
                }
            }
        }

        // Local, so it answers even with no engine and no network — which is the
        // state the car is in for the first few seconds after it connects.
        parentId == ID_RECENT ->
            app.recentStore.tracks.value.map { playable(ID_RECENT, it) }

        parentId.startsWith("spotify:") ->
            tracksOf(parentId).map { playable(parentId, it) }

        else -> emptyList()
    }

    /**
     * The tracks of a node, resolved once.
     *
     * Each track is its own access-point round trip, so the list is capped: a
     * car screen shows a handful of rows at a time and nobody scrolls two
     * thousand of them while driving.
     */
    private suspend fun tracksOf(parentId: String): List<CatalogTrack> {
        if (parentId == ID_RECENT) return app.recentStore.tracks.value
        trackCache[parentId]?.let { return it }
        if (!engineReady()) return emptyList()

        return lock.withLock {
            trackCache[parentId] ?: runCatching {
                Catalog.tracks(Catalog.contextTrackUris(parentId).take(TRACK_LIMIT))
            }.getOrElse {
                android.util.Log.w(TAG, "tracks of $parentId failed: $it")
                emptyList()
            }.also { if (it.isNotEmpty()) trackCache[parentId] = it }
        }
    }

    // --- Voice search -------------------------------------------------------

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val results = search(query)
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val node = searchNode(query)
        val items = search(query).map { playable(node, it) }
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = (from + pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
    }

    /**
     * Search runs on the Web API, which is the only place Spotify exposes it —
     * the access point has no search endpoint at all. It therefore needs the
     * user's own registered application: without it the car gets nothing rather
     * than an error it has no way to show.
     */
    private suspend fun search(query: String): List<CatalogTrack> {
        val node = searchNode(query)
        trackCache[node]?.let { return it }

        val tracks = runCatching {
            app.api.search(query, type = "track", limit = SEARCH_LIMIT)
                .tracks?.items.orEmpty().map { it.toCatalogTrack() }
        }.getOrElse {
            android.util.Log.w(TAG, "search failed: $it")
            emptyList()
        }
        if (tracks.isNotEmpty()) {
            trackCache[node] = tracks
            nodeLabels[node] = strings.getString(R.string.search)
        }
        return tracks
    }

    private fun searchNode(query: String) = "$SEARCH_PREFIX$query"

    // --- Playback -----------------------------------------------------------

    /**
     * Expands the single item a browser plays back into its whole list.
     *
     * Required, not optional, for a second reason too: a session never hands a
     * controller's items to the player directly, and the default implementation
     * rejects every item that carries no playback URI. Ours carry a Spotify URI
     * as their media id and nothing else, so without this the `setMediaItems`
     * call is dropped silently and only the following `play()` reaches the
     * engine — "Player::play called from invalid state: Stopped".
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val single = mediaItems.singleOrNull()
        val parts = single?.mediaId?.let(::splitTrackId)
            ?: return@future MediaSession.MediaItemsWithStartPosition(
                mediaItems.map(::stripBrowseId).toMutableList(),
                startIndex,
                startPositionMs,
            )

        val (parentId, trackUri) = parts
        val tracks = tracksOf(parentId)
        val index = tracks.indexOfFirst { it.uri == trackUri }
        if (index < 0) {
            return@future MediaSession.MediaItemsWithStartPosition(
                mediaItems.map(::stripBrowseId).toMutableList(),
                startIndex,
                startPositionMs,
            )
        }

        MediaSession.MediaItemsWithStartPosition(
            tracks.map { queueItem(it, parentId) }.toMutableList(),
            index,
            startPositionMs,
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(mediaItems.map(::stripBrowseId).toMutableList())

    /** Turns a browse id back into the plain Spotify URI [PlayQueue] insists on. */
    private fun stripBrowseId(item: MediaItem): MediaItem {
        val uri = splitTrackId(item.mediaId)?.second ?: return item
        return item.buildUpon().setMediaId(uri).build()
    }

    private fun queueItem(track: CatalogTrack, parentId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.uri)
            .setMediaMetadata(
                metadata(track)
                    .setExtras(
                        Bundle().apply {
                            // Only a real context can be handed to the engine as
                            // one; a search result list is a set of tracks that
                            // happen to be together.
                            if (parentId.startsWith("spotify:")) {
                                putString(EXTRA_CONTEXT_URI, parentId)
                                putBoolean(EXTRA_CONTEXT_ORDERED, true)
                            } else {
                                putBoolean(EXTRA_CONTEXT_ORDERED, false)
                            }
                            nodeLabels[parentId]?.let { putString(EXTRA_CONTEXT_LABEL, it) }
                        },
                    )
                    .build(),
            )
            .build()

    // --- Items --------------------------------------------------------------

    private fun browsable(
        id: String,
        title: String,
        mediaType: Int,
        artworkUri: Uri? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .setArtworkUri(artworkUri)
                .build(),
        )
        .build()

    private fun playable(parentId: String, track: CatalogTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId(parentId, track.uri))
            .setMediaMetadata(metadata(track).build())
            .build()

    private fun metadata(track: CatalogTrack): MediaMetadata.Builder =
        MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setDurationMs(track.durationMs)
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

    private fun trackId(parentId: String, trackUri: String) = "$TRACK_PREFIX$parentId/$trackUri"

    /**
     * Splits a browse id back into node and track.
     *
     * `/` is the separator because no Spotify URI contains one, so a playlist id
     * can be embedded whole without escaping. Split from the *last* one: a
     * search node carries the query, and a query may well contain a slash.
     */
    private fun splitTrackId(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(TRACK_PREFIX)) return null
        val body = mediaId.removePrefix(TRACK_PREFIX)
        val cut = body.lastIndexOf('/')
        if (cut <= 0) return null
        return body.take(cut) to body.substring(cut + 1)
    }

    private fun notFound(): LibraryResult<MediaItem> =
        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)

    /**
     * Waits for the native session, rather than answering with an empty shelf.
     *
     * The car binds the service cold — the app may not have been opened since
     * the phone booted — and the access-point handshake takes a moment. An empty
     * list at that point is indistinguishable from an empty account, and Auto
     * caches what it is told.
     */
    private suspend fun engineReady(): Boolean {
        repeat(CONNECT_TRIES) {
            if (NativeBridge.isConnected) return true
            delay(CONNECT_POLL_MS)
        }
        return NativeBridge.isConnected
    }

    private companion object {
        const val TAG = "MediaBrowseTree"

        const val ID_ROOT = "sq/root"
        const val ID_PLAYLISTS = "sq/playlists"
        const val ID_RECENT = "sq/recent"
        const val TRACK_PREFIX = "sq/t/"
        const val SEARCH_PREFIX = "sq/q/"

        const val TRACK_LIMIT = 100
        const val SEARCH_LIMIT = 30

        const val CONNECT_TRIES = 40
        const val CONNECT_POLL_MS = 250L
    }
}
