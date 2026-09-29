package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The identifiers Spotify's GraphQL gateway wants, kept where they can be
 * changed without an app release.
 *
 * A persisted query is addressed by the sha256 of a query Spotify already
 * knows, and it retires those hashes whenever its web client is rebuilt. Built
 * into the binary, that means the personalised home stops working one day and
 * stays broken until everyone installs a new version, for the sake of a
 * sixty-four character string. So the strings live in a file in this app's own
 * repository, and the binary carries only the last ones known to work.
 *
 * The same goes for the client version the gateway is told about, which ages
 * for the same reason and is fixed the same way.
 *
 * Nothing here is a secret and nothing here is trusted: the file names
 * queries, and a wrong value costs the pages those queries serve — the
 * personalised home, and Liked Songs, which has the Web API to fall back on.
 */
class PathfinderKeys(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    /** The hash for the `home` query, freshest first. */
    val home: String get() = prefs.getString(KEY_HOME, null) ?: DEFAULT_HOME

    /** The same for `getLikedSongs`; see SavedTracks. */
    val likedSongs: String get() = prefs.getString(KEY_LIKED, null) ?: DEFAULT_LIKED

    /** And for `fetchPlaylistContents`; see PlaylistContents. */
    val playlist: String get() = prefs.getString(KEY_PLAYLIST, null) ?: DEFAULT_PLAYLIST

    /** And for `areEntitiesInLibrary`, which answers the heart on a track. */
    val library: String get() = prefs.getString(KEY_LIBRARY, null) ?: DEFAULT_LIBRARY

    /** And for `searchDesktop`, which is what the search box asks. */
    val search: String get() = prefs.getString(KEY_SEARCH, null) ?: DEFAULT_SEARCH

    /** And for `browsePage`, which is a whole page of Spotify's own browse. */
    val browsePage: String get() = prefs.getString(KEY_BROWSE_PAGE, null) ?: DEFAULT_BROWSE_PAGE

    /** And `queryArtistOverview`, which is an artist's page; see ArtistPlaylists. */
    val artistOverview: String
        get() = prefs.getString(KEY_ARTIST_OVERVIEW, null) ?: DEFAULT_ARTIST_OVERVIEW

    /** And `browseSection`, for reading one of that page's rows past its first few. */
    val browseSection: String
        get() = prefs.getString(KEY_BROWSE_SECTION, null) ?: DEFAULT_BROWSE_SECTION

    /** And `playlistSection`, the row under a playlist; see RelatedPlaylists. */
    val playlistSection: String
        get() = prefs.getString(KEY_PLAYLIST_SECTION, null) ?: DEFAULT_PLAYLIST_SECTION

    /** The web client version the gateway is told about. */
    val appVersion: String get() = prefs.getString(KEY_VERSION, null) ?: DEFAULT_VERSION

    /**
     * Reads the file, at most once a day.
     *
     * Failure is silent and cheap: what is already known stays, and that is
     * either yesterday's copy or what shipped in the binary. This runs before
     * the home page is asked for, and the page is drawn either way.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val last = prefs.getLong(KEY_CHECKED, 0)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return@withContext

        runCatching {
            val request = Request.Builder().url(URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = JSONObject(response.body?.string().orEmpty())
                val edit = prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis())
                body.optString("home").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_HOME, it) }
                body.optString("likedSongs").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_LIKED, it) }
                body.optString("playlist").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_PLAYLIST, it) }
                body.optString("library").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_LIBRARY, it) }
                body.optString("search").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_SEARCH, it) }
                body.optString("browsePage").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_BROWSE_PAGE, it) }
                body.optString("browseSection").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_BROWSE_SECTION, it) }
                body.optString("artistOverview").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_ARTIST_OVERVIEW, it) }
                body.optString("playlistSection").takeIf { it.length == HASH_LENGTH }
                    ?.let { edit.putString(KEY_PLAYLIST_SECTION, it) }
                body.optString("appVersion").takeIf { it.isNotEmpty() }
                    ?.let { edit.putString(KEY_VERSION, it) }
                edit.apply()
            }
        }.onFailure { android.util.Log.i(TAG, "keeping the known query hashes: $it") }
    }

    private companion object {
        const val TAG = "PathfinderKeys"
        const val FILE_NAME = "square_pathfinder"
        const val KEY_HOME = "home"
        const val KEY_LIKED = "liked_songs"
        const val KEY_PLAYLIST = "playlist"
        const val KEY_LIBRARY = "library"
        const val KEY_SEARCH = "search"
        const val KEY_BROWSE_PAGE = "browse_page"
        const val KEY_BROWSE_SECTION = "browse_section"
        const val KEY_ARTIST_OVERVIEW = "artist_overview"
        const val KEY_PLAYLIST_SECTION = "playlist_section"
        const val KEY_VERSION = "app_version"
        const val KEY_CHECKED = "checked_at"

        /** Once a day: these change with Spotify's releases, not with ours. */
        const val INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** A sha256 in hex, and a way to notice a file that says something else. */
        const val HASH_LENGTH = 64

        const val URL =
            "https://raw.githubusercontent.com/${dev.lelonio.square.update.Updater.REPO}" +
                "/master/pathfinder.json"

        /** What was true when this version was built; see the note above. */
        const val DEFAULT_HOME =
            "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a"
        const val DEFAULT_LIKED =
            "c2c53c28f71da143c0753c22dc84d98b315cb4275472ea5a597c29338ae20b23"
        const val DEFAULT_PLAYLIST =
            "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a"
        const val DEFAULT_LIBRARY =
            "134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed"
        const val DEFAULT_SEARCH =
            "db61238974d27839a136c9dc02bfdbe3fab7635f21cf85976ebff9a1ee281345"
        const val DEFAULT_BROWSE_PAGE =
            "f5c4e6d668f5716464a231c1cc8b22c1cbf6ad68b09929fd7de813a30581298b"
        const val DEFAULT_BROWSE_SECTION =
            "b13c1cccbfcb6947753c2613411b3566485c21fd5f36d80a80bb64be61ba2d51"
        const val DEFAULT_ARTIST_OVERVIEW =
            "9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6"
        const val DEFAULT_PLAYLIST_SECTION =
            "2615df403a9043c1d7d3094fbeb4c9653b07b11a33d8081fbd31f0f7959ff4a1"
        const val DEFAULT_VERSION = "1.2.97.155.g5dd0dcaf-development"
    }
}
