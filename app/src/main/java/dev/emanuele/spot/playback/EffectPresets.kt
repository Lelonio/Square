package dev.emanuele.spot.playback

import android.content.Context
import dev.emanuele.spot.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A named speed / pitch / reverb combination.
 *
 * @param id stable across renames and restarts; the name is not unique because
 *   nothing stops the user from saving "test" twice.
 */
@Serializable
data class EffectPreset(
    val id: String,
    val name: String,
    val speed: Float,
    val pitch: Float,
    /**
     * Reverb amount, 0 to 1.
     *
     * Named differently from the enum this replaced, and given a default, so a
     * preset saved by an older build still loads: the unknown key is ignored and
     * the amount falls back to dry rather than the whole stored list failing to
     * parse.
     */
    val reverbAmount: Float = 0f,
    /** Built-ins cannot be deleted, so the UI hides the control for them. */
    val builtIn: Boolean = false,
) {
    /** True when playback currently sounds like this preset. */
    fun matches(speed: Float, pitch: Float, reverb: Float): Boolean =
        kotlin.math.abs(this.speed - speed) < TOLERANCE &&
            kotlin.math.abs(this.pitch - pitch) < TOLERANCE &&
            kotlin.math.abs(this.reverbAmount - reverb) < TOLERANCE

    companion object {
        /**
         * Sliders produce values like 0.8499999; comparing them exactly would
         * leave a preset looking unselected right after it was applied.
         */
        private const val TOLERANCE = 0.005f
    }
}

/**
 * The presets everyone gets.
 *
 * Named the way these edits are actually labelled rather than translated. Both
 * move pitch and tempo together, which is the convention they follow: changing
 * only tempo is a different effect and sounds wrong to anyone expecting these.
 */
val BuiltInPresets = listOf(
    EffectPreset(
        id = "original",
        name = "Originale",
        speed = 1f,
        pitch = 1f,
        reverbAmount = 0f,
        builtIn = true,
    ),
    EffectPreset(
        id = "slowed",
        name = "Slowed + Reverb",
        // A slowed edit is usually somewhere near 0.85; further down and vocals
        // start to sound obviously dragged rather than dreamy.
        speed = 0.85f,
        pitch = 0.92f,
        reverbAmount = 0.55f,
        builtIn = true,
    ),
    EffectPreset(
        id = "sped_up",
        name = "Sped Up",
        // 1.25 is where these edits sit: fast enough to be the point, short of
        // the chipmunk register that starts around 1.4.
        speed = 1.25f,
        pitch = 1.25f,
        reverbAmount = 0f,
        builtIn = true,
    ),
)

/**
 * Stores the user's own presets.
 *
 * Only the saved *library* is persisted, not which one is playing: an app that
 * reopened already pitched down would look broken, and the cause would not be
 * obvious. Applying a preset stays an explicit action.
 */
class EffectPresetStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _custom = MutableStateFlow(load())

    /** Built-ins first, then the user's own in the order they were saved. */
    val presets: StateFlow<List<EffectPreset>> = _custom.asStateFlow()

    fun save(name: String, speed: Float, pitch: Float, reverb: Float): EffectPreset {
        val preset = EffectPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { app.getString(R.string.unnamed) },
            speed = speed,
            pitch = pitch,
            reverbAmount = reverb,
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
        prefs.edit()
            .putString(KEY_PRESETS, json.encodeToString(serializer, _custom.value))
            .apply()
    }

    private fun load(): List<EffectPreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        // A stored list that no longer parses is dropped rather than crashing
        // the player: these are conveniences, not data worth failing over.
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE_NAME = "spot_effect_presets"
        const val KEY_PRESETS = "presets"
        val serializer = ListSerializer(EffectPreset.serializer())
    }
}
