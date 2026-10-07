package dev.lelonio.square.data

import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Public discographies are cached per artist; the private follow list is read afresh. */
class FollowedReleases(private val api: SpotifyApi, private val directory: File) {
    data class Result(val albums: List<AlbumDto>, val artistCount: Int, val failedArtists: Int)

    @Serializable
    private data class Entry(val savedAt: Long, val albums: List<AlbumDto>)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun artists(): List<ArtistDto> {
        val gathered = mutableListOf<ArtistDto>()
        val cursors = mutableSetOf<String>()
        var after: String? = null
        while (true) {
            val page = api.followedArtists(after = after).artists
            gathered += page.items
            val next = page.cursors?.after?.takeIf { page.items.isNotEmpty() } ?: break
            check(cursors.add(next)) { "Repeated followed-artist cursor" }
            after = next
        }
        return gathered.distinctBy { it.id ?: it.uri }
    }

    suspend fun load(force: Boolean = false, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val artists = artists()
        val limit = Semaphore(3)
        val completed = AtomicInteger()
        val failed = AtomicInteger()
        val cutoff = LocalDate.now().minusMonths(12)
        onProgress(0, artists.size)
        val albums = coroutineScope {
            artists.map { artist ->
                async {
                    limit.withPermit {
                        val id = artist.id ?: artist.uri?.substringAfterLast(':')
                        val cached = id?.let { read(it) }
                        val releases = try {
                            if (id == null) error("Artist has no id")
                            if (!force && cached != null && System.currentTimeMillis() - cached.savedAt in 0..CACHE_MS) {
                                cached.albums
                            } else {
                                discography(id).also { write(id, it) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed.incrementAndGet()
                            cached?.albums.orEmpty()
                        }
                        // Progress is reported on the caller's coroutine dispatcher.
                        onProgress(completed.incrementAndGet(), artists.size)
                        releases
                    }
                }
            }.awaitAll().flatten()
        }
        return Result(recentReleases(albums, cutoff, LocalDate.now()), artists.size, failed.get())
    }

    private suspend fun discography(id: String): List<AlbumDto> {
        val albums = mutableListOf<AlbumDto>()
        var offset = 0
        while (true) {
            val page = api.artistAlbums(id, groups = "album,single", limit = 50, offset = offset)
            if (page.next != null && offset > 0) {
                val seen = albums.map { it.uri ?: it.id }.toSet()
                check(page.items.any { (it.uri ?: it.id) !in seen }) { "Repeated artist album page" }
            }
            albums += page.items
            if (page.next == null) break
            check(page.items.isNotEmpty()) { "Empty artist page with a continuation" }
            offset += page.items.size
        }
        return albums.distinctBy { it.uri ?: it.id }
    }

    private suspend fun read(id: String): Entry? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<Entry>(file(id).readText()) }.getOrNull()
    }

    private suspend fun write(id: String, albums: List<AlbumDto>) = withContext(Dispatchers.IO) {
        // Cache failures do not turn a successfully fetched artist into an error.
        runCatching {
            directory.mkdirs()
            val target = file(id)
            val temp = File(directory, "$id.tmp")
            temp.writeText(json.encodeToString(Entry.serializer(), Entry(System.currentTimeMillis(), albums)))
            check(temp.renameTo(target))
        }
        Unit
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9]+")))
        return File(directory, "$id.json")
    }

    companion object {
        private const val CACHE_MS = 6 * 60 * 60 * 1_000L
    }
}

/** Spotify can give a day, a month or just a year. Never invent a recent day. */
internal fun releaseDay(date: String?): LocalDate? = runCatching {
    when (date?.length) {
        4 -> LocalDate.parse("$date-01-01")
        7 -> LocalDate.parse("$date-01")
        10 -> LocalDate.parse(date)
        else -> null
    }
}.getOrNull()

internal fun recentReleases(albums: List<AlbumDto>, cutoff: LocalDate, today: LocalDate): List<AlbumDto> =
    albums.filter { album ->
        val day = releaseDay(album.releaseDate)
        day != null && day >= cutoff && day <= today &&
            (album.albumGroup == null || album.albumGroup in setOf("album", "single")) &&
            (album.uri != null || album.id != null)
    }.sortedWith(compareByDescending<AlbumDto> { releaseDay(it.releaseDate) }.thenBy { it.name })
        .distinctBy { it.uri ?: it.id }
