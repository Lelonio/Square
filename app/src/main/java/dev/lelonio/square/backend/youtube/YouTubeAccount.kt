package dev.lelonio.square.backend.youtube

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in Google account, for the YouTube Music backend.
 *
 * There is no OAuth here, and that is not a shortcut: YouTube Music has no
 * public API and issues no tokens to third parties. What `music.youtube.com`
 * itself uses is a session cookie plus a `SAPISIDHASH` header derived from it,
 * so signing in means letting the user log into Google in a web view and
 * keeping the cookie it leaves behind — see [YouTubeLoginScreen].
 *
 * Encrypted at rest like the Spotify tokens are: this cookie is full access to
 * the user's Google account, which makes it the most sensitive thing this app
 * stores.
 */
class YouTubeAccount(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** The display name of the signed-in account, or null when signed out. */
    private val _accountName = MutableStateFlow(prefs.getString(KEY_NAME, null))
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    val isSignedIn: Boolean get() = prefs.getString(KEY_COOKIE, null) != null

    init {
        // Before anything asks the module for a page: the cookie is what turns
        // its calls from "the anonymous catalogue" into "this account's".
        applyToInnerTube()
    }

    /**
     * Hands the stored session to the InnerTube client.
     *
     * The three values travel together and are only meaningful together:
     * `visitorData` and `dataSyncId` identify which of a Google account's
     * several YouTube channels is being asked about, and a cookie without them
     * reads the wrong one.
     */
    private fun applyToInnerTube() {
        YouTube.cookie = prefs.getString(KEY_COOKIE, null)
        YouTube.visitorData = prefs.getString(KEY_VISITOR_DATA, null).orEmpty()
        YouTube.dataSyncId = prefs.getString(KEY_DATA_SYNC_ID, null).orEmpty()
    }

    fun save(cookie: String, visitorData: String, dataSyncId: String, name: String) {
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            .putString(KEY_VISITOR_DATA, visitorData)
            .putString(KEY_DATA_SYNC_ID, dataSyncId)
            .putString(KEY_NAME, name)
            .apply()
        _accountName.value = name
        applyToInnerTube()
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _accountName.value = null
        applyToInnerTube()
    }

    private companion object {
        const val FILE_NAME = "square_youtube_account"
        const val KEY_COOKIE = "cookie"
        const val KEY_VISITOR_DATA = "visitor_data"
        const val KEY_DATA_SYNC_ID = "data_sync_id"
        const val KEY_NAME = "name"
    }
}
