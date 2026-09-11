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
private const val ACTION_LISTEN = "dev.lelonio.square.LISTEN_ONLY"
private const val ACTION_TOGGLE = "dev.lelonio.square.PIP_TOGGLE"

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
                onEnqueueAll = ::enqueueAll,
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
        // Either kind of video: YouTube's, and the music video Spotify has for
        // a track. Leaving the app while watching one means the same thing in
        // both cases.
        val watching = dev.lelonio.square.backend.youtube.YouTubeVideoMode.enabled.value ||
            dev.lelonio.square.backend.spotify.SpotifyVideoMode.enabled.value
        if (!watching) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /**
     * The floating window, and the one button in it.
     *
     * The button is the thing YouTube puts there: keep the sound, drop the
     * picture. Pausing to close a video is the wrong end of what the listener
     * asked for — they left the app, so the music is what they are keeping.
     */
    private fun pipParams(): android.app.PictureInPictureParams {
        val size = controller?.videoSize
        val width = size?.width?.takeIf { it > 0 } ?: 16
        val height = size?.height?.takeIf { it > 0 } ?: 9

        // Play and pause first, because that is the one control anybody
        // expects a floating video to have.
        val playing = controller?.isPlaying == true
        val transport = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(
                this,
                if (playing) {
                    dev.lelonio.square.R.drawable.ic_pip_pause
                } else {
                    dev.lelonio.square.R.drawable.ic_pip_play
                },
            ),
            getString(if (playing) dev.lelonio.square.R.string.pause else dev.lelonio.square.R.string.play),
            getString(if (playing) dev.lelonio.square.R.string.pause else dev.lelonio.square.R.string.play),
            android.app.PendingIntent.getBroadcast(
                this,
                1,
                android.content.Intent(ACTION_TOGGLE).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val listen = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, dev.lelonio.square.R.drawable.ic_headphones),
            getString(dev.lelonio.square.R.string.pip_listen),
            getString(dev.lelonio.square.R.string.pip_listen),
            android.app.PendingIntent.getBroadcast(
                this,
                0,
                android.content.Intent(ACTION_LISTEN).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(width, height))
            .setActions(listOf(transport, listen))
            .build()
    }

    /**
     * Told by the button above: the picture goes, the song stays.
     *
     * Registered while the activity is alive and not exported — nothing outside
     * this app has any business asking for it.
     */
    private val listenRequest = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_TOGGLE) {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                return
            }
            if (intent?.action != ACTION_LISTEN) return
            val at = controller?.currentPosition ?: 0L
            if (dev.lelonio.square.backend.spotify.SpotifyVideoMode.enabled.value) {
                dev.lelonio.square.backend.spotify.SpotifyVideoMode.listen(at)
            } else {
                controller?.let(dev.lelonio.square.backend.youtube.YouTubeVideoMode::toggle)
            }
            // Said before finishing, and this matters: the flag is the app's,
            // not the window's, and finishing from inside a floating window
            // does not always report the window closing. Left set, the next
            // time the app was opened it drew the floating window's layout —
            // a black rectangle with a video surface and nothing else.
            dev.lelonio.square.backend.youtube.YouTubeVideoMode.setPictureInPicture(false)

            // Closing the window rather than going back to a full screen the
            // listener has already left. The music is in the service, and it
            // carries on.
            finish()
        }
    }

    /** Whether this activity is the floating window right now. */
    private var inPictureInPicture = false

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
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
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            listenRequest,
            android.content.IntentFilter(ACTION_LISTEN).apply { addAction(ACTION_TOGGLE) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                // The activity may already be stopping by the time this lands.
                controller = runCatching { future.get() }.getOrNull()
                // The floating window's own button has to say what it does now,
                // and the system only redraws it when the parameters are set
                // again.
                controller?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!inPictureInPicture) return
                        runCatching { setPictureInPictureParams(pipParams()) }
                    }
                })
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(listenRequest) }
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

        // Offline, the queue is only what can actually be played.
        //
        // The rows for the rest are already inert, so nothing here is being
        // taken away that the listener could have chosen — but a queue is not
        // only what was tapped. Left whole it would advance into a track that
        // cannot be fetched, and the silence after a downloaded song would look
        // like the download had failed rather than like the song after it was
        // never here. Files on the phone play in every mode; they were never
        // coming over the network.
        val playable = if (!dev.lelonio.square.playback.OfflineMode.active.value) {
            tracks
        } else {
            val here = (application as dev.lelonio.square.SquareApplication)
                .downloads.files.value
            tracks.filter { it.uri.startsWith("local:") || it.uri in here }
        }
        if (playable.isEmpty()) return

        // The song that was tapped keeps its place, wherever the filtering left
        // it; falling back to the top rather than to whatever now sits at the
        // old index, which would be a different song entirely.
        val wanted = tracks.getOrNull(index)?.uri
        val start = playable.indexOfFirst { it.uri == wanted }.coerceAtLeast(0)

        player.setMediaItems(
            playable.map { toMediaItem(it, contextUri, asContext, contextLabel) },
            start,
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

    /**
     * Appends multiple tracks to the end of the queue (used for continuous Autoplay).
     */
    private fun enqueueAll(tracks: List<CatalogTrack>) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val wasEmpty = player.mediaItemCount == 0
        val items = tracks.map { toMediaItem(it, playNext = false) }
        player.addMediaItems(items)
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
                    // The copy saved beside the download, when there is one.
                    //
                    // Not a fallback but a preference, the same way the app's
                    // own artwork reads it: the URL names the picture, so the
                    // bytes cannot have changed, and a file plays offline while
                    // a URL is a fetch that fails. The notification was the one
                    // place still handed the URL, so a downloaded song played
                    // with a blank tile on the lock screen.
                    .setArtworkUri(
                        track.artworkUrl?.let { url ->
                            dev.lelonio.square.download.DownloadExtras.fileOf(url, "art")
                                ?.let(android.net.Uri::fromFile)
                                ?: android.net.Uri.parse(url)
                        },
                    )
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
                            // And the record, so its name under the title is a
                            // way in as well.
                            track.albumUri?.let { putString(EXTRA_ALBUM_URI, it) }
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

/** The record the playing track is from; see [EXTRA_ARTIST_URI]. */
const val EXTRA_ALBUM_URI = "dev.lelonio.square.ALBUM_URI"

/** Every credited artist, in order, as two lists that line up. */
const val EXTRA_ARTIST_NAMES = "dev.lelonio.square.ARTIST_NAMES"
const val EXTRA_ARTIST_URIS = "dev.lelonio.square.ARTIST_URIS"
