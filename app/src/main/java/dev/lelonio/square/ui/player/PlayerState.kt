package dev.lelonio.square.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL
import kotlinx.coroutines.delay

/**
 * Everything the player UI draws **except** the playback position.
 *
 * Position is deliberately kept out: it changes several times a second, and
 * anything that reads it recomposes at that rate. Keeping it in this class would
 * drag the whole screen — including a hundred-row list — along with the seek
 * bar, which is enough jank to make the audio stutter. See [rememberPositionMs].
 */
data class PlaybackState(
    val hasItem: Boolean = false,
    /** Spotify URI of the current track, for highlighting it in lists. */
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Tempo, 1.0 being the track as recorded. */
    val speed: Float = 1f,
    /** Pitch, independent of [speed]. */
    val pitch: Float = 1f,
    /**
     * Where this queue came from, ready to read: "Playlist · Estate 2025".
     *
     * Carried on the media item rather than derived here, because by the time
     * the player sees a track the only thing left of the tap that started it is
     * a URI, and a URI does not say whether it was reached from a playlist, an
     * album, an artist or a search.
     */
    val source: String = "",
)

/** How long an empty freshly connected player is given before it is believed. */
private const val EMPTY_PLAYER_GRACE_MS = 4_000L

/** Mirrors the slow-changing part of a [Player] into Compose state. */
@Composable
fun rememberPlaybackState(
    player: Player?,
    /**
     * What to report until the media controller connects.
     *
     * Connecting takes a few hundred milliseconds, and the whole player is left
     * uncomposed while there is no item, so without this the app came back from
     * the launcher showing the home page underneath until the controller
     * landed. Read from the saved session, which already holds the track.
     */
    seed: PlaybackState? = null,
): State<PlaybackState> {
    val state = remember { mutableStateOf(seed ?: PlaybackState()) }
    // Whether a live player has ever answered. Once one has, the saved session
    // is out of date by definition — going back to it when the controller drops
    // is what made the player flash the previous track on the way back into the
    // app.
    val seenLive = remember { mutableStateOf(false) }
    // Whether a live player has ever had a track in it.
    //
    // Connecting and being ready are not the same thing. The controller answers
    // in a few tens of milliseconds, but when the service had been stopped the
    // player behind it is empty for another second and a half while the engine
    // reconnects and the queue is restored. Answering "no track" during that
    // window is not news, it is the service still getting dressed, and taking
    // it at its word is what made the mini player vanish on the way back in and
    // reappear a moment later.
    val seenItem = remember { mutableStateOf(false) }

    DisposableEffect(player) {
        if (player == null) {
            if (!seenLive.value) state.value = seed ?: PlaybackState()
            return@DisposableEffect onDispose {}
        }
        seenLive.value = true

        fun snapshot() {
            val metadata = player.mediaMetadata
            val next = PlaybackState(
                hasItem = player.currentMediaItem != null,
                mediaId = player.currentMediaItem?.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                artworkUrl = metadata.artworkUri?.toString(),
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                durationMs = metadata.durationMs ?: player.duration.coerceAtLeast(0),
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                speed = player.playbackParameters.speed,
                pitch = player.playbackParameters.pitch,
                source = metadata.extras?.getString(EXTRA_CONTEXT_LABEL).orEmpty(),
            )
            if (next.hasItem) {
                seenItem.value = true
            } else if (!seenItem.value && state.value.hasItem) {
                // Still getting dressed: keep what is on screen rather than
                // clearing it. Bounded by seenItem, so the first real track
                // ends this for good, and by the timeout below, so a queue that
                // genuinely is empty does not leave a stale track behind for
                // the life of the screen.
                return
            }
            // Equality check, not blind assignment: the player emits events far
            // more often than these fields actually change.
            if (next != state.value) state.value = next
        }

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = snapshot()
        }
        player.addListener(listener)
        snapshot()

        onDispose { player.removeListener(listener) }
    }

    // The end of the grace period above. Long enough for a cold service to
    // restore its queue, measured at about 1.6 seconds, and short enough that
    // an empty player really is empty by the time it expires.
    LaunchedEffect(player) {
        if (player == null || seenItem.value) return@LaunchedEffect
        delay(EMPTY_PLAYER_GRACE_MS)
        if (!seenItem.value && player.currentMediaItem == null) {
            seenItem.value = true
            state.value = PlaybackState()
        }
    }

    return state
}

/**
 * The upcoming tracks, taken from the player's timeline.
 *
 * Rebuilt only when the timeline or the current item changes, not on every
 * event: walking a hundred media items is not something to do four times a
 * second.
 */
@Composable
fun rememberQueue(player: Player?): State<List<QueueEntry>> {
    val queue = remember { mutableStateOf(emptyList<QueueEntry>()) }

    DisposableEffect(player) {
        if (player == null) {
            queue.value = emptyList()
            return@DisposableEffect onDispose {}
        }

        fun rebuild() {
            val current = player.currentMediaItemIndex
            // From the playing track onwards. What has already been heard is
            // not a queue — it is history, and it pushed what comes next off
            // the bottom of the panel on any list longer than a screen.
            queue.value = (current until player.mediaItemCount).map { index ->
                val metadata = player.getMediaItemAt(index).mediaMetadata
                QueueEntry(
                    index = index,
                    title = metadata.title?.toString().orEmpty(),
                    artist = metadata.artist?.toString().orEmpty(),
                    isCurrent = index == current,
                )
            }
        }

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                    )
                ) {
                    rebuild()
                }
            }
        }
        player.addListener(listener)
        rebuild()

        onDispose { player.removeListener(listener) }
    }

    return queue
}

/**
 * Playback position, polled while playing.
 *
 * Separate from [PlaybackState] so only the seek bar reads it. The engine
 * reports position once a second, which on screen would advance in visible
 * steps; polling four times a second smooths it out and costs nothing when
 * paused, because the loop simply does not run.
 */
@Composable
fun rememberPositionMs(player: Player?, isPlaying: Boolean): State<Long> {
    val position = remember { mutableLongStateOf(0L) }

    LaunchedEffect(player, isPlaying) {
        if (player == null) {
            position.longValue = 0
            return@LaunchedEffect
        }
        position.longValue = player.currentPosition.coerceAtLeast(0)
        if (!isPlaying) return@LaunchedEffect

        while (true) {
            delay(250)
            position.longValue = player.currentPosition.coerceAtLeast(0)
        }
    }

    return position
}
