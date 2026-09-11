package dev.lelonio.square.data

import android.content.res.Resources
import android.util.Base64
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel.ArtistPage
import dev.lelonio.square.ui.MainViewModel.ArtistRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves artist top tracks, albums, singles, followers, and monthly listeners
 * directly from Spotify's web player artist entity.
 *
 * Spotify's public Web API rate-limits librespot's shared client ID (HTTP 429)
 * for users without a custom developer app. Spotify's web player CDN renders
 * the full artist entity into the page HTML without authentication or API quota limits,
 * providing the top 5 tracks, all albums, singles/EPs, monthly listeners, and bio.
 */
object SpotifyWebArtist {
    private const val TAG = "SpotifyWebArtist"
    private val scriptRegex = Regex("<script[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    /** Session cache to prevent redundant scrapes across multiple callers. */
    private val sessionCache = ConcurrentHashMap<String, ArtistPage>()

    fun clearCache() {
        sessionCache.clear()
    }

    suspend fun fetch(
        artistId: String,
        client: OkHttpClient,
        resources: Resources? = null,
    ): ArtistPage? = withContext(Dispatchers.IO) {
        sessionCache[artistId]?.let { return@withContext it }

        val url = "https://open.spotify.com/artist/$artistId"
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .header("Accept-Language", Locale.getDefault().language)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "HTTP ${response.code} for artist $artistId")
                    return@withContext null
                }
                val html = response.body?.string() ?: return@withContext null
                val page = parseHtml(html, artistId, resources)
                if (page != null) {
                    sessionCache[artistId] = page
                }
                page
            }
        }.onFailure {
            android.util.Log.w(TAG, "Failed to load web artist $artistId: ${it.message}")
        }.getOrNull()
    }

    fun parseHtml(html: String, artistId: String, resources: Resources? = null): ArtistPage? {
        val matches = scriptRegex.findAll(html)
        for (match in matches) {
            val content = match.groupValues[1].trim()
            if (content.length < 500 || !content.startsWith("eyJ")) continue

            val decoded = runCatching {
                val bytes = Base64.decode(content, Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            }.getOrNull() ?: continue

            if (!decoded.contains(artistId)) continue

            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: continue
            val items = root.optJSONObject("entities")?.optJSONObject("items") ?: continue
            val artistKey = "spotify:artist:$artistId"
            val artistData = items.optJSONObject(artistKey)
                ?: items.keys().asSequence().firstOrNull { it.startsWith("spotify:artist:") }?.let { items.optJSONObject(it) }
                ?: continue

            return parseArtistData(artistData, artistId, resources)
        }
        return null
    }

    private fun parseArtistData(data: JSONObject, artistId: String, resources: Resources?): ArtistPage {
        val profile = data.optJSONObject("profile")
        val name = profile?.optString("name").orEmpty()
        val biography = profile?.optJSONObject("biography")?.optString("text")

        val stats = data.optJSONObject("stats")
        val followersCount = stats?.optInt("followers") ?: 0
        val monthlyListenersCount = stats?.optLong("monthlyListeners") ?: 0L

        val monthlyListenersFormatted = if (monthlyListenersCount > 0) {
            val locale = Locale.getDefault()
            val formattedCount = when {
                monthlyListenersCount >= 1_000_000 -> String.format(locale, "%.1f M", monthlyListenersCount / 1_000_000.0)
                monthlyListenersCount >= 1_000 -> String.format(locale, "%.1f K", monthlyListenersCount / 1_000.0)
                else -> String.format(locale, "%,d", monthlyListenersCount)
            }
            if (resources != null) {
                val quantity = monthlyListenersCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                resources.getQuantityString(R.plurals.monthly_listeners, quantity, formattedCount)
            } else {
                formattedCount
            }
        } else null

        val visuals = data.optJSONObject("visuals")
        val avatarUrl = extractBestImage(visuals?.optJSONObject("avatarImage")?.optJSONArray("sources"), 320)

        val disco = data.optJSONObject("discography")

        // Parse top tracks (strictly top 5)
        val topTracksArray = disco?.optJSONObject("topTracks")?.optJSONArray("items")
        val tracks = mutableListOf<CatalogTrack>()
        if (topTracksArray != null) {
            for (i in 0 until topTracksArray.length()) {
                val item = topTracksArray.optJSONObject(i) ?: continue
                val track = item.optJSONObject("track") ?: continue
                val uri = track.optString("uri")
                if (!uri.startsWith("spotify:track:")) continue

                val trackName = track.optString("name")
                val albumObj = track.optJSONObject("albumOfTrack")
                val albumName = albumObj?.optString("name").orEmpty()
                val coverArt = extractBestImage(albumObj?.optJSONObject("coverArt")?.optJSONArray("sources"), 300)

                val artistsArray = track.optJSONObject("artists")?.optJSONArray("items")
                val credited = mutableListOf<CatalogArtist>()
                if (artistsArray != null) {
                    for (j in 0 until artistsArray.length()) {
                        val aObj = artistsArray.optJSONObject(j) ?: continue
                        val aName = aObj.optJSONObject("profile")?.optString("name").orEmpty()
                        if (aName.isNotEmpty()) {
                            credited.add(CatalogArtist(aName, aObj.optString("uri").takeIf { it.isNotEmpty() }))
                        }
                    }
                }

                val explicit = track.optJSONObject("contentRating")?.optString("label") == "EXPLICIT"

                // Extract real duration from Spotify entity:
                // Typically duration.totalMilliseconds or trackDuration.totalMilliseconds or duration_ms
                val durationMs = track.optJSONObject("duration")?.optLong("totalMilliseconds")
                    ?: track.optJSONObject("trackDuration")?.optLong("totalMilliseconds")
                    ?: track.optLong("durationMs")
                    ?: track.optLong("duration_ms")
                    ?: track.optLong("duration")
                    ?: 0L

                tracks.add(
                    CatalogTrack(
                        uri = uri,
                        name = trackName,
                        artist = credited.joinToString(", ") { it.name },
                        artistUri = credited.firstOrNull()?.uri,
                        artists = credited,
                        album = albumName,
                        durationMs = durationMs,
                        explicit = explicit,
                        artworkUrl = coverArt,
                    ),
                )
            }
        }

        data class ReleaseEntry(val item: SearchItem, val year: Int)

        fun extractReleases(sectionKey: String): List<SearchItem> {
            val section = disco?.optJSONObject(sectionKey)
            val items = section?.optJSONArray("items") ?: return emptyList()
            val list = mutableListOf<ReleaseEntry>()
            for (i in 0 until items.length()) {
                val group = items.optJSONObject(i) ?: continue
                val releases = group.optJSONObject("releases")?.optJSONArray("items") ?: continue
                for (j in 0 until releases.length()) {
                    val rel = releases.optJSONObject(j) ?: continue
                    val uri = rel.optString("uri")
                    if (!uri.startsWith("spotify:album:")) continue

                    val title = rel.optString("name")
                    val type = rel.optString("type")
                    val yearInt = rel.optJSONObject("date")?.optInt("year")?.takeIf { it > 0 }
                        ?: rel.optString("date").take(4).toIntOrNull()
                        ?: 0
                    val yearStr = if (yearInt > 0) yearInt.toString() else ""

                    val label = when (type) {
                        "SINGLE" -> resources?.getString(R.string.single) ?: "Single"
                        "ALBUM" -> resources?.getString(R.string.album) ?: "Album"
                        "COMPILATION" -> resources?.getString(R.string.compilation) ?: "Compilation"
                        else -> ""
                    }
                    val subtitle = if (label.isNotEmpty() && yearStr.isNotEmpty()) "$label • $yearStr" else yearStr.ifEmpty { label }
                    val cover = extractBestImage(rel.optJSONObject("coverArt")?.optJSONArray("sources"), 300)

                    list.add(
                        ReleaseEntry(
                            item = SearchItem(
                                uri = uri,
                                title = title,
                                subtitle = subtitle,
                                artworkUrl = cover,
                            ),
                            year = yearInt,
                        ),
                    )
                }
            }
            return list.distinctBy { it.item.title.lowercase() }
                .sortedByDescending { it.year }
                .map { it.item }
        }

        val albumsList = extractReleases("albums") + extractReleases("compilations")
        val singlesList = extractReleases("singles")

        val latestObj = disco?.optJSONObject("latest")
        val latestRelease = if (latestObj != null) {
            val lUri = latestObj.optString("uri")
            if (lUri.startsWith("spotify:album:")) {
                val lTitle = latestObj.optString("name")
                val lDate = latestObj.optJSONObject("date")?.optInt("year")?.takeIf { it > 0 }?.toString()
                    ?: latestObj.optString("date").take(4)
                val lCover = extractBestImage(latestObj.optJSONObject("coverArt")?.optJSONArray("sources"), 300)
                val totalCount = latestObj.optJSONObject("tracks")?.optInt("totalCount") ?: 1
                ArtistRelease(
                    uri = lUri,
                    title = lTitle,
                    artworkUrl = lCover,
                    releaseDate = lDate,
                    trackCount = totalCount,
                )
            } else null
        } else null

        return ArtistPage(
            playlists = emptyList(),
            tracks = tracks.take(5),
            latest = latestRelease,
            albums = albumsList,
            singles = singlesList,
            appearsOn = emptyList(),
            relatedArtists = emptyList(),
            followers = followersCount,
            genres = emptyList(),
            monthlyListeners = monthlyListenersFormatted,
            name = name.takeIf { it.isNotEmpty() },
            artworkUrl = avatarUrl,
            biography = biography,
        )
    }

    private fun extractBestImage(sources: JSONArray?, preferredWidth: Int): String? {
        if (sources == null || sources.length() == 0) return null
        var fallback: String? = null
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val url = src.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val width = src.optInt("width")
            if (width in (preferredWidth - 40)..(preferredWidth + 60)) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }
}
