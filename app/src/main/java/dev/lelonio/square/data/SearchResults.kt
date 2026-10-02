package dev.lelonio.square.data

/** Anything a search result can point at, flattened for the UI. */
data class SearchItem(
    val uri: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
)

data class SearchResults(
    val tracks: List<CatalogTrack> = emptyList(),
    val artists: List<SearchItem> = emptyList(),
    val albums: List<SearchItem> = emptyList(),
    val playlists: List<SearchItem> = emptyList(),
    /**
     * The tracks that were found by their words rather than their titles.
     *
     * Spotify says which those are — a match carries the fields it matched on
     * — and it is worth showing: a list of songs whose names have nothing to
     * do with what was typed looks like a broken search until the reason is on
     * the row. Empty from the Web API, which does not search lyrics at all.
     */
    val lyricMatches: Set<String> = emptySet(),
) {
    /** How many rows this holds in total, for telling a page from an echo. */
    val count: Int
        get() = tracks.size + artists.size + albums.size + playlists.size

    /**
     * This, with another page's rows after it.
     *
     * De-duplicated by address in every kind: an offset is the server's idea of
     * where a page starts, and a catalogue that has changed under it hands back
     * rows that are already on the screen.
     */
    operator fun plus(page: SearchResults): SearchResults {
        val heldTracks = tracks.mapTo(mutableSetOf()) { it.uri }
        val heldArtists = artists.mapTo(mutableSetOf()) { it.uri }
        val heldAlbums = albums.mapTo(mutableSetOf()) { it.uri }
        val heldLists = playlists.mapTo(mutableSetOf()) { it.uri }
        return SearchResults(
            tracks = tracks + page.tracks.filter { it.uri !in heldTracks },
            artists = artists + page.artists.filter { it.uri !in heldArtists },
            albums = albums + page.albums.filter { it.uri !in heldAlbums },
            playlists = playlists + page.playlists.filter { it.uri !in heldLists },
            lyricMatches = lyricMatches + page.lyricMatches,
        )
    }

    val isEmpty: Boolean
        get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
}

/**
 * Maps a Web API search response.
 *
 * Tracks are asked for in greater number than the rest and shown first: finding
 * a song is what a search box in a music player is mostly for, and burying it
 * under artist and album rows makes the common case the slow one.
 */
fun SearchDto.toResults(
    /** What each kind is called, in the app's language; see MainViewModel. */
    artistLabel: String,
    albumLabel: String,
    playlistLabel: String,
): SearchResults = SearchResults(
    tracks = tracks?.items.orEmpty().map { it.toCatalogTrack() },
    artists = artists?.items.orEmpty().mapNotNull { artist ->
        SearchItem(
            uri = artist.uri ?: return@mapNotNull null,
            title = artist.name,
            subtitle = artistLabel,
            artworkUrl = artist.images.firstOrNull()?.url,
        )
    },
    albums = albums?.items.orEmpty().mapNotNull { album ->
        SearchItem(
            uri = album.uri ?: return@mapNotNull null,
            title = album.name,
            subtitle = albumLabel,
            artworkUrl = album.images.firstOrNull()?.url,
        )
    },
    // Spotify returns nulls in this array for playlists it will not serve, so
    // the element type is nullable and the nulls are dropped rather than
    // crashing the whole response.
    playlists = playlists?.items.orEmpty().filterNotNull().map { playlist ->
        SearchItem(
            uri = playlist.uri,
            title = playlist.name,
            subtitle = playlistLabel,
            artworkUrl = playlist.images.firstOrNull()?.url,
        )
    },
)

/**
 * One row of an artist page as the source lays it out: its own heading, in
 * the source's own words and order.
 */
data class ArtistShelf(
    val title: String,
    /** Records, lists and artists, opened as pages. */
    val items: List<SearchItem> = emptyList(),
    /** Videos and performances, played where they are. */
    val tracks: List<CatalogTrack> = emptyList(),
    /** The whole of it, where the source has a page for that. */
    val moreUri: String? = null,
)

/** Everything a YouTube Music artist page holds. */
data class YouTubeArtistPage(
    val name: String,
    val artworkUrl: String?,
    val description: String?,
    val subscribers: String?,
    val monthlyListeners: String?,
    val topSongs: List<CatalogTrack>,
    val topSongsMoreUri: String?,
    val shelves: List<ArtistShelf>,
)
