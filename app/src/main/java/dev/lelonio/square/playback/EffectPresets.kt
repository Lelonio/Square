package dev.lelonio.square.playback

import android.content.Context
import dev.lelonio.square.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A persisted, provider-independent playback/DSP configuration preset. */
@Serializable
data class EffectPreset(
    val id: String,
    val name: String,
    val speed: Float,
    val pitch: Float,
    val reverbAmount: Float = 0f,
    val dsp: AdvancedDspConfig = AdvancedDspConfig(),
    val builtIn: Boolean = false,
) {
    fun matches(speed: Float, pitch: Float, reverb: Float): Boolean =
        kotlin.math.abs(this.speed - speed) < TOLERANCE &&
            kotlin.math.abs(this.pitch - pitch) < TOLERANCE &&
            kotlin.math.abs(this.reverbAmount - reverb) < TOLERANCE

    fun matches(speed: Float, pitch: Float, reverb: Float, dsp: AdvancedDspConfig): Boolean =
        matches(speed, pitch, reverb) && this.dsp == dsp

    companion object { private const val TOLERANCE = 0.005f }
}

private fun presetDsp(
    gainDb: Float = 0f,
    bassBoostDb: Float = 0f,
    equalizer: List<EqualizerBand> = emptyList(),
    virtualizer: Float = 0f,
    reverb: Float = 0f,
): AdvancedDspConfig = AdvancedDspConfig(
    enabled = gainDb != 0f || bassBoostDb != 0f || equalizer.isNotEmpty() || virtualizer != 0f || reverb != 0f,
    gainDb = gainDb,
    bassBoostDb = bassBoostDb,
    equalizer = equalizer,
    virtualizer = virtualizer,
    reverb = reverb,
    limiterEnabled = true,
)

/** Actual effect configurations, not UI-only labels. */
val BuiltInPresets = listOf(
    EffectPreset("normal", "Normal", 1f, 1f, 0f, presetDsp(), true),
    EffectPreset("bass_boost", "Bass Boost", 1f, 1f, 0f, presetDsp(bassBoostDb = 4f), true),
    EffectPreset("vocal", "Vocal", 1f, 1f, 0f, presetDsp(equalizer = listOf(
        EqualizerBand(250f, -2f), EqualizerBand(2_500f, 3f), EqualizerBand(6_000f, 2f),
    )), true),
    EffectPreset("rock", "Rock", 1f, 1f, 0f, presetDsp(equalizer = listOf(
        EqualizerBand(80f, 3f), EqualizerBand(400f, -1f), EqualizerBand(2_500f, 2f), EqualizerBand(8_000f, 3f),
    )), true),
    EffectPreset("classical", "Classical", 1f, 1f, 0f, presetDsp(equalizer = listOf(
        EqualizerBand(100f, 2f), EqualizerBand(1_000f, -1f), EqualizerBand(6_000f, 2f),
    )), true),
    EffectPreset("night", "Night", 1f, 1f, 0f, presetDsp(equalizer = listOf(
        EqualizerBand(100f, 2f), EqualizerBand(2_500f, -2f), EqualizerBand(8_000f, -4f),
    ), gainDb = -3f), true),
    EffectPreset("podcast", "Podcast", 1f, 1f, 0f, presetDsp(equalizer = listOf(
        EqualizerBand(100f, -4f), EqualizerBand(1_500f, 3f), EqualizerBand(4_500f, 2f),
    )), true),
    EffectPreset("slowed", "Slowed + Reverb", 0.85f, 0.92f, 0.55f, presetDsp(reverb = 0.55f), true),
    EffectPreset("sped_up", "Sped Up", 1.25f, 1.25f, 0f, presetDsp(), true),
)

/** Stores user presets while keeping built-ins immutable. */
class EffectPresetStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _custom = MutableStateFlow(load())

    val presets: StateFlow<List<EffectPreset>> = _custom.asStateFlow()

    fun save(
        name: String,
        speed: Float,
        pitch: Float,
        reverb: Float,
        dsp: AdvancedDspConfig = AdvancedDspConfig(),
    ): EffectPreset {
        val preset = EffectPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { app.getString(R.string.unnamed) },
            speed = speed.coerceIn(0.5f, 2f),
            pitch = pitch.coerceIn(0.5f, 2f),
            reverbAmount = reverb.coerceIn(0f, 1f),
            dsp = dsp,
        )
        _custom.value = _custom.value + preset
        persist()
        return preset
    }

    fun delete(id: String) {
        _custom.value = _custom.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_PRESETS, json.encodeToString(serializer, _custom.value)).apply()
    }

    private fun load(): List<EffectPreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE_NAME = "square_effect_presets"
        const val KEY_PRESETS = "presets"
        val serializer = ListSerializer(EffectPreset.serializer())
    }
}
