package dev.lelonio.square.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Effect settings shared between the player UI and the playback service.
 *
 * A process-wide object rather than something passed through the media session:
 * the service and the UI run in the same process here, and reverb is not part of
 * the Media3 player interface, so there is no session command that carries it.
 * Speed and pitch deliberately do *not* live here — those are real
 * [androidx.media3.common.Player] state and go through `setPlaybackParameters`,
 * so the notification and any connected controller see them too.
 *
 * Persisted, on second thoughts. The first version deliberately reset on every
 * launch, on the reasoning that an app which reopened sounding altered would
 * seem broken. In use that was the wrong call: setting a slowed-and-reverbed
 * mood and losing it on the next launch — or on the next playlist — is the more
 * annoying of the two, and the effects panel makes the current state plain.
 *
 * [load] has to be called once before the values mean anything, and it is called
 * from the application object rather than from the service. Nothing may write
 * before it: a value set while the file has not been read is a value written
 * over what was saved, and the whole point of persisting was not to lose the
 * mood the listener set.
 */
object AudioEffects {

    private var prefs: android.content.SharedPreferences? = null

    private val _reverb = MutableStateFlow(0f)

    /** How much reverb, 0 (dry) to 1 (largest space). */
    val reverb: StateFlow<Float> = _reverb.asStateFlow()

    private val _speed = MutableStateFlow(1f)

    /**
     * Tempo, mirrored here only so it can be saved.
     *
     * The player remains the source of truth for playback: speed and pitch are
     * real [androidx.media3.common.Player] state and reach the engine through
     * `setPlaybackParameters`, so the notification and any connected controller
     * see them. This copy exists to survive a restart, not to drive anything.
     */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pitch = MutableStateFlow(1f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    fun load(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = store
        _reverb.value = store.getFloat(KEY_REVERB, 0f)
        _speed.value = store.getFloat(KEY_SPEED, 1f)
        _pitch.value = store.getFloat(KEY_PITCH, 1f)
        android.util.Log.i(
            "SpotAudio",
            "effects restored: reverb=${_reverb.value} speed=${_speed.value} pitch=${_pitch.value}",
        )
    }

    fun setReverb(amount: Float) {
        val wanted = amount.coerceIn(0f, 1f)
        if (wanted == _reverb.value) return
        _reverb.value = wanted
        val store = prefs ?: return
        // `commit`, not `apply`, and deliberately.
        //
        // These are set by hand a moment before the app is put away, and an
        // asynchronous write does not always survive the process being killed
        // straight afterwards, which is exactly how a setting is "sometimes"
        // lost. One synchronous write per deliberate change is nothing.
        store.edit().putFloat(KEY_REVERB, wanted).commit()
    }

    /**
     * Records what was last sent to the player, so a restart can restore it.
     *
     * Ignored until the file has been read. Media3 announces the playback
     * parameters of a player that has just been built, which are 1.0 and 1.0,
     * and taking that for an instruction wrote the default over a saved speed
     * before anyone had touched anything.
     */
    fun rememberSpeedAndPitch(speed: Float, pitch: Float) {
        val store = prefs ?: return
        if (speed == _speed.value && pitch == _pitch.value) return
        _speed.value = speed
        _pitch.value = pitch
        store.edit()
            .putFloat(KEY_SPEED, speed)
            .putFloat(KEY_PITCH, pitch)
            .commit()
    }

    private const val FILE_NAME = "spot_audio_effects"
    private const val KEY_REVERB = "reverb"
    private const val KEY_SPEED = "speed"
    private const val KEY_PITCH = "pitch"
}
