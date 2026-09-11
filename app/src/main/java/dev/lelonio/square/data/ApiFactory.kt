package dev.lelonio.square.data

import dev.lelonio.square.auth.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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
             * Spotify's back-offs on this client id run to tens of seconds.
             * Blocking a background OkHttp thread that long is cheaper than
             * showing an error the user can only answer by tapping retry —
             * which costs another request against the same quota.
             */
            const val MAX_WAIT_SECONDS = 90L
            const val TAG = "SquareApi"
        }
    }

    private class AuthInterceptor(
        private val tokens: TokenStore,
        private val fallbackTokens: TokenStore? = null,
        private val nativeToken: (() -> String?)? = null,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // OkHttp interceptors are blocking by contract and always run on a
            // background thread, so bridging into the suspending token store
            // with runBlocking is safe here.
            //
            // Everything must leave as an IOException: OkHttp's async dispatcher
            // only routes IOException to onFailure and rethrows anything else on
            // its own thread, which takes the whole process down instead of
            // failing the one call.
            var usedFallback = false
            var usedNative = false
            val token = try {
                runBlocking {
                    if (tokens.isLoggedIn) {
                        try {
                            tokens.validAccessToken()
                        } catch (e: Exception) {
                            if (fallbackTokens?.isLoggedIn == true) {
                                usedFallback = true
                                fallbackTokens.validAccessToken()
                            } else {
                                usedNative = true
                                nativeToken?.invoke() ?: throw e
                            }
                        }
                    } else if (fallbackTokens?.isLoggedIn == true) {
                        usedFallback = true
                        fallbackTokens.validAccessToken()
                    } else {
                        usedNative = true
                        nativeToken?.invoke() ?: tokens.validAccessToken()
                    }
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("could not obtain a Spotify access token", e)
            }

            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            val response = chain.proceed(request)

            // If the current token is refused (401 or 403), retry with the next available token
            if ((response.code == 401 || response.code == 403) && (!usedFallback || !usedNative)) {
                android.util.Log.w("SquareApi", "HTTP ${response.code} with current token; retrying with backup token")
                response.close()
                val nextToken = try {
                    runBlocking {
                        if (!usedFallback && fallbackTokens?.isLoggedIn == true) {
                            usedFallback = true
                            fallbackTokens.validAccessToken()
                        } else if (!usedNative) {
                            usedNative = true
                            nativeToken?.invoke() ?: throw IOException("no backup token available")
                        } else {
                            throw IOException("no backup token available")
                        }
                    }
                } catch (e: Exception) {
                    throw IOException("could not obtain fallback access token", e)
                }
                val retryRequest = chain.request().newBuilder()
                    .header("Authorization", "Bearer $nextToken")
                    .build()
                return chain.proceed(retryRequest)
            }

            return response
        }
    }
}
