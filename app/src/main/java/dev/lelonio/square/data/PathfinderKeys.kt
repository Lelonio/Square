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
        const val DEFAULT_VERSION = "1.2.97.155.g5dd0dcaf-development"
    }
}
