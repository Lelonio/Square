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
    }
}
