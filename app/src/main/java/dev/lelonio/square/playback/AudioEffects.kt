package dev.lelonio.square.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Persistent effect controls shared by UI and playback.
 *
 * This is a control-plane snapshot, not playback authority. Media3 remains the
 * source of truth for speed/pitch, while DSP processors consume immutable
 * configuration snapshots without doing persistence or UI work on the audio thread.
 */
object AudioEffects {
    private var prefs: android.content.SharedPreferences? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val _reverb = MutableStateFlow(0f)
    val reverb: StateFlow<Float> = _reverb.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pitch = MutableStateFlow(1f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _karaoke = MutableStateFlow(0f)
    val karaoke: StateFlow<Float> = _karaoke.asStateFlow()

    private val _dsp = MutableStateFlow(AdvancedDspConfig())
    val dsp: StateFlow<AdvancedDspConfig> = _dsp.asStateFlow()

    fun load(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = store
        _reverb.value = store.getFloat(KEY_REVERB, 0f).coerceIn(0f, 1f)
        _speed.value = store.getFloat(KEY_SPEED, 1f).coerceIn(MIN_RATE, MAX_RATE)
        _pitch.value = store.getFloat(KEY_PITCH, 1f).coerceIn(MIN_RATE, MAX_RATE)
        _karaoke.value = store.getFloat(KEY_KARAOKE, 0f).coerceIn(0f, 1f)
        _dsp.value = store.getString(KEY_DSP, null)?.let { raw ->
            runCatching { json.decodeFromString<AdvancedDspConfig>(raw) }.getOrNull()
        } ?: AdvancedDspConfig()
    }

    fun setReverb(amount: Float) {
        val wanted = amount.coerceIn(0f, 1f)
        if (wanted == _reverb.value) return
        _reverb.value = wanted
        setDsp(_dsp.value.copy(enabled = true, reverb = wanted))
    }

    fun setKaraoke(amount: Float) {
        val wanted = amount.coerceIn(0f, 1f)
        if (wanted == _karaoke.value) return
        _karaoke.value = wanted
        persist { putFloat(KEY_KARAOKE, wanted) }
    }

    fun rememberSpeedAndPitch(speed: Float, pitch: Float) {
        val safeSpeed = speed.coerceIn(MIN_RATE, MAX_RATE)
        val safePitch = pitch.coerceIn(MIN_RATE, MAX_RATE)
        if (safeSpeed == _speed.value && safePitch == _pitch.value) return
        _speed.value = safeSpeed
        _pitch.value = safePitch
        persist {
            putFloat(KEY_SPEED, safeSpeed)
            putFloat(KEY_PITCH, safePitch)
        }
    }

    fun setDsp(config: AdvancedDspConfig) {
        _dsp.value = config
        persist { putString(KEY_DSP, json.encodeToString(config)) }
    }

    fun setGainDb(value: Float) =
        setDsp(_dsp.value.copy(enabled = true, gainDb = value.coerceIn(-18f, 12f)))

    fun setBassBoostDb(value: Float) =
        setDsp(_dsp.value.copy(enabled = true, bassBoostDb = value.coerceIn(0f, 9f)))

    fun setVirtualizer(value: Float) =
        setDsp(_dsp.value.copy(enabled = true, virtualizer = value.coerceIn(0f, 1f)))

    fun setLimiterEnabled(enabled: Boolean) =
        setDsp(_dsp.value.copy(enabled = true, limiterEnabled = enabled))

    /** Only measured/metadata-derived loudness correction is accepted. */
    fun setNormalizationGainDb(value: Float?) =
        setDsp(_dsp.value.copy(enabled = true, normalizationGainDb = value?.coerceIn(-18f, 12f)))

    fun setEqualizer(bands: List<EqualizerBand>) =
        setDsp(_dsp.value.copy(enabled = true, equalizer = bands.take(MAX_BANDS)))

    private fun persist(write: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(write)?.apply()
    }

    private const val FILE_NAME = "spot_audio_effects"
    private const val KEY_REVERB = "reverb"
    private const val KEY_SPEED = "speed"
    private const val KEY_PITCH = "pitch"
    private const val KEY_KARAOKE = "karaoke"
    private const val KEY_DSP = "advanced_dsp"
    private const val MIN_RATE = 0.5f
    private const val MAX_RATE = 2f
    private const val MAX_BANDS = 16
}
