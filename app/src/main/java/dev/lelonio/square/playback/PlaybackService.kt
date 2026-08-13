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
    private var session: MediaLibrarySession? = null
    private var engineStarted = false

    private lateinit var container: dev.lelonio.square.SquareApplication

    /** Whatever the active backend plays through. */
    private lateinit var player: androidx.media3.common.Player

    /**
     * The same player, when the active backend is Spotify — null otherwise.
     *
     * Everything below that reads this is genuinely Spotify's and has no
     * meaning for a backend with no native engine behind it: the saved queue's
     * shuffle order, the bitrate restart, the access-point login.
     */
    private var librespot: LibrespotPlayer? = null

    private val queue: PlayQueue? get() = librespot?.let { container.spotifyBackend.queue }

    /** Owns the AudioTrack the native sink writes into; Spotify's alone. */
    private val audioOutput get() = container.spotifyBackend.audioOutput

    private lateinit var playbackStore: PlaybackStore
    private lateinit var quality: dev.lelonio.square.data.QualityStore
    private lateinit var crossfade: dev.lelonio.square.data.CrossfadeStore
    private var saveJob: Job? = null

    private val playbackHost = object : dev.lelonio.square.backend.PlaybackHost {
        override val context get() = this@PlaybackService
        override val looper get() = mainLooper
    }

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore(this)
        playbackStore = PlaybackStore(this)
        container = application as dev.lelonio.square.SquareApplication
        quality = container.quality
        crossfade = container.crossfade

        player = buildPlayer(container.preferences.backend.value)
        val browseTree = MediaBrowseTree(this, scope)
        session = MediaLibrarySession.Builder(this, player, browseTree)
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

        // The car's shuffle and repeat buttons carry their own state, so they
        // are redrawn whenever the mode changes here — from the app, from a
        // headset, or from another Connect device.
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = redrawButtons()

            override fun onRepeatModeChanged(repeatMode: Int) = redrawButtons()

            /**
             * Per controller, because the two layouts differ: the shade shows
             * radio where the car shows repeat, and one blanket update would
             * hand every controller the same pair.
             */
            private fun redrawButtons() {
                val live = session ?: return
                live.connectedControllers.forEach { controller ->
                    live.setCustomLayout(
                        controller,
                        browseTree.layoutFor(
                            player,
                            radioInsteadOfRepeat = live.isMediaNotificationController(controller),
                        ),
                    )
                }
            }
        })

        // Already read by the application object, and harmless twice: `load`
        // returns at once once the file is open.
        AudioEffects.load(this)

        // The bitrate is fixed when the player is built, so a change means a new
        // engine. Watched here rather than acted on from the settings screen:
        // the service owns the engine's lifetime, and it is the only place that
        // can put playback back afterwards.
        scope.launch {
            quality.quality.drop(1).collect { reconfigureEngine("quality") }
        }

        // Which stretcher does speed and pitch. Unlike the bitrate this one is
        // switchable while playing: it is a property of the output, not of the
        // session.
        scope.launch {
            container.effectQuality.quality.collect { quality ->
                audioOutput.setLightEffects(
                    quality == dev.lelonio.square.data.EffectQuality.Light,
                )
            }
        }

        // The crossfade is part of the same configuration and just as fixed, so
        // it takes the same road: a new engine, with the queue put back where
        // it was.
        scope.launch {
            crossfade.seconds.drop(1).collect { reconfigureEngine("crossfade") }
        }

        // Playback handed to this phone from another client's device list.
        //
        // The engine obeys that transfer on its own and starts playing, so the
        // only thing missing is the queue: without it there is one track and
        // nothing after it. Watched here rather than in the UI because the
        // queue belongs to the service, and this has to work with no screen on.
        scope.launch {
            dev.lelonio.square.data.RemoteConnect.here.collect(::adoptRemoteQueue)
        }

        // Playback on another device, mirrored into this one's player.
        //
        // The notification, the lock screen and anything else holding a media
        // controller read the player and nothing else, so without this they
        // showed a stopped app while the account was playing in the next room.
        scope.launch {
            dev.lelonio.square.data.RemoteConnect.playback.collect { elsewhere ->
                librespot?.showRemote(elsewhere?.let { describeRemote(it) })
            }
        }

        // Same reasoning for the source itself: swapping backends is swapping
        // the player under a live session, which only the service can do.
        scope.launch {
            container.preferences.backend.drop(1).collect(::switchBackend)
        }

        startSpotifyEngineIfActive()
    }

    /**
     * Builds the player for [backendId] and wires up whatever is specific to it.
     *
     * The Spotify half is everything the native engine needs: the sink it
     * writes into, the effects chain applied to that sink, and the JNI context.
     * The YouTube half needs none of it — ExoPlayer owns its own output — so
     * this is the one place the two genuinely differ.
     */
    private fun buildPlayer(
        backendId: dev.lelonio.square.backend.BackendId,
    ): androidx.media3.common.Player = when (backendId) {
        dev.lelonio.square.backend.BackendId.SPOTIFY -> {
            val built = container.spotifyBackend.createPlayer(playbackHost) as LibrespotPlayer
            librespot = built

            // Another client pointing this device at a track of its own; see
            // LibrespotPlayer.onUnknownTrack.
            built.onUnknownTrack = { uri -> scope.launch { adoptPlayingTrack(uri) } }

            // Applied straight to the output rather than waiting for the UI: the
            // service can be running with no activity attached at all.
            audioOutput.setSpeedAndPitch(AudioEffects.speed.value, AudioEffects.pitch.value)
            built.restorePlaybackParameters(AudioEffects.speed.value, AudioEffects.pitch.value)

            NativeBridge.initContext(this)
            // Before connectEngine: the sink is built as soon as playback starts
            // and has nowhere to write without it.
            NativeBridge.setAudioOutput(audioOutput)

            // Reverb is not part of the Player interface, so it arrives here
            // rather than through the media session.
            scope.launch {
                AudioEffects.reverb.collect(audioOutput::setReverbAmount)
            }
            built
        }

        dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC -> {
            librespot = null
            container.youtubeBackend.createPlayer(playbackHost).also { built ->
                built.playbackParameters = androidx.media3.common.PlaybackParameters(
                    AudioEffects.speed.value,
                    AudioEffects.pitch.value,
                )
            }
        }
    }

    /**
     * Swaps the player under the running session.
     *
     * The session itself is kept: rebuilding it would drop the notification and
     * every controller bound to it, and the media session API exists precisely
     * so the player behind it can be replaced.
     */
    private fun switchBackend(backendId: dev.lelonio.square.backend.BackendId) = scope.launch {
        runCatching { savePlayback() }
        saveJob?.cancel()

        if (librespot != null) {
            // Off the main thread, and this is why the switch is a coroutine at
            // all: shutting the native engine down means stopping its threads
            // and closing its session, which took long enough to hang the input
            // queue — the app was reported as not responding, and the animation
            // meant to cover the switch never got a frame to draw in.
            runCatching { withContext(Dispatchers.IO) { NativeBridge.shutdown() } }
            engineStarted = false
        }
        // Emptied before it goes: the session keeps reporting whatever the old
        // player had loaded until something replaces it, and the new source's
        // queue may legitimately be empty — which would leave the previous
        // source's track sitting in the player with the new source's controls
        // around it.
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
        runCatching { player.release() }
        // Video belongs to the source that was playing it.
        dev.lelonio.square.backend.youtube.YouTubeVideoMode.reset()

        player = buildPlayer(backendId)
        session?.player = player
        startSpotifyEngineIfActive()
    }

    /**
     * Starts whatever the active backend needs before it can play.
     *
     * For Spotify that is the engine, and the queue is put back once it has
     * connected — it is the engine that holds it. A backend playing through a
     * plain player has nothing to wait for, so its queue goes back now.
     */
    private fun startSpotifyEngineIfActive() {
        if (librespot != null) {
            connectEngine()
        } else {
            restoreTimeline(container.preferences.backend.value)
            observeForSaving()
        }
    }

    /**
     * Fills in what the other device did not say about its track.
     *
     * Whether a client publishes a title and an artist beside the uri is up to
     * that client: the web player does, the desktop app does not always, and
     * what reached the notification then was a song with no name. The uri is
     * always there, and this app can read a track from a uri, so it does.
     */
    private suspend fun describeRemote(
        playback: dev.lelonio.square.data.RemotePlayback,
    ): dev.lelonio.square.data.RemotePlayback {
        if (playback.title.isNotEmpty() && playback.artist.isNotEmpty()) return playback
        val track = runCatching { container.activeBackend.tracksOf(playback.uri).firstOrNull() }
            .onFailure { android.util.Log.w(TAG, "cannot read ${playback.uri}: $it") }
            .getOrNull() ?: return playback

        val filled = playback.copy(
            title = playback.title.ifEmpty { track.name },
            artist = playback.artist.ifEmpty { track.artist },
            album = playback.album.ifEmpty { track.album },
            coverUri = playback.coverUri.ifEmpty { track.artworkUrl.orEmpty() },
        )
        // Kept there rather than here: every part of the app reads that state,
        // and the next cluster update rebuilds it from what the other device
        // published, which is what left this out in the first place.
        dev.lelonio.square.data.RemoteConnect.describe(filled)
        return filled
    }

    /** The queue last taken on, so the same transfer is not resolved twice. */
    private var adoptedContext: String? = null

    /**
     * Fills in the queue behind a track the engine was handed.
     *
     * Only when this side does not already know the track: playing from Square
     * puts it in the queue first, and re-resolving the context then would throw
     * away the order the listener is looking at.
     */
    private suspend fun adoptRemoteQueue(here: dev.lelonio.square.data.RemotePlayback?) {
        val engine = librespot ?: return
        if (here == null || here.uri.isEmpty()) return
        if (queue?.items.orEmpty().any { it.uri == here.uri }) return

        val key = "${here.contextUri}|${here.uri}"
        if (adoptedContext == key) return
        adoptedContext = key

        val tracks = runCatching {
            container.activeBackend.tracksOf(here.realContext ?: here.uri)
        }
            .onFailure { android.util.Log.w(TAG, "handed a queue we cannot read: $it") }
            .getOrDefault(emptyList())

        val index = tracks.indexOfFirst { it.uri == here.uri }
        if (tracks.isEmpty()) return
        // A long context takes seconds to read, and an answer about a track the
        // engine has already left behind would drag the screen back to it; see
        // adoptPlayingTrack.
        val playing = engineUri()
        if (playing.isNotEmpty() && playing != here.uri) {
            android.util.Log.i(TAG, "${here.uri} is over by now, leaving the queue alone")
            return
        }

        engine.adopt(
            tracks = tracks.map(::toQueueTrack),
            index = index.coerceAtLeast(0),
            positionMs = here.positionMs,
            contextUri = here.realContext,
            contextLabel = "",
            playing = here.playing,
        )
    }

    /**
     * Puts the queue behind a track another client started here.
     *
     * A pause on this phone and a tap in the web player on some playlist track:
     * the engine obeys and plays it, and this side had no idea. Not a transfer,
     * so nothing arrives that way, and not a cluster update either, since the
     * account does not describe a device to that device. The Connect state the
     * engine publishes is the one place the answer exists, so it is asked
     * directly, and what comes back is resolved the same way a handover is.
     */
    private suspend fun adoptPlayingTrack(uri: String) {
        val engine = librespot ?: return

        val here = withContext(Dispatchers.IO) {
            runCatching { org.json.JSONObject(NativeBridge.playingHere()) }
                .onFailure { android.util.Log.w(TAG, "cannot read what we play: $it") }
                .getOrNull()
        }
        val contextUri = here?.optString("contextUri").orEmpty()
        val source = contextUri.takeIf {
            it.isNotEmpty() && it.startsWith("spotify:") && !it.startsWith("spotify:web-api")
        }

        val key = "$contextUri|$uri"
        if (adoptedContext == key) return
        adoptedContext = key

        // The context first, because it is the whole list in its own order: a
        // playlist read this way comes back complete, while what the account
        // hands a device is a window of the next few dozen tracks.
        var tracks = if (source == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                runCatching { container.activeBackend.tracksOf(source) }
                    .onFailure { android.util.Log.w(TAG, "cannot read $source: $it") }
                    .getOrDefault(emptyList())
            }
        }

        // Nothing came back, which is the normal answer for the playlists
        // Spotify makes rather than stores: a daily mix, a radio, "Pop Mix"
        // exist for the account and resolve to nothing for anyone else. The
        // account sent the list along with the order to play it, so that is
        // what is used.
        if (tracks.none { it.uri == uri }) {
            val queued = buildList {
                val array = here?.optJSONArray("tracks")
                for (i in 0 until (array?.length() ?: 0)) add(array!!.getString(i))
            }
            tracks = withContext(Dispatchers.IO) {
                runCatching { dev.lelonio.square.data.Catalog.tracks(queued) }
                    .onFailure { android.util.Log.w(TAG, "cannot read the engine's queue: $it") }
                    .getOrDefault(emptyList())
            }
            android.util.Log.i(TAG, "adopting ${tracks.size} tracks from the engine itself")
        } else {
            android.util.Log.i(TAG, "adopting ${tracks.size} tracks from $source")
        }

        val index = tracks.indexOfFirst { it.uri == uri }
        if (index < 0) return
        // Resolving a long context takes seconds, and in those seconds the
        // listener can have moved on: an answer about a track that is no longer
        // playing would put the screen somewhere the engine has already left.
        if (engineUri() != uri) {
            android.util.Log.i(TAG, "$uri is over by now, leaving the queue alone")
            return
        }

        engine.adopt(
            tracks = tracks.map(::toQueueTrack),
            index = index,
            positionMs = 0L,
            contextUri = source,
            contextLabel = "",
            playing = true,
        )
    }

    /** What the engine says it is playing this instant. */
    private suspend fun engineUri(): String = withContext(Dispatchers.IO) {
        runCatching { org.json.JSONObject(NativeBridge.playingHere()).optString("trackUri") }
            .getOrDefault("")
    }

    /**
     * Rebuilds the engine so a new bitrate takes effect.
     *
     * librespot reads the bitrate when a track loads but the player owns its
     * configuration until it is dropped, so there is nothing to set: the
     * session is torn down and started again. The queue and position are saved
     * first and put back after, which is the same path a cold start takes.
     */
    /**
     * Rebuilds the player so a new bitrate or crossfade takes effect.
     *
     * The engine is not shut down for this any more. Tearing it down from here
     * meant dropping the tokio runtime from a JNI thread while librespot's own
     * threads were still inside it, which aborted the process on a destroyed
     * mutex: that is the crash that came back the moment the audio quality was
     * changed. The engine can now replace its session and player on its own and
     * leave the runtime and the output alone; all this side has to do is hand
     * the queue back afterwards, since the new Connect device has never seen it.
     */
    private fun reconfigureEngine(reason: String) {
        if (rebuilding) return
        val engine = librespot ?: return
        if (!engineStarted) return
        rebuilding = true
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.coerceAtLeast(0)
        runCatching { savePlayback() }
        android.util.Log.i(TAG, "rebuilding the player ($reason)")

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    NativeBridge.setQuality(quality.bitrateKbps(), crossfade.durationMs())
                }
                    .onFailure { android.util.Log.e(TAG, "could not rebuild the player: $it") }
                    .isSuccess
            }
            rebuilding = false
            if (!ok) return@launch
            // The queue on screen is untouched and is still the right one; the
            // engine's copy went with the session.
            engine.reloadQueue(playing = wasPlaying, positionMs = position)
        }
    }

    /**
     * Whether a rebuild is already under way.
     *
     * The loss is noticed after every command, so a listener pressing skip twice
     * while offline would otherwise ask for a second teardown in the middle of
     * the first one.
     */
    private var rebuilding = false

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
        // Nothing to authenticate against while another backend is playing, and
        // starting the engine would take the audio device out from under it.
        val engine = librespot ?: return@launch
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
                    // Kept across launches, so the account sees this phone as
                    // one device rather than a new one every time.
                    deviceId = container.preferences.deviceId(),
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
                    // Off is zero, which is upstream librespot's own behaviour.
                    crossfadeMs = crossfade.durationMs(),
                    listener = engine,
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
        val engine = librespot ?: return
        if (player.mediaItemCount > 0) return
        val saved = playbackStore.load() ?: return
        // Spotify's own queue only. The store holds whichever source was last
        // playing, and handing librespot a queue of `ytmusic:` URIs would fill
        // the player with tracks it cannot load.
        if (saved.tracks.none { it.uri.startsWith("spotify:") }) return

        // One call rather than a sequence of commands: see LibrespotPlayer.restore
        // for why the order, position and modes have to be applied together.
        engine.restore(
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
        // A backend with no PlayQueue behind it — the YouTube one — is saved off
        // the Media3 timeline instead. That loses the pre-shuffle order, which
        // only librespot's queue knows, so a shuffled queue comes back in the
        // order it was actually playing rather than the order it was built in.
        val queue = queue ?: return saveTimeline()
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

    /**
     * The same snapshot, taken from the player's own timeline.
     *
     * Everything written here is already in the media items — the app puts the
     * track's URI, its metadata and the context it came from into each one — so
     * this is a read of what is loaded rather than a second bookkeeping of it.
     */
    private fun saveTimeline() {
        val count = player.mediaItemCount
        if (count == 0) {
            playbackStore.clear()
            return
        }
        val tracks = (0 until count).map { index ->
            val item = player.getMediaItemAt(index)
            val metadata = item.mediaMetadata
            CatalogTrack(
                uri = item.mediaId,
                name = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                // Kept per track rather than taken from the current one: the
                // artist page a row opens is its own.
                artistUri = metadata.extras
                    ?.getString(dev.lelonio.square.ui.EXTRA_ARTIST_URI),
                artists = creditedArtists(metadata.extras),
                durationMs = metadata.durationMs ?: 0L,
                artworkUrl = metadata.artworkUri?.toString(),
            )
        }
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        playbackStore.save(
            SavedPlayback(
                tracks = tracks,
                shuffleOrder = null,
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                repeatMode = player.repeatMode,
                contextUri = extras?.getString(dev.lelonio.square.ui.EXTRA_CONTEXT_URI),
                contextOrdered = extras?.getBoolean(dev.lelonio.square.ui.EXTRA_CONTEXT_ORDERED) == true,
                contextLabel = extras?.getString(dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL).orEmpty(),
            ),
        )
    }

    /**
     * Puts the last queue back into a plain [androidx.media3.common.Player].
     *
     * Paused, like the Spotify one: see [dev.lelonio.square.data.PlaybackStore].
     * A queue saved by the other backend is left alone — its URIs mean nothing
     * to this player, and loading them would fill the screen with tracks that
     * fail one after another.
     */
    private fun restoreTimeline(backendId: dev.lelonio.square.backend.BackendId) {
        if (player.mediaItemCount > 0) return
        val saved = playbackStore.load() ?: return
        val backend = when (backendId) {
            dev.lelonio.square.backend.BackendId.SPOTIFY -> container.spotifyBackend
            dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC -> container.youtubeBackend
        }
        if (saved.tracks.none { backend.owns(it.uri) }) return

        player.setMediaItems(
            saved.tracks.map { track ->
                androidx.media3.common.MediaItem.Builder()
                    .setMediaId(track.uri)
                    .setUri(track.uri)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.name)
                            .setArtist(track.artist)
                            .setDurationMs(track.durationMs.takeIf { it > 0 })
                            .setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse))
                            .setExtras(
                                android.os.Bundle().apply {
                                    track.artistUri?.let {
                                        putString(dev.lelonio.square.ui.EXTRA_ARTIST_URI, it)
                                    }
                                    val credited = track.artists.filter { it.uri != null }
                                    if (credited.isNotEmpty()) {
                                        putStringArrayList(
                                            dev.lelonio.square.ui.EXTRA_ARTIST_NAMES,
                                            ArrayList(credited.map { it.name }),
                                        )
                                        putStringArrayList(
                                            dev.lelonio.square.ui.EXTRA_ARTIST_URIS,
                                            ArrayList(credited.map { it.uri!! }),
                                        )
                                    }
                                    saved.contextUri?.let {
                                        putString(dev.lelonio.square.ui.EXTRA_CONTEXT_URI, it)
                                    }
                                    putBoolean(
                                        dev.lelonio.square.ui.EXTRA_CONTEXT_ORDERED,
                                        saved.contextOrdered,
                                    )
                                    putString(
                                        dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL,
                                        saved.contextLabel,
                                    )
                                },
                            )
                            .build(),
                    )
                    .build()
            },
            saved.index.coerceIn(0, saved.tracks.lastIndex),
            saved.positionMs,
        )
        player.repeatMode = saved.repeatMode
        player.prepare()
        android.util.Log.i(TAG, "restored ${saved.tracks.size} tracks at ${saved.index}")
    }

    private fun toCatalogTrack(track: PlayQueue.Track) = CatalogTrack(
        uri = track.uri,
        name = track.title,
        artist = track.artist,
        artistUri = track.artistUri,
        artists = track.artists,
        durationMs = track.durationMs,
        artworkUrl = track.artworkUri?.toString(),
    )

    private fun toQueueTrack(track: CatalogTrack) = PlayQueue.Track(
        uri = track.uri,
        title = track.name,
        artist = track.artist,
        artistUri = track.artistUri,
        artists = track.artists,
        durationMs = track.durationMs,
        artworkUri = track.artworkUrl?.let(android.net.Uri::parse),
    )

    /** The two parallel lists put back together; see EXTRA_ARTIST_NAMES. */
    private fun creditedArtists(
        extras: android.os.Bundle?,
    ): List<dev.lelonio.square.data.CatalogArtist> {
        val names = extras?.getStringArrayList(dev.lelonio.square.ui.EXTRA_ARTIST_NAMES)
        val uris = extras?.getStringArrayList(dev.lelonio.square.ui.EXTRA_ARTIST_URIS)
        if (names == null || uris == null) return emptyList()
        return names.zip(uris) { name, uri -> dev.lelonio.square.data.CatalogArtist(name, uri) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Saved first: this is the last chance to record where the track was,
        // and stopping is what makes the position worth having.
        runCatching { savePlayback() }
        // Swiping the app away stops it. The service used to survive a swipe
        // while something was playing, on the grounds that killing audio
        // mid-track is rude, but a player that goes on after its app has been
        // dismissed is a player the gesture did not reach: the one obvious way
        // to stop it did nothing, and what was left was a notification the user
        // had already tried to get rid of.
        //
        // Media3's own helper rather than a bare stopSelf: it pauses every
        // player attached to the service first, so the engine is told to stop
        // instead of being cut off when onDestroy releases it.
        pauseAllPlayersAndStopSelf()
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
        // Only if there is an engine to stop. Reaching for the Spotify backend
        // here would otherwise build its audio sink just to release it.
        if (librespot != null) {
            NativeBridge.shutdown()
            audioOutput.release()
        }
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
