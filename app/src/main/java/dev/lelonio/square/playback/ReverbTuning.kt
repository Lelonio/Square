package dev.lelonio.square.playback

import android.media.audiofx.EnvironmentalReverb

/**
 * What one number on a slider means as a room.
 *
 * Kept in one place because two players use it: the Spotify path attaches the
 * effect to its own AudioTrack ([AudioOutput]), and everything else attaches it
 * through ExoPlayer ([PlayerReverb]). The same setting has to be the same room
 * whichever is playing, and a second copy of these numbers would drift from the
 * first the moment either was adjusted.
 */
object ReverbTuning {

    /**
     * Sets the room up for [amount], 0 to 1.
     *
     * The room grows with it: a longer tail and a louder reflection, from a
     * small live room up to a hall.
     */
    fun tune(effect: EnvironmentalReverb, amount: Float) {
        val level = amount.coerceIn(0f, 1f)
        effect.decayTime = (MIN_DECAY_MS + level * (MAX_DECAY_MS - MIN_DECAY_MS)).toInt()
        effect.reverbLevel =
            (MIN_LEVEL_MB + level * (MAX_LEVEL_MB - MIN_LEVEL_MB)).toInt().toShort()
        // The one that decides whether any of this is audible at all. An
        // EnvironmentalReverb starts with roomLevel at -9000 mB — ninety
        // decibels down, which is silence — and it is a master attenuation
        // sitting in front of everything else.
        effect.roomLevel = (MIN_ROOM_MB + level * (MAX_ROOM_MB - MIN_ROOM_MB)).toInt().toShort()
        // Keeps the tail from being all low end, which is what a room with its
        // highs rolled off sounds like: muddy rather than spacious.
        effect.roomHFLevel = (-400).toShort()
        // Held steady: these shape the character of the room rather than its
        // size, and moving them with the slider makes the low end swell as well
        // as the tail, which reads as the track getting muddier rather than
        // more distant.
        effect.decayHFRatio = 700
        effect.density = 1000
        effect.diffusion = 1000
    }

    /**
     * How much of the signal goes to the tail, for [amount].
     *
     * The dry path is untouched, so this is purely the send. Curved rather than
     * linear: the first part of the range is where the audible change happens,
     * and a straight mapping leaves most of the slider doing almost nothing.
     */
    fun sendLevel(amount: Float): Float {
        val level = amount.coerceIn(0f, 1f)
        return level * level * 0.85f + level * 0.15f
    }

    /** Decay range, in milliseconds: a live room up to a large hall. */
    private const val MIN_DECAY_MS = 500f
    private const val MAX_DECAY_MS = 4500f

    /**
     * Reverb level in millibels. The API allows down to -9000, which is
     * inaudible, so the useful part of the range starts much higher.
     */
    private const val MIN_LEVEL_MB = -2600f
    private const val MAX_LEVEL_MB = -200f

    /**
     * Master room level, in millibels. 0 is unattenuated; the API default of
     * -9000 is inaudible.
     */
    private const val MIN_ROOM_MB = -2000f
    private const val MAX_ROOM_MB = 0f
}
