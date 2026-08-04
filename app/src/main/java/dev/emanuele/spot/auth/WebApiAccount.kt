package dev.emanuele.spot.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own Spotify application, used for Web API calls only.
 *
 * Playback authenticates as librespot's shared "keymaster" client, which is the
 * only id the access point accepts. That same id is useless for
 * `api.spotify.com`: the Web API meters requests per application over a rolling
 * window, and since every librespot-based client in the world shares that id,
 * its quota is permanently spent — which is why search answered 429 here no
 * matter how little this app asked for.
 *
 * The fix is the one Outify settled on: the user registers their own app in the
 * Spotify developer dashboard and pastes its client id, so Web API calls are
 * metered against a quota only they use. No client secret is involved — the flow
 * is PKCE, so nothing confidential is stored on the device.
 *
 * The two sessions are kept deliberately separate. Tokens are not
 * interchangeable between client ids, and mixing them is what would produce the
 * "logged in but every request is 401" state.
 */
class WebApiAccount(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _clientId = MutableStateFlow(prefs.getString(KEY_CLIENT_ID, null))

    /** The registered client id, or null while the user has not set one. */
    val clientId: StateFlow<String?> = _clientId.asStateFlow()

    /**
     * Tokens for that application.
     *
     * A store of its own rather than a second set of keys in the playback
     * store: [TokenStore.clear] wipes the file, and a dead Web API session must
     * not log the user out of playback.
     */
    val tokens = TokenStore(
        context,
        fileName = FILE_NAME_TOKENS,
        clientId = { _clientId.value ?: SpotifyOAuth.CLIENT_ID },
    )

    /** True when search and the rest of the Web API can be used. */
    val isReady: Boolean get() = _clientId.value != null && tokens.isLoggedIn

    /**
     * Records a client id, discarding any tokens issued for a previous one.
     *
     * Keeping them would leave a refresh token that only the old application can
     * redeem, and the failure surfaces much later as an unexplained 400.
     */
    fun setClientId(value: String) {
        val trimmed = value.trim()
        if (trimmed == _clientId.value) return
        tokens.clear()
        prefs.edit().putString(KEY_CLIENT_ID, trimmed.ifEmpty { null }).apply()
        _clientId.value = trimmed.ifEmpty { null }
    }

    fun disconnect() {
        tokens.clear()
    }

    private companion object {
        const val FILE_NAME = "spot_webapi"
        const val FILE_NAME_TOKENS = "spot_webapi_tokens"
        const val KEY_CLIENT_ID = "client_id"
    }
}
