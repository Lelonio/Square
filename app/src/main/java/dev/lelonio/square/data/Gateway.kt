package dev.lelonio.square.data

import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Track lists read from Spotify's own GraphQL gateway.
 *
 * The endpoint the web player uses, and the reason to prefer it: the Web API
 * meters every request against an application the listener has to register for
 * themselves, and the access point answers a context with URIs that then cost
 * a round trip each to turn into names. This answers with whole tracks, two
 * hundred at a time, for anyone who is logged in.
 *
 * What it asks in return is that nothing here be relied on. A persisted query
 * is addressed by the hash of a query Spotify already knows, and those are
 * retired whenever its web client is rebuilt, so every read here can come back
 * with nothing and every caller has to have somewhere else to go. See
 * [PathfinderKeys] for how the hashes are kept current, and
 * native/src/pathfinder.rs for the request itself.
 */
class Gateway(private val keys: PathfinderKeys) {

    /** One page of the account's saved tracks; see [SavedTracks]. */
    suspend fun savedTracks(offset: Int): GatewayPage? = SavedTracks.parse(
        query(
            operation = "getLikedSongs",
            hash = keys.likedSongs,
            variables = """{"offset":$offset,"limit":$PAGE}""",
        ),
    )

    /**
     * The playlists an artist is in, "This Is" first; see [ArtistPlaylists].
     *
     * Null when the gateway will not answer, which leaves the caller its own
     * way in.
     */
    suspend fun artistPlaylists(artistUri: String): List<SearchItem>? {
        keys.refresh()
        // The web player's own variables. Its locale is a path segment, which
        // English does without.
        val language = java.util.Locale.getDefault().language
        val locale = if (language == "en") "" else "intl-$language"
        return runCatching {
            ArtistPlaylists.parse(
                query(
                    operation = "queryArtistOverview",
                    hash = keys.artistOverview,
                    variables = """{"uri":"$artistUri","locale":"$locale","preReleaseV2":true}""",
                ),
            )
        }
            .onFailure { android.util.Log.i(TAG, "artist playlists unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * The playlists like this one, for the row under it; see [RelatedPlaylists].
     *
     * Null when the gateway will not answer, and the row is then simply not
     * there.
     */
    suspend fun relatedPlaylists(playlistUri: String): List<SearchItem>? {
        keys.refresh()
        return runCatching {
            RelatedPlaylists.parse(
                query(
                    operation = "playlistSection",
                    hash = keys.playlistSection,
                    variables = """{"sectionUri":"${RelatedPlaylists.SECTION}",""" +
                        """"playlistUri":"$playlistUri"}""",
                ),
            )
        }
            .onFailure { android.util.Log.i(TAG, "related playlists unavailable: ${it.message}") }
            .getOrNull()
    }

    /** One page of a playlist; see [PlaylistContents]. */
    suspend fun playlistTracks(uri: String, offset: Int): GatewayPage? = PlaylistContents.parse(
        query(
            operation = "fetchPlaylistContents",
            hash = keys.playlist,
            variables = """{"uri":"$uri","offset":$offset,"limit":$PAGE,""" +
                """"includeEpisodeContentRatingsV2":true}""",
        ),
    )

    /**
     * Whether each of these is in the account's library, in the order asked.
     *
     * One request for a handful of tracks rather than one request per track,
     * which is the shape that matters here as much as the count: a question
     * asked once a song, all day, is a pattern nothing but a machine makes.
     *
     * Null when the gateway will not answer, or when it answers about a
     * different number of things than it was asked about.
     */
    suspend fun inLibrary(uris: List<String>): List<Boolean>? {
        if (uris.isEmpty()) return emptyList()
        keys.refresh()
        return runCatching {
            val raw = query(
                operation = "areEntitiesInLibrary",
                hash = keys.library,
                variables = """{"uris":[${uris.joinToString(",") { "\"$it\"" }}]}""",
            )
            val lookup = org.json.JSONObject(raw)
                .getJSONObject("data")
                .getJSONArray("lookup")
            if (lookup.length() != uris.size) return null
            (0 until lookup.length()).map { index ->
                lookup.optJSONObject(index)?.optJSONObject("data")?.optBoolean("saved") == true
            }
        }
            .onFailure { android.util.Log.i(TAG, "library lookup unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * Everything the search box asks for, in one query.
     *
     * The reason to ask here rather than the Web API is not the quota this
     * time: Spotify's own search reads the words as a listener would, so a line
     * of a song finds the song. "Is this the real life" answers with Bohemian
     * Rhapsody, which no amount of matching titles and artist names will ever
     * do. It also means a search works for anyone signed in, with no registered
     * application of their own.
     *
     * Null when the gateway will not answer, and the Web API is where the
     * caller goes then; see SpotifyBackend.
     */
    suspend fun search(term: String, offset: Int = 0): String? {
        if (term.isBlank()) return null
        keys.refresh()
        val quoted = org.json.JSONObject.quote(term)
        return runCatching {
            query(
                operation = "searchDesktop",
                hash = keys.search,
                // Exactly what the web player asks, values included.
                //
                // Not a detail. A query nobody else sends — twenty of every
                // kind, audiobooks turned off, fields left out — is a caller
                // that stands out from every other caller of the same query,
                // and standing out is the one thing this app should never do
                // here. Ten and five is the web player's own page size, so an
                // hour of searching from this app reads like an hour of
                // searching from a browser tab.
                variables = """{"searchTerm":$quoted,"offset":$offset,"limit":$SEARCH_LIMIT,""" +
                    """"numberOfTopResults":$TOP_RESULTS,"includeAudiobooks":true,""" +
                    """"includeArtistHasConcertsField":false,"includePreReleases":true,""" +
                    """"includeLocalConcertsField":false,"includeAlbumPreReleases":false,""" +
                    """"includeAuthors":false,"includeEpisodeContentRatingsV2":false}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "gateway search unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * One page of Spotify's own browse, as rows.
     *
     * The home query answers with what this account is shown when it opens the
     * app; this answers with what the catalogue has to offer — new releases
     * chosen for the listener, the editors' playlists, the charts, the daily
     * mixes — which is a different question and a much larger answer. Pages are
     * addressed by uri: see BrowsePages.
     *
     * Null when the gateway will not answer, which is the ordinary case for a
     * retired hash; every caller draws its page without it.
     */
    suspend fun browsePage(uri: String, sections: Int = BROWSE_SECTIONS): String? {
        keys.refresh()
        return runCatching {
            query(
                operation = "browsePage",
                hash = keys.browsePage,
                // Exactly the shape the web player sends, values included; see
                // the note on search.
                variables = """{"pagePagination":{"offset":0,"limit":$sections},""" +
                    """"sectionPagination":{"offset":0,"limit":$BROWSE_ITEMS},""" +
                    """"uri":"$uri","browseEndUserIntegration":"INTEGRATION_WEB_PLAYER",""" +
                    """"includeEpisodeContentRatingsV2":true}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "browse page unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * One row of a browse page, past the handful the page itself carries.
     *
     * The page answers with ten items a row and says how many there are — a
     * thousand, for the new releases picked for a listener — so a row worth
     * scrolling is this query, not that one.
     */
    suspend fun browseSection(uri: String, offset: Int, limit: Int = BROWSE_PAGE): String? {
        keys.refresh()
        return runCatching {
            query(
                operation = "browseSection",
                hash = keys.browseSection,
                variables = """{"pagination":{"offset":$offset,"limit":$limit},""" +
                    """"uri":"$uri","browseEndUserIntegration":"INTEGRATION_WEB_PLAYER",""" +
                    """"includeEpisodeContentRatingsV2":true}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "browse section unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * A podcast's own page: its name, who makes it, what it is about, and its
     * newest episode. The rest of the episodes are [showEpisodes].
     */
    suspend fun show(uri: String): String? {
        keys.refresh()
        return runCatching {
            query(
                operation = "queryShowMetadataV2",
                hash = keys.show,
                variables = """{"uri":"$uri","includeContentCapabilityTrait":true,""" +
                    """"includeEpisodeContentRatingsV2":false}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "show unavailable: ${it.message}") }
            .getOrNull()
    }

    /** A page of a podcast's episodes, newest first; see SpotifyPodcasts. */
    suspend fun showEpisodes(uri: String, offset: Int, limit: Int = SHOW_PAGE): String? {
        keys.refresh()
        return runCatching {
            query(
                operation = "queryPodcastEpisodes",
                hash = keys.showEpisodes,
                variables = """{"uri":"$uri","offset":$offset,"limit":$limit,""" +
                    """"includeEpisodeContentRatingsV2":false}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "episodes unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * The podcasts the account follows, from its library.
     *
     * The library query with Spotify's own filter for them, which is how the
     * web player's "Podcasts & Shows" chip asks the same thing.
     */
    suspend fun followedShows(offset: Int = 0, limit: Int = SHOWS_PAGE): String? {
        keys.refresh()
        return runCatching {
            query(
                operation = "libraryV3",
                hash = keys.libraryContents,
                variables = """{"filters":["Podcasts & Shows"],"order":null,"textFilter":null,""" +
                    """"features":["YOUR_EPISODES_V2","PRERELEASES","PRERELEASES_V2","EVENTS"],""" +
                    """"limit":$limit,"offset":$offset,"flatten":false,"expandedFolders":[],""" +
                    """"folderUri":null,"includeFoldersWhenFlattening":true}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "followed shows unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * Puts [uris] in the account's library, or takes them out: shows, which the
     * Web API keeps behind a registered application, and anything else the web
     * player keeps the same way. True when the gateway took it.
     */
    suspend fun setInLibrary(uris: List<String>, keep: Boolean): Boolean {
        keys.refresh()
        val list = uris.joinToString(",") { org.json.JSONObject.quote(it) }
        return runCatching {
            val answer = query(
                operation = if (keep) "addToLibrary" else "removeFromLibrary",
                hash = keys.libraryEdit,
                variables = if (keep) {
                    """{"libraryItemUris":[$list],"interactionId":null}"""
                } else {
                    """{"libraryItemUris":[$list]}"""
                },
            )
            !org.json.JSONObject(answer).has("errors")
        }
            .onFailure { android.util.Log.i(TAG, "library edit refused: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Spotify's home as one of its own chips has it: "podcasts-chip" is the
     * page of shows and episodes the app's Podcast chip shows.
     */
    suspend fun homeFacet(facet: String): String? {
        keys.refresh()
        val zone = org.json.JSONObject.quote(java.util.TimeZone.getDefault().id)
        return runCatching {
            query(
                operation = "home",
                hash = keys.home,
                variables = """{"homeEndUserIntegration":"INTEGRATION_WEB_PLAYER","timeZone":$zone,""" +
                    """"sp_t":"","facet":"$facet","sectionItemsLimit":10,""" +
                    """"includeEpisodeContentRatingsV2":false}""",
            )
        }
            .onFailure { android.util.Log.i(TAG, "home $facet unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * Reads a list to its end.
     *
     * Null when the first page will not come back, and null part way through
     * as well: what this returns is cached and stamped as the list, so half a
     * playlist would be remembered as all of it.
     *
     * @param onPage called with everything read so far, and whether more is
     *   coming, so a screen can fill in as it arrives.
     */
    suspend fun readAll(
        onPage: ((List<CatalogTrack>, Boolean) -> Unit)? = null,
        page: suspend (offset: Int) -> GatewayPage?,
    ): List<CatalogTrack>? {
        keys.refresh()

        val loaded = mutableListOf<CatalogTrack>()
        var offset = 0
        while (true) {
            val read = runCatching { page(offset) }
                .onFailure { android.util.Log.i(TAG, "gateway list unavailable: ${it.message}") }
                .getOrNull()
                ?: return null

            loaded += read.tracks
            val next = read.nextOffset
            onPage?.invoke(loaded.toList(), next != null)
            // Counted as well as followed: a page naming itself as its own
            // successor would otherwise be read for ever.
            if (next == null || next <= offset) {
                android.util.Log.i(TAG, "gateway list: ${loaded.size} of ${read.total}")
                return loaded
            }
            offset = next
        }
    }

    private suspend fun query(operation: String, hash: String, variables: String): String =
        withContext(Dispatchers.IO) {
            NativeBridge.gatewayQuery(
                operation,
                hash,
                variables,
                java.util.Locale.getDefault().language,
                keys.appVersion,
            )
        }

    private companion object {
        const val TAG = "SquareGateway"

        /**
         * What one request takes.
         *
         * Two hundred whole tracks came back in half a second on a phone, so a
         * collection of two thousand is ten requests. The Web API's own
         * maximum for the same lists is a hundred, and fifty for the library.
         */
        const val PAGE = 200

        /**
         * What one page of each kind of search result holds.
         *
         * Twenty rather than the ten the web player's overview asks for — that
         * is the size it uses itself the moment you open one of the categories,
         * so it is still a page shape this endpoint sees all day, and it is the
         * difference between four songs under a heading and a list worth
         * scrolling.
         */
        const val SEARCH_LIMIT = 20

        /** And how many of the best of everything it asks for; see [search]. */
        const val TOP_RESULTS = 8

        /** How many rows of a browse page to read, and how much of each. */
        const val BROWSE_SECTIONS = 10
        const val BROWSE_ITEMS = 10

        /** And how much of a single row, when one is read on its own. */
        const val BROWSE_PAGE = 20

        /** Episodes a page, as the web player reads a show. */
        const val SHOW_PAGE = 50

        /** Podcasts followed, a page: more than most people follow. */
        const val SHOWS_PAGE = 50
    }
}
