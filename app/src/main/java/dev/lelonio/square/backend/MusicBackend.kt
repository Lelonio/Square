package dev.lelonio.square.backend

import androidx.media3.common.Player
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
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
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && items.isEmpty()
}

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
interface MusicBackend {

    val id: BackendId
    val authState: StateFlow<BackendAuthState>

    /** True once catalogue calls and playback can be expected to work. */
    val isReady: Boolean

    suspend fun refreshAuth()
    suspend fun logOut()

    suspend fun search(query: String, labels: SearchLabels): SearchResults

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
     */
    suspend fun homeRows(): List<HomeRow> = emptyList()

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
    suspend fun createPlaylist(name: String): CatalogPlaylist =
        throw UnsupportedOperationException()

    suspend fun renamePlaylist(uri: String, name: String): Unit =
        throw UnsupportedOperationException()

    /** Removes it from the account's library. */
    suspend fun deletePlaylist(uri: String): Unit =
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
