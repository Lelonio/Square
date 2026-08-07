package dev.lelonio.square.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.auth.TokenStore
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.PlaybackStore
import dev.lelonio.square.data.SavedPlayback
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the media session and owns the lifetime of the native engine.
 *
 * The engine lives in the service rather than in an activity or a singleton so
 * that playback survives the UI being destroyed, and so a single `shutdown()`
 * in [onDestroy] is guaranteed to run.
 */
@UnstableApi
/** Set on the intent the notification fires: open straight into the player. */
const val EXTRA_OPEN_PLAYER = "dev.lelonio.square.OPEN_PLAYER"

/** Its action; see the note where the PendingIntent is built. */
const val ACTION_OPEN_PLAYER = "dev.lelonio.square.action.OPEN_PLAYER"

class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var tokens: TokenStore
    private lateinit var queue: PlayQueue
    private lateinit var player: LibrespotPlayer
    private var session: MediaLibrarySession? = null
    private var engineStarted = false

    /** Owns the AudioTrack the native sink writes into. */
    private val audioOutput = AudioOutput()

    private lateinit var playbackStore: PlaybackStore
    private lateinit var quality: dev.lelonio.square.data.QualityStore
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore(this)
        playbackStore = PlaybackStore(this)
        quality = (application as dev.lelonio.square.SquareApplication).quality
        queue = PlayQueue()
        player = LibrespotPlayer(
            this,
            mainLooper,
            queue,
            audioOutput::setSpeedAndPitch,
            audioOutput::fadeOutThen,
            audioOutput::fadeIn,
            audioOutput::setPlaybackActive,
        )
        session = MediaLibrarySession.Builder(this, player, MediaBrowseTree(this, scope))
            // Without this the notification is inert to a tap: Media3 has no way
            // to know which activity owns the session. `SINGLE_TOP` so an app
            // already running comes forward rather than starting a second copy
            // on top of itself.
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    // Deliberately not ACTION_MAIN/CATEGORY_LAUNCHER. A launcher
                    // intent aimed at a singleTask activity that is already
                    // running is treated as "bring the task forward" and the
                    // intent is never delivered — the app came up on whatever
                    // screen it was last on and onNewIntent never fired. A
                    // custom action is delivered.
                    android.content.Intent(this, dev.lelonio.square.ui.MainActivity::class.java)
                        .setAction(ACTION_OPEN_PLAYER)
                        .putExtra(EXTRA_OPEN_PLAYER, true),
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        // The app's own mark in the shade, instead of Media3's generic note.
        // The provider is built rather than subclassed: the small icon is the
        // only thing being changed.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(dev.lelonio.square.R.drawable.ic_notification) },
        )

        AudioEffects.load(this)
        // Applied straight to the output rather than waiting for the UI: the
        // service can be running with no activity attached at all.
        audioOutput.setSpeedAndPitch(AudioEffects.speed.value, AudioEffects.pitch.value)
        player.restorePlaybackParameters(AudioEffects.speed.value, AudioEffects.pitch.value)

        NativeBridge.initContext(this)
        // Before connectEngine: the sink is built as soon as playback starts and
        // has nowhere to write without it.
        NativeBridge.setAudioOutput(audioOutput)

        // Reverb is not part of the Player interface, so it arrives here rather
        // than through the media session.
        scope.launch {
            AudioEffects.reverb.collect(audioOutput::setReverbAmount)
        }

        // The bitrate is fixed when the player is built, so a change means a new
        // engine. Watched here rather than acted on from the settings screen:
        // the service owns the engine's lifetime, and it is the only place that
        // can put playback back afterwards.
        scope.launch {
            quality.quality.drop(1).collect { restartForQuality() }
        }

        connectEngine()
    }

    /**
     * Rebuilds the engine so a new bitrate takes effect.
     *
     * librespot reads the bitrate when a track loads but the player owns its
     * configuration until it is dropped, so there is nothing to set: the
     * session is torn down and started again. The queue and position are saved
     * first and put back after, which is the same path a cold start takes.
     */
    private fun restartForQuality() {
        val wasPlaying = player.playWhenReady
        runCatching { savePlayback() }

        runCatching { NativeBridge.shutdown() }
        engineStarted = false
        player.clearForRestart()

        scope.launch {
            connectEngine().join()
            // restoreQueue, run by connectEngine, puts the queue back at the
            // position that was just saved, paused.
            if (wasPlaying && player.mediaItemCount > 0) player.play()
        }
    }

    /**
     * Re-runs [connectEngine] on demand.
     *
     * The service is created when the UI binds its controller, which happens
     * before the user has logged in, so the engine cannot be started once in
     * [onCreate] and left alone. The login flow sends this action when it has a
     * session.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONNECT) connectEngine()
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Authenticates the native session. Runs off the main thread because the
     * access-point handshake is blocking.
     */
    private fun connectEngine() = scope.launch {
        // Guards against the repeated ACTION_CONNECT the UI may send: the native
        // side rejects a second start, and that error would look like a login
        // failure and clear a perfectly good session.
        if (engineStarted || !tokens.isLoggedIn) return@launch
        engineStarted = true

        runCatching {
            val accessToken = tokens.validAccessToken()
            withContext(Dispatchers.IO) {
                NativeBridge.start(
                    // Must match the id the OAuth token was minted for.
                    // `Login5Manager::auth_token` always asks as a stored
                    // credential, and that path signs the request with the
                    // session's client id — so the two have to agree or the
                    // access point rejects every catalogue call.
                    clientId = SpotifyOAuth.CLIENT_ID,
                    deviceName = android.os.Build.MODEL ?: "Android",
                    accessToken = accessToken,
                    // filesDir, not cacheDir: credentials must survive the
                    // system reclaiming cache space, or the next launch loses
                    // catalogue access until the user logs in again.
                    credentialsDir = filesDir.resolve("librespot").absolutePath,
                    cacheDir = cacheDir.absolutePath,
                    // The app's own language, not the account's. Spotify
                    // localises what it answers with, artwork included: the
                    // generated playlists carry the language in the cover's
                    // URL, so the tiles came back in English.
                    language = appLanguage(),
                    bitrateKbps = quality.bitrateKbps(),
                    listener = player,
                )
            }
        }.onFailure { error ->
            engineStarted = false
            android.util.Log.e(TAG, "engine start failed: $error", error)
            // The access point rejecting the token means the session is no
            // longer usable, so send the user back through OAuth rather than
            // retrying with the same credentials. A revoked refresh token is
            // already cleared by TokenStore before it gets here.
            if (error.message?.contains(PREMIUM_REQUIRED) == true) {
                // Back to the login screen, with a reason. Staying signed in
                // would leave an app that looks connected and plays nothing.
                tokens.clear()
                android.widget.Toast.makeText(
                    this@PlaybackService,
                    getString(dev.lelonio.square.R.string.premium_required),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else if (error.message?.contains("login failed") == true) {
                tokens.clear()
            }
        }.onSuccess {
            android.util.Log.i(TAG, "engine connected")
            restoreQueue()
            observeForSaving()
        }
    }

    /**
     * Puts the last queue back, paused at the saved position.
     *
     * Skipped when something is already loaded: the user opened the app, played
     * something and only then did the engine finish connecting, and overwriting
     * that would be worse than not restoring at all.
     */
    private fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = playbackStore.load() ?: return

        // One call rather than a sequence of commands: see LibrespotPlayer.restore
        // for why the order, position and modes have to be applied together.
        player.restore(
            tracks = saved.tracks.map(::toQueueTrack),
            shuffleOrder = saved.shuffleOrder,
            index = saved.index,
            positionMs = saved.positionMs,
            repeatMode = saved.repeatMode,
            contextUri = saved.contextUri,
            contextIsOrdered = saved.contextOrdered,
            contextLabel = saved.contextLabel,
        )
        android.util.Log.i(TAG, "restored ${saved.tracks.size} tracks at ${saved.index}")
    }

    /**
     * Writes the queue back on every meaningful change, and on a slow tick while
     * playing.
     *
     * The tick is what makes the *position* survive being force-stopped, which
     * fires no lifecycle callback at all; events alone would only ever save the
     * position at the moment playback started.
     */
    private fun observeForSaving() {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onEvents(
                p: androidx.media3.common.Player,
                events: androidx.media3.common.Player.Events,
            ) {
                if (events.containsAny(
                        androidx.media3.common.Player.EVENT_TIMELINE_CHANGED,
                        androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION,
                        androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED,
                        androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) {
                    savePlayback()
                }
            }
        })

        saveJob?.cancel()
        saveJob = scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) savePlayback()
            }
        }
    }

    /**
     * Snapshots the queue.
     *
     * Read from [PlayQueue] rather than through the Media3 timeline because only
     * the queue knows the pre-shuffle order and the permutation on top of it;
     * the timeline exposes just the current sequence.
     */
    private fun savePlayback() {
        if (queue.items.isEmpty()) {
            playbackStore.clear()
            return
        }

        playbackStore.save(
            SavedPlayback(
                tracks = queue.originalTracks.map(::toCatalogTrack),
                shuffleOrder = queue.shuffleOrder,
                index = queue.currentIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                repeatMode = player.repeatMode,
                contextUri = queue.contextUri,
                contextOrdered = queue.contextIsOrdered,
                contextLabel = queue.contextLabel,
            ),
        )
    }

    private fun toCatalogTrack(track: PlayQueue.Track) = CatalogTrack(
        uri = track.uri,
        name = track.title,
        artist = track.artist,
        durationMs = track.durationMs,
        artworkUrl = track.artworkUri?.toString(),
    )

    private fun toQueueTrack(track: CatalogTrack) = PlayQueue.Track(
        uri = track.uri,
        title = track.name,
        artist = track.artist,
        durationMs = track.durationMs,
        artworkUri = track.artworkUrl?.let(android.net.Uri::parse),
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { savePlayback() }
        // Swiping the app away should not kill audio mid-track, but it should
        // stop an idle service from lingering in the notification shade.
        val current = session?.player
        if (current == null || !current.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Before cancelling the scope: the last position is the one worth having.
        runCatching { savePlayback() }
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        NativeBridge.shutdown()
        audioOutput.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"

        /** How often the position is written back while playing. */
        private const val SAVE_INTERVAL_MS = 10_000L

        /** What the engine reports for an account it cannot stream to. */
        private const val PREMIUM_REQUIRED = "premium account required"

        /** Tells the service a Spotify session now exists. */
        const val ACTION_CONNECT = "dev.lelonio.square.action.CONNECT"

        fun connect(context: android.content.Context) {
            context.startService(
                Intent(context, PlaybackService::class.java).setAction(ACTION_CONNECT),
            )
        }
    }
}

/**
 * The language Spotify should answer in.
 *
 * Read from the resources rather than from the device, so it follows what the
 * app is actually showing: today that is Italian for everyone, and the day it
 * has more languages this keeps pointing at the one in use.
 */
/**
 * The language to ask Spotify to answer in.
 *
 * Read from the app's own setting rather than from this context's resources: a
 * service is not re-created when the language changes, and its configuration
 * would still be yesterday's.
 */
private fun android.content.Context.appLanguage(): String =
    (applicationContext as dev.lelonio.square.SquareApplication).language.language()
