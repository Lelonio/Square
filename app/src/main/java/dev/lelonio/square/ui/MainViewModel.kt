package dev.lelonio.square.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.HomeRow
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.R
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.AddTracksRequestDto
import dev.lelonio.square.data.RemoveTracksRequestDto
import dev.lelonio.square.data.TrackUriDto
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.HomeShelf
import dev.lelonio.square.data.SpotifyHome
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.ContextCacheStore
import dev.lelonio.square.data.GatewayPage
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.TransferRequestDto
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.toCatalogTrack
import dev.lelonio.square.data.toResults
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.BuiltInPresets
import dev.lelonio.square.playback.EffectPreset
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
    enum class DetailKind(@StringRes val label: Int) {
        PLAYLIST(R.string.playlist),
        ALBUM(R.string.album),
        ARTIST(R.string.artist),
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
        /** What the source says the list is about; empty when it says nothing. */
        val description: String = "",
        /**
         * Whether this page is in the account's library — followed, for a
         * playlist, saved for an album.
         *
         * Null while unknown, and null forever for the pages where the question
         * does not apply: the button is absent rather than wrong.
         */
        val saved: Boolean? = null,
        /**
         * Whether this playlist is one the listener made.
         *
         * Null while unknown, and null for everything that is not a Spotify
         * playlist. It decides which of the actions in the menu are real:
         * Spotify has no way to delete somebody else's list, and offering it
         * meant offering a button that could only fail. Renaming is the same.
         */
        val mine: Boolean? = null,
        /** Populated for artists only. */
        val albums: List<SearchItem> = emptyList(),
        /** Their singles and EPs, kept apart from the albums above. */
        val singles: List<SearchItem> = emptyList(),
        /** Records that are somebody else's, with them on it. */
        val appearsOn: List<SearchItem> = emptyList(),
        /** The playlists Spotify built around them, "This Is" first. */
        val artistPlaylists: List<SearchItem> = emptyList(),
        /** How many accounts follow them, and what Spotify files them under. */
        val followers: Int = 0,
        val genres: List<String> = emptyList(),
        /**
         * Whether this account follows them.
         *
         * Null while unknown, which is not the same as false: a button drawn as
         * "follow" before the answer arrives is a button that lies for a second
         * and then corrects itself in front of the reader.
         */
        val following: Boolean? = null,
    )

    private val container get() = getApplication<SquareApplication>()

    /** The app's own text, which lives in resources so it can be translated. */
    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<SquareApplication>().getString(id, *args)

    private var inFlight: Job? = null
    private var playlistJob: Job? = null

    private val _state = MutableStateFlow<UiState>(
        if (container.spotifySignedIn) UiState.Connecting else UiState.LoggedOut,
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
        /** On repeat this month. */
        val topTracks: List<CatalogTrack> = emptyList(),
        /** What the account has come back to over the years. */
        val allTimeTracks: List<CatalogTrack> = emptyList(),
        /** Records the artists the account listens to have put out lately. */
        val fromYourArtists: List<SearchItem> = emptyList(),
        /** Playlists and albums the account played last, on any device. */
        val jumpBackIn: List<SearchItem> = emptyList(),
        val loading: Boolean = false,
    )

    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()
    private var feedJob: Job? = null

    /** YouTube Music's own home page, as it laid it out; see [MusicBackend.homeRows]. */
    data class YouTubeHomeState(
        val rows: List<HomeRow> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _youtubeHome = MutableStateFlow(YouTubeHomeState())
    val youtubeHome: StateFlow<YouTubeHomeState> = _youtubeHome.asStateFlow()
    private var youtubeHomeJob: Job? = null

    /**
     * Loads it, once per sign-in state.
     *
     * Re-read after signing in rather than cached for the session: the page is
     * a different page once YouTube knows whose it is, and leaving the
     * signed-out one up would make the login look like it did nothing.
     */
    fun loadYouTubeHome(force: Boolean = false) {
        val backend = container.activeBackend
        if (backend.id != BackendId.YOUTUBE_MUSIC) return
        if (youtubeHomeJob?.isActive == true) return
        if (!force && _youtubeHome.value.rows.isNotEmpty()) return

        _youtubeHome.value = _youtubeHome.value.copy(loading = true, error = null)
        youtubeHomeJob = viewModelScope.launch {
            runCatching { backend.homeRows() }
                .onSuccess { _youtubeHome.value = YouTubeHomeState(rows = it, loading = false) }
                .onFailure {
                    android.util.Log.e(TAG, "youtube home failed: ${chain(it)}", it)
                    _youtubeHome.value = YouTubeHomeState(loading = false, error = describe(it))
                }
        }
    }

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
                        subtitle = string(R.string.artist),
                        artworkUrl = artist.images.firstOrNull()?.url,
                    )
                }
            }.onFailure { android.util.Log.w(TAG, "top artists unavailable: ${describe(it)}") }
                .getOrDefault(emptyList())

            val onRepeat = topTracks("short_term")
            val allTime = topTracks("long_term")

            // Built from the artists the account actually listens to rather
            // than from the catalogue-wide new releases above: those are the
            // same for everyone, and half of them are records the account would
            // never open.
            val fresh = freshFromArtists(artists)

            val jumpBackIn = jumpBackIn()

            _feed.value = FeedState(
                newReleases = releases,
                topArtists = artists,
                topTracks = onRepeat,
                allTimeTracks = allTime,
                fromYourArtists = fresh,
                jumpBackIn = jumpBackIn,
                loading = false,
            )
        }
    }

    private suspend fun topTracks(range: String): List<CatalogTrack> = runCatching {
        container.api.topTracks(timeRange = range).items.map { it.toCatalogTrack() }
    }.onFailure { android.util.Log.w(TAG, "top tracks ($range) unavailable: ${describe(it)}") }
        .getOrDefault(emptyList())

    /**
     * Records from the account's own artists, newest first.
     *
     * One request per artist, so only the first few are asked: this runs on the
     * home page's first load and a dozen round trips would be felt. Anything
     * older than a year is dropped — "new from artists you listen to" that
     * opens on a 2019 album is just a discography.
     */
    private suspend fun freshFromArtists(artists: List<SearchItem>): List<SearchItem> {
        val cutoff = java.time.LocalDate.now().minusMonths(12).toString()
        return artists.take(FRESH_ARTISTS).flatMap { artist ->
            runCatching {
                container.api.artistAlbums(artist.uri.substringAfterLast(':'), limit = 6).items
            }.getOrDefault(emptyList())
        }
            .filter { (it.releaseDate ?: "") >= cutoff }
            .sortedByDescending { it.releaseDate }
            .distinctBy { it.uri }
            .mapNotNull { album ->
                SearchItem(
                    uri = album.uri ?: return@mapNotNull null,
                    title = album.name,
                    subtitle = album.artists.joinToString(", ") { it.name }
                        .ifBlank { album.releaseDate?.take(4).orEmpty() },
                    artworkUrl = album.images.firstOrNull()?.url,
                )
            }
    }

    /**
     * Where the account was listening last, on any device.
     *
     * The history gives the *context* a track was played from but not its name
     * or its cover, and resolving each one would be a request apiece. Albums
     * carry both on the track itself, and a playlist of the account's own is
     * already in the rootlist — so those two are shown and anything else (an
     * editorial playlist, a radio) is left out rather than guessed at.
     */
    private suspend fun jumpBackIn(): List<SearchItem> = runCatching {
        val mine = (_state.value as? UiState.Ready)?.playlists.orEmpty().associateBy { it.uri }
        container.api.recentlyPlayed().items.mapNotNull { play ->
            val context = play.context?.uri
            when {
                context != null && mine.containsKey(context) -> mine[context]?.let {
                    SearchItem(it.uri, it.name, string(R.string.playlist), it.artworkUrl)
                }

                context != null && context.startsWith("spotify:album:") ->
                    play.track.album?.let { album ->
                        SearchItem(
                            uri = context,
                            title = album.name,
                            subtitle = play.track.artists.joinToString(", ") { it.name },
                            artworkUrl = album.images.firstOrNull()?.url,
                        )
                    }

                else -> null
            }
        }.distinctBy { it.uri }.take(FEED_ROW)
    }.onFailure { android.util.Log.w(TAG, "play history unavailable: ${describe(it)}") }
        .getOrDefault(emptyList())

    /** The Spotify Connect device picker. */
    data class DevicesState(
        val open: Boolean = false,
        val loading: Boolean = false,
        /**
         * The device being switched to, while the switch is in flight.
         *
         * A handover takes a moment and used to look like nothing at all, so
         * the row was pressed again and again.
         */
        val switchingTo: String? = null,
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

    init {
        // The list, kept true by the engine rather than by asking.
        //
        // The Web API answers this too, but only when asked, so a sheet left
        // open while playback moved went on showing the old active device until
        // it was closed and opened again. The cluster arrives on its own from
        // the session's own socket, and it is the same list.
        viewModelScope.launch {
            dev.lelonio.square.data.RemoteConnect.devices.collect { cluster ->
                if (cluster.isEmpty()) return@collect
                _devices.value = _devices.value.copy(
                    loading = false,
                    error = null,
                    devices = cluster.map { device ->
                        SpotifyDevice(
                            id = device.id,
                            name = device.name,
                            type = device.type,
                            isActive = device.active,
                        )
                    },
                )
            }
        }
    }

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
                error = string(R.string.needs_your_app),
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
    /**
     * Moves playback to another device.
     *
     * Through the engine when the device came from the cluster, which is the
     * same road the official clients take and needs no registered Web API
     * application. The Web API is the fallback for a list that came from it.
     */
    /**
     * What the player needs in order to carry on here what another device was
     * playing: the same queue, the same track, the same second.
     */
    data class ResumeHere(
        val tracks: List<CatalogTrack>,
        val index: Int,
        val contextUri: String?,
        val positionMs: Long,
    )

    private val _resumeHere = MutableSharedFlow<ResumeHere>(extraBufferCapacity = 1)

    /** Emitted when the listener asks for the music to come back to this phone. */
    val resumeHere = _resumeHere.asSharedFlow()

    /**
     * Brings the account's playback back to this phone.
     *
     * Not a transfer: a device cannot address a command to itself, and taking
     * the session on its own only makes this one active and silent, which is
     * exactly what choosing Square in the list used to do. A handover has to
     * carry the music with it, so the context the other device was playing is
     * reopened here, at the track it was on, at the position it had reached.
     *
     * A device playing something with no context, a single track started from a
     * search, leaves nothing to reopen: the track alone is the queue.
     */
    private fun takeBackPlayback() = viewModelScope.launch {
        // Whatever the account is playing, wherever it thinks it is playing it.
        //
        // Reading only the "somewhere else" half meant the request quietly did
        // nothing whenever the engine had already decided this phone was
        // active: choosing Square answered with silence and no explanation.
        val remote = dev.lelonio.square.data.RemoteConnect.playback.value
            ?: dev.lelonio.square.data.RemoteConnect.here.value
        if (remote == null) {
            android.util.Log.i(TAG, "nothing to bring back: the account is playing nothing")
            return@launch
        }

        android.util.Log.i(
            TAG,
            "taking playback back: ${remote.uri} from ${remote.realContext} at ${remote.positionMs}",
        )

        // The music first, the list afterwards.
        //
        // This used to resolve the whole context before a note was played,
        // which on a long playlist is a second or two in which nothing happens
        // and the listener, quite reasonably, presses the button again. The
        // queue on screen fills itself in from the state this publishes; see
        // PlaybackService.adoptRemoteQueue.
        val started = withContext(Dispatchers.IO) {
            runCatching {
                NativeBridge.resumeHere(
                    remote.realContext.orEmpty(),
                    remote.uri,
                    remote.positionMs.toInt(),
                )
            }.onFailure { android.util.Log.w(TAG, "could not resume here: $it") }.isSuccess
        }
        if (started) return@launch

        // The engine refused it. Fall back to opening the context the long way,
        // which is slower but asks nothing of the Connect layer.
        val context = remote.realContext
        val tracks = runCatching {
            if (context != null) container.activeBackend.tracksOf(context) else emptyList()
        }
            .onFailure { android.util.Log.w(TAG, "context unreadable: ${describe(it)}") }
            .getOrDefault(emptyList())

        val index = tracks.indexOfFirst { it.uri == remote.uri }
        if (tracks.isEmpty() || index < 0) {
            val single = runCatching { container.activeBackend.tracksOf(remote.uri) }
                .getOrDefault(emptyList())
                .ifEmpty { return@launch }
            _resumeHere.emit(ResumeHere(single, 0, null, remote.positionMs))
            return@launch
        }

        _resumeHere.emit(ResumeHere(tracks, index, context, remote.positionMs))
    }

    fun transferPlayback(deviceId: String, positionMs: Long = 0L) = viewModelScope.launch {
        if (_devices.value.switchingTo != null) return@launch
        _devices.value = _devices.value.copy(switchingTo = deviceId)
        try {
            switchTo(deviceId, positionMs)
        } finally {
            _devices.value = _devices.value.copy(switchingTo = null)
        }
    }

    private suspend fun switchTo(deviceId: String, positionMs: Long) {
        val devices = dev.lelonio.square.data.RemoteConnect.devices.value
        android.util.Log.i(TAG, "device chosen: $deviceId, ${devices.size} known")
        // Asked of the engine rather than looked up in the list: the list is
        // filled by cluster updates and is empty until the first one arrives,
        // and a handover chosen in that window took the wrong road entirely.
        if (dev.lelonio.square.data.RemoteConnect.isThisPhone(deviceId)) {
            takeBackPlayback().join()
            return
        }
        // The queue is republished as the playlist it came from first, so
        // whatever carries the handover has something resolvable to carry; see
        // NativeBridge.publishContext.
        if (!dev.lelonio.square.data.RemoteConnect.elsewhereActive.value) {
            val republished = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.publishContext(positionMs.toInt()) }
                    .onFailure { android.util.Log.w(TAG, "context not republished: $it") }
                    .getOrDefault(false)
            }
            // Only when something actually changed does Spotify need a moment
            // to see it. A queue that already carried its playlist goes over at
            // once, which is nearly every handover.
            if (republished) delay(TRANSFER_SETTLE_MS)
        }

        // Spotify's own transfer, when the account has an application to ask it
        // with. The dealer command below is the same word without the state:
        // its `data` field is the playback being moved, and building that is
        // Spotify's job in this call. Sent without it, the device does become
        // active and has nothing to play, which is a progress bar advancing over
        // silence and controls that answer to nothing.
        if (!container.webApi.isReady) {
            withContext(Dispatchers.IO) {
                dev.lelonio.square.data.RemoteConnect.transferTo(deviceId)
            }
            return
        }

        runCatching { container.api.transferPlayback(TransferRequestDto(listOf(deviceId))) }
            .onSuccess {
                // Spotify reports the move a beat after acknowledging it, so the
                // list is re-read rather than edited optimistically.
                delay(TRANSFER_SETTLE_MS)
                // Named, not assumed: a transfer that lands on a device which
                // then sits there paused is the one failure this cannot see
                // from here, and asking the device by name to play costs one
                // request that is harmless when it is playing already.
                runCatching { container.api.play(deviceId) }
                    .onFailure { android.util.Log.i(TAG, "play on $deviceId: ${describe(it)}") }
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
                    string(R.string.track_cannot_be_added)
                !container.webApi.isReady ->
                    string(R.string.connect_app_in_settings)
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

        // Liked Songs is in this list like any other, and is the one entry that
        // is not a playlist: it is the account's library, saved to by its own
        // endpoint. Picking it is what puts the heart on the track, and picking
        // anything else is what puts the tick there.
        val toLibrary = playlist.uri.endsWith(":collection")

        _addToPlaylist.value = current.copy(busy = playlist.uri, done = null, error = null)
        viewModelScope.launch {
            runCatching {
                if (toLibrary) {
                    container.api.saveTracks(trackUri.substringAfterLast(':'))
                } else {
                    container.api.addToPlaylist(id, AddTracksRequestDto(listOf(trackUri)))
                }
            }
                .onSuccess {
                    _addToPlaylist.value =
                        _addToPlaylist.value.copy(busy = null, done = playlist.name)
                    // Known at once, rather than when that playlist is next
                    // read: the tick is about the track, and the track is in a
                    // playlist from this moment.
                    if (toLibrary) _liked.value = _liked.value + trackUri
                    else _inPlaylists.value = _inPlaylists.value + trackUri
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
                        error = string(R.string.add_failed, playlist.name),
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

    private val _search = MutableStateFlow(
        SearchState(
            needsSetup = container.activeBackend.id == BackendId.SPOTIFY &&
                !container.webApi.isReady,
        ),
    )
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /**
     * Runs a search, debounced.
     *
     * Firing on every keystroke would spend the Web API quota several times per
     * word, mostly on prefixes nobody wanted results for.
     */
    fun onSearchQuery(query: String) {
        val backend = container.activeBackend
        // Only Spotify needs a registered application to search; YouTube Music
        // is anonymous, so the setup prompt would be asking for nothing.
        val ready = backend.id != BackendId.SPOTIFY || container.webApi.isReady
        _search.value = _search.value.copy(query = query, needsSetup = !ready)
        searchJob?.cancel()

        if (query.isBlank() || !ready) {
            _search.value = SearchState(query = query, needsSetup = !ready)
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _search.value = _search.value.copy(loading = true, error = null)
            runCatching {
                backend.search(
                    query,
                    SearchLabels(
                        artist = string(R.string.artist),
                        album = string(R.string.album),
                        playlist = string(R.string.playlist),
                    ),
                )
            }
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
        /**
         * Set when Spotify has refused the stored session, so the app can say
         * so and offer the way back in.
         *
         * The client id is already known and does not expire, so signing in
         * again is one tap: nothing has to be pasted or registered a second
         * time.
         */
        val expired: Boolean = false,
    )

    private val _webApi = MutableStateFlow(
        WebApiState(
            clientId = container.webApi.clientId.value.orEmpty(),
            connected = container.webApi.isReady,
        ),
    )
    val webApi: StateFlow<WebApiState> = _webApi.asStateFlow()

    init {
        // Only worth saying while there is a client id to sign in with again:
        // an account that was never connected has its own setup card, and a
        // notice about a session that expired would be about nothing.
        viewModelScope.launch {
            container.webApi.tokens.sessionLost.collect {
                if (container.webApi.clientId.value == null) return@collect
                _webApi.value = _webApi.value.copy(connected = false, expired = true)
            }
        }
    }

    /** The notice is dismissed by hand; search and the feed keep working without it. */
    fun dismissWebApiExpiry() {
        _webApi.value = _webApi.value.copy(expired = false)
    }

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
            _webApi.value = _webApi.value.copy(error = string(R.string.enter_client_id))
            return@launch
        }

        _webApi.value = _webApi.value.copy(connecting = true, error = null, expired = false)
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
                    // Following artists, and the list of the ones followed.
                    "user-follow-read",
                    "user-follow-modify",
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

    /** Locally recorded listening history; see [dev.lelonio.square.data.RecentStore]. */
    /**
     * Recently played, filtered to the source in use.
     *
     * One list on disk, two catalogues drawing from it: a Spotify URI in the
     * YouTube page is a tile that cannot be played, and the other way round.
     * Filtered rather than kept apart so that switching back finds the history
     * still there.
     */
    private val _friends =
        MutableStateFlow<List<dev.lelonio.square.data.FriendListen>>(emptyList())

    /**
     * What the people this account follows are playing.
     *
     * Kept rather than fetched per screen: it is one request, it changes on its
     * own schedule and not on the app's, and the header shows a corner of it
     * whether or not anybody has opened the list.
     */
    val friends: StateFlow<List<dev.lelonio.square.data.FriendListen>> = _friends.asStateFlow()

    /**
     * Reads the list again.
     *
     * Silent on failure by design. This is a private endpoint with no promises
     * attached, an account may follow nobody, and everybody it follows may be
     * listening privately: all three are the same empty list, and none of them
     * is worth an error on the home page.
     */
    fun loadFriends() = viewModelScope.launch {
        if (container.activeBackend.id != BackendId.SPOTIFY) {
            _friends.value = emptyList()
            return@launch
        }
        if (!awaitEngine()) return@launch
        runCatching { dev.lelonio.square.data.FriendActivity.friends() }
            .onSuccess { _friends.value = it }
            .onFailure { android.util.Log.w(TAG, "friend activity unavailable: ${describe(it)}") }
    }

    val recent: StateFlow<List<CatalogTrack>> =
        combine(container.recentStore.tracks, container.preferences.backend) { tracks, _ ->
            tracks.filter { container.activeBackend.owns(it.uri) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Called when a track starts, to keep the home page's history current. */
    fun recordPlayed(track: CatalogTrack) = viewModelScope.launch {
        container.recentStore.record(track)
    }

    init {
        // Everything on screen belongs to one source, so a change of source
        // starts the app's data over rather than patching it: the library, the
        // feed, the search results, the open detail page and the recent list all
        // describe a catalogue that is no longer the one being used.
        viewModelScope.launch {
            container.preferences.backend.drop(1).collect { reload() }
        }

        if (container.activeBackend.id != BackendId.SPOTIFY) {
            // No engine to authenticate, so nothing to send the service.
            refresh()
        } else if (container.spotifySignedIn) {
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
        // The engine's own credential too, or the next launch would log itself
        // back in with it and signing out would mean nothing.
        dev.lelonio.square.auth.EngineCredentials.clear(getApplication())
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

    /** Starts over on the newly chosen source; see the note in `init`. */
    private fun reload() {
        inFlight?.cancel()
        feedJob?.cancel()
        searchJob?.cancel()
        contextCache.clear()
        _playlist.value = PlaylistState()
        _feed.value = FeedState()
        _search.value = SearchState()
        _youtubeHome.value = YouTubeHomeState()
        _state.value = UiState.Loading
        if (container.activeBackend.id == BackendId.SPOTIFY && container.spotifySignedIn) {
            PlaybackService.connect(getApplication())
        }
        refresh()
        if (container.activeBackend.id == BackendId.SPOTIFY) {
            // The home page's own sections, which nothing else asks for again:
            // the screen is already composed, so its one-shot load will not run
            // a second time.
            loadFeed()
        } else {
            loadYouTubeHome(force = true)
        }
    }

    fun refresh(): Job {
        inFlight?.takeIf { it.isActive }?.let { return it }
        return launchRefresh().also { inFlight = it }
    }

    private fun launchRefresh() = viewModelScope.launch {
        val backend = container.activeBackend
        if (backend.id != BackendId.SPOTIFY) {
            // No access point to wait for and no Premium account to check: this
            // backend is usable at once, and signing in only adds the library.
            _state.value = UiState.Loading
            val name = (backend.authState.value as? BackendAuthState.LoggedIn)?.displayName
            runCatching { backend.playlists() }
                .onSuccess { playlists ->
                    _state.value = UiState.Ready(
                        displayName = name.orEmpty(),
                        playlists = playlists,
                    )
                }
                .onFailure {
                    android.util.Log.w(TAG, "youtube library unavailable: ${describe(it)}")
                    // Not a failure worth a whole error screen: the library is
                    // the one part that needs an account, and everything else
                    // on this backend works without one.
                    _state.value = UiState.Ready(displayName = name.orEmpty(), playlists = emptyList())
                }
            return@launch
        }

        if (!container.spotifySignedIn) {
            _state.value = UiState.LoggedOut
            return@launch
        }

        _state.value = UiState.Connecting
        if (!awaitEngine()) {
            _state.value = if (container.spotifySignedIn) {
                UiState.Failed(string(R.string.cannot_connect))
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
                // Spotify's own shelves, once there is a session to ask with.
                loadHomeShelves()
                loadFriends()
                loadFollowedArtists()
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
     * The account's library, as the active backend assembles it.
     *
     * This used to build the Spotify list itself, which is why it went on
     * showing the old one: the backend is where the access point, the Web API
     * fallback and Liked Songs are put together, and a second copy here simply
     * missed everything added there.
     */
    private suspend fun playlists(): List<CatalogPlaylist> =
        container.activeBackend.playlists()

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

    /** The account's own Spotify id, once the profile has been read. */
    private var meId: String? = null

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
                // Kept for the one question the playlist menu asks: is this
                // list mine. The id, not the display name — two accounts can
                // be called the same thing and only one of them owns it.
                meId = profile.id
            }
            .onFailure { android.util.Log.w(TAG, "profile unavailable: ${describe(it)}") }
    }

    /** Playlists in the order this device last opened them; see [PlaylistOrderStore]. */
    val playlistOrder: StateFlow<List<String>> get() = container.playlistOrder.order

    /** Pinned playlists, in the order they were pinned. */
    val pinnedPlaylists: StateFlow<List<String>> get() = container.pinnedPlaylists.pinned

    fun togglePinned(uri: String) = container.pinnedPlaylists.toggle(uri)

    /**
     * A station built from one track, as the official client's radio button does.
     *
     * `spotify:station:track:…` is a context like any other to the access
     * point: Spotify assembles the list and this only has to ask for it. Empty
     * when it will not, and the caller then leaves the queue alone rather than
     * playing something the listener did not choose.
     */
    suspend fun radioFor(trackUri: String): List<CatalogTrack> {
        val id = trackUri.substringAfterLast(':')
        val station = "spotify:station:track:$id"
        return runCatching { Catalog.tracks(Catalog.contextTrackUris(station)) }
            .onFailure { android.util.Log.w(TAG, "no station for $trackUri: ${describe(it)}") }
            .getOrDefault(emptyList())
    }

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
    /**
     * Opens a page the app was handed from outside, by URI alone.
     *
     * A link carries no name and no picture, and a page whose title is blank
     * until its tracks land looks broken rather than busy. The name is asked for
     * first when there is an application to ask with, and the page is opened
     * either way: a list of tracks under no title is still the playlist somebody
     * sent.
     */
    fun openLink(uri: String) = viewModelScope.launch {
        // A Spotify link is a Spotify page. Arriving on the other backend, it
        // would resolve against a catalogue that has never heard of it.
        if (container.activeBackend.id != BackendId.SPOTIFY) {
            container.preferences.setBackend(BackendId.SPOTIFY)
        }
        // No name and no picture: the page asks for both itself, and shows the
        // list without waiting for either.
        openContext(uri, "")
    }

    /**
     * Publishes a detail page without taking back what arrived while it was
     * being built.
     *
     * The page is assembled from a snapshot taken when it opened, and the title
     * and the picture of a page opened by URI alone are not in that snapshot:
     * they are asked for separately and land whenever the network answers. Every
     * later write is built from the same snapshot, so a name that arrived in
     * between was on screen until the next one, which is a title appearing for
     * half a second and vanishing. Neither field is ever cleared here: an empty
     * name and a missing picture mean "not known yet", never "gone".
     */
    private fun publishPlaylist(state: PlaylistState) {
        val current = _playlist.value
        _playlist.value = if (current.uri != state.uri) {
            state
        } else {
            state.copy(
                name = state.name.ifEmpty { current.name },
                artworkUrl = state.artworkUrl ?: current.artworkUrl,
                description = state.description.ifEmpty { current.description },
                saved = state.saved ?: current.saved,
                mine = state.mine ?: current.mine,
            )
        }
    }

    /**
     * The two things one lookup answers: what the list says, and whose it is.
     *
     * One request rather than two. They are asked at the same moment and the
     * playlist endpoint returns both, so splitting them would double the
     * traffic for a page that is already waiting on its tracks.
     */
    private suspend fun detailsOf(uri: String): Pair<String?, Boolean?>? {
        if (!container.webApi.isReady || !uri.startsWith("spotify:playlist:")) return null
        val dto = runCatching { container.api.playlist(uri.substringAfterLast(':')) }
            .onFailure { android.util.Log.w(TAG, "no details for $uri: ${describe(it)}") }
            .getOrNull() ?: return null
        val owner = dto.owner?.id
        // Null rather than false when either side is missing: "not yours" hides
        // the actions, and hiding them because a request came back thin is
        // worse than the button that was there before.
        val mine = if (owner == null || meId == null) null else owner == meId
        val text = dto.description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#x27;", "'")
            ?.replace("&#39;", "'")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return text to mine
    }


    /**
     * Whether a playlist is followed or an album saved.
     *
     * Two endpoints for one idea, because Spotify keeps the two words apart:
     * you follow somebody else's list and you save a record. Null when there is
     * no application to ask with, which leaves the button off the page rather
     * than showing a state nobody checked.
     */
    private suspend fun savedState(uri: String): Boolean? {
        if (!container.webApi.isReady) return null
        val id = uri.substringAfterLast(':')
        return runCatching {
            when {
                uri.startsWith("spotify:album:") ->
                    container.api.albumsAreSaved(id).firstOrNull()

                uri.startsWith("spotify:playlist:") ->
                    container.api.playlistIsFollowed(id, container.api.me().id).firstOrNull()

                else -> null
            }
        }
            .onFailure { android.util.Log.w(TAG, "cannot tell if kept: ${describe(it)}") }
            .getOrNull()
    }

    /**
     * Keeps the open page, or lets it go.
     *
     * The button moves first and is put back if Spotify refuses, for the same
     * reason the follow button does: this is a round trip, and a control that
     * waits for one before acknowledging a tap feels broken even when it works.
     */
    fun toggleSaved() = viewModelScope.launch {
        val page = _playlist.value
        val uri = page.uri ?: return@launch
        val was = page.saved ?: return@launch
        val id = uri.substringAfterLast(':')

        _playlist.value = _playlist.value.copy(saved = !was)
        runCatching {
            when {
                uri.startsWith("spotify:album:") ->
                    if (was) container.api.removeAlbums(id) else container.api.saveAlbums(id)

                else ->
                    if (was) container.api.unfollowPlaylist(id) else container.api.followPlaylist(id)
            }
        }
            .onSuccess {
                // The library is what this button is about, so it is the library
                // that has to show the answer.
                refresh()
            }
            .onFailure {
                android.util.Log.e(TAG, "keeping failed: ${chain(it)}", it)
                if (_playlist.value.uri == uri) {
                    _playlist.value = _playlist.value.copy(saved = was)
                }
            }
    }

    /** A link's own title, from whichever side of Spotify will say. */
    private suspend fun nameOf(uri: String): String? {
        val kind = kindOf(uri)
        val id = uri.substringAfterLast(':')

        if (container.webApi.isReady) {
            runCatching {
                when (kind) {
                    DetailKind.ARTIST -> container.api.artist(id).name
                    DetailKind.ALBUM -> container.api.album(id).name
                    DetailKind.PLAYLIST -> container.api.playlist(id).name
                }
            }
                .onFailure { android.util.Log.w(TAG, "no name for $uri: ${describe(it)}") }
                .getOrNull()
                ?.let { return it }
        }

        // The Web API answers 404 for everything Spotify generates itself — the
        // daily mixes, the editorial lists, the one somebody is most likely to
        // send — and those are exactly the playlists the access point can name.
        if (kind == DetailKind.PLAYLIST && awaitEngine()) {
            return Catalog.playlistName(uri)
        }
        return null
    }

    /**
     * One track, resolved from its URI, for a link that names a song rather
     * than a page.
     */
    suspend fun resolveTrack(uri: String): CatalogTrack? {
        if (!awaitEngine()) return null
        return runCatching { Catalog.tracks(listOf(uri)).firstOrNull() }
            .onFailure { android.util.Log.e(TAG, "link track failed: ${chain(it)}", it) }
            .getOrNull()
    }

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
    private val _homeShelves = MutableStateFlow<List<HomeShelf>>(emptyList())

    /**
     * Spotify's own personalised home: the mixes, "made for you", the shelves
     * that change through the day.
     *
     * Empty is a perfectly good answer. It comes from a private gateway whose
     * queries are addressed by a hash of themselves, and those hashes are
     * retired whenever Spotify rebuilds its web client, so this page has to be
     * one the app can do without. Everything below it is read the old way and
     * does not depend on this at all.
     */
    val homeShelves: StateFlow<List<HomeShelf>> = _homeShelves.asStateFlow()

    private fun loadHomeShelves() = viewModelScope.launch {
        val keys = container.pathfinderKeys
        keys.refresh()
        val shelves = withContext(Dispatchers.IO) {
            runCatching {
                SpotifyHome.parse(
                    NativeBridge.homeFeed(
                        java.util.TimeZone.getDefault().id,
                        java.util.Locale.getDefault().language,
                        keys.home,
                        keys.appVersion,
                    ),
                )
            }
                .onFailure { android.util.Log.i(TAG, "no personalised home: ${describe(it)}") }
                .getOrDefault(emptyList())
        }
        if (shelves.isNotEmpty()) {
            android.util.Log.i(TAG, "home: ${shelves.size} shelves from the gateway")
            _homeShelves.value = shelves
        }
    }

    private val _inPlaylists = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Tracks known to be in one of the account's playlists.
     *
     * Known, not proven. Spotify has no endpoint for "which of my playlists
     * hold this track", and asking would mean reading every playlist on the
     * account to answer a question about one song. This is filled from the
     * playlists the app has already read, which is what it has been looking at,
     * plus whatever is added from inside the app. So a tick means yes; the
     * absence of one means "not that I have seen".
     */
    val inPlaylists: StateFlow<Set<String>> = _inPlaylists.asStateFlow()

    /** Notes the tracks of a playlist that has just been read. */
    private fun rememberMembership(contextUri: String, tracks: List<CatalogTrack>) {
        if (tracks.isEmpty()) return
        // Liked Songs is not one of the playlists, and the player says so with
        // a heart rather than a tick.
        if (contextUri.endsWith(":collection")) {
            _liked.value = _liked.value + tracks.map { it.uri }
            // Just read, so nothing missing from it is saved.
            likedSeeded = true
            likedListTrusted = true
            return
        }
        if (!contextUri.startsWith("spotify:playlist:")) return
        _inPlaylists.value = _inPlaylists.value + tracks.map { it.uri }
    }

    private val _liked = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Tracks known to be in Liked Songs.
     *
     * Read from the list itself wherever possible rather than asked about one
     * track at a time. Spotify will answer "is this saved?" for a single URI,
     * and asking that once a song for as long as the music plays is both a
     * request the app does not need — it has usually read the whole list
     * already — and a pattern nothing but a machine produces. So the copy of
     * Liked Songs on disk answers first, and the network only hears about the
     * tracks it cannot.
     */
    val likedTracks: StateFlow<Set<String>> = _liked.asStateFlow()

    /** Tracks already asked about, saved or not, so each is asked once. */
    private val likedAsked = mutableSetOf<String>()

    /** Waiting to be asked about, so a run of skips is one question. */
    private val likedPending = linkedSetOf<String>()
    private var likedBatch: kotlinx.coroutines.Job? = null

    /** Whether the copy of Liked Songs on disk has been looked at yet. */
    private var likedSeeded = false

    /**
     * Whether that copy is recent enough to answer for itself.
     *
     * When it is, a track missing from the set is simply not saved and there
     * is nothing to ask. When it is not — an old copy, or none — a miss is a
     * question, because the listener may have saved it somewhere else since.
     */
    private var likedListTrusted = false

    fun checkLiked(uri: String?) {
        if (uri == null || !uri.startsWith("spotify:track:")) return
        viewModelScope.launch {
            seedLiked()
            if (uri in _liked.value || likedListTrusted) return@launch
            if (!likedAsked.add(uri)) return@launch

            likedPending += uri
            // A skip is not an answer worth having: while the listener is
            // still moving, the questions pile up and go out together.
            likedBatch?.cancel()
            likedBatch = viewModelScope.launch {
                kotlinx.coroutines.delay(LIKED_BATCH_WAIT_MS)
                val batch = likedPending.toList()
                likedPending.clear()
                askLiked(batch)
            }
        }
    }

    /** Fills the set from the copy of Liked Songs already on disk, at no cost. */
    private suspend fun seedLiked() {
        if (likedSeeded) return
        likedSeeded = true

        val uri = runCatching { withContext(Dispatchers.IO) { NativeBridge.collectionUri() } }
            .getOrNull()
            .orEmpty()
        if (uri.isEmpty()) return

        val entry = contextCache[uri] ?: container.contextCache.read(uri) ?: return
        _liked.value = _liked.value + entry.tracks.map { it.uri }
        likedListTrusted = System.currentTimeMillis() - entry.savedAt < LIKED_TRUSTED_MS
    }

    /**
     * Asks about a handful of tracks at once.
     *
     * The gateway first, as everywhere else: it answers for anyone logged in,
     * and it takes the whole batch in one request. The Web API answers the
     * same question against the listener's own application.
     */
    private suspend fun askLiked(uris: List<String>) {
        if (uris.isEmpty()) return
        val answers = container.gateway.inLibrary(uris) ?: webApiSaved(uris)
        if (answers == null) {
            // Asked again next time they play: this is a decoration.
            likedAsked -= uris.toSet()
            return
        }
        val saved = uris.filterIndexed { index, _ -> answers.getOrNull(index) == true }
        if (saved.isNotEmpty()) _liked.value = _liked.value + saved
    }

    /** The same question through the listener's own application. */
    private suspend fun webApiSaved(uris: List<String>): List<Boolean>? {
        if (!container.webApi.isReady) return null
        return runCatching {
            container.api.tracksAreSaved(uris.joinToString(",") { it.substringAfterLast(':') })
        }
            .onFailure { android.util.Log.i(TAG, "cannot tell what is saved: ${describe(it)}") }
            .getOrNull()
    }

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
        // Reassigned when the picture arrives late; every state published below
        // is built from this, so filling it in one place is what stops a later
        // write from taking the picture back off the screen.
        var base = PlaylistState(
            uri = playlist.uri,
            name = playlist.name,
            artworkUrl = playlist.artworkUrl,
            kind = kind,
        )

        val remembered = contextCache[playlist.uri]
        publishPlaylist(base.copy(
            tracks = remembered?.tracks.orEmpty(),
            loading = remembered == null,
        ))

        playlistJob = viewModelScope.launch {
            // Opened by URI alone, from a tap on the player's text: there was no
            // row to take the picture from. Asked for alongside the track list
            // rather than before it, since the list is what the page is for.
            // Opened by URI alone from outside the app: a link carries no title
            // either. Written through `base` like the picture below, and for the
            // same reason: every state published here is built from it, so a
            // name patched onto the state instead would be taken back off the
            // screen by the next write, which is a title that appears for half a
            // second and vanishes.
            if (base.name.isEmpty()) launch {
                val name = nameOf(playlist.uri) ?: return@launch
                base = base.copy(name = name)
                if (_playlist.value.uri == playlist.uri) {
                    _playlist.value = _playlist.value.copy(name = name)
                }
            }

            // What the list says about itself, when its source says anything.
            // Asked for beside the picture and written the same way, through
            // `base`, so a later publish cannot take it back off the screen.
            if (base.description.isEmpty() && kind == DetailKind.PLAYLIST) launch {
                val details = detailsOf(playlist.uri) ?: return@launch
                val text = details.first
                val mine = details.second
                base = base.copy(description = text.orEmpty(), mine = mine)
                if (_playlist.value.uri == playlist.uri) {
                    _playlist.value = _playlist.value.copy(
                        description = text.orEmpty().ifEmpty { _playlist.value.description },
                        mine = mine,
                    )
                }
            }

            // Whether it is already kept, for the button that keeps it.
            if (kind != DetailKind.ARTIST) launch {
                val kept = savedState(playlist.uri) ?: return@launch
                if (_playlist.value.uri == playlist.uri) {
                    _playlist.value = _playlist.value.copy(saved = kept)
                }
                base = base.copy(saved = kept)
            }

            if (base.artworkUrl == null) launch {
                val art = artworkFor(playlist.uri, kind) ?: return@launch
                base = base.copy(artworkUrl = art)
                if (_playlist.value.uri == playlist.uri) {
                    _playlist.value = _playlist.value.copy(artworkUrl = art)
                }
            }

            // The disk copy, if this run has not opened the playlist yet. Read
            // before anything is asked of the network: the whole point is that a
            // list already known appears at once rather than filling in.
            val entry = remembered ?: container.contextCache.read(playlist.uri)?.also {
                contextCache[playlist.uri] = it
                rememberMembership(playlist.uri, it.tracks)
                publishPlaylist(base.copy(tracks = it.tracks, loading = false))
            }
            val cached = entry?.tracks.orEmpty()

            runCatching {
                if (!playlist.uri.startsWith("spotify:")) {
                    // Another backend's context. Resolved in one call rather
                    // than page by page: the Spotify paths below are built
                    // around the Web API's paging and the access point's
                    // per-track lookups, neither of which exists here.
                    val tracks = container.activeBackend.tracksOf(playlist.uri)
                    publishPlaylist(base.copy(tracks = tracks, loading = false))
                } else if (kind == DetailKind.ARTIST) {
                    val page = loadArtist(playlist.uri)
                    publishPlaylist(
                        base.copy(
                            tracks = page.tracks,
                            albums = page.albums,
                            singles = page.singles,
                            appearsOn = page.appearsOn,
                            artistPlaylists = page.playlists,
                            followers = page.followers,
                            genres = page.genres,
                            following = _playlist.value.following,
                        ),
                    )
                } else if (cached.isNotEmpty() && isUnchanged(playlist.uri, entry?.snapshotId)) {
                    // Nothing to do: one small request said the copy on screen
                    // is the current one.
                    publishPlaylist(base.copy(tracks = cached, loadingMore = false))
                } else {
                    loadContextInto(base, playlist.uri, showProgress = cached.isEmpty())
                }
            }
                .onFailure {
                    android.util.Log.e(TAG, "detail load failed: ${chain(it)}", it)
                    // Only when there is nothing to show. A refresh that fails
                    // over a list already on screen should leave the list.
                    publishPlaylist(
                        if (cached.isEmpty()) base.copy(error = describe(it)) else base.copy(tracks = cached),
                    )
                }
        }
    }

    /**
     * The picture at the top of a page opened by URI alone.
     *
     * The account's own playlists are already in hand, so those cost nothing.
     * Anything else is one request, and a page without a picture is a perfectly
     * good page, so every failure here is silent.
     */
    private suspend fun artworkFor(uri: String, kind: DetailKind): String? {
        (_state.value as? UiState.Ready)?.playlists
            ?.firstOrNull { it.uri == uri }
            ?.artworkUrl
            ?.let { return it }

        val id = uri.substringAfterLast(':')
        if (container.webApi.isReady) {
            val fetched = runCatching {
                when (kind) {
                    DetailKind.ARTIST -> container.api.artist(id).images
                    DetailKind.ALBUM -> container.api.album(id).images
                    DetailKind.PLAYLIST -> container.api.playlist(id).images
                }.firstOrNull()?.url
            }
                .onFailure { android.util.Log.w(TAG, "no cover for $uri: ${describe(it)}") }
                .getOrNull()
            if (fetched != null) return fetched
        }

        // The access point carries the art for the lists Spotify generates,
        // which is where the Web API answers 404.
        if (kind == DetailKind.PLAYLIST) return Catalog.playlistCover(uri)

        // An album's cover is on every one of its tracks, which is the way in
        // when the user has not registered an application of their own.
        if (kind != DetailKind.ALBUM) return null
        return contextCache[uri]?.tracks?.firstNotNullOfOrNull { it.artworkUrl }
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

    /** Whether the active source lets the account make and change playlists. */
    val canEditPlaylists: Boolean get() = container.activeBackend.canEditPlaylists

    /**
     * Makes a playlist and puts it at the top of the library.
     *
     * Inserted locally rather than by refetching: the service takes a moment to
     * list a playlist it has just created, and a new playlist that does not
     * appear reads as the button having failed.
     */
    fun createPlaylist(name: String) = viewModelScope.launch {
        runCatching { container.activeBackend.createPlaylist(name.trim()) }
            .onSuccess { created ->
                val ready = _state.value as? UiState.Ready ?: return@onSuccess
                _state.value = ready.copy(playlists = listOf(created) + ready.playlists)
            }
            .onFailure { android.util.Log.e(TAG, "create playlist failed: ${chain(it)}", it) }
    }

    fun renamePlaylist(uri: String, name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        // Renamed on screen first: this is one field on a row, the failure is
        // rare, and putting the old name back is all an undo needs to be.
        val ready = _state.value as? UiState.Ready
        val before = ready?.playlists
        if (ready != null) {
            _state.value = ready.copy(
                playlists = ready.playlists.map {
                    if (it.uri == uri) it.copy(name = trimmed) else it
                },
            )
        }
        if (_playlist.value.uri == uri) {
            _playlist.value = _playlist.value.copy(name = trimmed)
        }

        runCatching { container.activeBackend.renamePlaylist(uri, trimmed) }
            .onFailure {
                android.util.Log.e(TAG, "rename playlist failed: ${chain(it)}", it)
                val current = _state.value as? UiState.Ready
                if (before != null && current != null) {
                    _state.value = current.copy(playlists = before)
                }
            }
        invalidateContext(uri)
    }

    fun deletePlaylist(uri: String) = viewModelScope.launch {
        val ready = _state.value as? UiState.Ready
        val before = ready?.playlists
        if (ready != null) {
            _state.value = ready.copy(playlists = ready.playlists.filterNot { it.uri == uri })
        }

        runCatching { container.activeBackend.deletePlaylist(uri) }
            .onFailure {
                android.util.Log.e(TAG, "delete playlist failed: ${chain(it)}", it)
                val current = _state.value as? UiState.Ready
                if (before != null && current != null) {
                    _state.value = current.copy(playlists = before)
                }
            }
        invalidateContext(uri)
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
        val paged = runCatching { pagedTracks(base, uri, showProgress) }
            .onFailure { android.util.Log.w(TAG, "paged read failed: ${describe(it)}") }
            .getOrNull()

        val tracks = paged ?: accessPointTracks(base, uri, showProgress)

        publishPlaylist(base.copy(tracks = tracks, loadingMore = false))

        // Stamped with the version it was read from, so the next open can ask
        // one question instead of reading it all again. Without a stamp the
        // entry still saves — it just gets refreshed silently every time.
        val snapshotId = snapshotOf(uri)
        contextCache[uri] = ContextCacheStore.Entry(tracks, snapshotId)
        rememberMembership(uri, tracks)
        container.contextCache.write(uri, tracks, snapshotId)
    }

    /** The playlist's current version stamp, or null if it has none to give. */
    private suspend fun snapshotOf(uri: String): String? {
        if (!container.webApi.isReady || !uri.startsWith("spotify:playlist:")) return null
        return runCatching {
            container.api.playlistSnapshot(uri.substringAfterLast(':')).snapshotId
        }.getOrNull()
    }

    /**
     * A playlist or the saved tracks, paged.
     *
     * Spotify's gateway first and the Web API behind it, for both. The gateway
     * is the endpoint the web player itself reads and it answers for anyone
     * logged in, two hundred whole tracks a request; the Web API is metered
     * against an application the listener has to register for themselves, and
     * gives a hundred. So the Web API is what is left when a query hash has
     * been retired, rather than the way in.
     *
     * Null when this is neither of those — an album, a list from the access
     * point — which is the caller's cue to resolve it the other way.
     */
    private suspend fun pagedTracks(
        base: PlaylistState,
        uri: String,
        showProgress: Boolean,
    ): List<CatalogTrack>? {
        if (uri.endsWith(":collection")) {
            gatewayTracks(base, showProgress) { offset ->
                container.gateway.savedTracks(offset)
            }?.let { return it }
            // Nothing else knows this list: the access point refuses the
            // collection as a context, so without the gateway and without a
            // registered application there is no answer to give.
            check(container.webApi.isReady) { string(R.string.liked_songs_failed) }
            return webApiSavedTracks(base, showProgress)
        }

        if (!uri.startsWith("spotify:playlist:")) return null
        gatewayTracks(base, showProgress) { offset ->
            container.gateway.playlistTracks(uri, offset)
        }?.let { return it }
        if (!container.webApi.isReady) return null
        return webApiPlaylistTracks(base, uri, showProgress)
    }

    /** The shared gateway reader, with each page published as it lands. */
    private suspend fun gatewayTracks(
        base: PlaylistState,
        showProgress: Boolean,
        page: suspend (offset: Int) -> GatewayPage?,
    ): List<CatalogTrack>? = container.gateway.readAll(
        onPage = if (showProgress) {
            { tracks, more -> publishPlaylist(base.copy(tracks = tracks, loadingMore = more)) }
        } else {
            null
        },
        page = page,
    )

    /** A playlist through the listener's own application; see [pagedTracks]. */
    private suspend fun webApiPlaylistTracks(
        base: PlaylistState,
        uri: String,
        showProgress: Boolean,
    ): List<CatalogTrack> {
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
                publishPlaylist(base.copy(
                    tracks = loaded.toList(),
                    loadingMore = offset < page.total,
                ))
            }
            if (page.items.isEmpty() || offset >= page.total) return loaded
        }
    }

    /** Liked Songs through the listener's own application; see [pagedTracks]. */
    private suspend fun webApiSavedTracks(
        base: PlaylistState,
        showProgress: Boolean,
    ): List<CatalogTrack> {
        val loaded = mutableListOf<CatalogTrack>()
        var offset = 0
        while (true) {
            val page = container.api.savedTracks(limit = WEB_API_LIBRARY_PAGE, offset = offset)
            // The same rule as a playlist's pages: anything the engine could
            // only skip has no business being in the queue.
            loaded += page.items.mapNotNull { saved ->
                saved.track
                    .takeIf { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
                    ?.toCatalogTrack(saved.addedAt)
            }
            offset += page.items.size
            if (showProgress) {
                publishPlaylist(base.copy(
                    tracks = loaded.toList(),
                    loadingMore = offset < page.total,
                ))
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
                publishPlaylist(base.copy(
                    tracks = loaded.toList(),
                    // Counted in batches, not in tracks: a delisted track
                    // resolves to nothing, so the list is legitimately shorter
                    // than the URIs asked for and comparing the two totals left
                    // "more coming" on forever.
                    loadingMore = index < batches.lastIndex,
                ))
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
    /** Everything an artist page shows, gathered in one go. */
    private data class ArtistPage(
        val tracks: List<CatalogTrack>,
        val albums: List<SearchItem>,
        val singles: List<SearchItem>,
        val appearsOn: List<SearchItem>,
        val playlists: List<SearchItem>,
        val followers: Int,
        val genres: List<String>,
    )

    private suspend fun loadArtist(uri: String): ArtistPage {
        if (!container.webApi.isReady) {
            error(string(R.string.artist_needs_app))
        }
        val id = uri.substringAfterLast(':')
        val tracks = container.api.artistTopTracks(id).tracks.map { it.toCatalogTrack() }

        // One request for every kind of record rather than three. Spotify says
        // which shelf each one belongs on, and asking per shelf would spend the
        // account's quota three times for the same answer.
        val releases = container.api.artistAlbums(
            id,
            groups = "album,single,compilation,appears_on",
            limit = 50,
        ).items

        fun shelf(vararg groups: String): List<SearchItem> = releases
            .filter { it.albumGroup in groups }
            .mapNotNull { album ->
                SearchItem(
                    uri = album.uri ?: return@mapNotNull null,
                    title = album.name,
                    subtitle = album.releaseDate?.take(4).orEmpty(),
                    artworkUrl = album.images.firstOrNull()?.url,
                )
            }
            // The same record is often listed several times, once per market.
            .distinctBy { it.title.lowercase() }
            .sortedByDescending { it.subtitle }

        // Not fatal, either of them. The page is worth showing without the
        // number under the name, and an account whose application predates the
        // follow permissions still gets everything else.
        val artist = runCatching { container.api.artist(id) }.getOrNull()
        val following = runCatching { container.api.isFollowing(ids = id).firstOrNull() }
            .onFailure { android.util.Log.w(TAG, "cannot tell if followed: ${describe(it)}") }
            .getOrNull()

        if (following != null) {
            _playlist.value = _playlist.value.let {
                if (it.uri == uri) it.copy(following = following) else it
            }
        }

        return ArtistPage(
            playlists = artistPlaylists(artist?.name.orEmpty()),
            tracks = tracks,
            albums = shelf("album", "compilation"),
            singles = shelf("single"),
            appearsOn = shelf("appears_on"),
            followers = artist?.followers?.total ?: 0,
            genres = artist?.genres.orEmpty(),
        )
    }

    /**
     * The playlists Spotify built around one artist.
     *
     * There is no endpoint for this. The desktop client reads it from the
     * gateway that answers the personalised pages, addressed by a hash of a
     * query this app does not have, so the way in is the search: "This Is" and
     * the rest are ordinary public playlists, and what makes them Spotify's own
     * rather than a stranger's copy is the owner.
     *
     * Named after the artist too, because searching a name returns everything
     * anybody ever titled after them.
     */
    private suspend fun artistPlaylists(name: String): List<SearchItem> {
        if (name.isEmpty()) return emptyList()
        val needle = name.lowercase()
        return runCatching {
            container.api.search(
                query = "\"This Is $name\"",
                type = "playlist",
                limit = 20,
            ).playlists?.items.orEmpty()
                .filterNotNull()
                .filter { it.owner?.id == "spotify" && it.name.lowercase().contains(needle) }
                .map { playlist ->
                    SearchItem(
                        uri = playlist.uri,
                        title = playlist.name,
                        subtitle = playlist.description.orEmpty(),
                        artworkUrl = playlist.images.firstOrNull()?.url,
                    )
                }
                // "This Is" first when it is there: it is the one everybody
                // means by "the artist's playlist".
                .sortedBy { if (it.title.lowercase().startsWith("this is")) 0 else 1 }
                .take(10)
        }
            .onFailure { android.util.Log.w(TAG, "no playlists for $name: ${describe(it)}") }
            .getOrDefault(emptyList())
    }

    /**
     * Follows or unfollows the artist whose page is open.
     *
     * The button moves first and is put back if Spotify refuses: this is one
     * request over a network, and a control that waits for a round trip before
     * acknowledging a tap feels broken even when it works.
     */
    fun toggleFollowArtist() = viewModelScope.launch {
        val page = _playlist.value
        val uri = page.uri ?: return@launch
        if (page.kind != DetailKind.ARTIST) return@launch
        val was = page.following ?: false
        val id = uri.substringAfterLast(':')

        _playlist.value = _playlist.value.copy(following = !was)
        runCatching {
            if (was) container.api.unfollowArtists(ids = id) else container.api.followArtists(ids = id)
        }
            .onSuccess { loadFollowedArtists() }
            .onFailure {
                android.util.Log.e(TAG, "follow failed: ${chain(it)}", it)
                if (_playlist.value.uri == uri) {
                    _playlist.value = _playlist.value.copy(following = was)
                }
                // Almost always the same cause: an application connected before
                // this feature existed, whose token carries no permission to
                // follow anybody. Saying so is more use than the error itself.
                _webApi.value = _webApi.value.copy(expired = true)
            }
    }

    private val _followedArtists = MutableStateFlow<List<SearchItem>>(emptyList())

    /** The artists this account follows, for the library. */
    val followedArtists: StateFlow<List<SearchItem>> = _followedArtists.asStateFlow()

    fun loadFollowedArtists() = viewModelScope.launch {
        if (!container.webApi.isReady || container.activeBackend.id != BackendId.SPOTIFY) {
            _followedArtists.value = emptyList()
            return@launch
        }
        runCatching {
            val gathered = mutableListOf<SearchItem>()
            var after: String? = null
            // Cursor-paged, and an account can follow hundreds: walk it to the
            // end rather than showing the first page and calling it the list.
            while (true) {
                val page = container.api.followedArtists(after = after).artists
                gathered += page.items.mapNotNull { artist ->
                    SearchItem(
                        uri = artist.uri ?: return@mapNotNull null,
                        title = artist.name,
                        subtitle = "",
                        artworkUrl = artist.images.firstOrNull()?.url,
                    )
                }
                after = page.cursors?.after?.takeIf { page.items.isNotEmpty() } ?: break
            }
            gathered.sortedBy { it.title.lowercase() }
        }
            .onSuccess { _followedArtists.value = it }
            .onFailure {
                android.util.Log.w(TAG, "followed artists unavailable: ${describe(it)}")
                // 403 here has one cause: an application connected before the
                // follow permissions were asked for. The token is valid and
                // will never be allowed to read this, so the way out is the
                // same one an expired session takes — sign in again, once.
                if (describe(it).contains("403")) {
                    _webApi.value = _webApi.value.copy(expired = true)
                }
            }
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
            if (!container.spotifySignedIn) return@withTimeoutOrNull false
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
        const val TAG = "SquareUi"

        /** Artists asked for new records on the home page; one request each. */
        const val FRESH_ARTISTS = 6

        /** How long a home row gets before it stops being worth scrolling. */
        const val FEED_ROW = 12

        const val ENGINE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 250L

        /** Each track is its own access-point round trip. */
        /** Tracks resolved per access-point round trip; see loadContextInto. */
        const val METADATA_BATCH = 100

        /** The Web API's own maximum page size for playlist tracks. */
        const val WEB_API_PAGE = 100

        /**
         * And what the account's own lists take, which is half that: `me/tracks`
         * answers 400 to anything larger rather than an empty page.
         */
        const val WEB_API_LIBRARY_PAGE = 50

        /**
         * How long the copy of Liked Songs on disk answers for the whole list.
         *
         * Long enough that a listening session costs nothing, short enough
         * that a track saved on another device is not denied all day.
         */
        const val LIKED_TRUSTED_MS = 6L * 60 * 60 * 1000

        /** How long a question about a track waits for the next one. */
        const val LIKED_BATCH_WAIT_MS = 400L

        /** How many track lists to keep resolved; see contextCache. */
        const val CONTEXT_CACHE_SIZE = 8

        const val SEARCH_DEBOUNCE_MS = 350L

        /** Spotify acknowledges a transfer before it has taken effect. */
        const val TRANSFER_SETTLE_MS = 700L
    }
}
