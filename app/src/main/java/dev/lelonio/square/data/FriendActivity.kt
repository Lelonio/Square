package dev.lelonio.square.data

import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one person the account follows was last heard playing.
 *
 * Flat, like everything else that reaches the UI: the endpoint's own shape is
 * four levels of nesting and none of it belongs above this file.
 */
data class FriendListen(
    val userUri: String,
    val userName: String,
    val avatarUrl: String?,
    val trackUri: String,
    val trackName: String,
    val artist: String,
    val artworkUrl: String?,
    /** The playlist, album or artist page they were playing it from. */
    val contextName: String,
    /** When they were last heard from, in milliseconds since the epoch. */
    val at: Long,
)

/**
 * Spotify's "Friend Activity", read through the access point.
 *
 * The same list the desktop client shows down its right-hand side, and one of
 * the few things in this app that has no Web API equivalent at all: there is no
 * public endpoint for it, and the private one behind the desktop client answers
 * the session this app already holds.
 */
object FriendActivity {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The list, most recently heard first.
     *
     * Empty is an ordinary answer: an account can follow nobody, and everybody
     * it follows can have their listening set to private.
     */
    suspend fun friends(): List<FriendListen> = withContext(Dispatchers.IO) {
        val raw = NativeBridge.friendActivity()
        json.decodeFromString<BuddyList>(raw).friends
            .mapNotNull { friend ->
                val user = friend.user ?: return@mapNotNull null
                val track = friend.track ?: return@mapNotNull null
                FriendListen(
                    userUri = user.uri.orEmpty(),
                    userName = user.name.orEmpty(),
                    avatarUrl = user.imageUrl?.takeIf { it.isNotEmpty() },
                    trackUri = track.uri.orEmpty(),
                    trackName = track.name.orEmpty(),
                    artist = track.artist?.name.orEmpty(),
                    artworkUrl = track.imageUrl?.takeIf { it.isNotEmpty() },
                    contextName = track.context?.name.orEmpty(),
                    at = friend.timestamp,
                )
            }
            .filter { it.trackUri.startsWith("spotify:track:") }
            .sortedByDescending { it.at }
    }

    @Serializable
    private data class BuddyList(val friends: List<Friend> = emptyList())

    @Serializable
    private data class Friend(
        val timestamp: Long = 0,
        val user: User? = null,
        val track: PlayedTrack? = null,
    )

    @Serializable
    private data class User(
        val uri: String? = null,
        val name: String? = null,
        @SerialName("imageUrl") val imageUrl: String? = null,
    )

    @Serializable
    private data class PlayedTrack(
        val uri: String? = null,
        val name: String? = null,
        @SerialName("imageUrl") val imageUrl: String? = null,
        val artist: Named? = null,
        val album: Named? = null,
        val context: Named? = null,
    )

    @Serializable
    private data class Named(val uri: String? = null, val name: String? = null)
}
