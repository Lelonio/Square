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

    fun create(tokens: TokenStore, debug: Boolean = false): SpotifyApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens))
            .addInterceptor(RateLimitInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (debug) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().setLevel(
                            okhttp3.logging.HttpLoggingInterceptor.Level.BASIC,
                        ),
                    )
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpotifyApi::class.java)
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

    private class AuthInterceptor(private val tokens: TokenStore) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // OkHttp interceptors are blocking by contract and always run on a
            // background thread, so bridging into the suspending token store
            // with runBlocking is safe here.
            //
            // Everything must leave as an IOException: OkHttp's async dispatcher
            // only routes IOException to onFailure and rethrows anything else on
            // its own thread, which takes the whole process down instead of
            // failing the one call.
            val token = try {
                runBlocking { tokens.validAccessToken() }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("could not obtain a Spotify access token", e)
            }

            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            return chain.proceed(request)
        }
    }
}
