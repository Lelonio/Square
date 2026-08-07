package dev.lelonio.square.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.playback.PlaybackService

/**
 * Owns the connection to the playback service; everything visual lives in
 * [SquareApp].
 *
 * The controller is activity-scoped rather than kept in a ViewModel because it
 * has to be released when the UI goes away — the session keeps playing without
 * it, and holding one from a backgrounded app leaks a binder connection.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    private var controller by mutableStateOf<MediaController?>(null)

    /**
     * Below Android 13 nothing applies the chosen language for us, so every
     * resource this activity reads has to come from a context that carries it.
     */
    override fun attachBaseContext(base: android.content.Context) {
        val store = (base.applicationContext as dev.lelonio.square.SquareApplication).language
        super.attachBaseContext(store.wrap(base))
    }

    /**
     * Bumped when something asks for the player to be open — the notification,
     * for now.
     *
     * A counter rather than a flag: two taps in a row are two requests, and a
     * boolean that is already true the second time would be ignored.
     */
    private var openPlayer by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars; SquareTheme sets the icon colour, because it is the
        // only place that knows whether the app is currently light or dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        if (intent?.opensPlayer() == true) {
            openPlayer++
        }

        setContent {
            SquareApp(
                player = controller,
                onPlay = ::play,
                onEnqueue = ::enqueue,
                openPlayer = openPlayer,
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensPlayer()) {
            openPlayer++
        }
    }

    private fun android.content.Intent.opensPlayer(): Boolean =
        action == dev.lelonio.square.playback.ACTION_OPEN_PLAYER ||
            getBooleanExtra(dev.lelonio.square.playback.EXTRA_OPEN_PLAYER, false)

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                // The activity may already be stopping by the time this lands.
                controller = runCatching { future.get() }.getOrNull()
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    /** Sends the visible list to the session, starting at the tapped track. */
    private fun play(
        tracks: List<CatalogTrack>,
        index: Int,
        contextUri: String? = null,
        asContext: Boolean = false,
        contextLabel: String = "",
    ) {
        val player = controller ?: return
        player.setMediaItems(
            tracks.map { toMediaItem(it, contextUri, asContext, contextLabel) },
            index,
            0L,
        )
        player.prepare()
        player.play()
    }

    /**
     * Appends one track to the end of the queue.
     *
     * Starts playback when nothing is loaded: queueing onto an idle player and
     * having nothing happen reads as the gesture having failed.
     */
    private fun enqueue(track: CatalogTrack) {
        val player = controller ?: return
        val wasEmpty = player.mediaItemCount == 0
        // The index is a formality: the item asks to play next and the queue in
        // the service picks the place, because only it knows where the run of
        // already-queued tracks ends.
        player.addMediaItem(toMediaItem(track, playNext = true))
        if (wasEmpty) {
            player.prepare()
            player.play()
        }
    }

    private fun toMediaItem(
        track: CatalogTrack,
        contextUri: String? = null,
        asContext: Boolean = false,
        contextLabel: String = "",
        playNext: Boolean = false,
    ): MediaItem =
        MediaItem.Builder()
            // The media id carries the Spotify URI; PlayQueue refuses anything else.
            .setMediaId(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setDurationMs(track.durationMs)
                    .setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse))
                    // Where the queue came from, carried with the item because
                    // the engine lives in the service and this is the only
                    // channel between them that survives the session boundary.
                    .setExtras(
                        android.os.Bundle().apply {
                            contextUri?.let { putString(EXTRA_CONTEXT_URI, it) }
                            putBoolean(EXTRA_CONTEXT_ORDERED, asContext)
                            if (contextLabel.isNotEmpty()) {
                                putString(EXTRA_CONTEXT_LABEL, contextLabel)
                            }
                            if (playNext) putBoolean(EXTRA_PLAY_NEXT, true)
                        },
                    )
                    .build(),
            )
            .build()
}

/** Key for the context URI carried in a media item's metadata extras. */
const val EXTRA_CONTEXT_URI = "dev.lelonio.square.CONTEXT_URI"

/** Whether that queue is the context in its own order; see SquareApp's `onPlay`. */
const val EXTRA_CONTEXT_ORDERED = "dev.lelonio.square.CONTEXT_ORDERED"

/**
 * Set by "add to queue": play this right after the current track rather than at
 * the end of the queue.
 */
const val EXTRA_PLAY_NEXT = "dev.lelonio.square.PLAY_NEXT"

/** What to show the listener: "Playlist · Estate 2025", "Ricerca". */
const val EXTRA_CONTEXT_LABEL = "dev.lelonio.square.CONTEXT_LABEL"
