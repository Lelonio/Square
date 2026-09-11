package dev.lelonio.square.ui

import coil.imageLoader
import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.HomeChip
import dev.lelonio.square.backend.HomeRow
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.R
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.DownloadStore
import dev.lelonio.square.download.DownloadService
import dev.lelonio.square.data.AddTracksRequestDto
import dev.lelonio.square.data.RemoveTracksRequestDto
import dev.lelonio.square.data.TrackUriDto
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.HomeShelf
import dev.lelonio.square.data.SpotifyHome
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.ContextCacheStore
import dev.lelonio.square.data.GatewayPage
import dev.lelonio.square.data.LocalLibrary
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.TransferRequestDto
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.toCatalogTrack
import dev.lelonio.square.data.toResults
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.BuiltInPresets
import dev.lelonio.square.playback.EffectPreset
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /**
     * One record, said the way the artist page's top card says it.
     *
     * Its own type rather than a [SearchItem] because the card leads with the
     * date and counts the songs, and neither fits in a subtitle line.
     */
    data class ArtistRelease(
        val uri: String,
        val title: String,
        val artworkUrl: String?,
        /** ISO-8601 as Spotify gives it: a full date, a month, or a year. */
        val releaseDate: String,
        val trackCount: Int,
    )

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
        /**
         * The record they put out last, for the card under the artist's photo.
         *
         * Null on every page that is not an artist, and on an artist whose
         * discography came back empty — the card is then simply absent rather
         * than a row of placeholders.
         */
        /**
         * The picture the page opens with, when there is an editorial one.
         *
         * Apple's catalogue keeps a full-height photograph for artists and for
         * some records; where it exists the page is that photograph, and where
         * it does not the page is the square cover — which is the difference
         * between the two kinds of header the reference shows.
         */
        val heroUrl: String? = null,
        /**
         * The sleeve as Apple scanned it, where the page has one.
         *
         * Beside [artworkUrl] rather than replacing it: that one is the cover
         * this app downloads, caches and colours pages from, and swapping the
         * source under it would have every kept playlist fetch its cover again.
         * This is only what the header draws.
         */
        val coverUrl: String? = null,
        /** What Apple's editors wrote about the record; see AppleCatalog. */
        val notes: String? = null,
        /** The page's tint as six hex digits, where the catalogue names one. */
        val tintHex: String? = null,
        /** The ink the catalogue picked for that tint; see AppleCatalog. */
        val inkHex: String? = null,
        /** The header picture's own proportions, width over height. */
        val heroAspect: Float? = null,
        /**
         * Whose list it is: the account that made a playlist.
         *
         * An album's is taken from its own tracks and does not need a request,
         * so only a playlist fills this in; see openPlaylist.
         */
        val byline: String = "",
        /** Where an artist is from, or when the band was formed. */
        val origin: String? = null,
        /** The cover as a moving picture, where the label made one. */
        val motionUrl: String? = null,
        /**
         * The other catalogue has been asked and has not answered yet.
         *
         * The header holds its picture back while this is true. Spotify's cover
         * is already in hand and could be drawn at once, but on a page that is
         * about to have a different photograph that only means opening on the
         * wrong one and swapping it out in front of the reader. The wait is
         * capped; see dressPage.
         */
        val heroPending: Boolean = false,
        /** The artist's name drawn in their own face; see AppleCatalog. */
        val logoUrl: String? = null,
        val latest: ArtistRelease? = null,
        /**
         * Whether that record is already in the library.
         *
         * Null while unknown, and the "+" on the card is then absent — the same
         * rule the save button on every other page follows.
         */
        val latestSaved: Boolean? = null,
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
        /**
         * Empty because nobody has been allowed to look, rather than because
         * there is nothing there.
         *
         * Only the phone's own music can be in this state; see openLocalFiles.
         */
        val needsPermission: Boolean = false,
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

    /**
     * What is out, arranged the way a "new" page is read.
     *
     * Separate from [FeedState] and loaded only when the tab is opened: the
     * home page needs a dozen records for one shelf, and this needs forty of
     * them split by date plus a song out of each of the first few. Making the
     * home feed carry all that would spend it on every cold start for a page
     * most sessions never open.
     */
    data class NewPage(
        /** The few records the page leads with, drawn large. */
        val hero: List<SearchItem> = emptyList(),
        /** Out in the last seven days. */
        val thisWeek: List<SearchItem> = emptyList(),
        /** Everything else Spotify calls new, newest first. */
        val recent: List<SearchItem> = emptyList(),
        /** One song out of each of the first few records above. */
        val songs: List<CatalogTrack> = emptyList(),
        val loading: Boolean = false,
    )

    private val _newPage = MutableStateFlow(NewPage())
    val newPage: StateFlow<NewPage> = _newPage.asStateFlow()
    private var newPageJob: Job? = null

    /**
     * Fills the new-releases tab, once.
     *
     * Reloaded only when it is empty: records do not come out while somebody is
     * switching tabs, and rebuilding the page under the reader every time they
     * come back to it would move what they were about to tap.
     */
    fun loadNewPage() {
        if (!container.webApi.isReady || newPageJob?.isActive == true) return
        if (_newPage.value.recent.isNotEmpty() || _newPage.value.thisWeek.isNotEmpty()) return

        _newPage.value = _newPage.value.copy(loading = true)
        newPageJob = viewModelScope.launch {
            // Three pages rather than one. Fifty is what this endpoint will
            // answer with at a time, and one page of it is two shelves' worth
            // on a page that is meant to be browsed — the whole complaint about
            // this tab was that it ended almost as soon as it started.
            val albums = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                    (0 until NEW_PAGES).map { page ->
                        async {
                            container.api
                                .newReleases(limit = NEW_RELEASES, offset = page * NEW_RELEASES)
                                .albums?.items.orEmpty()
                        }
                    }.awaitAll().flatten()
                    }
                }
            }
                .onFailure { android.util.Log.w(TAG, "new releases unavailable: ${describe(it)}") }
                .getOrDefault(emptyList())
                .distinctBy { it.uri }
                .sortedByDescending { it.releaseDate.orEmpty() }

            val week = java.time.LocalDate.now().minusDays(7).toString()
            fun item(album: dev.lelonio.square.data.AlbumDto): SearchItem? = SearchItem(
                uri = album.uri ?: return null,
                title = album.name,
                subtitle = album.artists.joinToString(", ") { it.name },
                artworkUrl = album.images.firstOrNull()?.url,
            )

            val thisWeek = albums.filter { (it.releaseDate ?: "") >= week }.mapNotNull(::item)
            val rest = albums.filter { (it.releaseDate ?: "") < week }.mapNotNull(::item)

            // The records by the artists this account listens to lead the page,
            // because "new" without "to you" is a catalogue. The global list is
            // the fallback for an account too young to have artists of its own.
            val mine = _feed.value.fromYourArtists
            // Turned over daily, like the radio page's stations: the six the
            // page leads with are drawn from a list that changes slowly, and
            // showing the same six until it does made a page that is supposed
            // to be about what is out look like it had stopped.
            val leading = (if (mine.isNotEmpty()) mine else thisWeek + rest)
            val hero = leading.turnedDaily().take(HERO_RELEASES)

            _newPage.value = NewPage(
                hero = hero,
                thisWeek = thisWeek,
                recent = rest,
                loading = false,
            )

            // And a song out of each of the first few, which is the one part of
            // the page that has to be heard rather than looked at. Resolved
            // through the access point rather than the Web API: this is a track
            // list for a context, which is exactly what that side answers, and
            // it does not come out of the account's own quota.
            if (!awaitEngine()) return@launch
            val songs = withContext(Dispatchers.IO) {
                coroutineScope {
                (thisWeek + rest).turnedDaily().take(NEW_SONGS).map { album ->
                    async {
                        runCatching {
                            Catalog.tracks(Catalog.contextTrackUris(album.uri).take(1)).firstOrNull()
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
                }
            }
            _newPage.value = _newPage.value.copy(songs = songs)
        }
    }

    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()
    private var feedJob: Job? = null

    /** YouTube Music's own home page, as it laid it out; see [MusicBackend.homeFeed]. */
    data class YouTubeHomeState(
        val rows: List<HomeRow> = emptyList(),
        val chips: List<HomeChip> = emptyList(),
        /** Which filter is on, by title; null is the home page itself. */
        val chip: String? = null,
        val loading: Boolean = false,
        /** A page after the first, on its way. */
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** Where the next shelves are; null when the page has run out. */
        val cursor: String? = null,
    ) {
        /** True while there are more shelves to ask for. */
        val hasMore: Boolean get() = cursor != null
    }

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

        youtubeParams = null
        _youtubeHome.value = _youtubeHome.value.copy(loading = true, error = null, chip = null)
        youtubeHomeJob = viewModelScope.launch { readYouTubeHome(replace = true) }
    }

    /**
     * The next shelves, asked for as the page runs out.
     *
     * The home page is served four or five shelves at a time and the list is
     * what says when more are wanted, so this is called from the bottom of it
     * rather than on a timer: what the listener never scrolls to is never
     * fetched.
     */
    fun loadMoreYouTubeHome() {
        val state = _youtubeHome.value
        if (!state.hasMore || state.loading || state.loadingMore) return
        if (youtubeHomeJob?.isActive == true) return

        _youtubeHome.value = state.copy(loadingMore = true)
        youtubeHomeJob = viewModelScope.launch { readYouTubeHome(replace = false) }
    }

    /**
     * Picks one of the filters above the page, or clears it.
     *
     * The chips are the service's own views of the same home — "Relax",
     * "Workout" — and each is a page in its own right, continuation and all,
     * so choosing one starts over rather than filtering what is already here.
     */
    fun selectYouTubeChip(chip: HomeChip?) {
        if (chip?.title == _youtubeHome.value.chip) return
        youtubeHomeJob?.cancel()
        youtubeParams = chip?.params
        _youtubeHome.value = _youtubeHome.value.copy(
            rows = emptyList(),
            chip = chip?.title,
            loading = true,
            loadingMore = false,
            error = null,
        )
        youtubeHomeJob = viewModelScope.launch { readYouTubeHome(replace = true) }
    }

    private var youtubeParams: String? = null

    private suspend fun readYouTubeHome(replace: Boolean) {
        val backend = container.activeBackend
        val before = _youtubeHome.value
        runCatching {
            backend.homeFeed(
                cursor = if (replace) null else before.cursor,
                params = youtubeParams,
            )
        }
            .onSuccess { feed ->
                val rows = if (replace) feed.rows else before.rows + feed.rows
                _youtubeHome.value = before.copy(
                    rows = rows,
                    // A continuation carries no chips, so the ones already on
                    // screen stay where they are.
                    chips = feed.chips.ifEmpty { before.chips },
                    loading = false,
                    loadingMore = false,
                    error = null,
                    cursor = feed.cursor,
                )
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.e(TAG, "youtube home failed: ${chain(it)}", it)
                _youtubeHome.value = before.copy(
                    loading = false,
                    loadingMore = false,
                    // A page that failed after the first leaves what is already
                    // read on screen: the shelves above it are still good.
                    error = if (before.rows.isEmpty()) describe(it) else null,
                    cursor = if (replace) null else before.cursor,
                )
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

    /**
     * The same list, started at a different place each day.
     *
     * Not a shuffle: a page that rearranges itself every time it is opened is
     * not richer, it is unreadable. This moves the starting point once a day,
     * so a list that Spotify itself only recomputes weekly still shows
     * something different when the tab is opened tomorrow.
     */
    private fun <T> List<T>.turnedDaily(): List<T> {
        if (size < 2) return this
        val day = java.time.LocalDate.now().toEpochDay().toInt()
        val from = Math.floorMod(day * DAILY_TURN, size)
        return drop(from) + take(from)
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
        // All of them at once. One request per artist is what this costs
        // whatever happens, but asked in turn a dozen of them is several
        // seconds of the home page waiting on a shelf that is not even the
        // first thing on it.
        return coroutineScope {
            artists.take(FRESH_ARTISTS).map { artist ->
                async(Dispatchers.IO) {
                    runCatching {
                        container.api.artistAlbums(
                            artist.uri.substringAfterLast(':'),
                            limit = 6,
                        ).items
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
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
        // Offline and back, without a restart in between.
        //
        // The library and the home page are each built once, from whichever
        // source was available at the time, and nothing rebuilt them when that
        // changed: a phone that found signal again went on showing the handful
        // of downloaded playlists, and one that lost it went on offering rows
        // it could no longer fetch. Both directions are the same event, so both
        // are handled here.
        //
        // The current value is skipped — the first load is already on its way
        // when this starts, and running it twice would be two libraries built
        // at once.
        viewModelScope.launch {
            dev.lelonio.square.playback.OfflineMode.active
                .drop(1)
                .distinctUntilChanged()
                .collect { offline ->
                    android.util.Log.i(TAG, "offline changed to $offline: rebuilding")
                    if (offline) {
                        // What needed a connection goes now rather than staying
                        // as rows that answer nothing when tapped.
                        _feed.value = FeedState()
                        _homeShelves.value = emptyList()
                        _friends.value = emptyList()
                    }
                    // Waited for, not merely started. The library load is
                    // what waits for the session to come back up, and
                    // everything below reads that session: fired alongside it
                    // they all asked while the handshake was still in flight,
                    // failed quietly, and were never tried again — which is why
                    // coming back online left the app's own shelves on the home
                    // page and none of Spotify's.
                    refresh().join()
                    if (!offline) {
                        loadFeed()
                        loadHomeShelves()
                        loadFriends()
                    }
                }
        }

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
        /** And of the one it just came out of, which only Liked Songs can be. */
        val removed: String? = null,
        /**
         * Whether the track is already in Liked Songs.
         *
         * The one row in this list whose answer is known, and the one that can
         * be undone: picking it again takes the track back out.
         */
        val liked: Boolean = false,
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
            liked = trackUri != null && trackUri in _liked.value,
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
        // Picking Liked Songs when the track is already there takes it out
        // again: the heart is the one mark in this list that can be undone,
        // and pressing it twice is how everything else undoes a like.
        val unsaving = toLibrary && current.liked

        _addToPlaylist.value =
            current.copy(busy = playlist.uri, done = null, removed = null, error = null)
        viewModelScope.launch {
            runCatching {
                when {
                    unsaving -> container.api.removeSavedTracks(trackUri.substringAfterLast(':'))
                    toLibrary -> container.api.saveTracks(trackUri.substringAfterLast(':'))
                    else -> container.api.addToPlaylist(id, AddTracksRequestDto(listOf(trackUri)))
                }
            }
                .onSuccess {
                    _addToPlaylist.value = _addToPlaylist.value.copy(
                        busy = null,
                        done = if (unsaving) null else playlist.name,
                        removed = if (unsaving) playlist.name else null,
                        liked = toLibrary && !unsaving,
                    )
                    // Known at once, rather than when that playlist is next
                    // read: the tick is about the track, and the track is in a
                    // playlist from this moment.
                    if (unsaving) _liked.value = _liked.value - trackUri
                    else if (toLibrary) _liked.value = _liked.value + trackUri
                    else _inPlaylists.value = _inPlaylists.value + trackUri
                    // The detail screen holds a list resolved before this track
                    // was in it; if that is the playlist just written to, read
                    // it again.
                    invalidateContext(playlist.uri)
                    if (_playlist.value.uri == playlist.uri) openPlaylist(playlist)
                }
                .onFailure {
                    android.util.Log.e(TAG, "add to playlist failed: ${chain(it)}", it)
                    // The same 403 the followed artists take, for the same
                    // reason: an application connected before a permission was
                    // asked for holds a token that will never be allowed to do
                    // this. Saying the playlist is not yours would be a lie
                    // about Liked Songs, which is nobody else's.
                    val forbidden = describe(it).contains("403")
                    if (forbidden) _webApi.value = _webApi.value.copy(expired = true)
                    _addToPlaylist.value = _addToPlaylist.value.copy(
                        busy = null,
                        error = when {
                            forbidden -> string(R.string.permission_needed)
                            unsaving -> string(R.string.unlike_failed)
                            toLibrary -> string(R.string.like_failed)
                            // A playlist the account follows but does not own is
                            // the one failure worth naming: it looks identical
                            // to the user's own in every list the app draws.
                            else -> string(R.string.add_failed, playlist.name)
                        },
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
         * Set when a search had nowhere to go, so the screen can offer the way
         * out instead of an empty page.
         *
         * Rare now: Spotify's own gateway answers anyone who is signed in, and
         * only a retired query hash sends a search back to the registered
         * application that used to be the only way in.
         */
        val needsSetup: Boolean = false,
        /** A second helping of the same search, on its way. */
        val loadingMore: Boolean = false,
        /** Set once a page comes back with nothing new to add. */
        val exhausted: Boolean = false,
    )

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /** How far into the results the next page starts. */
    private var searchOffset = 0
    private var searchMoreJob: Job? = null

    /**
     * Runs a search, debounced.
     *
     * Firing on every keystroke would spend the Web API quota several times per
     * word, mostly on prefixes nobody wanted results for.
     */
    fun onSearchQuery(query: String) {
        val backend = container.activeBackend
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()

        searchMoreJob?.cancel()
        searchOffset = 0

        if (query.isBlank()) {
            _search.value = SearchState(query = query)
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
                    // The answer to a question nobody is asking any more is
                    // not shown: a slow search that was replaced while it ran
                    // would otherwise land on top of the newer one.
                    currentCoroutineContext().ensureActive()
                    searchOffset = SEARCH_PAGE
                    _search.value = _search.value.copy(
                        loading = false,
                        results = results,
                        needsSetup = backend.searchNeedsSetup,
                        loadingMore = false,
                        exhausted = results.isEmpty,
                    )
                }
                .onFailure {
                    // A cancelled search is not a failed one. Every keystroke
                    // cancels the one before it, and runCatching catches that
                    // like anything else — which put "StandaloneCoroutine was
                    // cancelled" on the page while the next search was already
                    // on its way.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.e(TAG, "search failed: ${chain(it)}", it)
                    _search.value = _search.value.copy(loading = false, error = describe(it))
                }
        }
    }

    /**
     * The next page of the same search, appended.
     *
     * Called by the list as it nears its end rather than by a button: twenty of
     * each kind is the web player's own page size and a fine first answer, but
     * a search for a name that a hundred records share stopped dead at twenty
     * with no way to see the rest.
     *
     * Appended rather than replaced, and de-duplicated by address: the offset
     * is the server's idea of where the page starts, and a catalogue that has
     * changed under it can hand back a row that is already on the screen.
     */
    fun loadMoreSearch() {
        val state = _search.value
        if (state.query.isBlank() || state.loading || state.loadingMore) return
        if (state.exhausted) return
        val backend = container.activeBackend
        searchMoreJob?.cancel()
        _search.value = state.copy(loadingMore = true)
        searchMoreJob = viewModelScope.launch {
            runCatching {
                backend.search(
                    state.query,
                    SearchLabels(
                        artist = string(R.string.artist),
                        album = string(R.string.album),
                        playlist = string(R.string.playlist),
                    ),
                    offset = searchOffset,
                )
            }
                .onSuccess { page ->
                    currentCoroutineContext().ensureActive()
                    // Still the same search: a page that arrives after the box
                    // has been retyped belongs to a question nobody is asking.
                    if (_search.value.query != state.query) return@onSuccess
                    val grown = _search.value.results.plus(page)
                    val added = grown.count - _search.value.results.count
                    searchOffset += SEARCH_PAGE
                    _search.value = _search.value.copy(
                        results = grown,
                        loadingMore = false,
                        exhausted = added == 0,
                    )
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.e(TAG, "search page failed: ${chain(it)}", it)
                    _search.value = _search.value.copy(loadingMore = false, exhausted = true)
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
                    // Liked Songs. It sits in the "add to" list like a
                    // playlist, but it is the account's library and takes its
                    // own permission — without this, saving a track answers
                    // 403 and the heart never fills.
                    "user-library-read",
                    "user-library-modify",
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
        // Not needsSetup: search comes from Spotify's own gateway now, and
        // giving up the registered application only gives up the fallback.
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

    /**
     * How adding a friend is going, for the panel that asks for one.
     *
     * Idle until somebody types; a message afterwards, kept until the panel is
     * closed. Following somebody is a write to the account, and a write with no
     * answer is a button people press twice.
     */
    enum class AddFriend { IDLE, WORKING, DONE, FAILED, UNSUPPORTED }

    private val _addFriend = MutableStateFlow(AddFriend.IDLE)
    val addFriendState: StateFlow<AddFriend> = _addFriend.asStateFlow()

    fun clearAddFriend() {
        _addFriend.value = AddFriend.IDLE
    }

    /**
     * Follows a Spotify listener, which is what "adding a friend" is here.
     *
     * By link or username, and only by those: Spotify's search covers tracks,
     * albums, artists and playlists, and has never covered people. So there is
     * nobody to look up — what can be done is take the profile the listener was
     * given, in any of the shapes it arrives in, and follow it.
     *
     * Appearing in the activity list afterwards is a separate matter and not
     * this app's to promise: that list is only ever people who both are
     * followed and have chosen to share what they play.
     */
    fun addFriend(input: String) = viewModelScope.launch {
        val id = friendIdOf(input)
        if (id == null) {
            _addFriend.value = AddFriend.FAILED
            return@launch
        }
        if (!container.webApi.isReady) {
            _addFriend.value = AddFriend.UNSUPPORTED
            return@launch
        }
        _addFriend.value = AddFriend.WORKING
        runCatching { container.api.followArtists(type = "user", ids = id) }
            .onSuccess {
                _addFriend.value = AddFriend.DONE
                loadFriends()
            }
            .onFailure {
                android.util.Log.w(TAG, "could not follow $id: ${describe(it)}")
                _addFriend.value = AddFriend.FAILED
            }
    }

    /**
     * The account id inside whatever was pasted.
     *
     * A profile link, a `spotify:user:` uri, or the username on its own — the
     * three shapes somebody's profile arrives in, from a browser, from the
     * share sheet, and from being read out.
     */
    private fun friendIdOf(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val raw = when {
            trimmed.startsWith("spotify:user:") -> trimmed.removePrefix("spotify:user:")
            "open.spotify.com/user/" in trimmed ->
                trimmed.substringAfter("open.spotify.com/user/")
            // Some links carry the locale: /intl-it/user/<id>
            "spotify.com" in trimmed && "/user/" in trimmed -> trimmed.substringAfter("/user/")
            else -> trimmed
        }
        val id = raw.substringBefore('?').substringBefore('/').trim()
        // A username is url-safe text; anything else came from a link this did
        // not understand, and following it would fail with a worse message.
        return id.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "._-" } }
    }

    private val _videoFileId = MutableStateFlow<String?>(null)

    /**
     * The music video for what is playing, when there is one.
     *
     * Looked up in the catalogue rather than waited for: the account attaches a
     * video's id to a track only for the devices it believes can show one, and
     * it does not believe that of this app however it declares itself. Asking
     * works for anybody. See `catalog.rs`.
     */
    val videoFileId: StateFlow<String?> = _videoFileId.asStateFlow()

    private val _videoMode = MutableStateFlow(false)

    /** Whether the listener has asked to watch rather than listen. */
    val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    private var videoLookup: kotlinx.coroutines.Job? = null

    /** Asked once per track, and forgotten the moment the track changes. */
    fun lookUpVideo(trackUri: String?) {
        videoLookup?.cancel()
        _videoFileId.value = null
        _videoMode.value = false
        if (trackUri == null || !trackUri.startsWith("spotify:track:")) return
        // Offline there is no video to offer: it is streamed, and the lookup
        // itself is a request. Leaving the answer null is what takes the button
        // off the player — the button is drawn from having one.
        if (dev.lelonio.square.playback.OfflineMode.active.value) return
        videoLookup = viewModelScope.launch(Dispatchers.IO) {
            val answer = NativeBridge.trackVideo(trackUri) ?: return@launch
            val fileId = runCatching {
                org.json.JSONObject(answer).optString("fileId").takeIf { it.isNotEmpty() }
            }.getOrNull()
            _videoFileId.value = fileId
        }
    }

    fun toggleVideo() {
        _videoMode.value = !_videoMode.value && _videoFileId.value != null
    }

    val recent: StateFlow<List<CatalogTrack>> =
        combine(container.recentStore.tracks, container.preferences.backend) { tracks, _ ->
            // A nameless row is one that was recorded before the session had
            // finished describing the track; see the history effect in
            // SquareApp. (From HectorZL's #19.)
            tracks.filter { it.name.isNotBlank() && container.activeBackend.owns(it.uri) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Called when a track starts, to keep the home page's history current. */
    fun recordPlayed(track: CatalogTrack) = viewModelScope.launch {
        container.recentStore.record(track)
    }

    /**
     * Songs found by searching and played, newest first.
     *
     * Filtered by the source in use, like [recent]: a Spotify track is not
     * something the YouTube Music backend can play, and offering it would be
     * offering a row that does nothing.
     */
    val searchHistory: StateFlow<List<CatalogTrack>> =
        combine(container.searchHistory.tracks, container.preferences.backend) { tracks, _ ->
            tracks.filter { container.activeBackend.owns(it.uri) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Everything a search led to, newest first — songs, artists, records, lists.
     *
     * The list the search page draws. [searchHistory] is the songs among these,
     * for the places that can only use a song: the radio's seeds, and anything
     * that plays rather than opens.
     */
    val searchTrail: StateFlow<List<dev.lelonio.square.data.SearchHistoryEntry>> =
        combine(container.searchHistory.entries, container.preferences.backend) { entries, _ ->
            entries.filter { container.activeBackend.owns(it.uri) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _credits =
        MutableStateFlow<dev.lelonio.square.backend.spotify.SpotifyCredits.Credits?>(null)

    /**
     * Who made the track on screen, once somebody asks.
     *
     * Fetched when the panel is opened rather than with the track: it is a page
     * most listeners never look at, and a request per song for it would be a
     * request per song nobody wanted.
     */
    val credits: StateFlow<dev.lelonio.square.backend.spotify.SpotifyCredits.Credits?> =
        _credits.asStateFlow()

    private val _creditsLoading = MutableStateFlow(false)
    val creditsLoading: StateFlow<Boolean> = _creditsLoading.asStateFlow()

    private var creditsFor: String? = null
    private var creditsJob: Job? = null

    /** Asked for the open track; a repeat for the same one is free. */
    fun loadCredits(trackUri: String?) {
        if (trackUri == null || !trackUri.startsWith("spotify:track:")) {
            creditsJob?.cancel()
            creditsFor = null
            _credits.value = null
            _creditsLoading.value = false
            return
        }
        if (creditsFor == trackUri && (_credits.value != null || _creditsLoading.value)) return

        creditsJob?.cancel()
        creditsFor = trackUri
        _credits.value = null
        _creditsLoading.value = true
        creditsJob = viewModelScope.launch {
            val found = dev.lelonio.square.backend.spotify.SpotifyCredits.of(trackUri)
            if (creditsFor == trackUri) {
                _credits.value = found
                _creditsLoading.value = false
            }
        }
    }

    /** Called when a search result is played, rather than when a track starts. */
    fun recordSearchPlay(track: CatalogTrack) = viewModelScope.launch {
        container.searchHistory.record(track)
    }

    /** And when a search result that is a page rather than a song is opened. */
    fun recordSearchOpen(item: dev.lelonio.square.data.SearchItem) = viewModelScope.launch {
        container.searchHistory.record(
            dev.lelonio.square.data.SearchHistoryEntry(
                uri = item.uri,
                title = item.title,
                subtitle = item.subtitle,
                artworkUrl = item.artworkUrl,
            ),
        )
    }

    fun forgetSearchPlay(uri: String) = viewModelScope.launch {
        container.searchHistory.remove(uri)
    }

    fun clearSearchHistory() = viewModelScope.launch {
        container.searchHistory.clear()
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
            // Before the library is asked for: a session that has come loose
            // reads as an empty library otherwise, with nothing on screen to
            // say the account is the thing that needs attention.
            runCatching { backend.refreshAuth() }
            val name = (backend.authState.value as? BackendAuthState.LoggedIn)?.displayName
            runCatching { backend.playlists() }
                .onSuccess { playlists ->
                    _state.value = UiState.Ready(
                        displayName = name.orEmpty(),
                        playlists = withLocalFiles(playlists),
                    )
                }
                .onFailure {
                    android.util.Log.w(TAG, "youtube library unavailable: ${describe(it)}")
                    // Not a failure worth a whole error screen: the library is
                    // the one part that needs an account, and everything else
                    // on this backend works without one.
                    _state.value =
                        UiState.Ready(displayName = name.orEmpty(), playlists = withLocalFiles(emptyList()))
                }
            // The other two shelves of the same library. They read the active
            // backend and empty themselves for one that keeps neither, which is
            // also what clears Spotify's off the screen when the source changes.
            loadSavedAlbums()
            loadFollowedArtists()
            return@launch
        }

        if (!container.spotifySignedIn) {
            _state.value = UiState.LoggedOut
            return@launch
        }

        // Offline, there is nothing to wait for.
        //
        // The wait below is for a session to come up, and it ends either when
        // one does or after thirty seconds. Neither happens with the network
        // gone: the engine is up, it simply has no session, so the spinner sat
        // there for several seconds before the downloads appeared — the one
        // moment the app should be quickest, since everything it is about to
        // show is already on the phone.
        if (dev.lelonio.square.playback.OfflineMode.active.value) {
            offlineLibrary()?.let {
                _state.value = it
                return@launch
            }
        }

        _state.value = UiState.Connecting
        if (!awaitEngine()) {
            // A library with no session is not an empty library. Everything
            // that was downloaded is still here and still playable, and the
            // shelf for it is the one thing the listener came for.
            offlineLibrary()?.let {
                _state.value = it
                return@launch
            }
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
                loadSavedAlbums()
                // The pictures of whatever is downloaded, while there is a
                // connection to fetch them with.
                keepPageCovers()
                catchUpDownloads()
            }
            .onFailure {
                android.util.Log.e(TAG, "library load failed: ${chain(it)}", it)
                // The same fallback: the session came up but the catalogue
                // could not be read, and the downloads are unaffected by that.
                _state.value = offlineLibrary() ?: UiState.Failed(describe(it))
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
     * The library as the download index knows it, for when Spotify cannot be
     * asked.
     *
     * Built from the labels stored beside every download — name, cover, kind —
     * rather than from the context cache, which expires and holds only the last
     * few things opened. A download is meant to outlive both, and a phone in a
     * tunnel should find its playlists exactly where it left them.
     *
     * Null when there is nothing downloaded, which is the one case where an
     * error really is the honest answer.
     */
    /**
     * Fetches the covers of downloaded pages that have none yet.
     *
     * Not left to the download queue, which is where the songs' own covers are
     * filled in: that queue only runs while there is something to download, so
     * a library that is already complete never starts it and the pages kept
     * their blank tiles for ever. This is a handful of images against a
     * library's worth of audio, so it can simply be done.
     *
     * Online only, and quietly: a failure here means a tile stays drawn.
     */
    private fun keepPageCovers() = viewModelScope.launch {
        if (dev.lelonio.square.playback.OfflineMode.active.value) return@launch
        val owners = container.downloads.owners.value.keys
        if (owners.isEmpty()) return@launch
        withContext(Dispatchers.IO) {
            owners.forEach { uri ->
                val url = container.downloads.labelOf(uri)?.artworkUrl ?: return@forEach
                if (dev.lelonio.square.download.DownloadExtras.fileOf(url, "art") != null) {
                    return@forEach
                }
                runCatching { dev.lelonio.square.download.DownloadExtras.keep(url, "art") }
            }
        }
    }

    /**
     * Starts the queue when something downloaded is still short of its extras.
     *
     * A download is meant to carry its sleeve, its tall picture, its words and
     * its Canvas, and songs fetched by an earlier build carry none of those.
     * The queue catches them up on its own — but only while it is running, and
     * it only runs while there is audio owed, so a library that is complete
     * would never have started it. This is that missing nudge, once per launch
     * and only when there is really something to fetch.
     */
    private fun catchUpDownloads() = viewModelScope.launch {
        if (dev.lelonio.square.playback.OfflineMode.active.value) return@launch
        val wanting = withContext(Dispatchers.IO) {
            runCatching { container.downloadQueue.anythingMissing() }.getOrDefault(false)
        }
        if (wanting) DownloadService.start(getApplication())
    }

    private suspend fun offlineLibrary(): UiState.Ready? {
        container.downloads.load()
        val owners = container.downloads.owners.value
        val labelled = owners.keys.mapNotNull { uri ->
            container.downloads.labelOf(uri)?.let {
                CatalogPlaylist(uri = uri, name = it.name, artworkUrl = it.artworkUrl)
            }
        }
        // Everything on the phone, not only the songs downloaded on their own.
        //
        // It was the loose ones at first, which is what the store calls them —
        // and a shelf named "downloaded songs" holding one of the hundred and
        // ninety on the phone is a shelf that lies. The playlists are listed
        // beside it either way, so this is the one place that answers "what can
        // I play right now" without picking through them.
        val shelf = container.downloads.files.value.keys.takeIf { it.isNotEmpty() }?.let {
            CatalogPlaylist(
                uri = DownloadStore.SINGLES,
                name = string(R.string.downloaded_tracks),
                artworkUrl = dev.lelonio.square.ui.components.DOWNLOADS_COVER,
            )
        }
        val everything = listOfNotNull(shelf) + labelled
        if (everything.isEmpty()) return null

        android.util.Log.i(TAG, "offline library: ${everything.size} downloaded")
        // The name and the picture from the last time there was a connection.
        // They cost nothing to keep and their absence reads as being signed
        // out, which is not what has happened.
        val (name, avatar) = container.preferences.profile()
        // The phone's own files belong here as much as they do online.
        return UiState.Ready(name, withLocalFiles(everything), avatarUrl = avatar)
    }

    /**
     * The account's library, as the active backend assembles it.
     *
     * This used to build the Spotify list itself, which is why it went on
     * showing the old one: the backend is where the access point, the Web API
     * fallback and Liked Songs are put together, and a second copy here simply
     * missed everything added there.
     */
    private suspend fun playlists(): List<CatalogPlaylist> =
        withLocalFiles(container.activeBackend.playlists())

    /**
     * The phone's own music, at the head of whichever library is on screen.
     *
     * Not a third service to switch to: a file on this phone is there whether
     * the listener is on Spotify or on YouTube Music, exactly as it is in
     * Spotify's own client, so it belongs in both libraries rather than behind
     * a setting that swaps one for the other.
     *
     * Shown whether or not the permission has been given. Hiding it until then
     * would leave the listener nowhere to say yes: the shelf is where the
     * asking happens.
     */
    private fun withLocalFiles(playlists: List<CatalogPlaylist>): List<CatalogPlaylist> =
        listOf(
            CatalogPlaylist(
                uri = LocalLibrary.CONTEXT_URI,
                name = string(R.string.local_files),
                artworkUrl = LocalLibrary.COVER,
            ),
        ) + playlists.filterNot { it.uri == LocalLibrary.CONTEXT_URI }
            .distinctBy { it.uri }

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
                // And kept on disk, for the next time there is no network to
                // ask with; see [offlineLibrary].
                container.preferences.setProfile(
                    _state.value.let { (it as? UiState.Ready)?.displayName.orEmpty() },
                    profile.images.lastOrNull()?.url,
                )
                // The picture itself, beside the downloads, so it is a file
                // rather than a URL that cannot be fetched.
                profile.images.lastOrNull()?.url?.let {
                    launch { dev.lelonio.square.download.DownloadExtras.keep(it, "art") }
                }
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

    val trackSortDescending: StateFlow<Boolean> get() = container.preferences.trackSortDescending

    fun setTrackSortDescending(value: Boolean) =
        container.preferences.setTrackSortDescending(value)

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
                // Everything the other catalogue answered, kept across the
                // republishes the page makes while it loads. It arrives once, on
                // its own schedule, and the batch of tracks that lands after it
                // used to take the photograph and the bio back off the page.
                heroUrl = state.heroUrl ?: current.heroUrl,
                coverUrl = state.coverUrl ?: current.coverUrl,
                logoUrl = state.logoUrl ?: current.logoUrl,
                notes = state.notes ?: current.notes,
                byline = state.byline.ifEmpty { current.byline },
                origin = state.origin ?: current.origin,
                tintHex = state.tintHex ?: current.tintHex,
                inkHex = state.inkHex ?: current.inkHex,
                heroAspect = state.heroAspect ?: current.heroAspect,
                motionUrl = state.motionUrl ?: current.motionUrl,
                // Owned by the lookup alone: every other publish carries the
                // flag's default and would clear the wait a batch of tracks
                // early, which is the swap this exists to prevent.
                heroPending = current.heroPending,
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
    /** What one lookup says about a playlist: its words, whose it is, and their name. */
    private data class PlaylistDetails(
        val description: String?,
        val mine: Boolean?,
        val owner: String?,
    )

    private suspend fun detailsOf(uri: String): PlaylistDetails? {
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
        return PlaylistDetails(
            description = text,
            mine = mine,
            owner = dto.owner?.displayName?.takeIf { it.isNotBlank() },
        )
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

    /**
     * Keeps the record on the artist page's top card, or lets it go.
     *
     * Its own function rather than [toggleSaved]: that one is about the page
     * being read, and this is about a different record shown on it. Sharing the
     * code would mean one of the two buttons writing the other's state.
     */
    fun toggleLatestSaved() = viewModelScope.launch {
        val page = _playlist.value
        val release = page.latest ?: return@launch
        val was = page.latestSaved ?: return@launch
        val id = release.uri.substringAfterLast(':')

        _playlist.value = _playlist.value.copy(latestSaved = !was)
        runCatching {
            if (was) container.api.removeAlbums(id) else container.api.saveAlbums(id)
        }
            .onSuccess { refresh() }
            .onFailure {
                android.util.Log.e(TAG, "keeping the release failed: ${chain(it)}", it)
                if (_playlist.value.uri == page.uri) {
                    _playlist.value = _playlist.value.copy(latestSaved = was)
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

    /**
     * Shows a station that has already been resolved.
     *
     * The radio button asks for the tracks before it does anything else — it
     * has to, since an empty station is a button that should do nothing — so
     * this publishes what is already in hand rather than sending the page off
     * to fetch the same list a second time. A station is not a context that can
     * be reopened later anyway: ask twice and Spotify builds two different ones.
     */
    /**
     * Tries to reach Spotify again, and says whether it worked.
     *
     * The switch first, because a listener who turned offline on and then asks
     * to go back online means the switch: leaving it set and rebuilding the
     * session would reconnect an app that immediately declares itself offline
     * again.
     *
     * Then the handshake. A failure is not an error worth showing — the banner
     * is already saying the thing it would say — so this reports it by leaving
     * the app exactly as it was.
     */
    suspend fun retryOnline(): Boolean {
        container.downloadSettings.setOfflineMode(false)
        withContext(Dispatchers.IO) {
            runCatching { NativeBridge.reconnect() }
                .onFailure { android.util.Log.w(TAG, "retry failed: ${describe(it)}") }
        }
        val offline = runCatching { NativeBridge.isOffline }.getOrDefault(true)
        dev.lelonio.square.playback.OfflineMode.setNoSession(offline)
        if (!offline) {
            // The library was built from the download index while there was no
            // session; now there is one, and it is a different library.
            refresh()
            loadHomeShelves()
        }
        return !offline
    }

    fun showStation(
        uri: String,
        name: String,
        artworkUrl: String?,
        tracks: List<CatalogTrack>,
    ) {
        playlistJob?.cancel()
        // Kept so the source line in the player can come back to it. Asking
        // Spotify for the same station again would build a different one, and
        // the page would then disagree with what is playing.
        lastStation = PlaylistState(
            uri = uri,
            name = name,
            artworkUrl = artworkUrl,
            kind = DetailKind.PLAYLIST,
            tracks = tracks,
        )
        lastStation?.let(::publishPlaylist)
    }

    /** The station last shown, so the player's source line can reopen it. */
    private var lastStation: PlaylistState? = null

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

    private val _newBrowse = MutableStateFlow<List<dev.lelonio.square.data.HomeShelf>>(emptyList())

    /** Spotify's own new-releases page, as rows; see SpotifyBrowse. */
    val newBrowse: StateFlow<List<dev.lelonio.square.data.HomeShelf>> = _newBrowse.asStateFlow()

    private val _radioBrowse =
        MutableStateFlow<List<dev.lelonio.square.data.HomeShelf>>(emptyList())

    /** And its "made for you" and charts pages, which is what a radio tab is. */
    val radioBrowse: StateFlow<List<dev.lelonio.square.data.HomeShelf>> = _radioBrowse.asStateFlow()

    private var browseJob: Job? = null

    private val _browseLoading = MutableStateFlow(false)

    /**
     * Whether the browse rows are still on their way.
     *
     * The two tabs draw an outline of what is coming rather than growing a row
     * at a time under the reader's thumb: a page that assembles itself while
     * being read moves what is being looked at.
     */
    val browseLoading: StateFlow<Boolean> = _browseLoading.asStateFlow()

    /**
     * Reads Spotify's own browse pages for the two tabs that show them.
     *
     * Kept apart from the personalised home on purpose. Both come off the same
     * gateway, but home answers "what should this account see on opening the
     * app" — which is the home page's question, and putting its answer on two
     * more tabs is how those tabs ended up repeating it. These pages answer
     * what the catalogue is offering: the releases picked for this listener,
     * the editors' lists, the charts, the mixes.
     *
     * Whatever the home page is already showing is dropped here, by title: the
     * two do overlap, and a row that appears twice in one app is worse than a
     * row that appears once.
     */
    fun loadBrowse() {
        if (browseJob?.isActive == true) return
        if (_newBrowse.value.isNotEmpty() && _radioBrowse.value.isNotEmpty()) return

        _browseLoading.value = true
        browseJob = viewModelScope.launch {
            val onHome = _homeShelves.value.map { it.title.lowercase() }.toSet()
            suspend fun page(uri: String) = withContext(Dispatchers.IO) {
                container.gateway.browsePage(uri)
                    ?.let { dev.lelonio.square.data.SpotifyBrowse.parse(it) }
                    .orEmpty()
                    .filter { it.title.lowercase() !in onHome }
            }

            val releases = page(dev.lelonio.square.data.SpotifyBrowse.Pages.NEW_RELEASES)
            if (releases.isNotEmpty()) _newBrowse.value = releases

            // Two pages behind one tab: the mixes made for this listener, and
            // the charts, which are the other half of what a radio is for.
            val mixes = page(dev.lelonio.square.data.SpotifyBrowse.Pages.MADE_FOR_YOU)
            val charts = page(dev.lelonio.square.data.SpotifyBrowse.Pages.CHARTS)
            val forRadio = (mixes + charts).distinctBy { it.title.lowercase() }
            if (forRadio.isNotEmpty()) _radioBrowse.value = forRadio

            android.util.Log.i(
                TAG,
                "browse: ${releases.size} rows for new, ${forRadio.size} for radio",
            )
            _browseLoading.value = false
        }
    }

    /**
     * Asks for the personalised shelves if they are not already here.
     *
     * The home page loads them with the library; the two browse tabs show them
     * too and can be opened without ever visiting home — on a cold start
     * straight into Radio, for instance.
     */
    fun ensureHomeShelves() {
        if (_homeShelves.value.isEmpty()) loadHomeShelves()
    }

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
            // Just read, so nothing missing from it is saved. The seeding
            // below is left to run: it is the tick's half that this says
            // nothing about.
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

    /** Whether what is on disk has been looked at yet, for both marks. */
    private var membershipSeeded = false

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
            seedMembership()
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

    /**
     * Fills both marks from what is already on disk, at no cost in requests.
     *
     * Both used to start every run empty. The heart then asked Spotify about
     * each track as it played, and the tick — which nobody can be asked about —
     * simply went missing: a song from one of the listener's own playlists
     * showed a plus until that playlist happened to be opened again, even
     * though the queue it was playing from had just been restored from this
     * same disk.
     */
    private suspend fun seedMembership() {
        if (membershipSeeded) return
        membershipSeeded = true

        val collection = runCatching { withContext(Dispatchers.IO) { NativeBridge.collectionUri() } }
            .getOrNull()
            .orEmpty()
        val liked = collection.takeIf { it.isNotEmpty() }
            ?.let { contextCache[it] ?: container.contextCache.read(it) }
        if (liked != null) {
            _liked.value = _liked.value + liked.tracks.map { it.uri }
            // Never downwards: this run may already have read the list itself,
            // which is fresher than anything on disk.
            likedListTrusted = likedListTrusted ||
                System.currentTimeMillis() - liked.savedAt < LIKED_TRUSTED_MS
        }

        val inPlaylists = runCatching { container.contextCache.tracksInPlaylists() }
            .onFailure { android.util.Log.i(TAG, "no playlists remembered: ${describe(it)}") }
            .getOrDefault(emptySet())
        if (inPlaylists.isNotEmpty()) _inPlaylists.value = _inPlaylists.value + inPlaylists
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

    // ------------------------------------------------------------ downloads

    /** What is on the phone, per track; see DownloadStore. */
    val downloadedFiles = container.downloads.files

    /** What is being fetched right now, 0f..1f per track. */
    val downloadProgress = container.downloads.progress

    /** Per playlist, album or artist: whether it is kept, and how far along. */
    val downloadOwners = container.downloads.ownerStates

    /**
     * Tracks downloaded on their own rather than as part of a page.
     *
     * The difference matters to the track menu: a song kept because a playlist
     * wants it cannot be removed by itself — the file is still owed to that
     * playlist — so offering to remove it there would be offering something
     * that does nothing.
     */
    val downloadSingles: StateFlow<Set<String>> =
        container.downloads.owners
            .map { it[DownloadStore.SINGLES].orEmpty().toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * Keeps a whole page on the phone, or stops keeping it.
     *
     * The page is the unit rather than the track because that is how a listener
     * thinks about it — a playlist goes on the plane, not forty songs — and
     * because it is what lets a shared track cost nothing: the store records
     * who wanted each file, so a song two playlists both keep is fetched once
     * and only leaves when the last of them lets go of it.
     */
    fun toggleDownload(page: PlaylistState) {
        val uri = page.uri ?: return
        if (page.tracks.isEmpty()) return
        viewModelScope.launch {
            if (container.downloads.owners.value.containsKey(uri)) {
                // Only what is actually being fetched. Cancelling every track
                // of the playlist would work — the engine clears the mark
                // before each download — but it would also be several hundred
                // JNI calls to stop the one or two that are running.
                val inFlight = container.downloads.progress.value.keys.toList()
                container.downloads.removeOwner(uri)
                inFlight.forEach { runCatching { NativeBridge.cancelDownload(it) } }
                sweepOrphans()
            } else {
                // The page's own cover, kept like the songs are. It is one
                // picture against a playlist's worth of audio, and without it
                // the library offline is a wall of drawn tiles.
                page.artworkUrl?.let {
                    launch { dev.lelonio.square.download.DownloadExtras.keep(it, "art") }
                }
                container.downloads.setOwner(
                    ownerUri = uri,
                    tracks = page.tracks,
                    label = DownloadStore.OwnerLabel(
                        name = page.name,
                        artworkUrl = page.artworkUrl,
                        kind = when (page.kind) {
                            DetailKind.ALBUM -> DownloadStore.KIND_ALBUM
                            DetailKind.ARTIST -> DownloadStore.KIND_ARTIST
                            DetailKind.PLAYLIST -> DownloadStore.KIND_PLAYLIST
                        },
                    ),
                )
                DownloadService.start(getApplication())
            }
        }
    }

    /**
     * One track, kept on its own.
     *
     * Filed under a owner of its own so that the answer to "why is this here"
     * stays honest: a song downloaded by itself does not disappear because a
     * playlist it also belongs to was let go of.
     */
    fun toggleTrackDownload(track: CatalogTrack) {
        viewModelScope.launch {
            val singles = container.downloads.owners.value[DownloadStore.SINGLES].orEmpty()
            if (track.uri in singles) {
                container.downloads.removeSingle(track.uri)
                runCatching { NativeBridge.cancelDownload(track.uri) }
                sweepOrphans()
            } else {
                container.downloads.addSingle(track)
                DownloadService.start(getApplication())
            }
        }
    }

    /** Everything, gone. The files first, then what pointed at them. */
    /**
     * Deletes the files nothing wants any more.
     *
     * Letting go of a playlist only says the playlist no longer wants its
     * songs; it does not say the songs can go, because another playlist may
     * want the same ones. So the two halves are separate: the index works out
     * what is now unclaimed, and this is what takes those off the disk.
     *
     * Without it the index kept every file it had ever fetched — the rows went
     * on showing the download mark for a playlist that had just been let go of,
     * and the space was never given back.
     */
    private suspend fun sweepOrphans() {
        container.downloads.pruneOrphans().forEach {
            runCatching { NativeBridge.removeDownload(it) }
            dev.lelonio.square.download.DownloadExtras.forget(it)
        }
        // And whatever is left in the extras that no surviving track can
        // explain: covers, words and Canvases of songs that are gone.
        withContext(Dispatchers.IO) {
            val tracks = container.downloads.files.value.keys
            dev.lelonio.square.download.DownloadExtras.sweep(
                trackUris = tracks,
                coverUrls = tracks.mapNotNull { container.downloads.trackOf(it)?.artworkUrl },
            )
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            container.downloads.progress.value.keys.forEach {
                runCatching { NativeBridge.cancelDownload(it) }
            }
            container.downloads.clearAll()
        }
    }

    fun retryFailedDownloads() {
        viewModelScope.launch {
            container.downloads.retryFailed()
            DownloadService.start(getApplication())
        }
    }

    /**
     * Detail pages opened over other detail pages, newest last.
     *
     * The navigation graph has one route for all of them, so an album opened
     * from an artist is not a second destination — it is the same screen showing
     * something else, and back had nowhere to go but out of the screen entirely.
     * This is that missing history: what the page was before it became this one.
     */
    private val pageStack = ArrayDeque<PlaylistState>()

    /**
     * Whether there is a page behind this one, as something the screen can watch.
     *
     * A plain getter is what this was, and it is worth saying why that was a
     * bug rather than a style: the back handler reads it while it composes, and
     * nothing about a plain field tells Compose to compose again. Opening an
     * album from an artist changed the answer and nothing noticed — so the
     * gesture kept leaving the screen entirely, or kept being swallowed after
     * the history had run out, which is the "back shows the wrong page" this is
     * behind.
     */
    private val _hasPreviousPage = MutableStateFlow(false)
    val hasPreviousPage: StateFlow<Boolean> = _hasPreviousPage.asStateFlow()

    /**
     * How deep into the detail pages the listener has walked.
     *
     * Only the screen's own transition uses it, and only to tell a push from a
     * pop: the two have to move in opposite directions, and one destination
     * shared by every page means the navigation library cannot say which way
     * this is going.
     */
    private val _pageDepth = MutableStateFlow(0)
    val pageDepth: StateFlow<Int> = _pageDepth.asStateFlow()

    /**
     * Whether the detail screen is the one on screen right now.
     *
     * What decides whether opening a page pushes the last one onto the history.
     * Without it the state left behind by a page that was closed minutes ago
     * still counted as "the page you were on": open a playlist, leave it, open
     * an artist, press back — and the playlist came back, because it was still
     * sitting in [_playlist] when the artist was opened.
     */
    private var pageOnScreen = false

    /** Told by the screen itself; see the detail route. */
    fun detailShown() {
        pageOnScreen = true
    }

    /** And when it goes, so does everything behind it. */
    fun detailClosed() {
        pageOnScreen = false
        clearPageHistory()
    }

    /**
     * Goes back one detail page, and says whether there was one.
     *
     * False leaves the caller to pop the navigation stack as it always did:
     * the page underneath is then a tab, not another detail.
     */
    fun popPage(): Boolean {
        val previous = pageStack.removeLastOrNull() ?: return false
        playlistJob?.cancel()
        _playlist.value = previous
        _hasPreviousPage.value = pageStack.isNotEmpty()
        _pageDepth.value = pageStack.size
        return true
    }

    /**
     * Nothing is behind a page reached from a tab.
     *
     * Called when a tab is tapped: the detail screen is one destination shared
     * by every tab, so a history left over from the last one would have back
     * walking into a page opened from somewhere the listener has since left.
     */
    fun clearPageHistory() {
        pageStack.clear()
        _hasPreviousPage.value = false
        _pageDepth.value = 0
    }

    fun openPlaylist(playlist: CatalogPlaylist) {
        // What is on screen now, if it is a different page and a real one. The
        // list is kept whole rather than re-resolved on the way back: an artist
        // page is four requests and a discography, and walking back into it
        // should be instant.
        _playlist.value
            .takeIf {
                pageOnScreen && it.uri != null && it.uri != playlist.uri &&
                    it.tracks.isNotEmpty()
            }
            ?.let {
                pageStack.addLast(it)
                // Deep enough to walk back through a listening session, short
                // enough that a thousand-track playlist is not held forever.
                while (pageStack.size > PAGE_HISTORY) pageStack.removeFirst()
                _hasPreviousPage.value = true
                _pageDepth.value = pageStack.size
            }

        // A station reopens as the list it already is; see [showStation].
        lastStation?.takeIf { it.uri == playlist.uri }?.let {
            playlistJob?.cancel()
            publishPlaylist(it)
            return
        }

        // Reopening one counts as opening it, and that is exactly the playlist
        // the home page should keep at the front.
        container.playlistOrder.record(playlist.uri)

        // The phone's own music answers for itself: no service to ask, no
        // snapshot to compare, and nothing worth keeping on disk about files
        // that are already on disk.
        if (LocalLibrary.isLocalContext(playlist.uri)) {
            openLocalFiles(playlist)
            return
        }

        // The shelf of downloaded songs is not a context at all: its uri
        // belongs to this app rather than to Spotify, and asking the access
        // point to resolve it answered "invalid argument". This app keeps the
        // list, so this app answers for it — online as much as offline, and
        // with everything on the phone rather than only the loose songs.
        if (playlist.uri == DownloadStore.SINGLES) {
            // Newest first: a shelf of everything is read from the top, and
            // what was just downloaded is what somebody came looking for.
            openDownloadedContext(
                playlist,
                container.downloads.files.value
                    .entries
                    .sortedByDescending { it.value.downloadedAt }
                    .map { it.key },
            )
            return
        }

        // Offline, a downloaded playlist is answered from the index too. Not a
        // fallback after a failed request but instead of one: with no session
        // the request cannot even be attempted, and the whole list is already
        // here — in the order the playlist had when it was downloaded, which is
        // the order it should still be in.
        if (dev.lelonio.square.playback.OfflineMode.active.value) {
            val wanted = container.downloads.owners.value[playlist.uri]
            if (wanted != null) {
                openDownloadedContext(playlist, wanted)
                return
            }
        }

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
            // Asked whenever anything it answers is still missing. Keyed on the
            // description alone, it never ran for a list that had one — and the
            // name under the title, which comes from the same request, was then
            // absent on exactly the lists that describe themselves.
            if (kind == DetailKind.PLAYLIST &&
                (base.description.isEmpty() || base.byline.isEmpty())
            ) launch {
                val details = detailsOf(playlist.uri) ?: return@launch
                val text = details.description
                val mine = details.mine
                base = base.copy(
                    description = text.orEmpty(),
                    mine = mine,
                    byline = details.owner.orEmpty(),
                )
                if (_playlist.value.uri == playlist.uri) {
                    _playlist.value = _playlist.value.copy(
                        description = text.orEmpty().ifEmpty { _playlist.value.description },
                        mine = mine,
                        byline = details.owner.orEmpty(),
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

            // Apple's own pictures for the page. Deliberately last and
            // deliberately quiet: it is a different catalogue reached over a
            // different network, everything on screen is already correct
            // without it, and an artist it has never heard of simply keeps the
            // header it has.
            if (kind == DetailKind.ARTIST || kind == DetailKind.ALBUM) {
                if (!dev.lelonio.square.playback.OfflineMode.active.value) {
                    base = base.copy(heroPending = true)
                    if (_playlist.value.uri == playlist.uri) {
                        _playlist.value = _playlist.value.copy(heroPending = true)
                    }
                }
                launch { dressPage(playlist.uri, playlist.name, kind) }
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
                            latest = page.latest,
                            albums = page.albums,
                            singles = page.singles,
                            appearsOn = page.appearsOn,
                            artistPlaylists = page.playlists,
                            followers = page.followers,
                            genres = page.genres,
                            following = _playlist.value.following,
                        ),
                    )

                    // Asked after the page is on screen, not before it: whether
                    // one album is in the library is not worth holding an
                    // artist's whole page for.
                    page.latest?.let { release ->
                        launch {
                            val kept = savedState(release.uri) ?: return@launch
                            if (_playlist.value.uri == playlist.uri) {
                                _playlist.value = _playlist.value.copy(latestSaved = kept)
                            }
                        }
                    }
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
     * The music on this phone, read straight from the media index.
     *
     * Empty is two different answers, and the screen has to tell them apart:
     * a phone with no music on it, and a phone this app has not been allowed
     * to look at. The second is what [PlaylistState.needsPermission] says, and
     * it is the only case in the app where an empty list is something to act
     * on rather than to report.
     */
    /** A downloaded list, read out of the index rather than off the network. */
    private fun openDownloadedContext(playlist: CatalogPlaylist, wanted: List<String>) {
        playlistJob?.cancel()
        val label = container.downloads.labelOf(playlist.uri)
        publishPlaylist(
            PlaylistState(
                uri = playlist.uri,
                name = playlist.name.ifEmpty { label?.name.orEmpty() },
                artworkUrl = playlist.artworkUrl ?: label?.artworkUrl,
                kind = when (label?.kind) {
                    DownloadStore.KIND_ALBUM -> DetailKind.ALBUM
                    DownloadStore.KIND_ARTIST -> DetailKind.ARTIST
                    else -> DetailKind.PLAYLIST
                },
                tracks = wanted.mapNotNull(container.downloads::trackOf),
            ),
        )
    }

    private fun openLocalFiles(playlist: CatalogPlaylist) {
        playlistJob?.cancel()
        val base = PlaylistState(
            uri = playlist.uri,
            name = playlist.name,
            // The shelf's own tile, drawn rather than fetched; see Artwork.
            artworkUrl = playlist.artworkUrl ?: LocalLibrary.COVER,
            kind = DetailKind.PLAYLIST,
            // Nothing here belongs to an account, so none of what a playlist
            // page offers applies: no following, no editing, no removing.
            mine = false,
        )
        publishPlaylist(base.copy(loading = true))

        playlistJob = viewModelScope.launch {
            val granted = LocalLibrary.granted(getApplication())
            val tracks = if (granted) LocalLibrary.tracks(getApplication()) else emptyList()
            publishPlaylist(
                base.copy(
                    tracks = tracks,
                    loading = false,
                    needsPermission = !granted,
                ),
            )
        }
    }

    /** Called once the listener has answered the system's permission dialog. */
    fun onLocalPermissionAnswered() {
        val open = _playlist.value
        if (!LocalLibrary.isLocalContext(open.uri)) return
        openLocalFiles(
            CatalogPlaylist(
                uri = open.uri.orEmpty(),
                name = open.name,
                artworkUrl = LocalLibrary.COVER,
            ),
        )
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
     * The artwork the record a track comes from carries, for the player.
     *
     * A song in Apple's catalogue has one square picture and nothing else; the
     * tall one and the moving one belong to its album, which is where their own
     * player takes them from too. Empty until a track is playing, and empty
     * again for anything the catalogue does not have — the player then shows the
     * square cover it already had.
     */
    private val _nowPlayingArt = MutableStateFlow<dev.lelonio.square.data.AppleCatalog.Album?>(null)
    val nowPlayingArt: StateFlow<dev.lelonio.square.data.AppleCatalog.Album?> =
        _nowPlayingArt.asStateFlow()

    /**
     * Whether the other catalogue is still being asked about this track.
     *
     * The player holds its picture back while this is true. Spotify's cover is
     * already in hand and could be drawn at once, but on a track that is about
     * to have a better copy of the same artwork that only means showing the
     * soft one and swapping it in front of the listener. Capped, so a slow
     * network costs a moment of plain colour rather than a player with no
     * picture at all.
     */
    private val _nowPlayingArtPending = MutableStateFlow(false)
    val nowPlayingArtPending: StateFlow<Boolean> = _nowPlayingArtPending.asStateFlow()

    private var nowPlayingArtJob: Job? = null
    private var prefetchArtJob: Job? = null

    /**
     * Looks up, and downloads, the artwork of the song that comes next.
     *
     * The wait a listener notices is not the picture arriving — thirty
     * kilobytes on a wifi is nothing — it is everything before it: a search of
     * the other catalogue, then a fetch, then a decode of a picture sixteen
     * hundred across, all of it started at the moment the song changed. Done
     * for the *next* song while the current one plays, all three are over
     * before the change, and the answer is waiting in the caches the real
     * lookup reads.
     *
     * Fails quietly and cancels freely: nothing on screen depends on it.
     */
    fun prefetchArt(title: String, album: String, artist: String) {
        if (artist.isBlank() || dev.lelonio.square.playback.OfflineMode.active.value) return
        prefetchArtJob?.cancel()
        prefetchArtJob = viewModelScope.launch {
            val found = dev.lelonio.square.data.AppleCatalog.album(album, artist)
                .takeIf { album.isNotBlank() }
            val cover = found?.coverUrl
                ?: dev.lelonio.square.data.AppleCatalog.song(title, artist, album)
            // The bytes and the decode as well as the answer: Coil keeps what it
            // has already read, and this is the half that takes the longest.
            val context = getApplication<SquareApplication>()
            // At the size the player will ask for, not at the picture's own.
            // Coil files what it has decoded under the size it decoded it at, so
            // a copy read at sixteen hundred across is not the copy the screen
            // wants and the decode happens again in front of the listener. The
            // header fills the width at three by four.
            val width = context.resources.displayMetrics.widthPixels
            val height = width * DETAIL_TALL / DETAIL_WIDE
            listOfNotNull(found?.heroUrl, cover).forEach { url ->
                runCatching {
                    context.imageLoader.enqueue(
                        coil.request.ImageRequest.Builder(context)
                            .data(url)
                            .size(width, height)
                            .scale(coil.size.Scale.FILL)
                            .build(),
                    )
                }
            }
            // And the colour the page takes from it, which is a second read of
            // the same file: without this the cover arrived out of memory in one
            // frame and the page followed it a moment later. See
            // warmArtworkColor.
            (found?.heroUrl ?: cover)?.let { url ->
                runCatching {
                    dev.lelonio.square.ui.theme.warmArtworkColor(context, url)
                }
            }
        }
    }

    /**
     * The record a track is on, asked for when nothing already knew it.
     *
     * The address travels with the queue where the source gave one, but a
     * playlist resolved before this app kept it — the copies on disk outlive a
     * version — has only the album's name. Rather than leave the title inert on
     * those, it is looked up once, on the tap.
     */
    suspend fun albumOf(trackUri: String): CatalogPlaylist? {
        if (!trackUri.startsWith("spotify:track:") || !container.webApi.isReady) return null
        val id = trackUri.substringAfterLast(':')
        val dto = runCatching { container.api.track(id) }
            .onFailure { android.util.Log.w(TAG, "no album for $trackUri: ${describe(it)}") }
            .getOrNull() ?: return null
        val album = dto.album ?: return null
        val uri = album.uri ?: return null
        return CatalogPlaylist(
            uri = uri,
            name = album.name,
            artworkUrl = album.images.firstOrNull()?.url,
        )
    }

    /** Called when the track changes; cached and cheap on a repeat. */
    fun loadNowPlayingArt(uri: String?, title: String, album: String, artist: String) {
        nowPlayingArtJob?.cancel()
        _nowPlayingArt.value = null
        if (artist.isBlank()) {
            _nowPlayingArtPending.value = false
            return
        }
        _nowPlayingArtPending.value = true
        nowPlayingArtJob = viewModelScope.launch {
            launch {
                delay(ART_WAIT_MS)
                _nowPlayingArtPending.value = false
            }

            // What came down with the download, first and without asking
            // anybody. A downloaded song keeps the tall picture beside its
            // audio, which is the whole point of keeping it: offline there is
            // nobody to ask, and online this is the same answer without the
            // round trip.
            //
            // "Asked and there is nothing" counts as an answer here, and ends
            // the wait just as an answer does. Otherwise every downloaded song
            // the other catalogue does not have would hold its own cover back
            // for the length of a lookup that was made days ago.
            keptArt(uri)?.let {
                _nowPlayingArt.value = it.album
                _nowPlayingArtPending.value = false
                return@launch
            }

            // The record's name is worth waiting a moment for.
            //
            // Metadata arrives in pieces: the id first, the title and artist
            // next, the record's name sometimes after that. Asking with it
            // missing does not fail — it falls through to searching for the
            // song, which answers with whichever release the search puts first,
            // and that is as often a compilation or a single as the record. Two
            // different answers for one song, each filed under its own key, is
            // why the cover could change between one opening of the app and the
            // next.
            //
            // Nothing is polled: the caller re-runs this the moment the name
            // lands, which cancels this job where it stands. The wait is only
            // for the songs that never get one — a local file, a stream — and
            // those go on to the search below as they did before.
            if (album.isBlank()) delay(ALBUM_WAIT_MS)

            val found = dev.lelonio.square.data.AppleCatalog.album(album, artist)
                .takeIf { album.isNotBlank() }
            _nowPlayingArt.value = found

            // No record found, or one with no sleeve of its own: ask for the
            // song instead. Spotify's largest cover is 640 across and the player
            // draws it the width of the screen, so falling back to it is
            // falling back to a soft picture — where Apple keeps a song's
            // artwork at several thousand.
            if (found?.coverUrl == null && title.isNotBlank()) {
                val fromSong = dev.lelonio.square.data.AppleCatalog.song(title, artist, album)
                if (fromSong != null) {
                    _nowPlayingArt.value = (found ?: dev.lelonio.square.data.AppleCatalog.Album(
                        heroUrl = null,
                        coverUrl = null,
                    )).copy(coverUrl = fromSong)
                }
            }
            _nowPlayingArtPending.value = false
        }
    }

    /**
     * What was filed for a downloaded song when it was asked about.
     *
     * Null and [album] null are different answers, and the difference is the
     * whole reason this is wrapped: null means nobody has asked yet, so the
     * catalogue still might have something; an [album] of null means it was
     * asked and had nothing, and there is nothing to wait for.
     */
    private class KeptArt(val album: dev.lelonio.square.data.AppleCatalog.Album?)

    /**
     * The pictures kept beside a downloaded song, as the player wants them.
     *
     * Only urls with a file behind them: a url whose picture never came down is
     * a header that draws nothing at all offline. See DownloadExtras.
     */
    private suspend fun keptArt(uri: String?): KeptArt? {
        val track = uri ?: return null
        return withContext(Dispatchers.IO) {
            val extras = dev.lelonio.square.download.DownloadExtras
            val (hero, cover) = extras.art(track) ?: return@withContext null
            val onDisk = { url: String? -> url?.takeIf { extras.fileOf(it, "art") != null } }
            val kept = dev.lelonio.square.data.AppleCatalog.Album(
                heroUrl = onDisk(hero),
                coverUrl = onDisk(cover),
            )
            KeptArt(kept.takeIf { it.heroUrl != null || it.coverUrl != null })
        }
    }

    /**
     * Asks Apple's catalogue for the page's photograph, and its name drawn.
     *
     * An album is matched on its own name and its artist's, and the artist is
     * only known once the tracks are in — so this waits for them rather than
     * guessing. Everything about it is optional: no network, no match, no
     * pictures, and the page keeps the header it already has.
     */
    private suspend fun dressPage(uri: String, opened: String, kind: DetailKind) {
        // Offline this still runs. AppleCatalog answers from what it wrote down
        // and reaches for nothing, so a record whose songs are on the phone
        // opens on its own photograph in a tunnel; one that was never looked up
        // simply keeps Spotify's header, as it did before.

        // Whatever happens below, the header stops waiting: an answer, no
        // answer, or a network that is simply slow. A page that holds its
        // picture back for a lookup that is never coming is worse than one that
        // shows Spotify's.
        viewModelScope.launch {
            delay(HERO_WAIT_MS)
            if (_playlist.value.uri == uri) {
                _playlist.value = _playlist.value.copy(heroPending = false)
            }
        }
        val done = { if (_playlist.value.uri == uri) {
            _playlist.value = _playlist.value.copy(heroPending = false)
        } }

        // The name the page was opened with is empty when it was opened from a
        // link — the title arrives with the rest of it a moment later — and a
        // lookup for "" is a lookup for nothing.
        val name = opened.ifBlank {
            withTimeoutOrNull(ARTIST_WAIT_MS) {
                while (_playlist.value.uri == uri && _playlist.value.name.isBlank()) {
                    delay(POLL_INTERVAL_MS)
                }
                _playlist.value.name
            }.orEmpty()
        }
        if (name.isBlank()) {
            done()
            return
        }

        if (kind == DetailKind.ARTIST) {
            val found = dev.lelonio.square.data.AppleCatalog.artist(name)
            if (found == null) {
                done()
                return
            }
            if (_playlist.value.uri != uri) return
            _playlist.value = _playlist.value.copy(
                heroUrl = found.heroUrl,
                logoUrl = found.logoUrl,
                tintHex = found.bgColor,
                inkHex = found.textHex,
                heroAspect = found.heroAspect,
                notes = found.bio,
                origin = found.origin,
                heroPending = false,
            )
            return
        }

        // The record's artist, once there is a track to read it off.
        val artist = withTimeoutOrNull(ARTIST_WAIT_MS) {
            while (_playlist.value.uri == uri && _playlist.value.tracks.isEmpty()) {
                delay(POLL_INTERVAL_MS)
            }
            _playlist.value.tracks.firstOrNull()?.artist
        }
        if (artist == null) {
            done()
            return
        }

        // The catalogue's answer, or the one that came down with the songs.
        // The rows it writes expire after a month; the pictures beside a
        // download do not, so a record kept on the phone opens on its own
        // photograph however long it has been there.
        val found = dev.lelonio.square.data.AppleCatalog.album(name, artist)
            ?: keptArt(_playlist.value.tracks.firstOrNull()?.uri)?.album
        if (found == null) {
            done()
            return
        }
        if (_playlist.value.uri != uri) return
        _playlist.value = _playlist.value.copy(
            heroUrl = found.heroUrl,
            coverUrl = found.coverUrl,
            notes = found.notes,
            tintHex = found.bgColor,
            inkHex = found.textHex,
            motionUrl = found.motionUrl,
            heroPending = false,
        )
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
        val latest: ArtistRelease?,
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

        // The newest thing with the artist's own name on it. Compilations and
        // the records that are somebody else's are left out: "latest release"
        // meaning a greatest-hits reissue somebody else assembled is how that
        // card ends up wrong on half the artists in the catalogue.
        val latest = releases
            .filter { it.albumGroup == "album" || it.albumGroup == "single" }
            .filter { !it.releaseDate.isNullOrBlank() }
            .maxByOrNull { it.releaseDate.orEmpty() }
            ?.let { album ->
                album.uri?.let { uri ->
                    ArtistRelease(
                        uri = uri,
                        title = album.name,
                        artworkUrl = album.images.firstOrNull()?.url,
                        releaseDate = album.releaseDate.orEmpty(),
                        trackCount = album.totalTracks,
                    )
                }
            }

        return ArtistPage(
            playlists = artistPlaylists(artist?.name.orEmpty()),
            tracks = tracks,
            latest = latest,
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

    private val _savedAlbums = MutableStateFlow<List<CatalogPlaylist>>(emptyList())

    /**
     * The albums the account has saved, for the library's own shelf of them.
     *
     * Kept apart from [UiState.Ready.playlists] rather than mixed into it: the
     * rootlist is playlists and nothing else, and a screen that filters by kind
     * needs to be able to say which is which without reading URIs.
     */
    val savedAlbums: StateFlow<List<CatalogPlaylist>> = _savedAlbums.asStateFlow()

    fun loadSavedAlbums() = viewModelScope.launch {
        // Whichever service is on. What follows is Spotify's own paging; every
        // other backend answers for itself, and one that keeps no albums
        // answers with none.
        if (container.activeBackend.id != BackendId.SPOTIFY) {
            _savedAlbums.value = runCatching { container.activeBackend.savedAlbums() }
                .getOrDefault(emptyList())
            return@launch
        }
        if (!container.webApi.isReady) {
            _savedAlbums.value = emptyList()
            return@launch
        }
        runCatching {
            val gathered = mutableListOf<CatalogPlaylist>()
            var offset = 0
            // Offset-paged, unlike the artists: walked to the end for the same
            // reason, which is that a first page is not a library.
            while (true) {
                val page = container.api.savedAlbums(limit = ALBUM_PAGE, offset = offset)
                gathered += page.items.mapNotNull { saved ->
                    CatalogPlaylist(
                        uri = saved.album.uri ?: return@mapNotNull null,
                        name = saved.album.name,
                        artworkUrl = saved.album.images.firstOrNull()?.url,
                    )
                }
                if (page.items.size < ALBUM_PAGE) break
                offset += ALBUM_PAGE
            }
            gathered.distinctBy { it.uri }
        }
            .onSuccess { _savedAlbums.value = it }
            .onFailure { android.util.Log.w(TAG, "saved albums unavailable: ${describe(it)}") }
    }

    private val _followedArtists = MutableStateFlow<List<SearchItem>>(emptyList())

    /** The artists this account follows, for the library. */
    val followedArtists: StateFlow<List<SearchItem>> = _followedArtists.asStateFlow()

    fun loadFollowedArtists() = viewModelScope.launch {
        if (container.activeBackend.id != BackendId.SPOTIFY) {
            _followedArtists.value = runCatching { container.activeBackend.followedArtists().distinctBy { it.uri } }
                .getOrDefault(emptyList())
            return@launch
        }
        if (!container.webApi.isReady) {
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
            gathered.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
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
            // An engine that came up offline is never going to report itself
            // connected, and waiting for it to is thirty seconds of spinner on
            // a phone that has the music already. There is a player behind this
            // — see build_offline_bundle — so the answer is "ready", and what
            // it is ready for is what the library below works out.
            if (runCatching { NativeBridge.isOffline }.getOrDefault(false)) {
                return@withTimeoutOrNull false
            }
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
        const val FRESH_ARTISTS = 14

        /** How far the daily turn moves; a prime, so it works through the list. */
        const val DAILY_TURN = 7

        /** A page of saved albums; the API's own maximum. */
        const val ALBUM_PAGE = 50

        /** How many detail pages back the in-screen history holds. */
        const val PAGE_HISTORY = 12

        /** How long a home row gets before it stops being worth scrolling. */
        const val FEED_ROW = 12

        /** Records asked of Spotify's own new-releases list for the New tab. */
        const val NEW_RELEASES = 50

        /** How many of those pages to read; see loadNewPage. */
        const val NEW_PAGES = 3

        /** How many of them the page leads with, drawn large. */
        const val HERO_RELEASES = 6

        /** And how many are opened for a song to put in the list under them. */
        const val NEW_SONGS = 18

        /**
         * How long a header holds its picture back for the other catalogue.
         *
         * Long enough for a cached answer and a quick search, short enough that
         * a bad network costs a moment of plain colour rather than a page with
         * no picture at all.
         */
        const val HERO_WAIT_MS = 1_200L

        /**
         * How long the player holds its picture back for the other catalogue.
         *
         * Long enough for a cached answer and a search on a working connection,
         * short enough that a bad one costs a moment of colour rather than a
         * screen with no artwork.
         */
        const val ART_WAIT_MS = 1_500L

        /** How long a song's record has to arrive before it is given up on. */
        const val ALBUM_WAIT_MS = 900L

        /** The shape the header draws a cover in; see PlayerScreen. */
        const val DETAIL_WIDE = 3
        const val DETAIL_TALL = 4

        /** How long the album lookup waits for a track to name the artist. */
        const val ARTIST_WAIT_MS = 8_000L

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

        /** How many of each kind a page of results holds; see Gateway. */
        const val SEARCH_PAGE = 20

        /** Spotify acknowledges a transfer before it has taken effect. */
        const val TRANSFER_SETTLE_MS = 700L
    }
}
