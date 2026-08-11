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
     * Told when playback starts and stops.
     *
     * The output needs this because the reverb sits on the global mix; see
     * AudioOutput.setPlaybackActive.
     */
    private val onPlaybackActive: (Boolean) -> Unit,
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
        val index = queue.currentIndex
        val contextUri = queue.contextUri.orEmpty()
        // Only when the queue is that context in its own order, and not while
        // shuffled: the engine would then play Spotify's order and the list on
        // screen would be someone else's.
        val asContext = queue.contextIsOrdered && !queue.isShuffled

        fadeOutThen {
            // Back on the player's looper before anything is read or called.
            // The fade runs on its own thread, and everything here — the queue,
            // playWhenReady, the engine's own idea of what is loaded — belongs
            // to the looper. Reading it from the fade thread is a race, and a
            // race in the middle of a track change is exactly the rare crash
            // that skipping quickly could produce.
            handler.post {
                if (released) return@post
                // A load is the one command with no way around the Connect
                // device, so when that is gone the track simply never changes.
                // Build a new one first and load on the other side of it.
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
                if (wanted) fadeIn()
            }
        }
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

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleEnabled)
            .setPlaybackParameters(playbackParameters)

        val items = queue.items
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
    private val deviceGone: Boolean
        get() = runCatching { NativeBridge.spircLost }.getOrDefault(false)

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
        val delay = if (skipInFlight) SKIP_SETTLE_MS else 0L
        skipInFlight = true
        handler.removeCallbacks(settleSkip)
        handler.postDelayed(settleSkip, delay)
    }

    private val settleSkip = Runnable {
        if (released) return@Runnable
        skipPending = false
        val target = queue.currentIndex
        val from = engineIndex

        when {
            from == target -> {
                if (positionMs > 0) engine("seek") { NativeBridge.seek(positionMs) }
            }

            // Nothing is listening for a skip, and nothing here can bring the
            // device back: librespot hands out the Spirc builder once per
            // session, so a second one cannot be built on this one. Stopping is
            // what the engine's own fallback does, and saying so here keeps the
            // two branches below from pretending otherwise.
            deviceGone -> engine("next") { NativeBridge.next() }

            // A step either way is a skip, and Spirc has to be the one making
            // it: reloading the queue for a skip would restart the context and
            // show up on other devices as a new session rather than a next.
            !engineQueueStale && from >= 0 && target == from + 1 -> fadeOutThen {
                handler.post {
                    if (released) return@post
                    runCatching { NativeBridge.next() }
                    fadeIn()
                }
            }

            !engineQueueStale && from >= 0 && target == from - 1 -> fadeOutThen {
                handler.post {
                    if (released) return@post
                    runCatching { NativeBridge.previous() }
                    fadeIn()
                }
            }

            else -> pushQueue(startPlaying = playWhenReady, positionMs = positionMs.toInt())
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
        if (playWhenReady) {
            // Refusing focus means something else owns the output — starting
            // anyway would talk over it.
            if (!focus.requestFocus()) return Futures.immediateVoidFuture()
            wantPlay = true
            onPlaybackActive(true)
            engine("play") { NativeBridge.play() }
        } else {
            wantPlay = false
            engine("pause") { NativeBridge.pause() }
            onPlaybackActive(false)
            focus.abandonFocus()
        }
        // Do not update local state here: the engine confirms via onEvent, and
        // reporting "playing" before audio actually starts makes the seek bar
        // run ahead of the sound.
        return Futures.immediateVoidFuture()
    }

    /**
     * Lower the volume for a transient interruption instead of pausing.
     *
     * librespot's volume is a raw 0..65535 value, so the previous one is kept
     * verbatim and restored rather than recomputed.
     */
    private fun applyDuck(ducked: Boolean) {
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
        queue.replaceFromMediaItems(mediaItems, startIndex)
        // A new queue: whatever the engine was playing is no longer at any
        // index of this one.
        engineIndex = -1
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
        engineQueueStale = true
        reapplyShuffle()
        onQueueChanged()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        queue.remove(fromIndex, toIndex)
        engineQueueStale = true
        reapplyShuffle()
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
    fun clearForRestart() {
        queue.replace(emptyList(), 0)
        wantPlay = false
        engineIndex = -1
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
    ) {
        if (tracks.isEmpty()) return

        // Put back before the queue is loaded: the load itself hands the
        // context to the engine, and a restored session with no context is one
        // whose listens are filed under nothing and whose player has no source
        // to show.
        queue.restoreContext(contextUri, contextIsOrdered, contextLabel)

        queue.replace(tracks, 0)
        engineIndex = -1
        shuffleOrder?.let(queue::applyShuffleOrder)
        queue.currentIndex = index.coerceIn(0, queue.items.lastIndex)

        shuffleEnabled = shuffleOrder != null
        this.repeatMode = repeatMode
        this.positionMs = positionMs
        playWhenReady = false
        wantPlay = false
        playbackState = Player.STATE_READY
        onQueueChanged()

        // Paused: an app that starts playing by itself when opened is worse
        // than one that forgets where it was.
        //
        // The load also takes over the Connect device, which is why it is not
        // done here at all when there is nothing to restore — activating on
        // launch would pull playback away from whatever the account is actually
        // playing on.
        if (queue.items.isNotEmpty()) {
            pushQueue(startPlaying = false, positionMs = positionMs.toInt())
        }
        invalidateState()
    }

    /**
     * Reorders the queue rather than only recording a flag; see [PlayQueue].
     */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffleEnabled = shuffleModeEnabled
        queue.setShuffled(shuffleModeEnabled)
        onQueueChanged()
        // Told to the engine as well: shuffle is part of the published Connect
        // state, and a device whose local order disagrees with what the account
        // shows is worse than one that cannot shuffle at all.
        runCatching { NativeBridge.setShuffle(shuffleModeEnabled) }
        invalidateState()
        return Futures.immediateVoidFuture()
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
        handler.removeCallbacks(settleSkip)
        focus.release()
        engine("shutdown") { NativeBridge.shutdown() }
        return Futures.immediateVoidFuture()
    }

    // --- NativeEvents: arrives on a tokio worker thread ---

    override fun onEvent(type: String, uri: String, positionMs: Long) {
        handler.post { applyEvent(type, uri, positionMs) }
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

    private fun applyEvent(type: String, uri: String, eventPositionMs: Long) {
        if (released) return

        // The engine decides what plays next now, so the current index is
        // followed rather than set: an advance, a remote skip from another
        // device and a local one all arrive here the same way.
        if (uri.isNotEmpty()) {
            val index = queue.items.indexOfFirst { it.uri == uri }
            if (index >= 0) {
                engineIndex = index
                if (index == queue.currentIndex) {
                    skipInFlight = false
                    if (type == "playing" || type == "loading") positionMs = 0
                } else if (
                    !skipPending &&
                    (type == "playing" || (!skipInFlight && type == "loading"))
                ) {
                    // The engine is the truth about what is coming out of the
                    // speaker. While a skip is on its way its events are about
                    // the track being left, and following them dragged the
                    // screen backwards — but once nothing is outstanding, the
                    // screen was simply wrong to disagree.
                    //
                    // Loading counts, not only playing: a run of tracks the
                    // account cannot play — region-locked, delisted — is
                    // skipped by the engine one after another, and each of
                    // those is a load with no play. Following only plays left
                    // the app three songs behind what was in the speaker,
                    // which is exactly how the two came apart.
                    queue.currentIndex = index
                    skipInFlight = false
                    positionMs = 0
                }
            }
        }

        when (type) {
            "loading" -> {
                playbackState = Player.STATE_BUFFERING
            }
            "playing" -> {
                playbackState = Player.STATE_READY
                playWhenReady = true
                if (!skipInFlight) positionMs = eventPositionMs
                // Here as well as in handleSetPlayWhenReady: a pause can arrive
                // from the notification, from a headset button or from another
                // Connect device, and only this path sees all of them.
                onPlaybackActive(true)
            }
            "paused" -> {
                playbackState = Player.STATE_READY
                playWhenReady = false
                if (!skipInFlight) positionMs = eventPositionMs
                onPlaybackActive(false)
            }
            "position" -> {
                // The old track goes on reporting until the new one starts.
                // Taking those would run the bar forward on a song that is no
                // longer the one on screen.
                if (skipPending || skipInFlight) return
                positionMs = eventPositionMs
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
            "end_of_track", "unavailable" -> {
                takeOverForQueued()
                return
            }
            else -> return
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
                            .setArtworkUri(track.artworkUri)
                            // Put back, because this item is rebuilt rather
                            // than passed through; see PlayQueue.contextLabel.
                            .setExtras(
                                queue.contextLabel.takeIf { it.isNotEmpty() }?.let {
                                    android.os.Bundle().apply {
                                        putString(EXTRA_CONTEXT_LABEL, it)
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
         * How long skips are gathered for before the engine is told.
         *
         * Long enough that a run of taps becomes one decision, short enough
         * that a single skip does not feel delayed.
         */
        const val SKIP_SETTLE_MS = 220L


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
