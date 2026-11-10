package dev.lelonio.square.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowedArtistsTest {

    @Test fun readsTheArtistsOfALibraryPage() {
        val json = """
            {"data":{"me":{"libraryV3":{"__typename":"LibraryPage","totalCount":24,"items":[
              {"item":{"__typename":"ArtistResponseWrapper","data":{"__typename":"Artist",
                "uri":"spotify:artist:4kqFrZkeqDfOIEqTWqbOOV","profile":{"name":"brakence"},
                "visuals":{"avatarImage":{"sources":[
                  {"url":"https://i.scdn.co/image/small","width":160,"height":160},
                  {"url":"https://i.scdn.co/image/large","width":640,"height":640}]}}}}},
              {"item":{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist",
                "uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"}}},
              {"item":{"__typename":"ArtistResponseWrapper","data":{"__typename":"Artist",
                "uri":"spotify:artist:1vCWHaC5f2uS3yhpwWbIA6","profile":{"name":"Avicii"}}}}
            ]}}}}
        """.trimIndent()
        val (artists, total) = parseFollowedArtists(json)!!
        assertEquals(24, total)
        assertEquals(listOf("brakence", "Avicii"), artists.map { it.name })
        assertEquals("4kqFrZkeqDfOIEqTWqbOOV", artists[0].id)
        assertEquals("https://i.scdn.co/image/large", artists[0].images.first().url)
        assertEquals(emptyList<ImageDto>(), artists[1].images)
    }

    @Test fun anythingButALibraryPageIsNoAnswer() {
        assertNull(parseFollowedArtists("""{"data":{"me":{"libraryV3":{"__typename":"GenericError"}}}}"""))
        assertNull(parseFollowedArtists("""{"errors":[{"message":"nope"}]}"""))
    }
}
