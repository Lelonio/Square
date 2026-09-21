package dev.lelonio.square.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import dev.lelonio.square.nativecore.NativeEvents

/**
 * Adapts the native librespot engine to the Media3 [Player] interface.
 *
 * Extending [SimpleBasePlayer] rather than implementing [Player] directly means
 * Media3 owns the listener bookkeeping, command masking and threading rules; we
 * only describe the current state and handle the four operations the engine
 * actually supports. Everything the engine does not support is simply left out
 * of the available-commands set, so the notification and any connected
 * controller grey out the right buttons instead of failing at runtime.
 *
 * All mutation happens on [applicationLooper]; native events are marshalled onto
 * it before touching state.
 */
@UnstableApi
class LibrespotPlayer(
    context: android.content.Context,
    looper: Looper,
    private val queue: PlayQueue,
    /**
     * Applies tempo and pitch to the output. Passed in rather than reached for
     * because the audio device belongs to the service, not to this adapter.
     */
    private val onSpeedAndPitch: (speed: Float, pitch: Float) -> Unit,
    /**
     * Fades the output down, runs the load, and lets it come back up.
     *
     * Track changes go through here so a skip dissolves instead of cutting.
     * Note this is a fade *through silence*, not a true crossfade: librespot
     * decodes one track at a time and the sink carries a single stream, so
     * there is no second source to overlap with. Overlapping would mean running
     * two engines and mixing them.
     */
    private val fadeOutThen: (() -> Unit) -> Unit,
    private val fadeIn: () -> Unit,
    /**
     * Whether the engine dissolves a change of track itself.
     *
     * When it does, the output must not be touched: the fade above ramps this
     * app's own audio track down and *throws away* what the engine produces
     * until it is brought back up, which is precisely the second of music the
     * dissolve is made of. What was heard was the silence of this fade, then
     * the tail of the old song arriving late, then the new one.
     */
    private val dissolves: () -> Boolean,
    /**
     * Told when playback starts and stops.
     *
     * The output needs this because the reverb sits on the global mix; see
     * AudioOutput.setPlaybackActive.
     */
    private val onPlaybackActive: (Boolean) -> Unit,
    /**
     * Holds the reverb down while the music is ducked.
     *
     * Separate from the volume below because it has to be: the room is an
     * effect on the output mix, and its send is fed before the level this
     * player sets. See AudioOutput.duckReverb.
     */
    private val onDuckReverb: (Float) -> Unit,
) : SimpleBasePlayer(looper), NativeEvents {

    /**
     * Hands the current queue to the engine, with the fade around it.
     *
     * The whole list, not the current track: the engine now drives playback
     * through Spotify Connect, where the queue is part of the state published
     * to the account. Loading one track at a time would show every other device
     * a queue with nothing in it, and would take the end-of-track advance away
     * from the side that owns it.
     */
    private fun pushQueue(startPlaying: Boolean, positionMs: Int = 0) {
        val uris = queue.items.map { it.uri }
        if (uris.isEmpty()) return
        // From here until the engine is on this queue, what it says it is
        // playing is the past; see [ownQueuePending].
        ownQueuePending = true
        handler.removeCallbacks(giveUpOnOwnQueue)
        handler.postDelayed(giveUpOnOwnQueue, OWN_QUEUE_MS)
        val index = queue.currentIndex
        val contextUri = queue.contextUri.orEmpty()
        // Never as a context, whatever it is.
        //
        // Handing the engine a playlist URI asks Spotify to resolve the list
        // itself, and its copy is not the one on screen: it steps over tracks
        // the account is not offered here, quietly and without a word in the
        // log, so a song sitting plainly in the list is jumped as if it were
        // not there. Liked Songs hit this first and was fixed on its own; the
        // same thing happens to any playlist.
        //
        // What goes over instead is this list, in this order, published under
        // the playlist it came from — see native/src/engine.rs, which files it
        // with the context so the account still records the listen against the
        // playlist and other devices still name it. What is lost is Spotify's
        // own ordering of a list the app has already read, which is nothing,
        // and what is gained is that the queue plays what it shows.
        val asContext = false

        // A track picked from a list is not one the engine could see coming,
        // and this used to fade the output down over its own half of the
        // dissolve while the engine went looking for the context. Two fades
        // over one change: the output came back up the moment the load was
        // sent, and the engine's tail, which had only just started going down,
        // was heard again underneath the new song rising. The engine fades the
        // song it is leaving whether or not it has anywhere to go yet, so this
        // one leaves the output alone; see handle_command_load in player.rs.
        changing {
            // Back on the player's looper before anything is read or called.
            // The fade runs on its own thread, and everything here — the queue,
            // playWhenReady, the engine's own idea of what is loaded — belongs
            // to the looper. Reading it from the fade thread is a race, and a
            // race in the middle of a track change is exactly the rare crash
            // that skipping quickly could produce.
            // A load is the one command with no way around the Connect device,
            // so when that is gone the track simply never changes. Build a new
            // one first and load on the other side of it.
            withDevice {
                if (released) return@withDevice
                // Read now, not when this was scheduled. The fade takes a
                // moment, and a tap on a track while paused arrives as "load
                // this" followed immediately by "play": with the flag frozen at
                // schedule time the load went out as paused, the play landed
                // before it, and the track sat there selected and silent.
                val wanted = startPlaying || playWhenReady || wantPlay
                runCatching {
                    NativeBridge.loadQueue(
                        uris,
                        index,
                        wanted,
                        positionMs,
                        contextUri,
                        playAsContext = asContext,
                    )
                }
                    .onFailure { android.util.Log.e("SquarePlayer", "load failed: ${it.message}") }
                    .onSuccess { engineQueueStale = false }
                if (wanted && !dissolves()) fadeIn()
            }
        }
    }

    /**
     * Runs a change of track, through the output fade or straight through.
     *
     * Straight through while the engine dissolves: what it mixes has to reach
     * the speaker to be heard at all. See [dissolves].
     */
    private fun changing(action: () -> Unit) {
        if (dissolves()) action() else fadeOutThen(action)
    }

    /**
     * Built here rather than injected because its callbacks have to reach back
     * into this player, and passing it in would need the two to be constructed
     * in a cycle.
     */
    private val focus = AudioFocusController(
        context,
        onPause = { engine("pause") { NativeBridge.pause() } },
        onResume = { engine("play") { NativeBridge.play() } },
        onDuck = ::applyDuck,
    )

    /** Volume before ducking, so it can be put back exactly. */
    private var volumeBeforeDuck: Int? = null

    /** Mirrors what the engine last told us. Only touched on the app looper. */
    private var playbackState: @Player.State Int = Player.STATE_IDLE
    private var playWhenReady = false
    private var positionMs = 0L
    private var released = false
    private var repeatMode: @Player.RepeatMode Int = Player.REPEAT_MODE_OFF

    /**
     * Shuffle as a *mode*, held here rather than inferred from the queue.
     *
     * The queue loses its shuffled order whenever it is replaced, and every
     * play action replaces it — so reading the flag off the queue turned shuffle
     * off the moment the user picked a track. Shuffle belongs to the player and
     * is re-applied to each new queue by [reapplyShuffle].
     */
    private var shuffleEnabled = false

    private var playbackParameters = androidx.media3.common.PlaybackParameters.DEFAULT

    /** See [playlistSnapshot]; null means "rebuild on next read". */
    private var cachedPlaylist: List<MediaItemData>? = null

    private val handler = android.os.Handler(looper)

    /**
     * The quality the listener asked for, which decides whether the one below
     * is allowed to have an opinion.
     */
    private val quality =
        (context.applicationContext as dev.lelonio.square.SquareApplication).quality

    /**
     * Follows what the connection can actually carry; see [BandwidthWatch].
     *
     * Only while the setting is automatic. On a fixed choice the listener has
     * said which file they want and a stall is their business, not ours.
     */
    private val bandwidth = BandwidthWatch { kbps ->
        if (quality.quality.value == dev.lelonio.square.data.Quality.Auto) {
            runCatching { dev.lelonio.square.nativecore.NativeBridge.setBitrate(kbps) }
        }
    }

    /**
     * Fires when the music should have reported progress and did not.
     *
     * Position arrives once a second from the decoding loop, so silence there
     * while the player still means to be playing is the buffer having run dry.
     * It is the one measurement of a connection that cannot be argued with.
     */
    private val stallWatch = Runnable {
        // Not while a track is being fetched: the decoding loop reports nothing
        // during a load, so a slow one looks exactly like a buffer that ran dry
        // and was being counted twice — once as a slow load, once as a stall.
        if (loadInFlight) return@Runnable
        if (playWhenReady && playbackState == Player.STATE_READY) bandwidth.stalled()
    }

    /** True between a track being asked for and that same track playing. */
    private var loadInFlight = false

    private fun expectProgress() {
        handler.removeCallbacks(stallWatch)
        if (playWhenReady) handler.postDelayed(stallWatch, STALL_AFTER_MS)
    }

    /**
     * The other device's playback, when the account is playing on one.
     *
     * Mirrored into this player rather than drawn only by the app, because the
     * notification and anything else holding a media controller read this and
     * nothing else: a car head unit and the lock screen do not know the app has
     * a second idea of what is playing. Fed by the service; see
     * PlaybackService.
     */
    private var remote: dev.lelonio.square.data.RemotePlayback? = null

    /**
     * Called with a track the engine is playing that this queue does not hold.
     *
     * Another client can point this device at anything: a track from a playlist
     * that was never opened here, with no transfer and no cluster update to
     * announce it, since the account does not describe a device to itself. The
     * engine simply starts playing something else, and this side went on
     * showing the track it thought was current. Set by the service, which is
     * the half that can resolve a context.
     */
    var onUnknownTrack: ((String) -> Unit)? = null

    /** The last one handed over, so a run of events asks for it once. */
    private var unknownAsked: String? = null

    fun showRemote(playback: dev.lelonio.square.data.RemotePlayback?) {
        if (released) return
        val was = remote
        remote = playback
        if (was?.uri != playback?.uri) cachedPlaylist = null
        invalidateState()
    }

    /**
     * What the transport buttons act on: the other device, or this one.
     *
     * A command sent to the wrong end is worse than a command that fails, since
     * it moves music the listener is not hearing.
     */
    private fun onRemote(what: String, command: (String) -> Unit): Boolean {
        val target = remote ?: return false
        Thread({
            runCatching { command(target.deviceId) }
                .onFailure { android.util.Log.w("SquarePlayer", "$what did not reach it", it) }
        }, "square-remote").start()
        return true
    }

    override fun getState(): State {
        remote?.let { return remoteState(it) }

        val items = queue.items

        // Media3 refuses a playing state with nothing to play, and says so by
        // throwing out of this method: "Empty playlist only allowed in
        // STATE_IDLE or STATE_ENDED", on the main thread, taking the app with
        // it. The two can come apart for a moment whenever the engine is
        // playing something this side has not been told about yet, which is
        // what taking playback back from another device does. Idle is the
        // honest answer while the queue is empty, and the next event, once the
        // queue has arrived, corrects it.
        val reportedState = if (items.isEmpty()) Player.STATE_IDLE else playbackState

        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(reportedState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleEnabled)
            .setPlaybackParameters(playbackParameters)

        if (items.isNotEmpty()) {
            builder.setPlaylist(playlistSnapshot())
                .setCurrentMediaItemIndex(queue.currentIndex.coerceIn(0, items.lastIndex))
        }
        return builder.build()
    }

    /**
     * The playlist as Media3 items, rebuilt only when the queue actually changes.
     *
     * `getState` runs on every `invalidateState`, which includes each position
     * update while playing. Rebuilding a 50-track playlist twice a second
     * allocates several hundred objects per second describing data that did not
     * change — enough GC churn to make the audio stutter. The cache is dropped
     * by [onQueueChanged] at the few points that mutate the queue.
     */
    /**
     * The same shape of state, describing a device in another room.
     *
     * One item rather than the whole queue: the account publishes what is
     * playing, not the list behind it, and inventing the rest would be inventing
     * a queue this app cannot skip through anyway.
     */
    private fun remoteState(playback: dev.lelonio.square.data.RemotePlayback): State {
        val item = cachedPlaylist?.firstOrNull() ?: MediaItemData.Builder(playback.uri)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(playback.uri)
                    .setUri(playback.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(playback.title)
                            .setArtist(playback.artist)
                            .setAlbumTitle(playback.album)
                            .setArtworkUri(
                                playback.coverUrl.takeIf { it.isNotEmpty() }
                                    ?.let(android.net.Uri::parse),
                            )
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build(),
                    )
                    .build(),
            )
            .setDurationUs(playback.durationMs * 1000)
            .build()
            .also { cachedPlaylist = listOf(it) }

        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playback.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(playback.positionMs)
            .setRepeatMode(
                when {
                    playback.repeatTrack -> Player.REPEAT_MODE_ONE
                    playback.repeatContext -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                },
            )
            .setShuffleModeEnabled(playback.shuffle)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    private fun playlistSnapshot(): List<MediaItemData> =
        cachedPlaylist ?: queue.items
            .mapIndexed(::toMediaItemData)
            .also { cachedPlaylist = it }

    /**
     * Puts a saved tempo and pitch back without routing through the output
     * again — the service has already applied them there.
     */
    fun restorePlaybackParameters(speed: Float, pitch: Float) {
        playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)
        invalidateState()
    }

    /**
     * Tempo and pitch changes.
     *
     * Note the engine keeps reporting position in *decoded* time, so at a speed
     * other than 1.0 the reported position and the wall clock drift apart. Left
     * as it is deliberately: the alternative is scaling every position the engine
     * sends, which would put the seek bar and the engine's own idea of the track
     * out of step and break seeking.
     */
    override fun handleSetPlaybackParameters(
        playbackParameters: androidx.media3.common.PlaybackParameters,
    ): ListenableFuture<*> {
        this.playbackParameters = playbackParameters
        onSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        AudioEffects.rememberSpeedAndPitch(playbackParameters.speed, playbackParameters.pitch)
        return Futures.immediateVoidFuture()
    }

    /**
     * A command to the engine, whose failure is not the app's death.
     *
     * These run inside Media3's own call stack — a controller pressing play in
     * the notification ends up here — and Media3 does not catch anything. An
     * engine that has lost its Connect channel answers "channel closed", the
     * JNI boundary turns that into an exception, and the exception came out of
     * the main looper with the process behind it. Observed after a skip:
     *
     *     java.lang.IllegalStateException: play failed: Internal error { channel closed }
     *         at NativeBridge.nativePlay(Native Method)
     *         at LibrespotPlayer.handleSetPlayWhenReady
     *
     * A failed transport command should cost the command.
     */
    /**
     * Sends one command to the engine, and says so loudly when it does not land.
     *
     * At error level rather than warning, because there is no such thing as a
     * harmless transport command that failed: the user pressed something and it
     * did not happen. This was a warning, which is how "pause does nothing and
     * the music keeps playing" stayed invisible in the log for so long.
     *
     * The engine itself now falls back to the player when the Connect layer
     * refuses a command, so reaching this line means both paths failed and
     * playback is genuinely beyond the app's reach.
     */
    private inline fun engine(what: String, command: () -> Unit) {
        runCatching(command)
            .onFailure { android.util.Log.e("SquarePlayer", "$what did not reach the engine", it) }
    }

    /** Whether the engine says its Connect device is gone; false if it cannot answer. */
    /**
     * Whether a step command has anything to reach.
     *
     * Offline counts, and has to: the engine reports no lost device then —
     * there is none to lose — so a skip took the ordinary road, was handed to a
     * Connect device that does not exist, and fell through to the player, whose
     * answer to a step it cannot make is to stop. Skipping simply stopped the
     * music, while picking a song by hand worked, because that path rebuilds
     * the queue instead of stepping through it.
     */
    private val deviceGone: Boolean
        get() = OfflineMode.active.value ||
            runCatching { NativeBridge.spircLost }.getOrDefault(false)

    /** Set while a reconnection is in flight, so a burst of taps starts one. */
    private var reconnecting = false

    /** How long a track dissolves into the next; the listener's own setting. */
    private val crossfade = dev.lelonio.square.data.CrossfadeStore(context)

    /** For the notices this says to the listener; see onKeyRefused. */
    private val appContext = context.applicationContext

    /** Watches the one thing outside this class that changes what it must do. */
    private val watch = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    init {
        // Coming back online, the engine has to be given this queue again.
        //
        // Offline the tracks are loaded one at a time, straight into the
        // player: the Connect device is not there to be told what the queue is.
        // When the network returns that device comes back knowing nothing, and
        // the song that was playing ran to its end with nobody to advance it —
        // so the music stopped and stayed stopped, on a queue of songs that
        // were all sitting on the phone.
        watch.launch {
            dev.lelonio.square.playback.OfflineMode.active
                .drop(1)
                .distinctUntilChanged()
                .collect { offline ->
                    if (offline) return@collect
                    // On returning online, guarantee native session reconnection
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { NativeBridge.reconnect() }
                            .onFailure { android.util.Log.w("SquarePlayer", "auto-reconnect failed: ${it.message}") }
                    }
                    handler.post {
                        if (released || queue.items.isEmpty()) return@post
                        val shouldPlay = playWhenReady || wantPlay
                        android.util.Log.i(
                            "SquarePlayer",
                            "back online: reconnected, restoring playback at ${positionMs}ms (shouldPlay=$shouldPlay)",
                        )
                        if (shouldPlay) {
                            playbackState = Player.STATE_BUFFERING
                            invalidateState()
                        }
                        // From where it is, playing if it was: this is a
                        // handover, not a restart, and the listener should hear
                        // the same second of the same song either side of it.
                        pushQueue(startPlaying = shouldPlay, positionMs = positionMs.toInt())
                    }
                }
        }
    }

    /**
     * The track this side has already moved on from, by uri.
     *
     * Offline nothing else advances the queue — there is no Connect device to
     * own it — so this side does, and it must do it once. Kept as the uri
     * rather than a flag so a track that comes round again in a loop is a
     * different question each time.
     */
    private var advancedFrom: String? = null

    /**
     * Runs `then` on the looper with a live Connect device, rebuilding one first
     * if the engine has lost its.
     *
     * A dropped network costs the session, and a session takes its Connect
     * device with it: librespot hands out the Spirc builder once per session, so
     * there is nothing to revive and the engine builds a new session instead.
     * That is a handshake, which is why it runs off the looper.
     *
     * A failed rebuild still runs `then`. There is no state where refusing to
     * try is better: the command was going to be ignored either way, and going
     * ahead at least reaches the engine's own fallback, which stops the sound.
     */
    private fun withDevice(then: () -> Unit) {
        if (!deviceGone) {
            handler.post { if (!released) then() }
            return
        }
        if (reconnecting) {
            android.util.Log.i("SquarePlayer", "already reconnecting, dropping this one")
            return
        }
        reconnecting = true
        Thread({
            runCatching { NativeBridge.reconnect() }
                .onFailure { android.util.Log.e("SquarePlayer", "reconnect failed", it) }
            handler.post {
                reconnecting = false
                // The new device has never been told what this queue is: its
                // predecessor's state went with the session it belonged to.
                engineQueueStale = true
                if (!released) then()
            }
        }, "square-reconnect").start()
    }

    /** Call after any queue mutation, before [invalidateState]. */
    private fun onQueueChanged() {
        cachedPlaylist = null
    }

    /**
     * Where the engine actually is in the queue; -1 until it says.
     *
     * Kept apart from [PlayQueue.currentIndex], which now moves as soon as the
     * user asks rather than when the engine catches up.
     */
    /**
     * Whether the listener has asked for sound, as opposed to whether there is
     * any yet.
     *
     * [playWhenReady] cannot answer this: it is only ever set from the engine's
     * own events, so that the seek bar never runs ahead of the speaker. But a
     * tap on a track sends "load this" and "play" one after the other, the load
     * is deferred by the fade, and the play lands on an engine that has not been
     * given the new queue yet. With only the reported state to go on the load
     * then went out as paused, and the tapped track sat there selected and
     * silent.
     */
    private var wantPlay = false

    private var engineIndex = -1

    /**
     * Whether the track the queue is on has actually started.
     *
     * A load is not a change of track. The engine fetches what is coming while
     * the current song plays, and each of those arrives here as a load: taken
     * as "the engine has moved on", the screen ran forward a song or two and
     * the one just started vanished from under the listener.
     *
     * A load still counts while nothing is sounding, and that case is the one
     * loads were followed for: a run of tracks the account cannot play is
     * loaded one after another with no play in between, and ignoring those
     * left the app behind the speaker.
     */
    private var sounding = false

    /**
     * True when the list the engine was given no longer matches ours.
     *
     * Spirc holds the track list it was loaded with and advances through it by
     * itself; there is no way to hand it an insertion. So a local edit is
     * invisible to it until the whole queue is loaded again, and until then its
     * "next" is not ours — which is why a skip cannot be delegated while this
     * is set.
     */
    private var engineQueueStale = false

    /** A skip the user has made and the engine has not been told about yet. */
    private var skipPending = false

    /** A skip already handed to the engine, still to be confirmed by an event. */
    private var skipInFlight = false

    /**
     * Moves to a track, without making the user wait for the engine.
     *
     * Every skip used to be a command of its own: ten taps were ten loads, each
     * behind its own fade, and the engine spent seconds working through a queue
     * of decisions the user had already changed their mind about. Worse, the
     * screen only moved when the engine did, so a fast run of skips felt stuck.
     *
     * Now the queue position moves immediately — the screen follows the taps —
     * and the engine is told once the tapping stops. What it is told depends on
     * where it ended up: one step either way is a real skip, which keeps the
     * context playing on the account and on other devices; anything further is
     * a load at the target, which is one request instead of a dozen.
     */
    private fun requestSkipTo(index: Int, positionMs: Long) {
        // A skip on a device in another room is a skip there; the index this
        // side would step through means nothing to it.
        if (remote != null) {
            val forward = index >= queue.currentIndex
            onRemote(if (forward) "next" else "previous") { id ->
                if (forward) dev.lelonio.square.data.RemoteConnect.next(id) else dev.lelonio.square.data.RemoteConnect.previous(id)
            }
            return
        }

        queue.currentIndex = index.coerceIn(0, maxOf(0, queue.items.lastIndex))
        // A skip starts the new track at its beginning. Media3 sends
        // C.TIME_UNSET for "wherever it starts", which as a number is very
        // negative and made the bar draw itself backwards.
        this.positionMs = positionMs.coerceAtLeast(0)
        playbackState = Player.STATE_BUFFERING
        skipPending = true
        invalidateState()

        // The first skip of a burst goes out at once; only the ones on top of
        // it wait. Waiting for the first was a fifth of a second between the
        // tap and anything happening at all — the delay this settle exists to
        // avoid, spent on the one skip that never needed it.
        //
        // Unless the change is dissolved, where nothing is waiting: the song
        // being left goes on playing through it, and the fade starts when the
        // engine takes the load. What the wait buys is one load instead of two
        // for a double tap, and a load is an audio key: Spotify meters those
        // per session and answers a burst by refusing everything for up to
        // half a minute; see audio_key.rs.
        val delay = if (skipInFlight || dissolves()) SKIP_SETTLE_MS else 0L
        skipInFlight = true
        handler.removeCallbacks(settleSkip)
        handler.postDelayed(settleSkip, delay)
        // Held until the engine says it is on the track that was asked for.
        //
        // Cleared as soon as the command went out, the events still coming in
        // about the track being left were taken as the truth, and the screen
        // walked backwards to the song the listener had just skipped. The
        // timeout is the way out of a command that never lands.
        handler.removeCallbacks(skipGaveUp)
        handler.postDelayed(skipGaveUp, SKIP_CONFIRM_MS)
    }

    /** The engine never confirmed the skip; stop ignoring what it says. */
    private val skipGaveUp = Runnable {
        skipPending = false
        skipInFlight = false
        // And stop contradicting it.
        //
        // Clearing the flags was not enough: the queue kept the track that was
        // asked for, so a skip the engine turned into something else left the
        // screen on one song and the speaker on another, and every step after
        // that was measured from the wrong place. A next pressed then asked for
        // the track already playing, so nothing was sent and the old song went
        // on. What the engine last said it was on is where the queue goes back
        // to. A load on its way still counts, since it names the track asked
        // for.
        if (engineIndex >= 0 && engineIndex != queue.currentIndex && engineIndex <= queue.items.lastIndex) {
            android.util.Log.w(
                "SquarePlayer",
                "skip to ${queue.currentIndex} never confirmed, back to the engine's $engineIndex",
            )
            queue.currentIndex = engineIndex
            invalidateState()
        }
    }

    private val settleSkip = Runnable {
        if (released) return@Runnable
        val target = queue.currentIndex
        val from = engineIndex

        when {
            from == target -> {
                if (positionMs > 0) engine("seek") { NativeBridge.seek(positionMs) }
            }

            // Nothing is listening for a skip: a step command would be
            // accepted by a dead device and discarded, and the old track would
            // play on. The queue is pushed instead, which rebuilds the device
            // first and then loads the track that was asked for.
            deviceGone -> {
                pushQueue(startPlaying = playWhenReady, positionMs = 0)
            }

            // A step either way is a skip, and Spirc has to be the one making
            // it: reloading the queue for a skip would restart the context and
            // show up on other devices as a new session rather than a next.
            !engineQueueStale && from >= 0 && target == from + 1 -> changing {
                handler.post {
                    if (released) return@post
                    runCatching { NativeBridge.next() }
                    if (!dissolves()) fadeIn()
                }
            }

            !engineQueueStale && from >= 0 && target == from - 1 -> changing {
                handler.post {
                    if (released) return@post
                    runCatching { NativeBridge.previous() }
                    if (!dissolves()) fadeIn()
                }
            }

            else -> {
                pushQueue(startPlaying = playWhenReady, positionMs = positionMs.toInt())
            }
        }
    }

    /**
     * Restores the shuffled order after the queue has been rebuilt.
     *
     * A new queue arrives unshuffled, and the mode has to be stamped back onto
     * it or turning shuffle on would last exactly until the next tap.
     */
    private fun reapplyShuffle() {
        if (shuffleEnabled && !queue.isShuffled) queue.setShuffled(true)
    }

    /**
     * There is nothing to prepare: the engine buffers on load. Reporting the
     * state the caller expects is enough, and without this override
     * [SimpleBasePlayer] throws for the advertised [Player.COMMAND_PREPARE].
     */
    override fun handlePrepare(): ListenableFuture<*> {
        if (playbackState == Player.STATE_IDLE && queue.items.isNotEmpty()) {
            playbackState = Player.STATE_BUFFERING
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine("stop") { NativeBridge.stop() }
        wantPlay = false
        focus.abandonFocus()
        playbackState = Player.STATE_IDLE
        playWhenReady = false
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // The buttons follow the music. Everything below drives this phone's
        // own engine, which is silent while another device is playing.
        val wanted = playWhenReady
        if (onRemote(if (wanted) "play" else "pause") { id ->
                if (wanted) dev.lelonio.square.data.RemoteConnect.play(id) else dev.lelonio.square.data.RemoteConnect.pause(id)
            }
        ) {
            return Futures.immediateVoidFuture()
        }

        if (playWhenReady) {
            // Refusing focus means something else owns the output — starting
            // anyway would talk over it.
            if (!focus.requestFocus()) return Futures.immediateVoidFuture()
            wantPlay = true
            this.playWhenReady = true
            if (playbackState == Player.STATE_IDLE) {
                playbackState = Player.STATE_READY
            }
            invalidateState()

            // Pressed before there is an engine to press it on. Remembered
            // rather than sent: the queue goes over the moment the access point
            // answers, and it goes over playing.
            if (awaitingEngine) {
                playbackState = Player.STATE_BUFFERING
                onPlaybackActive(true)
                invalidateState()
                return Futures.immediateVoidFuture()
            }

            // If no track is currently loaded but queue exists, load the queue.
            // Otherwise, unpause the existing loaded track immediately via NativeBridge.play().
            if (currentMediaItem == null && queue.items.isNotEmpty()) {
                pushQueue(startPlaying = true, positionMs = positionMs.toInt())
            } else {
                var playFailed = false
                engine("play") {
                    try {
                        NativeBridge.play()
                    } catch (e: Throwable) {
                        playFailed = true
                        throw e
                    }
                }
                if (playFailed && queue.items.isNotEmpty()) {
                    pushQueue(startPlaying = true, positionMs = positionMs.toInt())
                }
            }
            // After the engine, not before: waking the output is a call into
            // the audio server that took most of a tenth of a second here, and
            // it stood between the button and the command that brings the
            // music back.
            onPlaybackActive(true)
        } else {
            wantPlay = false
            this.playWhenReady = false
            onPlaybackActive(false)
            focus.abandonFocus()
            engine("pause") { NativeBridge.pause() }
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * Lower the volume for a transient interruption instead of pausing.
     *
     * librespot's volume is a raw 0..65535 value, so the previous one is kept
     * verbatim and restored rather than recomputed.
     */
    private fun applyDuck(ducked: Boolean) {
        onDuckReverb(if (ducked) DUCK_FACTOR.toFloat() else 1f)
        if (ducked) {
            if (volumeBeforeDuck != null) return
            val current = runCatching { NativeBridge.volume }.getOrNull() ?: return
            volumeBeforeDuck = current
            engine("duck") { NativeBridge.volume = (current * DUCK_FACTOR).toInt() }
        } else {
            volumeBeforeDuck?.let { level -> engine("unduck") { NativeBridge.volume = level } }
            volumeBeforeDuck = null
        }
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        newPositionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        // Seeking, skipping and stopping all belong to whichever device is
        // playing; see onRemote.
        if (remote != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> onRemote("next", dev.lelonio.square.data.RemoteConnect::next)

                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> onRemote("previous", dev.lelonio.square.data.RemoteConnect::previous)

                else -> onRemote("seek") { id -> dev.lelonio.square.data.RemoteConnect.seek(id, newPositionMs) }
            }
            return Futures.immediateVoidFuture()
        }

        queue.items.getOrNull(mediaItemIndex) ?: return Futures.immediateVoidFuture()

        if (mediaItemIndex == queue.currentIndex && !skipPending) {
            engine("seek") { NativeBridge.seek(newPositionMs) }
            positionMs = newPositionMs
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        requestSkipTo(mediaItemIndex, newPositionMs)
        positionMs = newPositionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // Choosing a track here is the listener asking for this phone, and it
        // is the only moment in the app where taking the account's playback is
        // what they meant. Everything else that reaches the engine leaves the
        // other device alone; see engine.rs.
        if (runCatching { NativeBridge.playbackElsewhere }.getOrDefault(false)) {
            android.util.Log.i("SquarePlayer", "taking playback back for this device")
            runCatching { NativeBridge.takeOver() }
                .onFailure { android.util.Log.w("SquarePlayer", "could not take over", it) }
        }

        android.util.Log.i(
            "SquarePlayer",
            "queue set: ${mediaItems.size} items at $startIndex, " +
                "first=${mediaItems.getOrNull(startIndex)?.mediaId}",
        )
        queue.replaceFromMediaItems(mediaItems, startIndex)
        // A new queue: whatever the engine was playing is no longer at any
        // index of this one.
        engineIndex = -1
        sounding = false
        // Shuffle before picking the track to load: with the mode on, the tapped
        // track moves to the front and the rest are reordered behind it.
        reapplyShuffle()
        onQueueChanged()

        val first = queue.items.getOrNull(queue.currentIndex) ?: run {
            // Every item was rejected for lacking a Spotify URI as its media id.
            android.util.Log.w(
                "SquarePlayer",
                "nothing playable in ${mediaItems.size} items; " +
                    "first id=${mediaItems.firstOrNull()?.mediaId}",
            )
            return Futures.immediateVoidFuture()
        }

        // Starts only if playback was already meant to be running. Loading with
        // startPlaying always true would make a restored session play by itself
        // on launch; when the user taps a track, Media3 follows this with
        // play(), which resumes the loaded-but-paused engine.
        // Report the start position immediately. Leaving it at zero makes the
        // seek bar read 0:00 while the engine is already further in, and nothing
        // corrects it until the first position event — which never arrives at
        // all while paused.
        positionMs = startPositionMs
        playbackState = Player.STATE_BUFFERING
        // `first` is only read to check there is something playable; the engine
        // is handed the whole list.
        check(first.uri.isNotEmpty())
        pushQueue(startPlaying = playWhenReady, positionMs = startPositionMs.toInt())
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * The next three exist because [Player.COMMAND_CHANGE_MEDIA_ITEMS] is
     * advertised. They only reorder the queue — the engine plays one track at a
     * time and is untouched unless the current track itself moved out from under
     * it, which [PlayQueue] handles by index.
     */
    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        queue.addFromMediaItems(index, mediaItems)
        reapplyShuffle()
        // The engine is told now, not at the end of the song.
        //
        // Left for later, the track a listener queued was not in the engine's
        // list when the current one ended: the engine moved to its own next
        // track and this side had to skip to the queued one after the fact,
        // which is a cut — the change of track the listener hears when they
        // press skip, in the middle of a crossfade they did not. Handed over as
        // an order, the queued track simply *is* what comes next, and the end
        // of the song dissolves into it like any other.
        pushOrder()
        onQueueChanged()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        queue.remove(fromIndex, toIndex)
        reapplyShuffle()
        // A track taken out of the queue is gone from the engine's list too, and
        // for the same reason as above: otherwise it plays anyway when the
        // current song ends, and this side skips past it afterwards.
        pushOrder()
        onQueueChanged()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        queue.move(fromIndex, toIndex, newIndex)
        engineQueueStale = true
        reapplyShuffle()
        onQueueChanged()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Puts a saved session back: queue, order, position and modes at once.
     *
     * Deliberately not expressed as a sequence of ordinary commands. Setting the
     * shuffle flag before the queue exists is a no-op, and setting it after
     * would draw a fresh random order instead of the one that was saved, so the
     * whole thing has to be applied as a unit.
     */
    /**
     * Empties the player so a rebuilt engine can be restored into it.
     *
     * Not a stop: nothing is sent to the engine, which by this point no longer
     * exists. It clears what the app believes about it, so the queue coming
     * back from storage is loaded rather than skipped as "already playing".
     */
    /**
     * Hands the queue back to an engine that has just been rebuilt.
     *
     * Everything on this side is still true — the list, the order, the track —
     * and only the engine's copy went away with its session, so this pushes it
     * again at the position it had reached rather than starting anything over.
     */
    fun reloadQueue(playing: Boolean, positionMs: Long) {
        if (released || queue.items.isEmpty()) return
        engineQueueStale = true
        engineIndex = -1
        sounding = false
        this.positionMs = positionMs
        wantPlay = playing
        pushQueue(startPlaying = playing, positionMs = positionMs.toInt())
        invalidateState()
    }

    fun clearForRestart() {
        queue.replace(emptyList(), 0)
        wantPlay = false
        engineIndex = -1
        sounding = false
        engineQueueStale = false
        skipPending = false
        skipInFlight = false
        handler.removeCallbacks(settleSkip)
        positionMs = 0
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        onQueueChanged()
        invalidateState()
    }

    /**
     * True while the queue on screen is one the engine has not been given yet.
     *
     * See [restore]: the saved queue is put back before there is an access point
     * to hand it to, so this is the window between the two.
     */
    private var awaitingEngine = false

    /**
     * True between handing the engine a queue and it playing from that queue.
     *
     * A queue takes a moment to land, and in that moment the engine goes on
     * reporting the track it is still on — the one being replaced. That track
     * is not in the new queue, which reads exactly like another device having
     * chosen something this app knows nothing about, and the answer to that is
     * to adopt what the engine is playing. So opening the app on a paused song
     * and tapping a track in a playlist wiped the queue just chosen and put the
     * old one back: the new song played, and the screen showed the old one.
     *
     * Nothing is adopted while this is set. It is only ever the app's own queue
     * arriving, and there is nothing to learn from it.
     */
    private var ownQueuePending = false

    /** A push that never landed must not switch adoption off for good. */
    private val giveUpOnOwnQueue = Runnable { ownQueuePending = false }

    fun restore(
        tracks: List<PlayQueue.Track>,
        shuffleOrder: List<Int>?,
        index: Int,
        positionMs: Long,
        repeatMode: @Player.RepeatMode Int,
        /** The playlist or album the queue came from; see PlayQueue. */
        contextUri: String?,
        contextIsOrdered: Boolean,
        contextLabel: String,
        /**
         * Whether the engine can be handed the queue now.
         *
         * False when the app has only just started. Authenticating takes a
         * handshake and a catalogue call, and none of that is a reason to leave
         * the screen blank in the meantime: what was paused is known from disk,
         * so it is put back straight away and shown as loading, and
         * [handOverToEngine] gives it to the access point once there is one.
         * The alternative, which is what this used to do, was an app that came
         * back looking as though nothing had ever been playing.
         */
        handOverNow: Boolean = true,
    ) {
        if (tracks.isEmpty()) return

        // Put back before the queue is loaded: the load itself hands the
        // context to the engine, and a restored session with no context is one
        // whose listens are filed under nothing and whose player has no source
        // to show.
        queue.restoreContext(contextUri, contextIsOrdered, contextLabel)

        queue.replace(tracks, 0)
        engineIndex = -1
        sounding = false
        shuffleOrder?.let(queue::applyShuffleOrder)
        queue.currentIndex = index.coerceIn(0, queue.items.lastIndex)

        shuffleEnabled = shuffleOrder != null
        this.repeatMode = repeatMode
        this.positionMs = positionMs
        playWhenReady = false
        wantPlay = false
        awaitingEngine = !handOverNow
        // Loading, not ready: there is a track and a position to show, and
        // nothing that could answer a tap on play yet. Buffering is what the
        // mini player already draws a spinner for.
        playbackState = if (handOverNow) Player.STATE_READY else Player.STATE_BUFFERING
        onQueueChanged()

        // Paused: an app that starts playing by itself when opened is worse
        // than one that forgets where it was.
        //
        // The load also takes over the Connect device, which is why it is not
        // done here at all when there is nothing to restore — activating on
        // launch would pull playback away from whatever the account is actually
        // playing on.
        if (queue.items.isNotEmpty() && handOverNow) {
            pushQueue(startPlaying = false, positionMs = positionMs.toInt())
        }
        invalidateState()
    }

    /**
     * Gives the engine the queue that was put back before it existed.
     *
     * Carries [wantPlay] with it, so a listener who pressed play while the app
     * was still connecting gets the track they asked for rather than a button
     * that did nothing.
     */
    fun handOverToEngine() {
        if (!awaitingEngine) return
        awaitingEngine = false
        android.util.Log.i(
            "SquarePlayer",
            "handing over ${queue.items.size} tracks at ${queue.currentIndex}, play=$wantPlay",
        )
        if (queue.items.isEmpty()) {
            playbackState = Player.STATE_IDLE
            invalidateState()
            return
        }
        playbackState = if (wantPlay) Player.STATE_BUFFERING else Player.STATE_READY
        pushQueue(startPlaying = wantPlay, positionMs = positionMs.toInt())
        invalidateState()
    }

    /**
     * Takes on a queue the engine is already playing.
     *
     * The other half of Connect, and the one this app was missing. Another
     * client can hand playback to this phone from its own device list: Spotify
     * sends the engine a transfer, the engine starts playing, and nothing here
     * is asked or told. What was left was a track playing with an empty queue,
     * so there was nothing after it and nothing on screen.
     *
     * Unlike [restore] this loads nothing. The engine already has the queue and
     * the position; the point is only that this side stops disagreeing.
     */
    fun adopt(
        tracks: List<PlayQueue.Track>,
        index: Int,
        positionMs: Long,
        contextUri: String?,
        contextLabel: String,
        playing: Boolean,
    ) {
        if (tracks.isEmpty()) return
        // The second line of the same defence as [ownQueuePending]: reading a
        // context takes seconds, and a listener who has chosen something in
        // those seconds has said what they want more recently than the engine
        // has.
        if (ownQueuePending) {
            android.util.Log.i("SquarePlayer", "not adopting: a queue of ours is on its way")
            return
        }
        android.util.Log.i("SquarePlayer", "adopting ${tracks.size} tracks at $index")

        queue.restoreContext(contextUri, contextUri != null, contextLabel)
        queue.replace(tracks, 0)
        queue.currentIndex = index.coerceIn(0, queue.items.lastIndex)
        // The engine is playing this queue; it is this side that has just
        // learned of it.
        engineIndex = queue.currentIndex
        engineQueueStale = false

        this.positionMs = positionMs
        playWhenReady = playing
        wantPlay = playing
        playbackState = Player.STATE_READY
        onQueueChanged()
        invalidateState()
    }

    /**
     * Reorders the queue rather than only recording a flag; see [PlayQueue].
     */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffleEnabled = shuffleModeEnabled
        queue.setShuffled(shuffleModeEnabled)
        onQueueChanged()
        // Deliberately not passed on to the Connect device, and its own shuffle
        // is held off instead.
        //
        // Both sides can shuffle, and both doing it is one shuffle too many: the
        // queue on screen is this list in this order, and the device was being
        // handed that list and then reordering it again for itself. A track put
        // up next landed somewhere else entirely, the engine played whatever its
        // own permutation said, and what came back looked like the queue being
        // thrown away and rebuilt.
        //
        // The order on screen is the one the user arranged, so it wins. The cost
        // is that the account shows this device as not shuffling.
        runCatching { NativeBridge.setShuffle(false) }

        // And the engine is given the new order at once.
        //
        // Reordering only this side is what made shuffling break playback: the
        // engine went on playing the list it was handed, the screen followed
        // the tracks the engine announced, and the two orders no longer agreed
        // on what came next. A skip then asked for the track after the one on
        // screen while the engine stepped through its own list, so tracks
        // appeared to be missed — and it survived shuffle being turned off
        // again, because turning it off is another reorder this side only.
        //
        // Handed over as an order rather than as a load. A load would restart
        // the decoder, and a reordering of what comes later has no business
        // interrupting the song that is playing.
        pushOrder()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Hands the engine the running order, without touching what is playing.
     *
     * A load would restart the decoder; a change to what comes *after* the
     * current track has no business interrupting it. When it cannot be sent the
     * queue is marked stale, and the next skip rebuilds it wholesale — better a
     * load then than a wrong order now.
     */
    private fun pushOrder() {
        if (queue.items.isEmpty()) return
        engineIndex = queue.currentIndex
        runCatching {
            NativeBridge.setQueueOrder(queue.items.map { it.uri }, queue.currentIndex)
        }.onFailure {
            android.util.Log.w("SquarePlayer", "could not reorder: ${it.message}")
            engineQueueStale = true
        }
    }

    override fun handleSetRepeatMode(repeatMode: @Player.RepeatMode Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        engine("repeat") {
            NativeBridge.setRepeat(
                repeatContext = repeatMode == Player.REPEAT_MODE_ALL,
                repeatTrack = repeatMode == Player.REPEAT_MODE_ONE,
            )
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        watch.cancel()
        handler.removeCallbacks(settleSkip)
        focus.release()
        engine("shutdown") { NativeBridge.shutdown() }
        return Futures.immediateVoidFuture()
    }

    // --- NativeEvents: arrives on a tokio worker thread ---

    override fun onEvent(type: String, uri: String, positionMs: Long) {
        // Downloads travel on this channel because the engine has one listener
        // and one JVM attachment, not because they have anything to do with
        // what is playing. Taken off here rather than posted to the player's
        // handler, where a progress tick several times a second would queue
        // behind whatever the player is in the middle of.
        if (dev.lelonio.square.download.DownloadEvents.accept(type, uri, positionMs)) return
        if (type == "key_refused") {
            handler.post { onKeyRefused(positionMs) }
            return
        }
        handler.post { applyEvent(type, uri, positionMs) }
    }

    /** Whether the listener has been told Spotify is refusing songs. */
    private var refusalShown = false

    /**
     * Where it is remembered that Spotify refuses this account every song,
     * across restarts: a fresh start would otherwise tell them to try again in
     * a few minutes all over again. Forgotten when a song plays.
     */
    private val keyNotes by lazy {
        appContext.getSharedPreferences("square_keys", android.content.Context.MODE_PRIVATE)
    }

    /**
     * Spotify refusing the key a song needs to play, or giving them again: 0
     * over, 1 refused, 2 refused to this account whatever is tried; see
     * audio_key.rs.
     *
     * Said in words for the listener rather than the reason: otherwise the
     * song sits at the start with no time on it, and what that looks like is
     * the app broken. Once for a run of refusals, and once more if the run
     * turns out to be the account's, since the advice is not the same.
     */
    private fun onKeyRefused(what: Long) {
        if (what == 0L) {
            refusalShown = false
            keyNotes.edit().remove(ACCOUNT_REFUSED).apply()
            return
        }
        val account = what == 2L || keyNotes.getBoolean(ACCOUNT_REFUSED, false)
        if (what == 2L) {
            keyNotes.edit().putBoolean(ACCOUNT_REFUSED, true).apply()
        } else if (refusalShown) {
            return
        }
        refusalShown = true
        android.widget.Toast.makeText(
            appContext,
            appContext.getString(
                if (account) {
                    dev.lelonio.square.R.string.playback_refused_account
                } else {
                    dev.lelonio.square.R.string.playback_refused
                },
            ),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Jumps to the next track if it is one the engine cannot know about.
     *
     * Called as the current track ends, not when the track was queued: a reload
     * mid-playback restarts what is playing, while here there is a moment of
     * silence to do it in anyway.
     */
    private fun takeOverForQueued(): Boolean {
        val next = queue.currentIndex + 1
        if (!engineQueueStale || next > queue.items.lastIndex) return false
        if (!queue.items[next].queued) return false
        requestSkipTo(next, 0L)
        return true
    }

    /**
     * Moves to the next track, offline, where nothing else will.
     *
     * With a connection the Connect device owns the queue: it loads the next
     * track while the current one is still sounding, and that overlap is what
     * the crossfade is made of. Offline there is no device, so the app has to
     * be the one that looks ahead — otherwise a track ends, the player stops,
     * and the next one is loaded into silence. That is why offline had no
     * dissolve at all, and why the queue stopped at the end of every song
     * unless somebody pressed skip.
     *
     * @param early true when this is the run-up to a crossfade rather than the
     *   end of the track, which is the only difference between a dissolve and a
     *   plain advance.
     */
    private fun advanceOffline(early: Boolean) {
        val from = queue.currentIndex
        val current = queue.items.getOrNull(from) ?: return
        if (advancedFrom == current.uri) return
        val next = from + 1
        // The end of the queue is the end. Repeat is the engine's own setting
        // and it is not reachable from here; a queue that restarted itself
        // offline would be doing something the listener did not ask for.
        if (next > queue.items.lastIndex) return

        advancedFrom = current.uri
        android.util.Log.i(
            "SquarePlayer",
            "offline: ${if (early) "dissolving into" else "advancing to"} track $next",
        )
        requestSkipTo(next, 0L)
    }

    private fun applyEvent(type: String, uri: String, eventPositionMs: Long) {
        if (released) return

        // One line per track change, and nothing for the events that arrive
        // several times a second. What a skip that nobody asked for looks like
        // afterwards is a sequence of these, and without them the only record
        // of it is the engine's own log — which says what the engine did, not
        // what this side made of it.
        if (type != "position" && type != "progress") {
            android.util.Log.i(
                "SquarePlayer",
                "engine $type $uri at ${eventPositionMs}ms " +
                    "(queue ${queue.currentIndex}/${queue.items.size}, engine $engineIndex, " +
                    "sounding=$sounding, skipPending=$skipPending, own=$ownQueuePending)",
            )
        }

        // A song playing is the account being given keys after all.
        if (type == "playing" && keyNotes.contains(ACCOUNT_REFUSED)) {
            keyNotes.edit().remove(ACCOUNT_REFUSED).apply()
        }

        // Nothing is coming out of the speaker any more, so the next load is a
        // real move rather than a head start on what is coming.
        if (type == "stopped" || type == "end_of_track" || type == "unavailable") {
            sounding = false
        }

        // The engine decides what plays next now, so the current index is
        // followed rather than set: an advance, a remote skip from another
        // device and a local one all arrive here the same way.
        if (uri.isNotEmpty()) {
            val index = queue.nearestIndexOf(uri, engineIndex.takeIf { it >= 0 } ?: queue.currentIndex)
            if (
                index < 0 && !ownQueuePending &&
                (type == "playing" || type == "loading") && unknownAsked != uri
            ) {
                // Not ours: somebody else chose it. Asked for once per track,
                // and only on a track that is actually starting, so a position
                // report on a queue mid-rebuild does not set this going.
                android.util.Log.i(
                    "SquarePlayer",
                    "engine is $type $uri, which is not in this queue of ${queue.items.size}",
                )
                unknownAsked = uri
                onUnknownTrack?.invoke(uri)
            }
            if (index >= 0) {
                unknownAsked = null
                ownQueuePending = false
                handler.removeCallbacks(giveUpOnOwnQueue)
                engineIndex = index
                if (index == queue.currentIndex) {
                    // The engine is where it was asked to be.
                    skipPending = false
                    handler.removeCallbacks(skipGaveUp)
                    skipInFlight = false
                    if (type == "playing") sounding = true
                    if (type == "playing" || type == "loading") positionMs = 0
                } else if (!skipPending && type == "playing") {
                    // The engine is the truth about what is coming out of the
                    // speaker. While a skip is on its way its events are about
                    // the track being left, and following them dragged the
                    // screen backwards — but once nothing is outstanding, the
                    // screen was simply wrong to disagree.
                    //
                    // Only what is playing. A load is not a track change:
                    // the engine fetches what is coming next while the current
                    // song plays, and following those walked the screen one or
                    // two songs ahead of the speaker. A run of tracks the
                    // account cannot play still lands here, because each of
                    // those ends in the engine playing something and that is
                    // the event this follows.
                    queue.currentIndex = index
                    skipInFlight = false
                    sounding = type == "playing"
                    positionMs = 0
                }
            }
        }

        when (type) {
            "loading" -> {
                playbackState = Player.STATE_BUFFERING
                if (uri.isNotEmpty()) bandwidth.loading(uri)
                loadInFlight = true
                handler.removeCallbacks(stallWatch)
            }
            "playing" -> {
                // A new track to watch, and one this side has not moved on
                // from yet.
                if (uri != advancedFrom) advancedFrom = null
                playbackState = Player.STATE_READY
                playWhenReady = true
                if (uri.isNotEmpty()) bandwidth.playing(uri)
                loadInFlight = false
                expectProgress()
                if (!skipInFlight) positionMs = eventPositionMs
                // Here as well as in handleSetPlayWhenReady: a pause can arrive
                // from the notification, from a headset button or from another
                // Connect device, and only this path sees all of them.
                onPlaybackActive(true)
            }
            "paused" -> {
                playbackState = Player.STATE_READY
                playWhenReady = false
                // A pause is not a stall.
                handler.removeCallbacks(stallWatch)
                if (!skipInFlight) positionMs = eventPositionMs
                onPlaybackActive(false)
            }
            "position" -> {
                // Progress arrived, so the stream is keeping up: the watch is
                // wound again rather than left to fire.
                expectProgress()
                // The old track goes on reporting until the new one starts.
                // Taking those would run the bar forward on a song that is no
                // longer the one on screen.
                if (skipPending || skipInFlight) return
                positionMs = eventPositionMs

                // Offline, the run-up to the next track is this side's job.
                //
                // Started exactly one crossfade before the end, so the two
                // tracks overlap by the length the listener asked for. With the
                // setting at zero there is nothing to run up to, and the track
                // is left to end on its own.
                if (dev.lelonio.square.playback.OfflineMode.active.value) {
                    val length = queue.items.getOrNull(queue.currentIndex)?.durationMs ?: 0L
                    val fade = crossfade.durationMs().toLong()
                    if (fade > 0 && length > 0 && length - eventPositionMs in 1..fade) {
                        advanceOffline(early = true)
                    }
                }
            }
            "stopped" -> {
                playbackState = Player.STATE_IDLE
                playWhenReady = false
                onPlaybackActive(false)
            }
            // Both used to advance the queue from here. The engine does it now
            // — it owns the queue — so acting on them as well would skip two
            // tracks for every one that ended.
            //
            // Except for a track put here by "add to queue": the engine's list
            // does not contain it, so left alone it would play the playlist's
            // own next one and the queued track would never be heard. Taking
            // over reloads the queue, which is the only way to tell Spirc about
            // an insertion.
            // Something changed on another of the account's devices. Nothing
            // here plays, so nothing here has to react: the state is read and
            // published, and whoever is showing it redraws.
            "cluster" -> {
                dev.lelonio.square.data.RemoteConnect.refresh()
                // And again shortly after.
                //
                // A handover is two facts arriving separately: the account says
                // which device is active, and the engine works out whether that
                // is this one. They settle a moment apart, and whichever is
                // read first leaves the screen describing the wrong device
                // until something else happens to change it, which can be
                // never. The second look costs two reads of memory.
                handler.postDelayed({ dev.lelonio.square.data.RemoteConnect.refresh() }, 700)
                handler.postDelayed({ dev.lelonio.square.data.RemoteConnect.refresh() }, 2_000)
                return
            }

            "end_of_track" -> {
                if (takeOverForQueued()) return
                // Offline the engine has no queue to advance, so a track that
                // ran to its end without a crossfade — because the setting is
                // off, or because it was shorter than one — stops here unless
                // this side moves on.
                //
                // Except when this is the end of the track already being
                // dissolved out of. That event belongs to the song being left,
                // and it arrives milliseconds after the crossfade has started —
                // by which point the queue has moved on, so advancing again
                // stepped over the track that was just beginning. One song in
                // two was skipped without ever being heard, which is what "it
                // sometimes jumps straight to the next one" was.
                if (uri == advancedFrom) return
                if (dev.lelonio.square.playback.OfflineMode.active.value) {
                    advanceOffline(early = false)
                }
                return
            }

            // A track the engine could not load, after it has already tried
            // again a few times. Only ever acted on for the track that is
            // actually playing: the same event is sent when the *next* track
            // fails to preload, and taking that as "move on" skipped the song
            // the listener was in the middle of because a later one was slow to
            // arrive.
            "unavailable" -> {
                val current = queue.items.getOrNull(queue.currentIndex)?.uri
                if (uri.isEmpty() || uri == current) takeOverForQueued()
                return
            }
            else -> return
        }
        // Whether the music is here or somewhere else can change without any
        // word from the account: taking playback back starts this player, and
        // the cluster may say nothing for a minute afterwards. The screen was
        // deciding with an answer from before the handover, which is how a
        // pause pressed here went out to a laptop.
        if (type == "playing" || type == "paused" || type == "stopped") {
            dev.lelonio.square.data.RemoteConnect.refresh()
        }

        invalidateState()
    }

    /**
     * @param index part of the uid because Media3 requires uids to be unique
     *   across the playlist, and a playlist may legitimately contain the same
     *   track more than once — using the URI alone crashes with
     *   "Duplicate MediaItemData UID in playlist".
     */
    private fun toMediaItemData(index: Int, track: PlayQueue.Track) =
        MediaItemData.Builder("$index ${track.uri}")
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(track.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album.takeIf { it.isNotEmpty() })
                            .setArtworkUri(track.artworkUri)
                            // Put back, because this item is rebuilt rather than
                            // passed through: whatever is not restored here is
                            // lost to everything reading the player, which is
                            // how the artist's name stopped being a link the
                            // moment the queue was rebuilt.
                            .setExtras(
                                android.os.Bundle().apply {
                                    queue.contextLabel.takeIf { it.isNotEmpty() }?.let {
                                        putString(EXTRA_CONTEXT_LABEL, it)
                                    }
                                    queue.contextUri?.let {
                                        putString(dev.lelonio.square.ui.EXTRA_CONTEXT_URI, it)
                                    }
                                    track.artistUri?.let {
                                        putString(dev.lelonio.square.ui.EXTRA_ARTIST_URI, it)
                                    }
                                    track.albumUri?.let {
                                        putString(dev.lelonio.square.ui.EXTRA_ALBUM_URI, it)
                                    }
                                    if (track.explicit) {
                                        putBoolean(dev.lelonio.square.ui.EXTRA_EXPLICIT, true)
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
                                },
                            )
                            .build(),
                    )
                    .build(),
            )
            .setDurationUs(track.durationMs * 1_000)
            .setIsSeekable(true)
            .setIsDynamic(false)
            .build()

    private companion object {
        /**
         * How long the music may go without reporting progress before the
         * buffer is taken to have run dry.
         *
         * Progress arrives once a second, so three is a gap and not a jitter.
         */
        const val STALL_AFTER_MS = 3_500L

        /**
         * How long skips are gathered for before the engine is told.
         *
         * Long enough that a run of taps becomes one decision, short enough
         * that a single skip does not feel delayed.
         */
        const val SKIP_SETTLE_MS = 220L

        /** Set while Spotify is refusing this account every song; see onKeyRefused. */
        const val ACCOUNT_REFUSED = "account_refused"

        /**
         * How long a skip is given to land before its events count again.
         *
         * Long enough for a slow load — the network is the reason this exists —
         * and short enough that a command lost on the way does not leave the
         * screen ignoring the engine for ever.
         */
        const val SKIP_CONFIRM_MS = 8_000L

        /**
         * How long a queue is given to reach the engine; see [ownQueuePending].
         *
         * Measured at about seven hundred milliseconds from the tap to the
         * first event about the new track, on a cold session that was still
         * connecting. This is that with room to spare, and short enough that a
         * queue which never arrived leaves adoption working again.
         */
        const val OWN_QUEUE_MS = 6_000L


        /** Ducked volume, as a fraction of the current one. */
        const val DUCK_FACTOR = 0.3

        /** Everything the engine and this queue can actually carry out. */
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SET_MEDIA_ITEM,
                // Required for setMediaItems(list, index, position). Without it
                // SimpleBasePlayer drops the call silently, so the queue is
                // never handed over and the following play() reaches a stopped
                // engine: "Player::play called from invalid state: Stopped".
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                // Speed and pitch are done by the platform's time stretcher in
                // AudioOutput, not by the engine, so this is genuinely supported.
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
