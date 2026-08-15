package dev.lelonio.square.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * Shared by every store over the same file, not owned by this instance.
     *
     * The container hands out one store and that is how it should stay, but a
     * second instance is a single line to write and its cost is invisible until
     * days later: two locks are no lock, both callers refresh the same token,
     * and whichever loses is told the session was revoked. Keying the lock on
     * the file makes the guarantee belong to the tokens rather than to whoever
     * remembered to reuse the object.
     */
    private val refreshLock = lockFor(fileName)

    private val _sessionLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emitted when a stored session is dropped because Spotify refused it.
     *
     * Something has to say so out loud. A session that ends quietly leaves the
     * app answering nothing and looking like it is merely slow, and the way back
     * in is a screen the user has to go looking for. Told about it, the app can
     * offer the one tap that fixes it.
     */
    val sessionLost: SharedFlow<Unit> = _sessionLost.asSharedFlow()

    /**
     * True when a request can be authenticated right now.
     *
     * A refresh token is the normal case, but Spotify is not obliged to issue
     * one; keying only off it would make a successful login that returned just
     * an access token look logged out.
     */
    val isLoggedIn: Boolean
        get() = prefs.contains(KEY_REFRESH) || currentIfFresh() != null

    /**
     * Written synchronously, and that is the point.
     *
     * Spotify hands back a new refresh token on every refresh and retires the
     * one that was used, so the moment a refresh succeeds the copy on disk is
     * the only way back in. `apply()` returns before the write lands: a process
     * killed in that window — which for a music app is any moment the user
     * leaves it — came back holding a token Spotify had already retired, was
     * told the session was revoked, and asked the user to sign in again.
     */
    fun save(tokens: SpotifyOAuth.Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .apply {
                tokens.refreshToken?.let { putString(KEY_REFRESH, it) }
            }
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtMillis)
            .commit()
    }

    fun clear() = prefs.edit().clear().commit()

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
                // One more look before giving up. Spotify retires a refresh
                // token as it issues the next one, so "revoked" is also what a
                // token that has just been replaced looks like: if what is on
                // disk is no longer what was tried, somebody else refreshed
                // while this call was in flight and that answer is good.
                val current = prefs.getString(KEY_REFRESH, null)
                if (current != null && current != refreshToken) {
                    return@withLock currentIfFresh()
                        ?: SpotifyOAuth.refresh(current, clientId())
                            .also(::save)
                            .accessToken
                }
                // Drop the dead session here rather than leaving callers to
                // recognise it: otherwise every later attempt retries the same
                // revoked token forever and the app never offers a way back in.
                clear()
                _sessionLost.tryEmit(Unit)
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

        private val locks = mutableMapOf<String, Mutex>()

        fun lockFor(fileName: String): Mutex = synchronized(locks) {
            locks.getOrPut(fileName) { Mutex() }
        }
    }
}
