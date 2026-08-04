package dev.emanuele.spot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.emanuele.spot.SpotApplication
import dev.emanuele.spot.auth.SpotifyOAuth
import dev.emanuele.spot.data.Catalog
import dev.emanuele.spot.data.AddTracksRequestDto
import dev.emanuele.spot.data.CatalogPlaylist
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.data.SearchItem
import dev.emanuele.spot.data.TransferRequestDto
import dev.emanuele.spot.data.SearchResults
import dev.emanuele.spot.data.toCatalogTrack
import dev.emanuele.spot.data.toResults
import dev.emanuele.spot.nativecore.NativeBridge
import dev.emanuele.spot.playback.BuiltInPresets
import dev.emanuele.spot.playback.EffectPreset
import dev.emanuele.spot.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Login gate and library browsing.
 *
 * Playback commands are deliberately absent: the UI talks to the media session
 * controller directly, so the notification, the lock screen and the app all read
 * one source of truth instead of two copies that can drift.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface UiState {
        data object LoggedOut : UiState

        /** Waiting for the access-point handshake. */
        data object Connecting : UiState
        data object Loading : UiState
        data class Ready(
            /**
             * The profile name where one is available, the account name
             * otherwise. The access point only knows the latter — a login id
             * like `31k4…` on many accounts — so the readable name has to come
             * from the Web API, and does not exist until the user has connected
             * their own application.
             */
            val displayName: String,
            val playlists: List<CatalogPlaylist>,
            val avatarUrl: String? = null,
        ) : UiState
        data class Failed(val message: String) : UiState
    }

    /** What kind of thing the detail screen is showing. */
    enum class DetailKind(val label: String) {
        PLAYLIST("Playlist"),
        ALBUM("Album"),
        ARTIST("Artista"),
    }

    /**
     * The playlist, album or artist currently open, keyed by its URI.
     *
     * One state and one screen for all three: they differ in where the tracks
     * come from and in a strip of albums the artist has and the other two do
     * not, which is not enough to justify three near-identical screens that
     * would then drift apart.
     */
    data class PlaylistState(
        val uri: String? = null,
        val name: String = "",
        val artworkUrl: String? = null,
        val tracks: List<CatalogTrack> = emptyList(),
        val loading: Boolean = false,
        /**
         * More of the list is still being resolved.
         *
         * Separate from [loading]: the first batch replaces the screen with a
         * spinner, the rest arrive under a list the user is already reading and
         * must not.
         */
        val loadingMore: Boolean = false,
        val error: String? = null,
        val kind: DetailKind = DetailKind.PLAYLIST,
        /** Populated for artists only. */
        val albums: List<SearchItem> = emptyList(),
    )

    private val container get() = getApplication<SpotApplication>()

    private var inFlight: Job? = null
    private var playlistJob: Job? = null

    private val _state = MutableStateFlow<UiState>(
        if (container.tokenStore.isLoggedIn) UiState.Connecting else UiState.LoggedOut,
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The home feed's catalogue sections.
     *
     * Everything here comes from the Web API and therefore needs the user's own
     * application, so an empty feed is an ordinary state rather than an error:
     * the rest of the home page — playlists, what was played — works without it.
     */
    data class FeedState(
        val newReleases: List<SearchItem> = emptyList(),
        val topArtists: List<SearchItem> = emptyList(),
        val loading: Boolean = false,
    )

    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()
    private var feedJob: Job? = null

    /**
     * Loads the feed, quietly.
     *
     * Each section is fetched on its own and a failure drops just that section:
     * top artists need the `user-top-read` scope, and an account that connected
     * its application before that scope was asked for answers 403 there while
     * everything else still works. One combined call would lose the lot.
     */
    fun loadFeed() {
        if (!container.webApi.isReady || feedJob?.isActive == true) return
        if (_feed.value.newReleases.isNotEmpty()) return

        _feed.value = _feed.value.copy(loading = true)
        feedJob = viewModelScope.launch {
            val releases = runCatching {
                container.api.newReleases().albums?.items.orEmpty().mapNotNull { album ->
                    SearchItem(
                        uri = album.uri ?: return@mapNotNull null,
                        title = album.name,
                        subtitle = album.artists.joinToString(", ") { it.name },
                        artworkUrl = album.images.firstOrNull()?.url,
                    )
                }
            }.onFailure { android.util.Log.w(TAG, "new releases unavailable: ${describe(it)}") }
                .getOrDefault(emptyList())

            val artists = runCatching {
                container.api.topArtists().items.mapNotNull { artist ->
                    SearchItem(
                        uri = artist.uri ?: return@mapNotNull null,
                        title = artist.name,
                        subtitle = "Artista",
                        artworkUrl = artist.images.firstOrNull()?.url,
                    )
                }
            }.onFailure { android.util.Log.w(TAG, "top artists unavailable: ${describe(it)}") }
                .getOrDefault(emptyList())

            _feed.value = FeedState(newReleases = releases, topArtists = artists, loading = false)
        }
    }

    /** The Spotify Connect device picker. */
    data class DevicesState(
        val open: Boolean = false,
        val loading: Boolean = false,
        val devices: List<SpotifyDevice> = emptyList(),
        val error: String? = null,
    )

    data class SpotifyDevice(
        val id: String,
        val name: String,
        val type: String,
        val isActive: Boolean,
    )

    private val _devices = MutableStateFlow(DevicesState())
    val devices: StateFlow<DevicesState> = _devices.asStateFlow()
    private var devicesJob: Job? = null

    fun openDevices() {
        _devices.value = _devices.value.copy(open = true)
        refreshDevices()
    }

    fun closeDevices() {
        _devices.value = _devices.value.copy(open = false)
    }

    /**
     * Reads the account's device list.
     *
     * Needs `user-read-playback-state`, which an application connected before
     * this feature existed was never asked for — hence the explicit message
     * rather than a bare 403.
     */
    fun refreshDevices() {
        devicesJob?.cancel()
        if (!container.webApi.isReady) {
            _devices.value = _devices.value.copy(
                loading = false,
                error = "Serve la tua applicazione Spotify: configurala in Cerca.",
            )
            return
        }

        _devices.value = _devices.value.copy(loading = true, error = null)
        devicesJob = viewModelScope.launch {
            runCatching { container.api.devices().devices }
                .onSuccess { list ->
                    _devices.value = _devices.value.copy(
                        loading = false,
                        devices = list.mapNotNull { device ->
                            SpotifyDevice(
                                // A device with no id cannot be addressed, so it
                                // is dropped rather than shown as a dead row.
                                id = device.id ?: return@mapNotNull null,
                                name = device.name,
                                type = device.type,
                                isActive = device.isActive,
                            )
                        },
                    )
                }
                .onFailure {
                    android.util.Log.e(TAG, "devices failed: ${chain(it)}", it)
                    _devices.value = _devices.value.copy(
                        loading = false,
                        error = describe(it),
                    )
                }
        }
    }

    /** Moves playback to another device, keeping it playing. */
    fun transferPlayback(deviceId: String) = viewModelScope.launch {
        runCatching { container.api.transferPlayback(TransferRequestDto(listOf(deviceId))) }
            .onSuccess {
                // Spotify reports the move a beat after acknowledging it, so the
                // list is re-read rather than edited optimistically.
                delay(TRANSFER_SETTLE_MS)
                refreshDevices()
            }
            .onFailure {
                android.util.Log.e(TAG, "transfer failed: ${chain(it)}", it)
                _devices.value = _devices.value.copy(error = describe(it))
            }
    }

    /**
     * The "add to playlist" sheet.
     *
     * Replaces the heart the player used to have. Liked Songs is one playlist
     * out of the account's several and the button spent its whole width saying
     * so; asking which playlist is the same gesture and answers the question the
     * heart could not.
     */
    data class AddToPlaylistState(
        val open: Boolean = false,
        /** The track the sheet will add, captured when it opens. */
        val trackUri: String? = null,
        val trackTitle: String = "",
        val playlists: List<CatalogPlaylist> = emptyList(),
        /** URI of the playlist currently being written to. */
        val busy: String? = null,
        /** Name of the playlist the track just went into. */
        val done: String? = null,
        val error: String? = null,
    )

    private val _addToPlaylist = MutableStateFlow(AddToPlaylistState())
    val addToPlaylist: StateFlow<AddToPlaylistState> = _addToPlaylist.asStateFlow()

    fun openAddToPlaylist(trackUri: String?, trackTitle: String) {
        _addToPlaylist.value = AddToPlaylistState(
            open = true,
            trackUri = trackUri,
            trackTitle = trackTitle,
            playlists = (_state.value as? UiState.Ready)?.playlists.orEmpty(),
            error = when {
                trackUri?.startsWith("spotify:track:") != true ->
                    "Questo brano non può essere aggiunto."
                !container.webApi.isReady ->
                    "Collega la tua applicazione nelle impostazioni."
                else -> null
            },
        )
    }

    fun closeAddToPlaylist() {
        _addToPlaylist.value = _addToPlaylist.value.copy(open = false)
    }

    /**
     * Appends the captured track to [playlist].
     *
     * Not optimistic, unlike the heart it replaces: this writes to something the
     * user keeps, the result is a row appearing in a list rather than a filled
     * icon, and there is nothing to undo it with if the call turns out to have
     * failed. So the sheet waits, then says which playlist it went into.
     */
    fun addToPlaylist(playlist: CatalogPlaylist) {
        val current = _addToPlaylist.value
        val trackUri = current.trackUri ?: return
        if (current.busy != null) return
        val id = playlist.uri.substringAfterLast(':')

        _addToPlaylist.value = current.copy(busy = playlist.uri, done = null, error = null)
        viewModelScope.launch {
            runCatching { container.api.addToPlaylist(id, AddTracksRequestDto(listOf(trackUri))) }
                .onSuccess {
                    _addToPlaylist.value =
                        _addToPlaylist.value.copy(busy = null, done = playlist.name)
                    // The detail screen holds a list resolved before this track
                    // was in it; if that is the playlist just written to, read
                    // it again.
                    if (_playlist.value.uri == playlist.uri) openPlaylist(playlist)
                }
                .onFailure {
                    android.util.Log.e(TAG, "add to playlist failed: ${chain(it)}", it)
                    _addToPlaylist.value = _addToPlaylist.value.copy(
                        busy = null,
                        // A playlist the account follows but does not own is the
                        // one failure worth naming: it looks identical to the
                        // user's own in every list the app draws.
                        error = "Non è stato possibile aggiungere a ${playlist.name}. " +
                            "Se la playlist non è tua, non puoi modificarla.",
                    )
                }
        }
    }

    private val _playlist = MutableStateFlow(PlaylistState())
    val playlist: StateFlow<PlaylistState> = _playlist.asStateFlow()

    /** Search box contents and results. */
    data class SearchState(
        val query: String = "",
        val loading: Boolean = false,
        val results: SearchResults = SearchResults(),
        val error: String? = null,
        /**
         * False until the user has registered their own Spotify application.
         * Search cannot work without one, so the screen offers the setup instead
         * of a search box that would only ever answer 429.
         */
        val needsSetup: Boolean = false,
    )

    private val _search = MutableStateFlow(SearchState(needsSetup = !container.webApi.isReady))
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /**
     * Runs a search, debounced.
     *
     * Firing on every keystroke would spend the Web API quota several times per
     * word, mostly on prefixes nobody wanted results for.
     */
    fun onSearchQuery(query: String) {
        val ready = container.webApi.isReady
        _search.value = _search.value.copy(query = query, needsSetup = !ready)
        searchJob?.cancel()

        if (query.isBlank() || !ready) {
            _search.value = SearchState(query = query, needsSetup = !ready)
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _search.value = _search.value.copy(loading = true, error = null)
            runCatching { container.api.search(query.trim()).toResults() }
                .onSuccess { results ->
                    _search.value = _search.value.copy(loading = false, results = results)
                }
                .onFailure {
                    android.util.Log.e(TAG, "search failed: ${chain(it)}", it)
                    _search.value = _search.value.copy(loading = false, error = describe(it))
                }
        }
    }

    /** The user's own Spotify application, used for search. */
    data class WebApiState(
        val clientId: String = "",
        val connected: Boolean = false,
        val connecting: Boolean = false,
        val error: String? = null,
        /** What the user must register as a redirect URI in their dashboard. */
        val redirectUri: String = SpotifyOAuth.REDIRECT_URI,
    )

    private val _webApi = MutableStateFlow(
        WebApiState(
            clientId = container.webApi.clientId.value.orEmpty(),
            connected = container.webApi.isReady,
        ),
    )
    val webApi: StateFlow<WebApiState> = _webApi.asStateFlow()

    fun onWebApiClientIdChange(value: String) {
        _webApi.value = _webApi.value.copy(clientId = value, error = null)
    }

    /**
     * Registers the client id and runs a second OAuth flow against it.
     *
     * A separate authorization from the playback login by necessity: an access
     * token is only valid for the application it was issued to, so the Web API
     * needs its own even though it is the same Spotify account behind both.
     */
    fun connectWebApi() = viewModelScope.launch {
        val clientId = _webApi.value.clientId.trim()
        if (clientId.isEmpty()) {
            _webApi.value = _webApi.value.copy(error = "Inserisci il client id.")
            return@launch
        }

        _webApi.value = _webApi.value.copy(connecting = true, error = null)
        container.webApi.setClientId(clientId)
        runCatching {
            // Search needs no scope — it reads public catalogue data — but the
            // home feed's "artisti che ascolti" is the user's own listening,
            // and that is gated behind `user-top-read`. It is the only
            // permission asked for, and an account connected before this
            // existed keeps working: the section simply stays empty until the
            // application is reconnected.
            SpotifyOAuth.authorize(
                getApplication(),
                clientId = clientId,
                scopes = listOf(
                    // The home feed's "artisti che ascolti".
                    "user-top-read",
                    // The Connect device picker: one to list them, one to move
                    // playback.
                    "user-read-playback-state",
                    "user-modify-playback-state",
                    // The player's "add to playlist". Which of the two
                    // applies is the playlist's own visibility, not the
                    // caller's, so both are asked for.
                    "playlist-modify-private",
                    "playlist-modify-public",
                ),
            )
        }
            .onSuccess {
                container.webApi.tokens.save(it)
                _webApi.value = _webApi.value.copy(connecting = false, connected = true)
                _search.value = _search.value.copy(needsSetup = false)
                // The feed could not have loaded before this point.
                loadFeed()
                // Re-run whatever the user had already typed.
                _search.value.query.takeIf { q -> q.isNotBlank() }?.let(::onSearchQuery)
            }
            .onFailure {
                android.util.Log.e(TAG, "web api login failed: ${chain(it)}", it)
                _webApi.value = _webApi.value.copy(connecting = false, error = describe(it))
            }
    }

    fun disconnectWebApi() {
        container.webApi.disconnect()
        _webApi.value = _webApi.value.copy(connected = false, error = null)
        _search.value = _search.value.copy(needsSetup = true)
    }

    /**
     * Saved effect presets: the built-in ones followed by the user's own.
     *
     * Combined here rather than stored together so the built-ins can be changed
     * or added to in a later version without migrating what the user saved.
     */
    val effectPresets: StateFlow<List<EffectPreset>> =
        container.effectPresets.presets
            .map { BuiltInPresets + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BuiltInPresets)

    fun saveEffectPreset(name: String, speed: Float, pitch: Float, reverb: Float) {
        container.effectPresets.save(name, speed, pitch, reverb)
    }

    fun deleteEffectPreset(id: String) = container.effectPresets.delete(id)

    /** Locally recorded listening history; see [dev.emanuele.spot.data.RecentStore]. */
    val recent: StateFlow<List<CatalogTrack>> get() = container.recentStore.tracks

    /** Called when a track starts, to keep the home page's history current. */
    fun recordPlayed(track: CatalogTrack) = viewModelScope.launch {
        container.recentStore.record(track)
    }

    init {
        if (container.tokenStore.isLoggedIn) {
            PlaybackService.connect(app)
            refresh()
        }
    }

    fun logIn() = viewModelScope.launch {
        _state.value = UiState.Loading
        runCatching { SpotifyOAuth.authorize(getApplication()) }
            .onSuccess {
                container.tokenStore.save(it)
                // The service was created before this session existed, so it has
                // to be told to authenticate the native engine now.
                PlaybackService.connect(getApplication())
                refresh()
            }
            .onFailure {
                android.util.Log.e(TAG, "login failed: ${chain(it)}", it)
                _state.value = UiState.Failed(describe(it))
            }
    }

    fun logOut() {
        container.tokenStore.clear()
        container.recentStore.clear()
        container.playlistOrder.clear()
        _playlist.value = PlaylistState()
        _feed.value = FeedState()
        _state.value = UiState.LoggedOut
    }

    fun refresh(): Job {
        inFlight?.takeIf { it.isActive }?.let { return it }
        return launchRefresh().also { inFlight = it }
    }

    private fun launchRefresh() = viewModelScope.launch {
        if (!container.tokenStore.isLoggedIn) {
            _state.value = UiState.LoggedOut
            return@launch
        }

        _state.value = UiState.Connecting
        if (!awaitEngine()) {
            _state.value = if (container.tokenStore.isLoggedIn) {
                UiState.Failed("Impossibile connettersi a Spotify.")
            } else {
                UiState.LoggedOut
            }
            return@launch
        }

        _state.value = UiState.Loading
        runCatching { UiState.Ready(Catalog.username(), Catalog.playlists()) }
            .onSuccess {
                _state.value = it
                loadProfile()
            }
            .onFailure {
                android.util.Log.e(TAG, "library load failed: ${chain(it)}", it)
                _state.value = UiState.Failed(describe(it))
            }
    }

    /**
     * Fills in the profile name and picture.
     *
     * Separate from the library load and allowed to fail quietly: the page is
     * already on screen with the account name by the time this runs, and an
     * account with no Web API application connected simply keeps it.
     */
    private fun loadProfile() = viewModelScope.launch {
        if (!container.webApi.isReady) return@launch
        runCatching { container.api.me() }
            .onSuccess { profile ->
                val ready = _state.value as? UiState.Ready ?: return@onSuccess
                _state.value = ready.copy(
                    displayName = profile.displayName?.takeIf(String::isNotBlank)
                        ?: ready.displayName,
                    avatarUrl = profile.images.lastOrNull()?.url,
                )
            }
            .onFailure { android.util.Log.w(TAG, "profile unavailable: ${describe(it)}") }
    }

    /** Playlists in the order this device last opened them; see [PlaylistOrderStore]. */
    val playlistOrder: StateFlow<List<String>> get() = container.playlistOrder.order

    /**
     * Loads a playlist's tracks.
     *
     * Reloading the playlist already shown is skipped: it is a few dozen
     * access-point round trips, and returning to a playlist from the player is
     * the common case.
     */
    fun openContext(uri: String, name: String, artworkUrl: String? = null) =
        openPlaylist(CatalogPlaylist(uri = uri, name = name, artworkUrl = artworkUrl))

    fun openPlaylist(playlist: CatalogPlaylist) {
        // Reopening one counts as opening it, and that is exactly the playlist
        // the home page should keep at the front.
        container.playlistOrder.record(playlist.uri)

        // Reopening what is already loaded re-reads it rather than showing the
        // copy in memory. It used to return here, which is how a track added
        // from the player could be genuinely in the playlist on Spotify and
        // missing from this screen: the list had been resolved before the track
        // existed and nothing ever asked again.
        //
        // The tracks already on screen stay up while the reload runs, so this
        // costs a round trip and shows no spinner.
        val cached = _playlist.value.takeIf { it.uri == playlist.uri }?.tracks.orEmpty()

        val kind = kindOf(playlist.uri)
        playlistJob?.cancel()
        val base = PlaylistState(
            uri = playlist.uri,
            name = playlist.name,
            artworkUrl = playlist.artworkUrl,
            kind = kind,
        )
        _playlist.value = base.copy(tracks = cached, loading = cached.isEmpty())
        playlistJob = viewModelScope.launch {
            runCatching {
                if (kind == DetailKind.ARTIST) {
                    val (tracks, albums) = loadArtist(playlist.uri)
                    _playlist.value = base.copy(tracks = tracks, albums = albums)
                } else {
                    loadContextInto(base, playlist.uri)
                }
            }
                .onFailure {
                    android.util.Log.e(TAG, "detail load failed: ${chain(it)}", it)
                    // Only when there is nothing to show. A refresh that fails
                    // over a list already on screen should leave the list.
                    _playlist.value =
                        if (cached.isEmpty()) base.copy(error = describe(it)) else base.copy(tracks = cached)
                }
        }
    }

    /**
     * Resolves a whole playlist or album, publishing it as it arrives.
     *
     * The list used to stop at the first hundred tracks, which is not a length
     * anyone's playlists respect, and a track added from the player landed past
     * the cut and looked like it had not been added at all.
     *
     * In batches rather than in one call because each track is its own
     * access-point lookup: the native side runs a batch concurrently, so asking
     * for two thousand at once would open two thousand requests and get itself
     * throttled. A batch at a time keeps that bounded and has the useful side
     * effect that the screen fills from the top while the rest is still coming.
     */
    private suspend fun loadContextInto(base: PlaylistState, uri: String) {
        val uris = Catalog.contextTrackUris(uri)
        if (uris.isEmpty()) {
            _playlist.value = base
            return
        }

        val loaded = mutableListOf<CatalogTrack>()
        uris.chunked(METADATA_BATCH).forEach { batch ->
            loaded += Catalog.tracks(batch)
            _playlist.value = base.copy(
                tracks = loaded.toList(),
                loadingMore = loaded.size < uris.size,
            )
        }
    }

    /**
     * Top tracks and albums for an artist.
     *
     * Web API rather than the access point: an artist is not a playable context,
     * so there is no track list to resolve there. That also means this is the one
     * screen which cannot work until the user has registered their own
     * application, hence the explicit message rather than a raw 401.
     */
    private suspend fun loadArtist(uri: String): Pair<List<CatalogTrack>, List<SearchItem>> {
        if (!container.webApi.isReady) {
            error("Per aprire un artista serve la tua applicazione Spotify, configurala in Cerca.")
        }
        val id = uri.substringAfterLast(':')
        val tracks = container.api.artistTopTracks(id).tracks.map { it.toCatalogTrack() }
        val albums = container.api.artistAlbums(id).items.mapNotNull { album ->
            SearchItem(
                uri = album.uri ?: return@mapNotNull null,
                title = album.name,
                subtitle = album.releaseDate?.take(4).orEmpty(),
                artworkUrl = album.images.firstOrNull()?.url,
            )
        }
        return tracks to albums
    }

    private fun kindOf(uri: String): DetailKind = when {
        uri.startsWith("spotify:artist:") -> DetailKind.ARTIST
        uri.startsWith("spotify:album:") -> DetailKind.ALBUM
        else -> DetailKind.PLAYLIST
    }

    /**
     * Waits for the native session to finish authenticating.
     *
     * Every catalogue call fails outright without it, so polling here is what
     * stops a cold start from showing a spurious error. Polling rather than a
     * callback because readiness lives behind a JNI boolean.
     */
    private suspend fun awaitEngine(): Boolean = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
        while (!NativeBridge.isConnected) {
            // The service clears the session when Spotify rejects it. Without
            // this check the UI would sit on "connecting" for the full timeout
            // and then report a connection problem, when the real answer is
            // that the user has to log in again.
            if (!container.tokenStore.isLoggedIn) return@withTimeoutOrNull false
            delay(POLL_INTERVAL_MS)
        }
        true
    } ?: false

    /** Full `Class: message` chain, for the log. */
    private fun chain(error: Throwable): String =
        generateSequence(error, Throwable::cause)
            .joinToString(" <- ") { "${it::class.java.name}: ${it.message}" }

    /**
     * A message worth putting on screen. `Throwable.message` alone is often null
     * or an empty wrapper, which is how "unknown error" screens happen.
     */
    private fun describe(error: Throwable): String {
        val parts = generateSequence(error, Throwable::cause)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .toList()
        return parts.firstOrNull() ?: error::class.java.simpleName
    }

    private companion object {
        const val TAG = "SpotUi"
        const val ENGINE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 250L

        /** Each track is its own access-point round trip. */
        /** Tracks resolved per access-point round trip; see loadContextInto. */
        const val METADATA_BATCH = 100

        const val SEARCH_DEBOUNCE_MS = 350L

        /** Spotify acknowledges a transfer before it has taken effect. */
        const val TRANSFER_SETTLE_MS = 700L
    }
}
