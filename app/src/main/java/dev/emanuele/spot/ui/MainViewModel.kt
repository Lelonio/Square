package dev.emanuele.spot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.emanuele.spot.SpotApplication
import dev.emanuele.spot.auth.SpotifyOAuth
import dev.emanuele.spot.data.Catalog
import dev.emanuele.spot.data.AddTracksRequestDto
import dev.emanuele.spot.data.RemoveTracksRequestDto
import dev.emanuele.spot.data.TrackUriDto
import dev.emanuele.spot.data.CatalogPlaylist
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.data.ContextCacheStore
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

    /**
     * @param asSheet whether to show the modal. False from the player, which
     *   shows the same picker in its own panel and would otherwise get both.
     */
    fun openAddToPlaylist(trackUri: String?, trackTitle: String, asSheet: Boolean = true) {
        _addToPlaylist.value = AddToPlaylistState(
            open = asSheet,
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
                    invalidateContext(playlist.uri)
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
                    // Reading what the account has actually played, which is a
                    // different permission from the top artists above.
                    "user-read-recently-played",
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
        // Somebody else's library must not be sitting in the cache when the
        // next account signs in.
        contextCache.clear()
        viewModelScope.launch { container.contextCache.clear() }
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
        runCatching { UiState.Ready(Catalog.username(), playlists()) }
            .onSuccess {
                _state.value = it
                loadProfile()
                loadMissingCovers()
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
    /**
     * The account's playlists, from the access point or from the Web API.
     *
     * The access point is asked first: it is the same list, it needs no
     * registered application, and it carries the playlists this account follows
     * as well as its own. But `rootlist` answers 502 often enough to matter —
     * a Spotify-side failure nothing here can prevent — and losing the whole
     * library screen to it is out of proportion, since the Web API can answer
     * the same question.
     */
    private suspend fun playlists(): List<CatalogPlaylist> =
        runCatching { Catalog.playlists() }
            .onFailure { android.util.Log.w(TAG, "rootlist unavailable: ${describe(it)}") }
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

    /** Covers already looked up, so a second visit to the home page is free. */
    private val coverCache = mutableMapOf<String, String>()

    /**
     * Fills in the covers the rootlist did not carry.
     *
     * Spotify's own playlists — the editorial ones, the daily mixes — keep their
     * art on the playlist rather than in the account's index of it, so those
     * tiles came out blank while the user's own were fine. One lookup each,
     * after the page is already on screen, and a failure just leaves the
     * generated tile in place.
     */
    private fun loadMissingCovers() = viewModelScope.launch {
        val ready = _state.value as? UiState.Ready ?: return@launch
        val missing = ready.playlists.filter { it.artworkUrl == null }
        if (missing.isEmpty()) return@launch

        missing.forEach { playlist ->
            val cover = coverCache[playlist.uri] ?: Catalog.playlistCover(playlist.uri)
            if (cover.isNullOrEmpty()) return@forEach
            coverCache[playlist.uri] = cover

            // Re-read each time: the list can have been replaced by a refresh
            // while these were being fetched, one at a time.
            val current = _state.value as? UiState.Ready ?: return@launch
            _state.value = current.copy(
                playlists = current.playlists.map {
                    if (it.uri == playlist.uri) it.copy(artworkUrl = cover) else it
                },
            )
        }
    }

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

    /** How the detail screen sorts its tracks; remembered between visits. */
    val trackSort: StateFlow<String?> get() = container.preferences.trackSort

    fun setTrackSort(value: String) = container.preferences.setTrackSort(value)

    /** False until the welcome tutorial has been finished once. */
    val onboarded: StateFlow<Boolean> get() = container.preferences.onboarded

    fun setOnboarded(value: Boolean) = container.preferences.setOnboarded(value)

    /**
     * Loads a playlist's tracks.
     *
     * Reloading the playlist already shown is skipped: it is a few dozen
     * access-point round trips, and returning to a playlist from the player is
     * the common case.
     */
    fun openContext(uri: String, name: String, artworkUrl: String? = null) =
        openPlaylist(CatalogPlaylist(uri = uri, name = name, artworkUrl = artworkUrl))

    /**
     * Track lists already resolved, keyed by context URI.
     *
     * Reopening a playlist used to re-resolve it from scratch, which on a
     * thousand-track playlist is a minute of watching rows appear. The list is
     * held for the session and shown immediately; a refresh runs behind it and
     * replaces it only when it has the whole thing, so reopening never takes a
     * finished list away and rebuilds it in front of the user.
     */
    private val contextCache =
        object : LinkedHashMap<String, ContextCacheStore.Entry>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ContextCacheStore.Entry>) =
                size > CONTEXT_CACHE_SIZE
        }

    fun openPlaylist(playlist: CatalogPlaylist) {
        // Reopening one counts as opening it, and that is exactly the playlist
        // the home page should keep at the front.
        container.playlistOrder.record(playlist.uri)

        val kind = kindOf(playlist.uri)
        playlistJob?.cancel()
        val base = PlaylistState(
            uri = playlist.uri,
            name = playlist.name,
            artworkUrl = playlist.artworkUrl,
            kind = kind,
        )

        val remembered = contextCache[playlist.uri]
        _playlist.value = base.copy(
            tracks = remembered?.tracks.orEmpty(),
            loading = remembered == null,
        )

        playlistJob = viewModelScope.launch {
            // The disk copy, if this run has not opened the playlist yet. Read
            // before anything is asked of the network: the whole point is that a
            // list already known appears at once rather than filling in.
            val entry = remembered ?: container.contextCache.read(playlist.uri)?.also {
                contextCache[playlist.uri] = it
                _playlist.value = base.copy(tracks = it.tracks, loading = false)
            }
            val cached = entry?.tracks.orEmpty()

            runCatching {
                if (kind == DetailKind.ARTIST) {
                    val (tracks, albums) = loadArtist(playlist.uri)
                    _playlist.value = base.copy(tracks = tracks, albums = albums)
                } else if (cached.isNotEmpty() && isUnchanged(playlist.uri, entry?.snapshotId)) {
                    // Nothing to do: one small request said the copy on screen
                    // is the current one.
                    _playlist.value = base.copy(tracks = cached, loadingMore = false)
                } else {
                    loadContextInto(base, playlist.uri, showProgress = cached.isEmpty())
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
     * Whether the stored copy of a playlist is still the current one.
     *
     * Spotify changes a playlist's `snapshot_id` whenever its contents do, so
     * this is one request against a dozen. False for anything without a stored
     * stamp — an album, a list read through the access point — which simply
     * means it is refreshed as before, silently, behind what is already shown.
     */
    private suspend fun isUnchanged(uri: String, snapshotId: String?): Boolean {
        if (snapshotId == null) return false
        if (!container.webApi.isReady || !uri.startsWith("spotify:playlist:")) return false
        return runCatching {
            container.api.playlistSnapshot(uri.substringAfterLast(':')).snapshotId == snapshotId
        }
            .onFailure { android.util.Log.w(TAG, "snapshot check failed: ${describe(it)}") }
            .getOrDefault(false)
    }

    /**
     * Removes a track from the playlist currently open.
     *
     * Optimistic, unlike adding: the row disappearing *is* the confirmation, and
     * putting it back is a possible outcome the user can see, whereas a row that
     * sits there for a round trip before vanishing reads as a tap that missed.
     * Every occurrence goes, which matters only on a playlist holding the same
     * track twice; see SpotifyApi.removeFromPlaylist.
     */
    fun removeFromPlaylist(track: CatalogTrack) {
        val uri = _playlist.value.uri ?: return
        if (!uri.startsWith("spotify:playlist:")) return

        val before = _playlist.value.tracks
        _playlist.value = _playlist.value.copy(tracks = before.filterNot { it.uri == track.uri })
        invalidateContext(uri)

        viewModelScope.launch {
            runCatching {
                container.api.removeFromPlaylist(
                    uri.substringAfterLast(':'),
                    RemoveTracksRequestDto(listOf(TrackUriDto(track.uri))),
                )
            }.onFailure {
                android.util.Log.e(TAG, "remove from playlist failed: ${chain(it)}", it)
                // Only if the screen is still showing the same playlist.
                if (_playlist.value.uri == uri) {
                    _playlist.value = _playlist.value.copy(tracks = before)
                }
            }
        }
    }

    /** Drops a context from both caches so the next open re-reads it. */
    private fun invalidateContext(uri: String) {
        contextCache.remove(uri)
        viewModelScope.launch { container.contextCache.remove(uri) }
    }

    /**
     * Resolves a whole playlist or album.
     *
     * Two sources, and which one is used matters more than anything else on this
     * screen. The Web API returns a hundred *complete* tracks per request, so a
     * twelve-hundred-track playlist is a dozen requests. The access point
     * answers with URIs and then charges one round trip per track to turn each
     * into a name — the same playlist is twelve hundred lookups, minutes of
     * waiting, and enough of them get dropped that the total came out different
     * on every open. So the Web API is the path whenever the user has connected
     * their application, and the access point is the fallback for when they have
     * not.
     *
     * @param showProgress publish each page as it arrives. Off when there is a
     *   cached list on screen: a finished list must not be replaced by a
     *   growing one.
     */
    private suspend fun loadContextInto(base: PlaylistState, uri: String, showProgress: Boolean) {
        val viaWebApi = runCatching { webApiTracks(base, uri, showProgress) }
            .onFailure { android.util.Log.w(TAG, "web api playlist read failed: ${describe(it)}") }
            .getOrNull()

        val tracks = viaWebApi ?: accessPointTracks(base, uri, showProgress)

        _playlist.value = base.copy(tracks = tracks, loadingMore = false)

        // Stamped with the version it was read from, so the next open can ask
        // one question instead of reading it all again. Without a stamp the
        // entry still saves — it just gets refreshed silently every time.
        val snapshotId = snapshotOf(uri)
        contextCache[uri] = ContextCacheStore.Entry(tracks, snapshotId)
        container.contextCache.write(uri, tracks, snapshotId)
    }

    /** The playlist's current version stamp, or null if it has none to give. */
    private suspend fun snapshotOf(uri: String): String? {
        if (!container.webApi.isReady || !uri.startsWith("spotify:playlist:")) return null
        return runCatching {
            container.api.playlistSnapshot(uri.substringAfterLast(':')).snapshotId
        }.getOrNull()
    }

    /** Null when this is not something the Web API can page through. */
    private suspend fun webApiTracks(
        base: PlaylistState,
        uri: String,
        showProgress: Boolean,
    ): List<CatalogTrack>? {
        if (!container.webApi.isReady || !uri.startsWith("spotify:playlist:")) return null
        val id = uri.substringAfterLast(':')

        val loaded = mutableListOf<CatalogTrack>()
        var offset = 0
        while (true) {
            val page = container.api.playlistTracks(id, limit = WEB_API_PAGE, offset = offset)
            // Episodes and delisted tracks come back as a null track, and
            // `is_playable` is false for anything the relinking could not find a
            // licensed copy of here. Keeping those would put items in the queue
            // that the engine can only skip.
            loaded += page.items.mapNotNull { item ->
                item.track
                    ?.takeIf { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
                    ?.toCatalogTrack(item.addedAt)
            }
            offset += page.items.size
            if (showProgress) {
                _playlist.value = base.copy(
                    tracks = loaded.toList(),
                    loadingMore = offset < page.total,
                )
            }
            if (page.items.isEmpty() || offset >= page.total) return loaded
        }
    }

    private suspend fun accessPointTracks(
        base: PlaylistState,
        uri: String,
        showProgress: Boolean,
    ): List<CatalogTrack> {
        val uris = Catalog.contextTrackUris(uri)
        if (uris.isEmpty()) return emptyList()

        val batches = uris.chunked(METADATA_BATCH)
        val loaded = mutableListOf<CatalogTrack>()
        batches.forEachIndexed { index, batch ->
            loaded += Catalog.tracks(batch)
            if (showProgress) {
                _playlist.value = base.copy(
                    tracks = loaded.toList(),
                    // Counted in batches, not in tracks: a delisted track
                    // resolves to nothing, so the list is legitimately shorter
                    // than the URIs asked for and comparing the two totals left
                    // "more coming" on forever.
                    loadingMore = index < batches.lastIndex,
                )
            }
        }
        return loaded
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

        /** The Web API's own maximum page size for playlist tracks. */
        const val WEB_API_PAGE = 100

        /** How many track lists to keep resolved; see contextCache. */
        const val CONTEXT_CACHE_SIZE = 8

        const val SEARCH_DEBOUNCE_MS = 350L

        /** Spotify acknowledges a transfer before it has taken effect. */
        const val TRANSFER_SETTLE_MS = 700L
    }
}
