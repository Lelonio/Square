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

    /**
     * A Spotify link the app was opened with.
     *
     * Carries a number for the same reason [openPlayer] does: the same link
     * twice is two requests, and a plain string would look unchanged the second
     * time.
     */
    private var link by mutableStateOf<LinkRequest?>(null)

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
        intent?.let(::takeLink)

        setContent {
            SquareApp(
                player = controller,
                onPlay = ::play,
                onEnqueue = ::enqueue,
                openPlayer = openPlayer,
                link = link,
            )
        }
    }

    /**
     * Shrinks into a floating window when leaving the app with a video playing.
     *
     * Only then: picture-in-picture on a music player with nothing to look at
     * would be a black rectangle covering whatever the user actually left to
     * do. The aspect ratio is the video's own, so the window is the picture
     * rather than the picture letterboxed inside a window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!dev.lelonio.square.backend.youtube.YouTubeVideoMode.enabled.value) return
        val size = controller?.videoSize
        val width = size?.width?.takeIf { it > 0 } ?: 16
        val height = size?.height?.takeIf { it > 0 } ?: 9
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(width, height))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dev.lelonio.square.backend.youtube.YouTubeVideoMode.setPictureInPicture(
            isInPictureInPictureMode,
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensPlayer()) {
            openPlayer++
        }
        takeLink(intent)
    }

    /**
     * The whole of link handling on this side: recognise it, and pass it on.
     *
     * A `singleTask` activity gets the second link through `onNewIntent` rather
     * than a new instance, so both ways in lead here.
     */
    private fun takeLink(intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_VIEW) return
        val uri = dev.lelonio.square.data.SpotifyLink.parse(intent.data) ?: return
        link = LinkRequest(uri, (link?.n ?: 0) + 1)
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
        /**
         * Where in the track to start, for playback that is being picked up
         * rather than begun: bringing the music back from another device is the
         * one caller that has somewhere to resume from. Starting at zero and
         * seeking afterwards is not the same thing, and sounds like it: the
         * track begins, then jumps.
         */
        positionMs: Long = 0L,
    ) {
        val player = controller ?: return
        player.setMediaItems(
            tracks.map { toMediaItem(it, contextUri, asContext, contextLabel) },
            index,
            positionMs,
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
            // The media id carries the track's URI; PlayQueue refuses anything else.
            .setMediaId(track.uri)
            // The same URI again, as the thing to play. The librespot player
            // ignores it and works off the media id, but ExoPlayer — which is
            // what the YouTube backend uses — plays the URI and nothing else;
            // its resolver turns a `ytmusic:` one into a real stream at load.
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    // Left unset when unknown rather than sent as zero: some of
                    // YouTube's shelves carry no length, and a zero here would
                    // win over the one the player works out from the stream.
                    .setDurationMs(track.durationMs.takeIf { it > 0 })
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
                            // So the artist's name in the player is a way to
                            // reach them, rather than a caption.
                            track.artistUri?.let { putString(EXTRA_ARTIST_URI, it) }
                            // And each credited artist separately, so a track
                            // by two people opens the one that was pressed.
                            val credited = track.artists.filter { it.uri != null }
                            if (credited.isNotEmpty()) {
                                putStringArrayList(
                                    EXTRA_ARTIST_NAMES,
                                    ArrayList(credited.map { it.name }),
                                )
                                putStringArrayList(
                                    EXTRA_ARTIST_URIS,
                                    ArrayList(credited.map { it.uri!! }),
                                )
                            }
                            if (playNext) putBoolean(EXTRA_PLAY_NEXT, true)
                        },
                    )
                    .build(),
            )
            .build()
}

/** A `spotify:` URI the app was opened with, and which opening it was. */
data class LinkRequest(val uri: String, val n: Int)

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

/** The first artist's own uri, so the player's second line can be opened. */
const val EXTRA_ARTIST_URI = "dev.lelonio.square.ARTIST_URI"

/** Every credited artist, in order, as two lists that line up. */
const val EXTRA_ARTIST_NAMES = "dev.lelonio.square.ARTIST_NAMES"
const val EXTRA_ARTIST_URIS = "dev.lelonio.square.ARTIST_URIS"
