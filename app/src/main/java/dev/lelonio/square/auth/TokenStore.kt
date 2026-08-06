package dev.lelonio.square.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists OAuth tokens in an AES-GCM encrypted preferences file backed by a
 * keystore master key, and hands out access tokens that are guaranteed fresh.
 *
 * [validAccessToken] serialises refreshes behind a mutex. Without it, several
 * screens hitting a just-expired token would each start their own refresh, and
 * because Spotify may rotate the refresh token the losers would persist a token
 * the server has already invalidated — the failure mode that makes most of these
 * clients log you out at random.
 */
class TokenStore(
    context: Context,
    fileName: String = FILE_NAME,
    /**
     * Which OAuth application these tokens belong to.
     *
     * A lambda rather than a value because the Web API session's client id is
     * supplied by the user at runtime and can change without the store being
     * rebuilt. Refreshing against the wrong id fails, so it must be read fresh
     * on every refresh.
     */
    private val clientId: () -> String = { SpotifyOAuth.CLIENT_ID },
) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val refreshLock = Mutex()

    /**
     * True when a request can be authenticated right now.
     *
     * A refresh token is the normal case, but Spotify is not obliged to issue
     * one; keying only off it would make a successful login that returned just
     * an access token look logged out.
     */
    val isLoggedIn: Boolean
        get() = prefs.contains(KEY_REFRESH) || currentIfFresh() != null

    fun save(tokens: SpotifyOAuth.Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .apply {
                tokens.refreshToken?.let { putString(KEY_REFRESH, it) }
            }
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtMillis)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /**
     * Returns an access token valid for at least [SKEW_MILLIS], refreshing it
     * first if needed.
     *
     * @throws IllegalStateException if there is no stored session
     */
    suspend fun validAccessToken(): String {
        currentIfFresh()?.let { return it }

        return refreshLock.withLock {
            // Another caller may have refreshed while we waited for the lock.
            currentIfFresh()?.let { return@withLock it }

            val refreshToken = prefs.getString(KEY_REFRESH, null)
                ?: error("not logged in")

            val tokens = try {
                SpotifyOAuth.refresh(refreshToken, clientId())
            } catch (e: SpotifyOAuth.SessionExpired) {
                // Drop the dead session here rather than leaving callers to
                // recognise it: otherwise every later attempt retries the same
                // revoked token forever and the app never offers a way back in.
                clear()
                throw e
            }
            save(tokens)
            tokens.accessToken
        }
    }

    private fun currentIfFresh(): String? {
        val token = prefs.getString(KEY_ACCESS, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        return token.takeIf { System.currentTimeMillis() < expiresAt - SKEW_MILLIS }
    }

    private companion object {
        const val FILE_NAME = "square_tokens"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"

        /** Refresh early so a token cannot expire mid-request. */
        const val SKEW_MILLIS = 60_000L
    }
}
