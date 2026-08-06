package dev.emanuele.spot.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import dev.emanuele.spot.ui.EXTRA_CONTEXT_LABEL
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

/** Mirrors the slow-changing part of a [Player] into Compose state. */
@Composable
fun rememberPlaybackState(player: Player?): State<PlaybackState> {
    val state = remember { mutableStateOf(PlaybackState()) }

    DisposableEffect(player) {
        if (player == null) {
            state.value = PlaybackState()
            return@DisposableEffect onDispose {}
        }

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
