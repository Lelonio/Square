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
        val limit = Semaphore(5)
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
                                discography(id, cutoff).also { write(id, it) }
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
            .also { if (it.failedArtists == 0) save(it) }
    }

    /**
     * An artist's albums and singles from the last year, newest first.
     *
     * Spotify lists each group newest first, so a group is read only until a
     * page reaches back past [cutoff]: for almost every artist that is one
     * short page of albums and one of singles. The whole discography used to be
     * read, fifty at a time, for every artist followed, and the row waited on
     * the longest of them.
     */
    private suspend fun discography(id: String, cutoff: LocalDate): List<AlbumDto> {
        val albums = mutableListOf<AlbumDto>()
        for (group in listOf("album", "single")) {
            var offset = 0
            while (true) {
                val page = api.artistAlbums(id, groups = group, limit = PAGE, offset = offset)
                albums += page.items
                val oldest = page.items.lastOrNull()?.let { releaseDay(it.releaseDate) }
                if (page.next == null || page.items.isEmpty() || oldest == null || oldest < cutoff) break
                offset += page.items.size
                check(offset < MAX_PER_GROUP) { "Artist album pages did not reach back a year" }
            }
        }
        return albums.distinctBy { it.uri ?: it.id }
    }

    /** What the last load found, to show while the next one runs. */
    suspend fun saved(): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val saved = json.decodeFromString<Saved>(File(directory, SAVED).readText())
            Result(recentReleases(saved.albums, LocalDate.now().minusMonths(12), LocalDate.now()), saved.artistCount, 0)
        }.getOrNull()
    }

    private suspend fun save(result: Result) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "$SAVED.tmp")
            temp.writeText(json.encodeToString(Saved.serializer(), Saved(result.albums, result.artistCount)))
            check(temp.renameTo(File(directory, SAVED)))
        }
        Unit
    }

    @Serializable
    private data class Saved(val albums: List<AlbumDto>, val artistCount: Int)

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
        private const val PAGE = 20
        /** Past this, an artist's pages are not in the order they should be. */
        private const val MAX_PER_GROUP = 200
        private const val SAVED = "last-result.json"
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
