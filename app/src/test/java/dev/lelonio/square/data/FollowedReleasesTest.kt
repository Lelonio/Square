package dev.lelonio.square.data

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FollowedReleasesTest {
    private fun api(answer: (String, Array<out Any?>) -> Any?): SpotifyApi = Proxy.newProxyInstance(
        SpotifyApi::class.java.classLoader, arrayOf(SpotifyApi::class.java),
    ) { _, method, args -> answer(method.name, args.orEmpty()) } as SpotifyApi

    private fun album(id: String, date: String, group: String = "album") = AlbumDto(
        id = id, uri = "spotify:album:$id", name = id, releaseDate = date, albumGroup = group,
    )

    @Test fun datesDeduplicationAndReleaseGroups() {
        val today = LocalDate.of(2026, 10, 7)
        val result = recentReleases(listOf(
            album("old", "2024-01-01"), album("recent", "2026-10-07", "single"),
            album("month", "2026-09"), album("year", "2026"),
            album("recent", "2026-10-07"), album("future", "2027-01-01"),
            album("compilation", "2026-10-06", "compilation"), album("unknown", "bad"),
        ), today.minusMonths(12), today)
        assertEquals(listOf("recent", "month", "year"), result.map { it.id })
        assertNull(releaseDay("2026-02-31"))
        assertEquals(LocalDate.of(2026, 9, 1), releaseDay("2026-09"))
    }

    @Test fun walksEveryFollowedArtistAndEveryAlbumPage() = runBlocking {
        var followPages = 0
        val offsets = mutableListOf<Pair<String, Int>>()
        val api = api { name, args ->
            when (name) {
                "followedArtists" -> {
                    followPages++
                    val second = args[2] != null
                    val artists = (if (second) 51..60 else 1..50).map {
                        ArtistDto(id = "A$it", uri = "spotify:artist:A$it", name = "Artist $it")
                    }
                    FollowedArtistsDto(ArtistCursorPageDto(artists, 60, CursorsDto(if (second) null else "next")))
                }
                "artistAlbums" -> {
                    val id = args[0] as String
                    val offset = args[4] as Int
                    offsets += id to offset
                    assertEquals("album,single", args[1])
                    PageDto(listOf(album("${id}P$offset", LocalDate.now().toString())), total = 2, next = if (offset == 0) "next" else null)
                }
                else -> error(name)
            }
        }
        val dir = Files.createTempDirectory("followed-releases-test").toFile()
        try {
            val result = FollowedReleases(api, dir).load()
            assertEquals(2, followPages)
            assertEquals(60, result.artistCount)
            assertEquals(120, result.albums.size)
            assertEquals(setOf(0, 1), offsets.filter { it.first == "A60" }.map { it.second }.toSet())
            assertEquals(0, result.failedArtists)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cachesDiscographiesButReadsFollowsAgainAndPreservesCacheOnFailure() = runBlocking {
        var fail = false
        var albumRequests = 0
        var followedRequests = 0
        val api = api { name, _ ->
            when (name) {
                "followedArtists" -> {
                    followedRequests++
                    FollowedArtistsDto(ArtistCursorPageDto(listOf(ArtistDto(id = "A1", name = "Artist"))))
                }
                "artistAlbums" -> {
                    albumRequests++
                    if (fail) error("network failure")
                    PageDto(listOf(album("release", LocalDate.now().toString())))
                }
                else -> error(name)
            }
        }
        val dir = Files.createTempDirectory("followed-releases-test").toFile()
        try {
            val repository = FollowedReleases(api, dir)
            val first = repository.load()
            assertEquals(first.albums, repository.load().albums)
            assertEquals(1, albumRequests)
            assertEquals(2, followedRequests)
            fail = true
            val failed = repository.load(force = true)
            assertEquals(first.albums, failed.albums)
            assertEquals(1, failed.failedArtists)
            assertEquals(2, albumRequests)
        } finally { dir.deleteRecursively() }
    }

    @Test fun repeatedFollowCursorFailsInsteadOfSilentlyTruncating() = runBlocking {
        val api = api { _, _ ->
            FollowedArtistsDto(ArtistCursorPageDto(listOf(ArtistDto(id = "A", name = "Artist")), cursors = CursorsDto("same")))
        }
        val dir = Files.createTempDirectory("followed-releases-test").toFile()
        try {
            assertTrue(runCatching { FollowedReleases(api, dir).artists() }.exceptionOrNull() is IllegalStateException)
        } finally { dir.deleteRecursively() }
    }
}
