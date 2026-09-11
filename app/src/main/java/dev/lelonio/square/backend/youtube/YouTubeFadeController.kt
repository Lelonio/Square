package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.lelonio.square.data.CrossfadeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Manages smooth volume transitions (fade out / fade in) between tracks on YouTube Music.
 *
 * ExoPlayer plays gapless sequentially; this controller applies equal-power volume
 * shaping as a track nears its end, and smoothly ramps the next track up upon transition.
 */
class YouTubeFadeController(
    private val player: ExoPlayer,
    private val crossfadeStore: CrossfadeStore?,
) : Player.Listener {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var tickerJob: Job? = null
    private var rampJob: Job? = null
    private var isFadingOut = false

    init {
        player.addListener(this)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && player.playWhenReady) {
            startTicker()
        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            stopTicker()
            resetVolume()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && player.playbackState == Player.STATE_READY) {
            startTicker()
        } else {
            stopTicker()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        isFadingOut = false
        val fadeMs = crossfadeStore?.durationMs() ?: 0
        if (fadeMs > 0) {
            rampUp(minOf(fadeMs / 2, 1500))
        } else {
            resetVolume()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            isFadingOut = false
            resetVolume()
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                checkFade()
                delay(80)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun checkFade() {
        val fadeMs = crossfadeStore?.durationMs() ?: 0
        if (fadeMs <= 0) {
            if (player.volume != 1f) player.volume = 1f
            return
        }

        if (rampJob?.isActive == true) return

        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0 || position <= 0 || duration <= fadeMs) return

        val remaining = duration - position
        if (remaining in 1..fadeMs) {
            isFadingOut = true
            val x = remaining.toFloat() / fadeMs.toFloat()
            // Equal-power curve: sin(x * pi / 2) so remaining=fadeMs -> 1.0, remaining=0 -> 0.0
            val volume = sin(x * (PI.toFloat() / 2f)).coerceIn(0f, 1f)
            player.volume = volume
        } else if (!isFadingOut && player.volume < 1f) {
            player.volume = 1f
        }
    }

    private fun rampUp(durationMs: Int) {
        rampJob?.cancel()
        if (durationMs <= 0) {
            resetVolume()
            return
        }
        player.volume = 0f
        rampJob = scope.launch {
            val steps = 20
            val stepDelay = (durationMs / steps).toLong().coerceAtLeast(10L)
            for (i in 1..steps) {
                if (!isActive) break
                val x = i.toFloat() / steps.toFloat()
                // Equal-power fade in
                val volume = sin(x * (PI.toFloat() / 2f)).coerceIn(0f, 1f)
                player.volume = volume
                delay(stepDelay)
            }
            player.volume = 1f
        }
    }

    private fun resetVolume() {
        rampJob?.cancel()
        player.volume = 1f
    }
}
