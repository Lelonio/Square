package dev.lelonio.square.backend.youtube

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.backend.lyrics.LrcLib
import dev.lelonio.square.backend.lyrics.Lossless
import dev.lelonio.square.backend.lyrics.LyricsOvh
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.download.DownloadExtras
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import dev.lelonio.square.backend.HomeChip
import dev.lelonio.square.backend.HomeFeed
import dev.lelonio.square.backend.HomeRow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * YouTube Music, over two clients rather than one.
 *
 * The split is by what needs an account, not by preference. Search, trending
 * and stream URLs are public, and NewPipeExtractor reads them without any
 * credentials — which is what lets the app work before anyone has signed in,
 * and for anyone who never does. The library is the account's own, so
 * [playlists] goes through the vendored InnerTube module with the session
 * cookie attached; see [YouTubeAccount].
 *
 * The other half of this backend is [YouTubeStreamResolver], which turns one of
 * these URIs into a URL ExoPlayer can read.
 */
@UnstableApi
class YouTubeBackend(private val account: YouTubeAccount) : MusicBackend {

    override val id = BackendId.YOUTUBE_MUSIC

    private val _authState = MutableStateFlow<BackendAuthState>(
        account.accountName.value?.let(BackendAuthState::LoggedIn) ?: ANONYMOUS,
    )
    override val authState: StateFlow<BackendAuthState> = _authState.asStateFlow()

    /**
     * Always. Signing in adds the account's own library on top; it is not what
     * makes the backend usable, and search and playback work without it.
     */
    override val isReady = true

    override suspend fun refreshAuth() {
        // Brings the stored cookie up to date and finds out whether it still
        // works; see [YouTubeAccount.revalidate].
        account.revalidate()
        _authState.value = account.accountName.value?.let(BackendAuthState::LoggedIn) ?: ANONYMOUS
    }

    override suspend fun logOut() {
        account.signOut()
        _authState.value = ANONYMOUS
    }

    /**
     * Songs, albums, artists and playlists, in four calls.
     *
     * InnerTube filters a search to one kind at a time, so the four rows the
     * UI shows are four requests. Run together rather than one after another:
     * serially this is the slowest thing on the screen, and they do not depend
     * on each other.
     */
    override suspend fun search(
        query: String,
        labels: SearchLabels,
        offset: Int,
    ): SearchResults =
        withContext(Dispatchers.IO) {
            // One page only. YouTube's search is a continuation token rather
            // than an offset, and asking for a second page means keeping that
            // token — worth doing when this backend's search is the one being
            // used, and not worth pretending to do from here.
            if (offset > 0) return@withContext SearchResults()
            val trimmed = query.trim()

            coroutineScope {
                val songs = async { searchItems(trimmed, YouTube.SearchFilter.FILTER_SONG) }
                val albums = async { searchItems(trimmed, YouTube.SearchFilter.FILTER_ALBUM) }
                val artists = async { searchItems(trimmed, YouTube.SearchFilter.FILTER_ARTIST) }
                val playlists = async {
                    searchItems(trimmed, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                }

                SearchResults(
                    tracks = songs.await().filterIsInstance<SongItem>().map(::toCatalogTrack),
                    albums = albums.await().filterIsInstance<AlbumItem>().map { album ->
                        SearchItem(
                            uri = "$PLAYLIST_PREFIX${album.playlistId}",
                            title = album.title,
                            subtitle = album.artists?.joinToString(", ") { it.name }
                                ?.ifBlank { labels.album } ?: labels.album,
                            artworkUrl = album.thumbnail.atSize(COVER_SIZE),
                        )
                    },
                    artists = artists.await().filterIsInstance<ArtistItem>().map { artist ->
                        SearchItem(
                            uri = "$ARTIST_PREFIX${artist.id}",
                            title = artist.title,
                            subtitle = labels.artist,
                            artworkUrl = artist.thumbnail?.atSize(COVER_SIZE),
                        )
                    },
                    playlists = playlists.await().filterIsInstance<PlaylistItem>().map { playlist ->
                        SearchItem(
                            uri = "$PLAYLIST_PREFIX${playlist.id}",
                            title = playlist.title,
                            subtitle = labels.playlist,
                            artworkUrl = playlist.thumbnail?.atSize(COVER_SIZE),
                        )
                    },
                )
            }
        }

    /** One filtered search, with a failure treated as "nothing of this kind". */
    private suspend fun searchItems(query: String, filter: YouTube.SearchFilter): List<YTItem> =
        YouTube.search(query, filter)
            .map { it.items }
            .getOrDefault(emptyList())

    /**
     * The account's own playlists — empty until someone has signed in.
     *
     * This is the one thing the anonymous path genuinely cannot do, and the
     * reason the InnerTube module is vendored at all: it goes out with the
     * session cookie attached, so YouTube answers with *this* account's library
     * rather than the public catalogue.
     */
    override suspend fun playlists(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        if (!account.isSignedIn) return@withContext emptyList()

        YouTube.library(LIKED_PLAYLISTS_BROWSE_ID)
            .getOrThrow()
            .items
            .filterIsInstance<PlaylistItem>()
            .map { item ->
                CatalogPlaylist(
                    uri = "$PLAYLIST_PREFIX${item.id}",
                    name = item.title,
                    artworkUrl = item.thumbnail?.atSize(COVER_SIZE),
                )
            }
    }

    /**
     * The albums in the account's library.
     *
     * Its own browse page rather than a filter over the playlists: YouTube
     * keeps saved albums on a page of their own, exactly as it keeps saved
     * playlists on theirs.
     */
    override suspend fun savedAlbums(): List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        if (!account.isSignedIn) return@withContext emptyList()
        runCatching {
            YouTube.library(SAVED_ALBUMS_BROWSE_ID)
                .getOrThrow()
                .items
                .filterIsInstance<AlbumItem>()
                .mapNotNull(::toCatalogPlaylist)
        }.getOrElse {
            android.util.Log.w(LOG_TAG, "saved albums unavailable: $it")
            emptyList()
        }
    }

    /** The artists the account subscribed to, from the library page of them. */
    override suspend fun followedArtists(): List<SearchItem> = withContext(Dispatchers.IO) {
        if (!account.isSignedIn) return@withContext emptyList()
        runCatching {
            YouTube.library(FOLLOWED_ARTISTS_BROWSE_ID)
                .getOrThrow()
                .items
                .filterIsInstance<ArtistItem>()
                .map { artist ->
                    SearchItem(
                        uri = "$ARTIST_PREFIX${artist.id}",
                        title = artist.title,
                        subtitle = "",
                        artworkUrl = artist.thumbnail?.atSize(COVER_SIZE),
                    )
                }
        }.getOrElse {
            android.util.Log.w(LOG_TAG, "followed artists unavailable: $it")
            emptyList()
        }
    }

    /**
     * YouTube Music's own home page, shelf by shelf.
     *
     * `FEmusic_home` is the page the app itself opens on, and it is the only
     * way to get what YouTube actually wants to show: signed in it is built
     * around what the account listens to, signed out it is charts and moods.
     * Either way the shelves arrive already titled, so nothing here has to
     * invent a name for them.
     *
     * A page at a time, because that is how it is served. The first response
     * carries four or five shelves and a token; the twenty that follow — the
     * mixes, the artists, the charts, the moods — only exist behind it. Reading
     * one response and stopping is what made this page look like an app with
     * nothing in it.
     */
    override suspend fun homeFeed(cursor: String?, params: String?): HomeFeed =
        withContext(Dispatchers.IO) {
            val page = YouTube.home(continuation = cursor, params = params).getOrThrow()
            HomeFeed(
                rows = page.sections.map(::toHomeRow).filterNot { it.isEmpty },
                cursor = page.continuation,
                chips = page.chips.orEmpty().map { chip ->
                    HomeChip(
                        title = chip.title,
                        // The chip that clears the filter has no view of its
                        // own: it is the home page, which is what null asks for.
                        params = chip.endpoint?.params,
                    )
                },
            )
        }

    private fun toHomeRow(section: HomePage.Section): HomeRow {
        val songs = section.items.filterIsInstance<SongItem>()
        // An episode plays like a song and is listed like one; the only
        // difference the queue would notice is its length.
        val episodes = section.items.filterIsInstance<EpisodeItem>()
        return HomeRow(
            title = section.title,
            strapline = section.label,
            tracks = songs.map(::toCatalogTrack) + episodes.map(::toCatalogTrack),
            // Everything that is not a song opens something: an album, a
            // playlist, an artist. They travel as one list because the row
            // draws them identically.
            items = section.items.filterNot { it is SongItem || it is EpisodeItem }
                .mapNotNull(::toCatalogPlaylist),
        )
    }

    private fun toCatalogPlaylist(item: YTItem): CatalogPlaylist? {
        val uri = when (item) {
            is AlbumItem -> "$PLAYLIST_PREFIX${item.playlistId}"
            is PlaylistItem -> "$PLAYLIST_PREFIX${item.id}"
            is ArtistItem -> "$ARTIST_PREFIX${item.id}"
            // A show is a playlist of episodes, and opens as one.
            is PodcastItem -> "$PLAYLIST_PREFIX${item.id}"
            else -> return null
        }
        return CatalogPlaylist(
            uri = uri,
            name = item.title,
            artworkUrl = item.thumbnail?.atSize(COVER_SIZE),
            // What the official app writes under the name: who made it, or how
            // many listen to it. Without it half a shelf of mixes is a column
            // of covers with interchangeable titles.
            subtitle = when (item) {
                is AlbumItem -> item.artists?.joinToString(", ") { it.name }
                is PlaylistItem -> item.author?.name
                is PodcastItem -> item.author?.name
                // An artist has no line worth writing: the portrait and the
                // name are the whole of it, and YouTube's own shelf says the
                // same nothing under theirs.
                is ArtistItem -> null
                else -> null
            }?.takeIf { it.isNotBlank() },
            isArtist = item is ArtistItem,
        )
    }

    private fun toCatalogTrack(item: EpisodeItem) = CatalogTrack(
        uri = "$TRACK_PREFIX${item.id}",
        name = item.title,
        artist = item.author?.name.orEmpty(),
        album = item.podcast?.name.orEmpty(),
        durationMs = item.duration?.takeIf { it > 0 }?.times(1000L) ?: 0L,
        explicit = item.explicit,
        artworkUrl = item.thumbnail,
    )

    private fun toCatalogTrack(item: SongItem) = CatalogTrack(
        uri = "$TRACK_PREFIX${item.id}",
        name = item.title,
        artist = item.artists.joinToString(", ") { it.name },
        album = item.album?.name.orEmpty(),
        durationMs = item.duration?.takeIf { it > 0 }?.times(1000L) ?: 0L,
        explicit = item.explicit,
        artworkUrl = item.thumbnail.atSize(COVER_SIZE),
    )

    override suspend fun tracksOf(uri: String): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val id = uri.substringAfterLast(':')
        when {
            uri.startsWith(TRACK_PREFIX) -> emptyList()

            // An artist has no track list of its own, so what "open an artist"
            // means here is the same as everywhere else: their best-known
            // songs.
            uri.startsWith(ARTIST_PREFIX) ->
                YouTube.artist(id).getOrThrow()
                    .sections
                    .flatMap { it.items }
                    .filterIsInstance<SongItem>()
                    .map(::toCatalogTrack)

            else -> YouTube.playlist(id).getOrThrow()
                .songs
                .map(::toCatalogTrack)
        }
    }

    /**
     * lossless.wtf first, LrcLib for everything it does not have.
     *
     * The TTML archive is the better answer where it has the track and much the
     * smaller of the two, so it is asked first and LrcLib carries the rest of
     * the catalogue as it did before.
     */
    private val lyricsJson = Json { ignoreUnknownKeys = true }

    override suspend fun lyrics(
        uri: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Lyrics? {
        DownloadExtras.lyrics(uri)?.let { raw ->
            runCatching { lyricsJson.decodeFromString<Lyrics>(raw) }.getOrNull()?.let { return it }
        }

        val found = Lossless.lyrics(title, artist, durationMs)
            ?: LrcLib.lyrics(title, artist, durationMs)
            ?: LyricsOvh.lyrics(title, artist, durationMs)

        if (found != null) {
            runCatching { DownloadExtras.rememberLyrics(uri, lyricsJson.encodeToString(found)) }
        }
        return found
    }

    override val canEditPlaylists: Boolean
        get() = account.isSignedIn

    override suspend fun createPlaylist(name: String): CatalogPlaylist =
        withContext(Dispatchers.IO) {
            // Blocking upstream, deliberately not wrapped in runCatching there:
            // a failure has to reach the caller, which is a screen waiting to
            // open what it just made.
            val id = YouTube.createPlaylist(name)
            CatalogPlaylist(uri = "$PLAYLIST_PREFIX$id", name = name, artworkUrl = null)
        }

    override suspend fun renamePlaylist(uri: String, name: String) {
        withContext(Dispatchers.IO) {
            YouTube.renamePlaylist(uri.substringAfterLast(':'), name).getOrThrow()
        }
    }

    override suspend fun deletePlaylist(uri: String) {
        withContext(Dispatchers.IO) {
            YouTube.deletePlaylist(uri.substringAfterLast(':')).getOrThrow()
        }
    }

    override fun owns(uri: String) = uri.startsWith(URI_SCHEME)

    override fun createPlayer(host: PlaybackHost): Player =
        YouTubePlayerFactory.create(host)

    companion object {
        const val URI_SCHEME = "ytmusic:"
        const val TRACK_PREFIX = "ytmusic:track:"
        const val PLAYLIST_PREFIX = "ytmusic:playlist:"
        const val ARTIST_PREFIX = "ytmusic:artist:"

        /** YouTube Music's own browse id for the account's playlist library. */
        private const val LOG_TAG = "SquareYouTube"
        private const val LIKED_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
        private const val SAVED_ALBUMS_BROWSE_ID = "FEmusic_liked_albums"
        private const val FOLLOWED_ARTISTS_BROWSE_ID = "FEmusic_library_corpus_track_artists"

        /** The `list=` id of a playlist URL. */
        private fun playlistIdOf(url: String?): String? =
            runCatching { android.net.Uri.parse(url ?: return null) }
                .getOrNull()?.getQueryParameter("list")

        /** The id half of a `ytmusic:track:` URI, for the stream resolver. */
        fun videoIdOfUri(uri: String): String = uri.substringAfterLast(':')

        private const val PLAYLIST_URL = "https://www.youtube.com/playlist?list="

        private val ANONYMOUS = BackendAuthState.LoggedIn("YouTube Music")

        /** `https://www.youtube.com/watch?v=<id>` and its short forms. */
        private fun videoIdOf(url: String?): String? {
            val parsed = runCatching { android.net.Uri.parse(url ?: return null) }.getOrNull()
                ?: return null
            return parsed.getQueryParameter("v")
                ?: parsed.lastPathSegment?.takeIf { parsed.host?.contains("youtu.be") == true }
        }
    }
}
