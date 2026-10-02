package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.lelonio.square.data.CrossfadeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dissolves one YouTube song into the next, the way the Spotify engine does.
 *
 * The main player owns the queue and moves to the next song as the dissolve
 * begins. The song it leaves carries on in a second player, the tail, which has
 * been playing the same song held back in the [PcmMixer]; the mixer swaps the
 * two at the very sample, so the handover cannot be heard, and the two songs
 * are then summed into one speaker on an equal-power curve.
 */
@UnstableApi
class YouTubeFadeController(
    private val player: ExoPlayer,
    private val crossfadeStore: CrossfadeStore?,
    private val mixer: PcmMixer,
    private val mainSlot: MixerSlot,
    private val tailSlot: MixerSlot,
    private val createOutgoing: () -> ExoPlayer,
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var transition: Job? = null
    private var outgoing: ExoPlayer? = null
    private var preparedItem: MediaItem? = null
    private var changing = false
    private var internalChange = false

    /** Which transition owns the state; bumped by every new one and every cancel. */
    private var generations = 0

    init { player.addListener(this) }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Buffering the incoming song must not interrupt the outgoing audio.
        syncTailPlayback()
        if (!isPlaying) {
            ticker?.cancel()
            ticker = null
        }
        if (isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    checkEnd()
                    delay(40)
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!internalChange && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
            cancelOverlap()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncTailPlayback()

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = syncTailPlayback()

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        outgoing?.playbackParameters = playbackParameters
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!internalChange) cancelOverlap()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (!internalChange && reason == Player.DISCONTINUITY_REASON_SEEK) cancelOverlap()
    }

    private fun syncTailPlayback() {
        outgoing?.playWhenReady = changing && player.playWhenReady && player.playbackSuppressionReason == 0
    }

    private fun prepareOutgoing(): ExoPlayer? {
        val item = player.currentMediaItem ?: return null
        val tail = outgoing ?: createOutgoing().also { instance ->
            outgoing = instance
            instance.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.w(TAG, "outgoing stream unavailable", error)
                    cancelOverlap()
                }
            })
        }
        if (preparedItem != item) {
            preparedItem = item
            tail.pause()
            tail.repeatMode = Player.REPEAT_MODE_OFF
            tail.playbackParameters = player.playbackParameters
            tail.setMediaItem(item, player.currentPosition)
            tail.prepare()
        }
        return tail
    }

    private fun checkEnd() {
        if (changing || !player.isPlaying) return
        val fadeMs = crossfadeStore?.durationMs() ?: 0
        if (fadeMs <= 0) {
            if (preparedItem != null) cancelOverlap()
            return
        }
        val remaining = player.duration - player.currentPosition
        if (player.duration <= fadeMs || remaining <= 0) return
        val next = if (player.repeatMode == Player.REPEAT_MODE_ONE) player.currentMediaItemIndex
            else player.nextMediaItemIndex
        if (next < 0) return
        // Resolve and buffer the tail ahead of time, without making it audible.
        if (remaining <= fadeMs + 5_000) prepareOutgoing()
        if (remaining <= fadeMs && outgoing?.playbackState == Player.STATE_READY) {
            changeWith(fadeMs) { player.seekToDefaultPosition(next) }
        }
    }

    fun changeWith(fadeMs: Int, change: () -> Unit) {
        if (changing) cancelOverlap()
        if (fadeMs <= 0 || !player.isPlaying) {
            cancelOverlap()
            change()
            return
        }
        val tail = outgoing?.takeIf {
            preparedItem == player.currentMediaItem && it.playbackState == Player.STATE_READY
        }
        // A skip far from the end has no tail buffered, and building one would delay
        // the skip by a whole stream resolution: what the mixer already holds of
        // the song is enough for the listener's short fade.
        if (tail == null || !mainSlot.mixed) {
            if (!crossWith(fadeMs, change)) dip(fadeMs, change)
            return
        }
        changing = true
        val generation = ++generations
        transition = scope.launch {
            try {
                tailSlot.fade = 0f
                tailSlot.held = true
                tail.playbackParameters = player.playbackParameters
                // A little ahead of what is heard, so that by the time the tail has
                // piled up its first frames the main player has reached them.
                tail.seekTo(player.currentPosition + TAIL_LEAD_MS)
                tail.play()
                val handed = withTimeoutOrNull(HANDOVER_WAIT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { mixer.cancelHandOver() }
                        mixer.handOver(mainSlot, tailSlot) { ok ->
                            scope.launch { if (continuation.isActive) continuation.resume(ok) }
                        }
                    }
                } ?: false
                if (!handed) {
                    android.util.Log.w(TAG, "no handover, crossing what is buffered instead")
                    retire(tail)
                    val short = fadeMs.coerceAtMost(MAX_BUFFERED_FADE_MS)
                    if (mixer.detach(mainSlot, short)) {
                        mainSlot.fade = 0f
                        internalChange = true
                        try { change() } finally { internalChange = false }
                        rise(short)
                    } else {
                        dipNow(fadeMs, change)
                    }
                    return@launch
                }
                // The mixer has silenced the main player and opened the tail.
                internalChange = true
                try { change() } finally { internalChange = false }
                android.util.Log.i(TAG, "overlap started: ${tail.currentMediaItem?.mediaId} -> ${player.currentMediaItem?.mediaId}, ${fadeMs}ms")
                var elapsed = 0L
                var previous = player.currentPosition
                while (elapsed < fadeMs) {
                    delay(20)
                    if (!player.playWhenReady || player.playbackSuppressionReason != 0) {
                        tail.pause()
                        previous = player.currentPosition
                        continue
                    }
                    tail.play()
                    val position = player.currentPosition
                    if (player.isPlaying) elapsed += (position - previous).coerceAtLeast(0)
                    previous = position
                    val x = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
                    mainSlot.fade = sin(x * PI.toFloat() / 2)
                    tailSlot.fade = cos(x * PI.toFloat() / 2)
                }
                android.util.Log.i(TAG, "overlap completed")
            } finally {
                // A cancelled job reaches here only after whatever cancelled it has moved
                // on, possibly into a new overlap: that one owns the state now.
                if (generation == generations) {
                    transition = null
                    changing = false
                    retire(tail)
                    setMainFade(1f)
                }
            }
        }
    }

    /**
     * The engine's change of song: the outgoing one fades out of what the mixer
     * holds of it while the player moves on at once, and the incoming one rises
     * on its own clock, from when it is actually heard.
     */
    private fun crossWith(fadeMs: Int, change: () -> Unit): Boolean {
        if (!mainSlot.mixed || !mixer.detach(mainSlot, fadeMs)) return false
        changing = true
        val generation = ++generations
        mainSlot.fade = 0f
        internalChange = true
        try { change() } finally { internalChange = false }
        transition = scope.launch {
            try {
                rise(fadeMs)
            } finally {
                if (generation == generations) {
                    transition = null
                    changing = false
                    setMainFade(1f)
                }
            }
        }
        return true
    }

    /** Brings the main player up over [fadeMs] of its own playback. */
    private suspend fun rise(fadeMs: Int) {
        var elapsed = 0L
        var previous = player.currentPosition
        val deadline = System.currentTimeMillis() + RISE_GIVE_UP_MS
        while (elapsed < fadeMs && System.currentTimeMillis() < deadline) {
            delay(16)
            val position = player.currentPosition
            if (player.isPlaying) elapsed += (position - previous).coerceIn(0, 100)
            previous = position
            mainSlot.fade = sin((elapsed.toFloat() / fadeMs).coerceIn(0f, 1f) * PI.toFloat() / 2)
        }
    }

    /** Out, change, in, on the main player alone: half of [fadeMs] each way. */
    private fun dip(fadeMs: Int, change: () -> Unit) {
        changing = true
        val generation = ++generations
        transition = scope.launch {
            try {
                dipNow(fadeMs, change)
            } finally {
                if (generation == generations) {
                    transition = null
                    changing = false
                    setMainFade(1f)
                }
            }
        }
    }

    private suspend fun dipNow(fadeMs: Int, change: () -> Unit) {
        val half = (fadeMs / 2).coerceIn(MIN_HALF_MS, MAX_HALF_MS)
        ramp(half) { x -> setMainFade(cos(x * PI.toFloat() / 2)) }
        setMainFade(0f)
        internalChange = true
        try { change() } finally { internalChange = false }
        ramp(half) { x -> setMainFade(sin(x * PI.toFloat() / 2)) }
    }

    /** Through the mixer when the main player is in it, on the player itself otherwise. */
    private fun setMainFade(value: Float) {
        if (mainSlot.mixed) {
            mainSlot.fade = value
            if (player.volume != 1f) player.volume = 1f
        } else {
            mainSlot.fade = 1f
            player.volume = value
        }
    }

    private suspend fun ramp(durationMs: Int, step: (Float) -> Unit) {
        var elapsed = 0
        while (elapsed < durationMs) {
            delay(16)
            elapsed += 16
            step((elapsed.toFloat() / durationMs).coerceIn(0f, 1f))
        }
    }

    /**
     * Silences the tail and holds it where it is.
     *
     * Not stopped: releasing its decoder while the main player is changing song
     * aborts inside MediaCodec (`ubsan: sub-overflow` in `updateMediametrics`) on
     * this hardware. The decoder goes when the tail is next given a song, seconds
     * before an end, with the main player steady.
     */
    private fun retire(tail: ExoPlayer) {
        tailSlot.fade = 0f
        tailSlot.held = true
        tail.pause()
        preparedItem = null
    }

    private fun cancelOverlap() {
        generations++
        transition?.cancel()
        transition = null
        changing = false
        mixer.cancelHandOver()
        outgoing?.let(::retire)
        setMainFade(1f)
    }

    fun release() {
        player.removeListener(this)
        scope.cancel()
        outgoing?.release()
        outgoing = null
    }

    /** After the main player is gone too: it writes into the mixer until then. */
    fun releaseMixer() = mixer.release()
}

private const val TAG = "SquareYTFade"

/** Shorter than this and a dip is a click with a delay in front of it. */
private const val MIN_HALF_MS = 120

/** A skip is late by this much at most: it is the listener's, not the song's end. */
private const val MAX_HALF_MS = 400

/** The most of a song the mixer holds ahead, with a margin: see PcmMixer. */
private const val MAX_BUFFERED_FADE_MS = 1_500

/** A song that will not start is not waited on with the volume down. */
private const val RISE_GIVE_UP_MS = 20_000L

/** How far ahead of the heard position the tail starts piling up. */
private const val TAIL_LEAD_MS = 300L

/** Longer than the mixer's own timeout, which is the one meant to fire. */
private const val HANDOVER_WAIT_MS = 4_000L
