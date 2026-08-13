package dev.lelonio.square.backend

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.PlaylistDetailsDto
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.toCatalogTrack
import dev.lelonio.square.data.toResults
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.lelonio.square.playback.AudioOutput
import dev.lelonio.square.playback.LibrespotPlayer
import dev.lelonio.square.playback.PlayQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The existing Spotify path, behind [MusicBackend].
 *
 * Nothing here is new: the catalogue calls are the ones the UI already made
 * against [Catalog] and the Web API, and [createPlayer] builds the same
 * [LibrespotPlayer] `PlaybackService` built inline. The engine's *lifetime* is
 * deliberately still the service's — starting it needs a token, a language, a
 * bitrate and a restart path for each, none of which a backend should own.
 */
@UnstableApi
class SpotifyBackend(private val container: SquareApplication) : MusicBackend {

    override val id = BackendId.SPOTIFY

    private val _authState = MutableStateFlow<BackendAuthState>(
        if (container.tokenStore.isLoggedIn) BackendAuthState.Connecting else BackendAuthState.LoggedOut,
    )
    override val authState: StateFlow<BackendAuthState> = _authState.asStateFlow()

    /**
     * Logged in *and* the access point has answered.
     *
     * Both halves matter: a stored token with no engine behind it yet is a
     * session every catalogue call would fail against.
     */
    override val isReady: Boolean
        get() = container.tokenStore.isLoggedIn && NativeBridge.isConnected

    override suspend fun refreshAuth() {
        if (!container.tokenStore.isLoggedIn) {
            _authState.value = BackendAuthState.LoggedOut
            return
        }
        _authState.value = runCatching { Catalog.username() }
            .fold(
                onSuccess = { BackendAuthState.LoggedIn(it) },
                onFailure = { BackendAuthState.Failed(it.message ?: it::class.java.simpleName) },
            )
    }

    override suspend fun logOut() {
        container.tokenStore.clear()
        _authState.value = BackendAuthState.LoggedOut
    }

    /**
     * Search goes through the user's own registered application; see
     * [dev.lelonio.square.auth.WebApiAccount] for why it cannot use the
     * playback session's client id.
     */
    override suspend fun search(query: String, labels: SearchLabels): SearchResults {
        if (!container.webApi.isReady) return SearchResults()
        return container.api.search(query.trim()).toResults(
            artistLabel = labels.artist,
            albumLabel = labels.album,
            playlistLabel = labels.playlist,
        )
    }

    /**
     * The account's Liked Songs, which Spotify keeps outside the playlists.
     *
     * It behaves like one everywhere it matters — a name, a cover, a list to
     * open and play — so it is put at the head of the library rather than given
     * a screen of its own, with the same purple cover Spotify's own clients use.
     */
    private suspend fun likedSongs(): CatalogPlaylist? {
        val uri = withContext(Dispatchers.IO) {
            runCatching { NativeBridge.collectionUri() }.getOrDefault("")
        }
        if (uri.isEmpty()) return null
        return CatalogPlaylist(
            uri = uri,
            name = container.getString(dev.lelonio.square.R.string.liked_songs),
            artworkUrl = LIKED_SONGS_COVER,
        )
    }

    /** The access point first, the Web API when `rootlist` fails; see MainViewModel. */
    override suspend fun playlists(): List<CatalogPlaylist> =
        likedSongs().let { liked ->
            listOfNotNull(liked) +
                rootlist().filterNot { it.uri == liked?.uri || it.uri == Catalog.DJ_URI }
        }

    private suspend fun rootlist(): List<CatalogPlaylist> =
        runCatching { Catalog.playlists() }
            .recoverCatching {
                check(container.webApi.isReady) { it.message ?: "rootlist non disponibile" }
                container.api.playlists(limit = WEB_API_PAGE).items.map { dto ->
                    CatalogPlaylist(
                        uri = dto.uri,
                        name = dto.name,
                        artworkUrl = dto.images.firstOrNull()?.url,
                    )
                }
            }
            .getOrThrow()

    /**
     * A whole playlist, album or artist, resolved in one go.
     *
     * The detail screen does not call this: it publishes each page as it lands
     * and needs the loop itself. This is for callers that just want the list.
     */
    override suspend fun tracksOf(uri: String): List<CatalogTrack> = when {
        uri.startsWith("spotify:artist:") -> {
            check(container.webApi.isReady) { "un artista richiede la tua applicazione Spotify" }
            container.api.artistTopTracks(uri.substringAfterLast(':')).tracks
                .map { it.toCatalogTrack() }
        }

        container.webApi.isReady && uri.startsWith("spotify:playlist:") ->
            webApiPlaylistTracks(uri.substringAfterLast(':'))

        // Liked Songs is not a context the access point will resolve: asked for
        // it answers 503, so this is the one list that has to come from the Web
        // API and therefore needs the user's own application.
        uri.endsWith(":collection") -> {
            check(container.webApi.isReady) {
                "i brani che ti piacciono richiedono la tua applicazione Spotify"
            }
            savedTracks()
        }

        else -> Catalog.tracks(Catalog.contextTrackUris(uri))
    }

    /** Everything the account has saved, newest first, a page at a time. */
    private suspend fun savedTracks(): List<CatalogTrack> {
        val loaded = mutableListOf<CatalogTrack>()
        var offset = 0
        while (true) {
            val page = container.api.savedTracks(limit = WEB_API_PAGE, offset = offset)
            loaded += page.items.mapNotNull { saved ->
                saved.track
                    .takeIf { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
                    ?.toCatalogTrack(saved.addedAt)
            }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) return loaded
        }
    }

    private suspend fun webApiPlaylistTracks(id: String): List<CatalogTrack> {
        val loaded = mutableListOf<CatalogTrack>()
        var offset = 0
        while (true) {
            val page = container.api.playlistTracks(id, limit = WEB_API_PAGE, offset = offset)
            // Episodes and delisted tracks arrive as a null track, and an
            // unplayable one is something the engine could only skip.
            loaded += page.items.mapNotNull { item ->
                item.track
                    ?.takeIf { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
                    ?.toCatalogTrack(item.addedAt)
            }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) return loaded
        }
    }

    /** Spotify's own, which the access point serves; see [Catalog.lyrics]. */
    override suspend fun lyrics(
        uri: String,
        title: String,
        artist: String,
        durationMs: Long,
    ) = Catalog.lyrics(uri)

    override val canEditPlaylists: Boolean
        get() = container.tokenStore.isLoggedIn && container.webApi.isReady

    override suspend fun createPlaylist(name: String): CatalogPlaylist {
        // The account's own id: Spotify creates a playlist under a user rather
        // than under "me", even though the token already says who that is.
        val userId = container.api.me().id
        val created = container.api.createPlaylist(userId, PlaylistDetailsDto(name))
        return CatalogPlaylist(
            uri = created.uri,
            name = created.name,
            artworkUrl = created.images.firstOrNull()?.url,
        )
    }

    override suspend fun renamePlaylist(uri: String, name: String) {
        container.api.updatePlaylistDetails(uri.substringAfterLast(':'), PlaylistDetailsDto(name))
    }

    override suspend fun deletePlaylist(uri: String) {
        container.api.unfollowPlaylist(uri.substringAfterLast(':'))
    }

    override fun owns(uri: String) = uri.startsWith("spotify:")

    /**
     * The queue behind the player built by [createPlayer].
     *
     * Exposed because only the queue knows the pre-shuffle order and the
     * permutation on top of it, which is what the service writes to disk; the
     * Media3 timeline carries just the current sequence.
     */
    lateinit var queue: PlayQueue
        private set

    /** Owns the AudioTrack the native sink writes into. */
    val audioOutput = AudioOutput()

    override fun createPlayer(host: PlaybackHost): Player {
        queue = PlayQueue()
        return LibrespotPlayer(
            host.context,
            host.looper,
            queue,
            audioOutput::setSpeedAndPitch,
            audioOutput::fadeOutThen,
            audioOutput::fadeIn,
            audioOutput::setPlaybackActive,
        )
    }

    private companion object {
        /** The Web API's own maximum page size for playlist tracks. */
        const val WEB_API_PAGE = 100

        /**
         * The purple heart Spotify's own clients draw for Liked Songs.
         *
         * A fixed asset of theirs rather than anything account-specific, and
         * the only way to have this row look like the row it stands for: it is
         * not a playlist, so no cover comes back for it anywhere.
         */
        const val LIKED_SONGS_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    }
}
