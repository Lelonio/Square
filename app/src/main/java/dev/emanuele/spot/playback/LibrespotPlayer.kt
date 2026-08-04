package dev.emanuele.spot.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.emanuele.spot.nativecore.NativeBridge
import dev.emanuele.spot.nativecore.NativeEvents

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
) : SimpleBasePlayer(looper), NativeEvents {

    /** Loads a track with the fade around it. */
    private fun loadFaded(uri: String, startPlaying: Boolean, positionMs: Int = 0) {
        fadeOutThen {
            NativeBridge.load(uri, startPlaying, positionMs)
            if (startPlaying) fadeIn()
        }
    }

    /**
     * Built here rather than injected because its callbacks have to reach back
     * into this player, and passing it in would need the two to be constructed
     * in a cycle.
     */
    private val focus = AudioFocusController(
        context,
        onPause = { NativeBridge.pause() },
        onResume = { NativeBridge.play() },
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

    /** Call after any queue mutation, before [invalidateState]. */
    private fun onQueueChanged() {
        cachedPlaylist = null
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
        NativeBridge.stop()
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
            NativeBridge.play()
        } else {
            NativeBridge.pause()
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
            val current = NativeBridge.volume
            volumeBeforeDuck = current
            NativeBridge.volume = (current * DUCK_FACTOR).toInt()
        } else {
            volumeBeforeDuck?.let { NativeBridge.volume = it }
            volumeBeforeDuck = null
        }
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        newPositionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        val target = queue.items.getOrNull(mediaItemIndex)
            ?: return Futures.immediateVoidFuture()

        if (mediaItemIndex != queue.currentIndex) {
            queue.currentIndex = mediaItemIndex
            loadFaded(target.uri, startPlaying = true, positionMs = newPositionMs.toInt())
        } else {
            NativeBridge.seek(newPositionMs)
        }
        positionMs = newPositionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        queue.replaceFromMediaItems(mediaItems, startIndex)
        // Shuffle before picking the track to load: with the mode on, the tapped
        // track moves to the front and the rest are reordered behind it.
        reapplyShuffle()
        onQueueChanged()

        val first = queue.items.getOrNull(queue.currentIndex) ?: run {
            // Every item was rejected for lacking a Spotify URI as its media id.
            android.util.Log.w(
                "SpotPlayer",
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
        NativeBridge.load(
            first.uri,
            startPlaying = playWhenReady,
            positionMs = startPositionMs.toInt(),
        )
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
        onQueueChanged()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        queue.remove(fromIndex, toIndex)
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
    fun restore(
        tracks: List<PlayQueue.Track>,
        shuffleOrder: List<Int>?,
        index: Int,
        positionMs: Long,
        repeatMode: @Player.RepeatMode Int,
    ) {
        if (tracks.isEmpty()) return

        queue.replace(tracks, 0)
        shuffleOrder?.let(queue::applyShuffleOrder)
        queue.currentIndex = index.coerceIn(0, queue.items.lastIndex)

        shuffleEnabled = shuffleOrder != null
        this.repeatMode = repeatMode
        this.positionMs = positionMs
        playWhenReady = false
        playbackState = Player.STATE_READY
        onQueueChanged()

        queue.items.getOrNull(queue.currentIndex)?.let { track ->
            // Paused: an app that starts playing by itself when opened is worse
            // than one that forgets where it was.
            loadFaded(track.uri, startPlaying = false, positionMs = positionMs.toInt())
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
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: @Player.RepeatMode Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        focus.release()
        NativeBridge.shutdown()
        return Futures.immediateVoidFuture()
    }

    // --- NativeEvents: arrives on a tokio worker thread ---

    override fun onEvent(type: String, uri: String, positionMs: Long) {
        handler.post { applyEvent(type, positionMs) }
    }

    private fun applyEvent(type: String, eventPositionMs: Long) {
        if (released) return

        when (type) {
            "loading" -> {
                playbackState = Player.STATE_BUFFERING
            }
            "playing" -> {
                playbackState = Player.STATE_READY
                playWhenReady = true
                positionMs = eventPositionMs
            }
            "paused" -> {
                playbackState = Player.STATE_READY
                playWhenReady = false
                positionMs = eventPositionMs
            }
            "position" -> {
                positionMs = eventPositionMs
            }
            "stopped" -> {
                playbackState = Player.STATE_IDLE
                playWhenReady = false
            }
            "end_of_track" -> {
                advance()
                return
            }
            // The track is region-locked or otherwise unplayable; skipping keeps
            // a bad item in a playlist from stalling the whole queue.
            "unavailable" -> {
                advance()
                return
            }
            else -> return
        }
        invalidateState()
    }

    /**
     * Moves to whatever should play next, honouring the repeat mode.
     *
     * Done here rather than left to Media3 because end-of-track comes from the
     * engine, not from a controller command.
     */
    private fun advance() {
        val nextIndex = when {
            repeatMode == Player.REPEAT_MODE_ONE -> queue.currentIndex
            queue.currentIndex + 1 <= queue.items.lastIndex -> queue.currentIndex + 1
            repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> null
        }

        val next = nextIndex?.let(queue.items::getOrNull)
        if (next == null) {
            playbackState = Player.STATE_ENDED
            playWhenReady = false
        } else {
            queue.currentIndex = nextIndex
            positionMs = 0
            playbackState = Player.STATE_BUFFERING
            loadFaded(next.uri, startPlaying = true)
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
                            .build(),
                    )
                    .build(),
            )
            .setDurationUs(track.durationMs * 1_000)
            .setIsSeekable(true)
            .setIsDynamic(false)
            .build()

    private companion object {
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
