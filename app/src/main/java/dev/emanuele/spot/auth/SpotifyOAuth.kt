package dev.emanuele.spot.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.emanuele.spot.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Authorization Code + PKCE against Spotify's accounts service.
 *
 * The redirect target is a loopback listener rather than a custom scheme or an
 * App Link. That is not a shortcut: the client id below is librespot's, and the
 * only redirect URIs registered for it are `http://127.0.0.1:{port}/login`, so
 * an app-owned scheme would be rejected by the authorize endpoint. Loopback
 * redirects for native apps are the pattern described in RFC 8252 §7.3, and the
 * port is chosen at runtime because Spotify ignores it for loopback hosts.
 *
 * Using librespot's client id is what makes streaming work at all — a client id
 * registered in the Spotify developer dashboard authenticates fine against the
 * Web API but is refused by the access point. It is also the point at which this
 * app stops being something Spotify's terms permit.
 */
object SpotifyOAuth {

    /**
     * librespot's "keymaster" client id.
     *
     * The session must be configured with this same id — see the cache note in
     * the native engine — because login5 issues the access point's HTTP token
     * against whichever id the session carries, and it has to match the one the
     * OAuth token was minted for.
     */
    const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"

    /**
     * Loopback port registered for [CLIENT_ID].
     *
     * Fixed rather than ephemeral: Spotify rejects unregistered redirect URIs
     * with "redirect_uri: not matching configuration".
     */
    private const val REDIRECT_PORT = 5588

    /**
     * The exact URI the user must register in their own dashboard application.
     *
     * Shown in the settings screen: Spotify matches redirect URIs literally, so
     * a typed-from-memory variant is the likeliest way for the setup to fail.
     */
    const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT/login"

    private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    /**
     * Scopes librespot requests. `streaming` is the one the access point checks;
     * the rest back the library and playback-state screens in the UI.
     */
    private val SCOPES = listOf(
        "app-remote-control",
        "playlist-modify",
        "playlist-modify-private",
        "playlist-modify-public",
        "playlist-read",
        "playlist-read-collaborative",
        "playlist-read-private",
        "streaming",
        "user-follow-modify",
        "user-follow-read",
        "user-library-modify",
        "user-library-read",
        "user-modify",
        "user-modify-playback-state",
        "user-modify-private",
        "user-personalized",
        "user-read-currently-playing",
        "user-read-email",
        "user-read-play-history",
        "user-read-playback-position",
        "user-read-playback-state",
        "user-read-private",
        "user-read-recently-played",
        "user-top-read",
    )

    private const val TAG = "SpotOAuth"

    private val http = OkHttpClient()
    private val random = SecureRandom()

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMillis: Long,
    )

    /**
     * The stored session is gone for good — revoked, or superseded by a newer
     * login. Distinguished from other failures because retrying can never
     * succeed: the only way out is a fresh OAuth flow, so callers must discard
     * the saved tokens rather than back off and try again.
     */
    class SessionExpired(message: String) : IllegalStateException(message)

    /**
     * Runs the full interactive flow: opens a Custom Tab, waits for Spotify to
     * redirect back to the loopback listener, then exchanges the code.
     *
     * Suspends until the user finishes or the listener times out. Cancelling the
     * calling coroutine closes the socket and aborts.
     */
    suspend fun authorize(
        context: Context,
        clientId: String = CLIENT_ID,
        scopes: List<String> = SCOPES,
    ): Tokens = withContext(Dispatchers.IO) {
        val verifier = randomVerifier()
        val challenge = challengeFor(verifier)
        val state = randomState()

        LoopbackReceiver(REDIRECT_PORT, context.getString(R.string.browser_return)).use { receiver ->
            val redirectUri = receiver.redirectUri
            // The port is ephemeral, so without this there is no way to tell a
            // listener that never accepted from a browser that refused to load
            // the redirect.
            android.util.Log.i(TAG, "listening for OAuth redirect on $redirectUri")

            val authUri = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", scopes.joinToString(" "))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("state", state)
                .build()

            withContext(Dispatchers.Main) {
                CustomTabsIntent.Builder().build().apply {
                    // The caller is a ViewModel, so this is the application
                    // context; starting an activity from one without
                    // FLAG_ACTIVITY_NEW_TASK throws. Launching the tab in its
                    // own task is also what we want — the redirect comes back
                    // over the loopback socket, not through an activity result,
                    // so the browser has no reason to sit on our stack.
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.launchUrl(context, authUri)
            }

            val callback = receiver.awaitRedirect()
            android.util.Log.i(TAG, "redirect received")
            check(callback.state == state) { "OAuth state mismatch — request was not ours" }
            val code = callback.code ?: error("authorization denied: ${callback.error}")

            exchangeCode(code, verifier, redirectUri, clientId)
        }
    }

    /** Trades a refresh token for a fresh access token. */
    suspend fun refresh(
        refreshToken: String,
        clientId: String = CLIENT_ID,
    ): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        // Spotify may or may not rotate the refresh token; keep the old one when
        // the response omits it, otherwise the next refresh has nothing to use.
        postToken(form).let { it.copy(refreshToken = it.refreshToken ?: refreshToken) }
    }

    private fun exchangeCode(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
    ): Tokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return postToken(form)
    }

    private fun postToken(form: FormBody): Tokens {
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // `invalid_grant` is Spotify saying the refresh token is dead —
                // revoked, or replaced by a later login. Retrying is pointless.
                if (body.contains("invalid_grant")) {
                    throw SessionExpired("sessione scaduta o revocata: accedi di nuovo")
                }
                error("token request failed (${response.code}): $body")
            }
            val json = JSONObject(body)
            val expiresIn = json.optLong("expires_in", 3600)
            return Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000,
            )
        }
    }

    private fun randomVerifier(): String = ByteArray(64)
        .also(random::nextBytes)
        .let(::base64Url)

    private fun randomState(): String = ByteArray(16)
        .also(random::nextBytes)
        .let(::base64Url)

    private fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Single-shot loopback HTTP listener for the OAuth redirect.
 *
 * Binds an ephemeral port on 127.0.0.1, serves exactly one request, and closes.
 * Only the device's own browser can reach it.
 */
private class LoopbackReceiver(port: Int, private val doneMessage: String) : AutoCloseable {

    data class Callback(val code: String?, val state: String?, val error: String?)

    /**
     * Bound to the IPv4 loopback explicitly, on the exact registered port.
     *
     * `InetAddress.getLoopbackAddress()` returns `::1` on Android, so binding
     * through it leaves nothing listening on `127.0.0.1` — which is the literal
     * Spotify's registered redirect URIs use, and the browser then fails the
     * redirect with ERR_CONNECTION_REFUSED.
     */
    private val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))

    /** The one line the browser shows once the redirect lands, in the app's language. */
    private val response: String = buildString {
        val page = "<html><body><h2>$doneMessage</h2></body></html>"
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n")
        append("Content-Length: ${page.toByteArray().size}\r\n")
        append("Connection: close\r\n\r\n")
        append(page)
    }

    /** Derived from the socket, so the URI can never disagree with the bind. */
    val redirectUri: String =
        "http://${server.inetAddress.hostAddress}:${server.localPort}/login"

    init {
        // If the user abandons the browser we must not leak the thread forever.
        server.soTimeout = REDIRECT_TIMEOUT_MS
    }

    fun awaitRedirect(): Callback {
        val socket = server.accept()
        socket.use {
            val reader = it.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: error("empty redirect request")
            // "GET /login?code=...&state=... HTTP/1.1"
            val target = requestLine.split(' ').getOrNull(1)
                ?: error("malformed redirect request: $requestLine")
            val uri = Uri.parse("http://127.0.0.1$target")

            it.getOutputStream().writer().apply {
                write(response)
                flush()
            }

            return Callback(
                code = uri.getQueryParameter("code"),
                state = uri.getQueryParameter("state"),
                error = uri.getQueryParameter("error"),
            )
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private companion object {
        const val REDIRECT_TIMEOUT_MS = 5 * 60 * 1000
    }
}
