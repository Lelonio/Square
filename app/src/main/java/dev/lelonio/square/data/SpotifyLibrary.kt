package dev.lelonio.square.data

/**
 * The account's library: what is saved, followed or liked, and the changes to
 * it.
 *
 * Asked of the web player's gateway first, as Spotify's own player asks: it
 * answers for anyone signed in, and it is not the Web API, whose quota a
 * listener can spend in an afternoon and whose endpoints Spotify retires for
 * applications in development. The Web API is the fallback, for when the
 * gateway will not answer.
 *
 * Takes tracks, albums, artists and shows alike; a playlist is kept in the
 * rootlist instead (see NativeBridge.setInRootlist).
 */
class SpotifyLibrary(
    private val gateway: Gateway,
    private val api: () -> SpotifyApi,
) {
    /** Saves or removes [uri]; throws when neither side took it. */
    suspend fun set(uri: String, keep: Boolean) {
        if (gateway.setInLibrary(listOf(uri), keep)) return
        android.util.Log.w(TAG, "gateway refused to ${if (keep) "save" else "remove"} $uri; trying the Web API")
        if (keep) api().saveToLibrary(uri) else api().removeFromLibrary(uri)
    }

    /** Whether each of [uris] is saved, in order; null when neither side will say. */
    suspend fun contains(uris: List<String>): List<Boolean>? {
        if (uris.isEmpty()) return emptyList()
        gateway.inLibrary(uris)?.let { return it }
        return runCatching { api().libraryContains(uris.joinToString(",")) }
            .onFailure { android.util.Log.i(TAG, "cannot tell what is saved: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.size == uris.size }
    }

    private companion object {
        const val TAG = "SpotifyLibrary"
    }
}
