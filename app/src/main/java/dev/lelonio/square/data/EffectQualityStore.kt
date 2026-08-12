package dev.lelonio.square.data

import android.content.Context
import androidx.annotation.StringRes
import dev.lelonio.square.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How speed and pitch are worked out.
 *
 * The two ways of doing it are genuinely different bargains rather than more or
 * less of one thing.
 *
 * [High] is Bungee, a phase vocoder: it analyses and resynthesises every grain
 * of audio, holds transients together and stays musical well past the range a
 * listener is likely to ask for. It also does that work for every packet, on a
 * phone, next to a decoder and a user interface.
 *
 * [Light] is the platform's own stretcher, the one behind `setPlaybackParams`.
 * It is nearly free, because the system does it below the app, and it is built
 * for speech: on music it smears attacks and adds a metallic edge that grows
 * with the amount. On an older phone that is the difference between altered
 * playback and stuttering playback.
 */
enum class EffectQuality(val key: String, @StringRes val label: Int, @StringRes val note: Int) {
    High("high", R.string.effect_quality_high, R.string.effect_quality_high_note),
    Light("light", R.string.effect_quality_light, R.string.effect_quality_light_note),
}

class EffectQualityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(
        EffectQuality.entries.firstOrNull { it.key == prefs.getString(KEY_QUALITY, null) }
            ?: EffectQuality.High,
    )
    val quality: StateFlow<EffectQuality> = _quality.asStateFlow()

    fun set(quality: EffectQuality) {
        if (quality == _quality.value) return
        _quality.value = quality
        prefs.edit().putString(KEY_QUALITY, quality.key).commit()
    }

    private companion object {
        const val FILE_NAME = "square_effect_quality"
        const val KEY_QUALITY = "quality"
    }
}
