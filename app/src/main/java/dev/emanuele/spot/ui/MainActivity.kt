package dev.emanuele.spot.ui

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
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.playback.PlaybackService

/**
 * Owns the connection to the playback service; everything visual lives in
 * [SpotApp].
 *
 * The controller is activity-scoped rather than kept in a ViewModel because it
 * has to be released when the UI goes away — the session keeps playing without
 * it, and holding one from a backgrounded app leaks a binder connection.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    private var controller by mutableStateOf<MediaController?>(null)

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
        // Transparent bars; SpotTheme sets the icon colour, because it is the
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
            SpotApp(
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
        action == dev.emanuele.spot.playback.ACTION_OPEN_PLAYER ||
            getBooleanExtra(dev.emanuele.spot.playback.EXTRA_OPEN_PLAYER, false)

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
    private fun play(tracks: List<CatalogTrack>, index: Int, contextUri: String? = null) {
        val player = controller ?: return
        player.setMediaItems(tracks.map { toMediaItem(it, contextUri) }, index, 0L)
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
        player.addMediaItem(toMediaItem(track))
        if (wasEmpty) {
            player.prepare()
            player.play()
        }
    }

    private fun toMediaItem(track: CatalogTrack, contextUri: String? = null): MediaItem =
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
                        contextUri?.let {
                            android.os.Bundle().apply { putString(EXTRA_CONTEXT_URI, it) }
                        },
                    )
                    .build(),
            )
            .build()
}

/** Key for the context URI carried in a media item's metadata extras. */
const val EXTRA_CONTEXT_URI = "dev.emanuele.spot.CONTEXT_URI"
