package dev.lelonio.square.playback

import android.media.audiofx.EnvironmentalReverb
import androidx.media3.common.AuxEffectInfo
import androidx.media3.exoplayer.ExoPlayer

/**
 * The same room, for the players that are not the Spotify engine.
 *
 * The reverb on the Spotify path is not a filter this app wrote: it is
 * Android's own [EnvironmentalReverb], attached to the output as an auxiliary
 * effect with a send level. ExoPlayer offers the same attachment through
 * [ExoPlayer.setAuxEffectInfo], so a file on the phone can be put in exactly the
 * same room rather than in a second one that sounds nearly like it.
 *
 * The tuning is shared with [AudioOutput] on purpose; see [ReverbTuning]. Two
 * copies of these numbers would drift apart the first time either was touched.
 */
class PlayerReverb {

    private var effect: EnvironmentalReverb? = null

    /**
     * Puts [amount] of room on [player], or takes it away at zero.
     *
     * Safe to call for a player that has just been built and for one that is
     * about to be released: the effect follows the player rather than the
     * track, so nothing has to be timed against playback starting.
     */
    fun apply(player: ExoPlayer, amount: Float) {
        val level = amount.coerceIn(0f, 1f)
        if (level <= 0f) {
            detach(player)
            return
        }

        val existing = effect
        val reverb = existing ?: runCatching {
            // Priority 0 and the output mix, as on the other path.
            EnvironmentalReverb(0, 0)
        }
            .onFailure { android.util.Log.w(TAG, "reverb unavailable: ${it.message}") }
            .getOrNull()
            ?: return
        effect = reverb

        runCatching {
            ReverbTuning.tune(reverb, level)
            reverb.enabled = true
            player.setAuxEffectInfo(AuxEffectInfo(reverb.id, ReverbTuning.sendLevel(level)))
            if (existing == null) android.util.Log.i(TAG, "reverb on at $level, id ${reverb.id}")
        }.onFailure { android.util.Log.w(TAG, "reverb not applied: ${it.message}") }
    }

    /** Takes the room away and gives the effect back to the system. */
    fun release(player: ExoPlayer?) {
        player?.let(::detach)
        effect?.runCatching {
            enabled = false
            release()
        }
        effect = null
    }

    private fun detach(player: ExoPlayer) {
        runCatching {
            player.setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
        }
        effect?.runCatching {
            enabled = false
            release()
        }
        effect = null
    }

    private companion object {
        const val TAG = "SquareReverb"
    }
}
