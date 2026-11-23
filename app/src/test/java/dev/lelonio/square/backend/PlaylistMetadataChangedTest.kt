package dev.lelonio.square.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMetadataChangedTest {

    private val current = PlaylistDetails(
        name = "My playlist",
        description = "Description",
        public = false,
        artworkUrl = null,
    )

    @Test
    fun coverOnlyEditDoesNotSendMetadataUpdateWhenBackendDoesNotStoreCoverMetadata() {
        assertFalse(
            playlistMetadataChanged(
                current = current,
                name = "My playlist",
                description = "",
                public = false,
                describesPlaylists = false,
            ),
        )
    }

    @Test
    fun aChangedNameStillRequiresAnUpdate() {
        assertTrue(
            playlistMetadataChanged(
                current = current,
                name = "Renamed playlist",
                description = current.description,
                public = current.public,
                describesPlaylists = false,
            ),
        )
    }

    @Test
    fun descriptionAndVisibilityChangesAreComparedForSupportingBackends() {
        assertTrue(
            playlistMetadataChanged(
                current = current,
                name = current.name,
                description = "Updated description",
                public = current.public,
                describesPlaylists = true,
            ),
        )
        assertTrue(
            playlistMetadataChanged(
                current = current,
                name = current.name,
                description = current.description,
                public = true,
                describesPlaylists = true,
            ),
        )
    }

    @Test
    fun unchangedMetadataDoesNotRequireAWrite() {
        assertFalse(
            playlistMetadataChanged(
                current = current,
                name = current.name,
                description = current.description,
                public = current.public,
                describesPlaylists = true,
            ),
        )
    }

    @Test
    fun missingCurrentDetailsForcesAnUpdate() {
        assertTrue(
            playlistMetadataChanged(
                current = null,
                name = "My playlist",
                description = "",
                public = false,
                describesPlaylists = false,
            ),
        )
    }
}
