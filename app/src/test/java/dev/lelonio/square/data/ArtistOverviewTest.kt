package dev.lelonio.square.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The artist page as `queryArtistOverview` answers it; see SpotifyWebArtist.parseOverview (#183). */
class ArtistOverviewTest {

    private val json = """
        {"data":{"artistUnion":{"__typename":"Artist","uri":"spotify:artist:0id62QV2SZZfvBn9xpmuCl","saved":true,
          "profile":{"name":"Aloe Blacc","biography":{"text":"Bio."}},
          "stats":{"followers":798123,"monthlyListeners":5069690},
          "visuals":{"avatarImage":{"sources":[
            {"height":640,"url":"https://i.scdn.co/image/a640","width":640},
            {"height":320,"url":"https://i.scdn.co/image/a320","width":320}]}},
          "discography":{
            "topTracks":{"items":[
              {"track":{"uri":"spotify:track:2","name":"Second most played","albumOfTrack":{"uri":"spotify:album:B",
                "coverArt":{"sources":[{"url":"https://i.scdn.co/image/b"}]}},
                "artists":{"items":[{"profile":{"name":"Aloe Blacc"},"uri":"spotify:artist:0id62QV2SZZfvBn9xpmuCl"}]},
                "contentRating":{"label":"NONE"},"duration":{"totalMilliseconds":200000}}},
              {"track":{"uri":"spotify:track:1","name":"Most played","albumOfTrack":{"uri":"spotify:album:A",
                "coverArt":{"sources":[{"url":"https://i.scdn.co/image/a"}]}},
                "artists":{"items":[{"profile":{"name":"Aloe Blacc"},"uri":"spotify:artist:0id62QV2SZZfvBn9xpmuCl"}]},
                "contentRating":{"label":"EXPLICIT"},"duration":{"totalMilliseconds":180000}}}
            ]},
            "albums":{"items":[{"releases":{"items":[{"uri":"spotify:album:A","name":"Lift Your Spirit","type":"ALBUM",
              "date":{"year":2014},"tracks":{"totalCount":12},"coverArt":{"sources":[{"url":"https://i.scdn.co/image/la","width":300}]}}]}}],
              "totalCount":13},
            "compilations":{"items":[],"totalCount":0},
            "singles":{"items":[
              {"releases":{"items":[{"uri":"spotify:album:S","name":"Superman","type":"SINGLE","date":{"year":2026},
                "tracks":{"totalCount":1},"coverArt":{"sources":[{"url":"https://i.scdn.co/image/s","width":300}]}}]}},
              {"releases":{"items":[{"uri":"spotify:album:E","name":"Live Sessions","type":"SINGLE","date":{"year":2025},
                "tracks":{"totalCount":4},"coverArt":{"sources":[{"url":"https://i.scdn.co/image/e","width":300}]}}]}}
            ],"totalCount":160},
            "latest":{"uri":"spotify:album:S","name":"Superman","type":"SINGLE","date":{"year":2026},"tracks":{"totalCount":1},
              "coverArt":{"sources":[{"url":"https://i.scdn.co/image/s","width":300}]}}
          },
          "relatedContent":{
            "appearsOn":{"items":[{"releases":{"items":[{"uri":"spotify:album:T","name":"TIM",
              "artists":{"items":[{"profile":{"name":"Avicii"},"uri":"spotify:artist:1vCWHaC5f2uS3yhpwWbIA6"}]},
              "coverArt":{"sources":[{"url":"https://i.scdn.co/image/t","width":300}]}}]}}]},
            "relatedArtists":{"items":[{"uri":"spotify:artist:C","profile":{"name":"CeeLo Green"},
              "visuals":{"avatarImage":{"sources":[{"url":"https://i.scdn.co/image/c","width":320}]}}}]}
          }}}}
    """.trimIndent()

    @Test fun readsTheWholePage() {
        val page = SpotifyWebArtist.parseOverview(json, "0id62QV2SZZfvBn9xpmuCl")!!
        assertEquals("Aloe Blacc", page.name)
        // Spotify's order, not anyone's guess at it.
        assertEquals(listOf("Second most played", "Most played"), page.tracks.map { it.name })
        assertEquals("spotify:album:B", page.tracks[0].albumUri)
        assertEquals(true, page.tracks[1].explicit)
        assertEquals(listOf("spotify:album:A"), page.albums.map { it.uri })
        assertEquals(listOf("spotify:album:S", "spotify:album:E"), page.singles.map { it.uri })
        assertEquals("EP • 2025", page.singles[1].subtitle)
        assertEquals("spotify:album:S", page.latest?.uri)
        assertEquals(listOf("TIM"), page.appearsOn.map { it.title })
        assertEquals(listOf("CeeLo Green"), page.relatedArtists.map { it.title })
        assertEquals(798123, page.followers)
        assertEquals("Bio.", page.biography)
        assertEquals("https://i.scdn.co/image/a320", page.artworkUrl)
    }

    @Test fun anythingButAnArtistIsNoAnswer() {
        assertNull(SpotifyWebArtist.parseOverview("""{"data":{"artistUnion":{"__typename":"NotFound"}}}""", "x"))
        assertNull(SpotifyWebArtist.parseOverview("""{"errors":[{"message":"PersistedQueryNotFound"}]}""", "x"))
    }
}
