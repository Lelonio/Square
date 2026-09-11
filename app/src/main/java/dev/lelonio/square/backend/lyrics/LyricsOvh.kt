package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fallback lyrics provider using the free and open Lyrics.ovh REST API.
 *
 * Useful for contemporary or regional tracks (e.g. Latin releases) that
 * have not yet been synced to LRCLIB or Spotify Musixmatch.
 */
object LyricsOvh {

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://api.lyrics.ovh/v1"
    private const val USER_AGENT = "SquareMusicApp/1.0"

    suspend fun lyrics(title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val cleanedTitle = title.songTitle()
            if (cleanedTitle.isBlank()) return@withContext null

            // Try each candidate artist (e.g. combined "CORKIDI, Alex Ponce", primary "CORKIDI", and featured "Alex Ponce")
            val candidates = artist.allArtists()

            for (candidate in candidates) {
                val rawLyrics = fetch(cleanedTitle, candidate)
                if (!rawLyrics.isNullOrBlank()) {
                    return@withContext LyricsEstimator.estimate(rawLyrics, durationMs)
                }
            }

            null
        }

    private fun fetch(title: String, artist: String): String? = runCatching {
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE_URL/$encodedArtist/$encodedTitle"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string()
            val json = JSONObject(body)
            json.optString("lyrics").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}
