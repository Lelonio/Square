package dev.lelonio.square.backend

import androidx.media3.common.Player
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SearchResults
import kotlinx.coroutines.flow.StateFlow

/** Which backend supplies catalogue and playback. */
enum class BackendId { SPOTIFY, YOUTUBE_MUSIC }

/** Where a [MusicBackend] stands with its own account/session. */
sealed interface BackendAuthState {
    data object LoggedOut : BackendAuthState
    data object Connecting : BackendAuthState
    data class LoggedIn(val displayName: String, val avatarUrl: String? = null) : BackendAuthState
    data class Failed(val message: String) : BackendAuthState
}

/**
 * What each kind of search result is called, in the app's language.
 *
 * Passed in rather than resolved by the backend: a backend has no context to
 * read resources from, and the strings are the UI's business anyway.
 */
data class SearchLabels(
    val artist: String,
    val album: String,
    val playlist: String,
)

/**
 * One shelf of a backend's home page.
 *
 * Either tracks or things to open, never both — that is how the services
 * themselves build a row, and a row that mixed the two would have no single
 * answer to what tapping it does.
 */
data class HomeRow(
    val title: String,
    val tracks: List<CatalogTrack> = emptyList(),
    /** Playlists, albums and artists alike: all of them open a track list. */
    val items: List<CatalogPlaylist> = emptyList(),
    /**
     * The small line above the title — "MIX", an artist's name, a genre.
     *
     * YouTube writes it for about half its shelves and it is most of what tells
     * two rows called "Radio" apart.
     */
    val strapline: String? = null,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && items.isEmpty()
}

/**
 * One of the filters above a home page: "Relax", "Workout", "Feel good".
 *
 * [params] is the service's own token for that view, passed straight back when
 * the chip is picked. Null on the chip that clears the filter.
 */
data class HomeChip(val title: String, val params: String?)

/**
 * A home page as it arrives: some shelves, and where the next ones are.
 *
 * The page is paged on purpose. YouTube's home opens with four or five shelves
 * and holds the other twenty behind a continuation token, which is why a home
 * read in one call looks like an app with nothing in it.
 */
data class HomeFeed(
    val rows: List<HomeRow> = emptyList(),
    /** Opaque, and handed back to fetch what comes after these rows. */
    val cursor: String? = null,
    /** Only on the first page; a continuation carries no chips of its own. */
    val chips: List<HomeChip> = emptyList(),
)

/**
 * A source of music: catalogue reads plus a [Player] that can play what the
 * catalogue points at.
 *
 * Every method mirrors something the UI already asks Spotify for directly, so
 * wrapping the existing code behind this changes who is asked, not what is
 * done. A backend that cannot answer something (YouTube Music has no Spotify
 * Connect device list, for instance) returns the empty value rather than
 * throwing: no screen should have to know which backend is active to stay
 * usable.
 */
/** A playlist as its edit sheet shows it. */
data class PlaylistDetails(
    val name: String,
    val description: String,
    val public: Boolean,
    val artworkUrl: String?,
)

interface MusicBackend {

    val id: BackendId
    val authState: StateFlow<BackendAuthState>

    /** True once catalogue calls and playback can be expected to work. */
    val isReady: Boolean

    suspend fun refreshAuth()
    suspend fun logOut()

    suspend fun search(query: String, labels: SearchLabels, offset: Int = 0): SearchResults

    /**
     * Whether the last search had nowhere to go.
     *
     * Only Spotify can be in this state, and only when the gateway will not
     * answer and no application has been registered either — the one case
     * where the screen has something to offer instead of an empty page.
     */
    val searchNeedsSetup: Boolean get() = false

    /** The signed-in account's own playlists; empty when logged out or unsupported. */
    suspend fun playlists(): List<CatalogPlaylist>

    /**
     * The backend's own home page, as it chooses to lay it out.
     *
     * Rows rather than named sections this app invents, because YouTube Music's
     * home really is a list of shelves with its own titles — "Listen again",
     * "Mixed for you", a chart — and picking a fixed set of them here would
     * throw away most of the page and mislabel the rest.
     *
     * Empty by default: a backend whose home is assembled from the account's
     * own data elsewhere has no use for it.
     *
     * @param cursor from a previous [HomeFeed], to read the next shelves.
     * @param params the token of a chip the user picked, to read that view.
     */
    suspend fun homeFeed(cursor: String? = null, params: String? = null): HomeFeed = HomeFeed()

    /**
     * What is new, as the service itself lays it out: its rows, under its own
     * titles, for the New tab.
     *
     * Empty by default. Spotify's tab is assembled elsewhere, out of the
     * gateway's browse pages; this is for a source whose page can be read as it
     * comes.
     */
    suspend fun newRows(): List<HomeRow> = emptyList()

    /** The same for the Radio tab: the service's own stations and mood lists. */
    suspend fun radioRows(): List<HomeRow> = emptyList()

    /**
     * The albums the account has saved, for the library's own shelf of them.
     *
     * Apart from [playlists] because the library filters by kind, and a shelf
     * that cannot say which is which has nothing to filter by.
     */
    suspend fun savedAlbums(): List<CatalogPlaylist> = emptyList()

    /** The artists the account follows, for the same shelf's artists chip. */
    suspend fun followedArtists(): List<SearchItem> = emptyList()

    /** Tracks of a playlist, album, or artist URI this backend owns. */
    suspend fun tracksOf(uri: String): List<CatalogTrack>

    /**
     * The words to a track, or null when nobody has them.
     *
     * Given the track's details rather than just its URI because not every
     * backend looks lyrics up by identity — the YouTube one matches on title,
     * artist and length against an outside database.
     */
    suspend fun lyrics(uri: String, title: String, artist: String, durationMs: Long): dev.lelonio.square.data.Lyrics? = null

    /**
     * Whether the account can be asked to make and change playlists.
     *
     * False when signed out, and on Spotify also when the user has not
     * registered their own application: the playback session's client id cannot
     * write. See `WebApiAccount`.
     */
    val canEditPlaylists: Boolean get() = false

    /** Creates an empty playlist and returns it, ready to be opened. */
    /**
     * Makes a playlist. The description and the visibility are taken where
     * the source keeps them; see [describesPlaylists].
     */
    suspend fun createPlaylist(
        name: String,
        description: String = "",
        public: Boolean = false,
    ): CatalogPlaylist = throw UnsupportedOperationException()

    /** Whether a new playlist can be given a description, a visibility and a cover. */
    val describesPlaylists: Boolean get() = false

    /** Sets a playlist's cover from a JPEG; see [describesPlaylists]. */
    suspend fun setPlaylistCover(uri: String, jpeg: ByteArray): Unit =
        throw UnsupportedOperationException()

    /** What the edit sheet starts from; null where the source only names a playlist. */
    suspend fun playlistDetails(uri: String): PlaylistDetails? = null

    /** Changes what [describesPlaylists] covers, or only the name elsewhere. */
    suspend fun updatePlaylist(uri: String, name: String, description: String, public: Boolean) =
        renamePlaylist(uri, name)

    suspend fun renamePlaylist(uri: String, name: String): Unit =
        throw UnsupportedOperationException()

    /** Removes it from the account's library. */
    suspend fun deletePlaylist(uri: String): Unit =
        throw UnsupportedOperationException()

    /**
     * The lists a track can be added to: the account's own, and the one that
     * keeps its liked songs where the source files those as a list.
     *
     * Not [playlists]: the library also holds lists somebody else made and the
     * account only saved, and offering those is offering a write that fails.
     * Empty where nothing can be written.
     */
    suspend fun writablePlaylists(): List<CatalogPlaylist> = emptyList()

    /** Whether a track can be taken out of this list by this account. */
    fun canWriteTo(playlistUri: String): Boolean = canEditPlaylists

    /** Appends a track to one of [writablePlaylists]. */
    suspend fun addToPlaylist(playlistUri: String, trackUri: String): Unit =
        throw UnsupportedOperationException()

    /** Takes a track back out of one of them. */
    suspend fun removeFromPlaylist(playlistUri: String, trackUri: String): Unit =
        throw UnsupportedOperationException()

    /** Whether the account follows an artist; null when the source cannot say. */
    suspend fun isFollowing(artistUri: String): Boolean? = null

    suspend fun setFollowing(artistUri: String, follow: Boolean): Unit =
        throw UnsupportedOperationException()

    /** Whether this backend's catalogue owns the given URI. */
    fun owns(uri: String): Boolean

    /**
     * Builds the [Player] this backend plays through.
     *
     * Called once by `PlaybackService` for the selected backend; the caller owns
     * the player from there — releasing it, and attaching it to the session.
     */
    fun createPlayer(host: PlaybackHost): Player
}

/**
 * What a backend's player needs from the service hosting it.
 *
 * Kept narrow on purpose: a player reaching back into `PlaybackService` would
 * couple every backend to Spotify's queue-saving and engine-restart logic, none
 * of which applies to a backend that has no native engine.
 */
interface PlaybackHost {
    val context: android.content.Context
    val looper: android.os.Looper
}
