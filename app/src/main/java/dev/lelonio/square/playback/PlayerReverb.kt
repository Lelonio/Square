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

    /** What the room is set to, so a change of level can re-apply it. */
    private var amount = 0f

    /**
     * Follows the player's own level.
     *
     * ExoPlayer ducks by turning itself down, and the send that feeds this
     * room is not part of that: a song ducked under a voice note would come
     * back as music at a third and reverb at full, which reads as the room
     * suddenly swallowing the track. The send is held down by the same amount.
     */
    private var watched: ExoPlayer? = null
    private val level = object : androidx.media3.common.Player.Listener {
        /**
         * Off on pause and fresh on play, as on the Spotify path (see
         * AudioOutput.setPlaybackActive). Left on through a pause, the room
         * rang on for a moment, was cut off when the system stopped feeding
         * it, and played the rest of that old tail over the next play.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val player = watched ?: return
            if (playWhenReady) {
                if (amount > 0f && effect == null) apply(player, amount)
            } else {
                drop(player)
            }
        }

        override fun onVolumeChanged(volume: Float) {
            val player = watched ?: return
            val room = amount
            if (room <= 0f) return
            runCatching {
                player.setAuxEffectInfo(
                    AuxEffectInfo(
                        effect?.id ?: return,
                        ReverbTuning.sendLevel(room) * volume.coerceIn(0f, 1f),
                    ),
                )
            }
        }
    }

    /**
     * Puts [amount] of room on [player], or takes it away at zero.
     *
     * Safe to call for a player that has just been built and for one that is
     * about to be released: the effect follows the player rather than the
     * track, so nothing has to be timed against playback starting.
     */
    fun apply(player: ExoPlayer, amount: Float) {
        val level = amount.coerceIn(0f, 1f)
        this.amount = level
        if (level <= 0f) {
            detach(player)
            return
        }

        if (watched !== player) {
            watched?.runCatching { removeListener(this@PlayerReverb.level) }
            watched = player
            runCatching { player.addListener(this.level) }
        }

        // Built on play: a paused player gets its room when it starts.
        if (!player.playWhenReady) return

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
            player.setAuxEffectInfo(
                AuxEffectInfo(
                    reverb.id,
                    ReverbTuning.sendLevel(level) * player.volume.coerceIn(0f, 1f),
                ),
            )
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
        if (watched === player) {
            runCatching { player.removeListener(level) }
            watched = null
        }
        drop(player)
    }

    /**
     * Takes the room off [player] and throws it away, with whatever is still
     * ringing inside it; released rather than disabled, see AudioOutput.
     */
    private fun drop(player: ExoPlayer) {
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
