package dev.lelonio.square.data

import dev.lelonio.square.auth.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Builds the Web API client.
 *
 * The auth interceptor is where most third-party clients go wrong: they attach a
 * token captured at construction time and never notice it expiring. Here every
 * request asks [TokenStore] for a token, and [TokenStore] refreshes under a
 * mutex, so a burst of parallel requests around expiry produces exactly one
 * refresh and no lost rotation.
 */
object ApiFactory {

    private const val BASE_URL = "https://api.spotify.com/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * @param baseClient the process-wide client whose connection pool, DNS cache
     *   and dispatcher this one should share. Null builds a stack of its own,
     *   which is what a test wants and what nothing else does: two independent
     *   pools to the same handful of hosts is two sets of sockets and two DNS
     *   caches for no gain. See SquareApplication's `sharedHttpClient`.
     */
    fun create(
        tokens: TokenStore,
        fallbackTokens: TokenStore? = null,
        nativeToken: (() -> String?)? = null,
        baseClient: OkHttpClient? = null,
        countryProvider: (() -> String)? = null,
        debug: Boolean = false,
    ): SpotifyApi {
        val clientBuilder = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            .addInterceptor(AuthInterceptor(tokens, fallbackTokens, nativeToken))
            .addInterceptor(RateLimitInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (countryProvider != null) {
                    addInterceptor(MarketInterceptor(countryProvider))
                }
                if (debug) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().setLevel(
                            okhttp3.logging.HttpLoggingInterceptor.Level.BASIC,
                        ),
                    )
                }
            }

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(clientBuilder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpotifyApi::class.java)
    }

    /**
     * Injects the listener's account market into Spotify catalog endpoints when
     * no explicit market parameter is present.
     *
     * Without a market parameter on endpoints like playlist tracks or album tracks,
     * Spotify returns the raw catalog item without regional track relinking, which
     * causes tracks to appear unplayable or missing for users outside the US.
     */
    private class MarketInterceptor(
        private val countryProvider: () -> String,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            val path = url.encodedPath

            if (url.queryParameter("market") == null && shouldAddMarket(path)) {
                val raw = countryProvider()
                val country = raw.takeIf { it.length == 2 && it.all(Char::isLetter) && it != "from_token" }
                    ?: java.util.Locale.getDefault().country.takeIf { it.length == 2 && it.all(Char::isLetter) }
                    ?: "US"
                val newUrl = url.newBuilder()
                    .addQueryParameter("market", country.uppercase())
                    .build()
                return chain.proceed(request.newBuilder().url(newUrl).build())
            }
            return chain.proceed(request)
        }

        private fun shouldAddMarket(path: String): Boolean {
            return (path.startsWith("/v1/playlists/") && path.endsWith("/tracks")) ||
                (path.startsWith("/v1/artists/") && path.endsWith("/top-tracks")) ||
                (path.startsWith("/v1/artists/") && path.endsWith("/albums")) ||
                path.startsWith("/v1/albums/") ||
                path.startsWith("/v1/tracks/") ||
                path == "/v1/search"
        }
    }


    /**
     * Waits out a single 429 when Spotify says how long to wait.
     *
     * Spotify rate-limits per app over a rolling window and answers with
     * `Retry-After` in seconds. Retrying once, only when the wait is short
     * enough to be worth blocking on, turns the common brief throttle into a
     * slow request instead of a visible error — while a long back-off is still
     * surfaced rather than silently stalling the UI.
     */
    private class RateLimitInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // Still inside a long back-off: answered here, without asking.
            //
            // Spotify puts a developer app that went over its quota on hold
            // for hours (a report showed Retry-After=70220, nineteen of them),
            // and the app went on asking: seven hundred more 429s in a few
            // minutes, every one of them a reason for Spotify to keep it there.
            val waitMs = blockedUntil - android.os.SystemClock.elapsedRealtime()
            if (waitMs > 0) {
                return Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Rate limited, waiting")
                    .header("Retry-After", ((waitMs + 999) / 1000).toString())
                    .body("".toResponseBody(null))
                    .build()
            }

            val response = chain.proceed(chain.request())

            // Spotify's gateway answers 502 and 503 a few times a day for no
            // reason anybody can act on, and it is over by the time the message
            // reaches the screen. One immediate retry turns "Errore 502" on an
            // artist page into a page that simply loaded.
            if (response.code == 502 || response.code == 503) {
                android.util.Log.w(TAG, "upstream ${response.code}, retrying once")
                response.close()
                return chain.proceed(chain.request())
            }

            if (response.code != 429) return response

            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            android.util.Log.w(TAG, "rate limited, Retry-After=${retryAfter ?: "absent"}")
            if (retryAfter != null && retryAfter > HOLD_FROM_SECONDS) {
                blockedUntil = android.os.SystemClock.elapsedRealtime() + retryAfter * 1000
                android.util.Log.w(TAG, "holding Web API requests for ${retryAfter}s, as Spotify asked")
            }
            if (retryAfter == null || retryAfter > MAX_WAIT_SECONDS) return response

            response.close()
            try {
                // +1s: Retry-After is whole seconds, so waiting exactly that
                // long can land a hair inside the window and burn the retry.
                Thread.sleep((retryAfter + 1) * 1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted while waiting out a rate limit", e)
            }
            return chain.proceed(chain.request())
        }

        private companion object {
            /**
             * Short back-offs (<=3s) can be waited out safely.
             * Longer back-offs should not block OkHttp dispatcher threads so that
             * subsequent requests and fallbacks can proceed without hanging the UI.
             */
            const val MAX_WAIT_SECONDS = 3L

            /** A back-off longer than this is a hold, and nothing is asked until it ends. */
            const val HOLD_FROM_SECONDS = 60L

            /** When the current hold ends, on the elapsed-realtime clock; 0 for none. */
            @Volatile
            var blockedUntil = 0L

            const val TAG = "SquareApi"
        }
    }

    private class AuthInterceptor(
        private val tokens: TokenStore,
        private val fallbackTokens: TokenStore? = null,
        private val nativeToken: (() -> String?)? = null,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            try {
                return proceedWithAuth(chain)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("AuthInterceptor failed: ${e.message}", e)
            }
        }

        private fun proceedWithAuth(chain: Interceptor.Chain): Response {
            val candidates = buildList<Candidate> {
                if (tokens.isLoggedIn) {
                    add(Candidate("webApi", tokens))
                }
                if (fallbackTokens?.isLoggedIn == true) {
                    add(Candidate("account", fallbackTokens))
                }
                if (nativeToken != null) {
                    add(Candidate("native", null, nativeToken))
                }
            }

            if (candidates.isEmpty()) {
                throw IOException("No Spotify access token available; user is not logged in")
            }

            var lastResponse: Response? = null

            for ((index, candidate) in candidates.withIndex()) {
                val token = runCatching {
                    runBlocking { candidate.getToken() }
                }.getOrNull()

                if (token == null) continue

                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()

                android.util.Log.i("SquareApi", "-> ${request.method} ${request.url} [candidate: ${candidate.name}]")
                var response = chain.proceed(request)
                android.util.Log.i("SquareApi", "<- ${response.code} ${request.url} [candidate: ${candidate.name}]")
                if (!response.isSuccessful) {
                    val peek = runCatching { response.peekBody(512).string() }.getOrNull()
                    android.util.Log.w("SquareApi", "<- error body: $peek")
                }

                // If 401 Unauthorized, try forcing an immediate refresh on this token store once
                if (response.code == 401 && candidate.store != null) {
                    val refreshed = runCatching {
                        runBlocking { candidate.store.forceRefresh() }
                    }.getOrNull()
                    if (refreshed != null) {
                        response.close()
                        val retryRequest = chain.request().newBuilder()
                            .header("Authorization", "Bearer $refreshed")
                            .build()
                        response = chain.proceed(retryRequest)
                    }
                }

                // If successful or an expected response (not 401 Unauthorized and not 403 Forbidden when candidates remain), return it.
                // 429 Rate Limited is handled by RateLimitInterceptor waiting out backoff; switching credentials on 429 risks account flagging.
                if (response.isSuccessful || (response.code != 401 && response.code != 403)) {
                    return response
                }

                val hasMoreCandidates = index < candidates.lastIndex
                if (hasMoreCandidates) {
                    android.util.Log.w("SquareApi", "HTTP ${response.code} with ${candidate.name} token; closing response and trying next candidate")
                    response.close()
                } else {
                    lastResponse = response
                }
            }

            return lastResponse ?: chain.proceed(chain.request())
        }

        private class Candidate(
            val name: String,
            val store: TokenStore?,
            val supplier: (() -> String?)? = null,
        ) {
            suspend fun getToken(): String? = when {
                store != null -> store.validAccessToken()
                supplier != null -> supplier.invoke()
                else -> null
            }
        }
    }
}
