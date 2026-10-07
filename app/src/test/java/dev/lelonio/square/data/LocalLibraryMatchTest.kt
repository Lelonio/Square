package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class LocalLibraryMatchTest {
    private fun file(name: String, artist: String = "Tazenda", album: String = "Vida", duration: Long = 200_000) =
        CatalogTrack(uri = "local:track:1", name = name, artist = artist, album = album, durationMs = duration, localFile = "/music/song.mp3")

    @Test fun matchesPcUriWhenGatewayMetadataIsMissing() {
        val local = file("Domo Mia (Feat. Eros Ramazzotti)")
        val entry = CatalogTrack(uri = LocalLibrary.spotifyUri(local), name = "", artist = "", album = "")
        assertEquals(local, LocalLibrary.match(entry, listOf(local)))
    }

    @Test fun toleratesTagsAndAccentsButRejectsDifferentDuration() {
        val entry = file("Dòmo Mia (Feat. Eros Ramazzotti)").copy(uri = "spotify:local:Tazenda:Vida:Domo+Mia:200")
        val local = file("Domo Mia")
        assertEquals(local, LocalLibrary.match(entry, listOf(local)))
        assertNull(LocalLibrary.match(entry, listOf(local.copy(durationMs = 230_000))))
    }

    @Test fun prefersMatchingAlbumAndDoesNotGuessAmongAmbiguousCopies() {
        val local = file("Domo Mia")
        val other = local.copy(uri = "local:track:2", album = "Live")
        val entry = local.copy(uri = LocalLibrary.spotifyUri(local))
        assertEquals(local, LocalLibrary.match(entry, listOf(other, local)))
        assertNull(LocalLibrary.match(entry.copy(album = "Missing"), listOf(other, local)))
    }

    @Test fun missingTitleCannotMatchAnUntaggedFile() {
        assertNull(LocalLibrary.match(file("").copy(uri = "spotify:local::::200"), listOf(file(""))))
    }
}
