package dev.lelonio.square.data

import android.content.Context
import androidx.annotation.StringRes
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the glass costs, as three answers rather than eight sliders.
 *
 * Every glass surface in this app records the screen behind it and runs a chain
 * of shaders over the recording — a saturation pass, a blur, and a lens that
 * bends the edges. That is the look, and it is also the single most expensive
 * thing the interface does: on the bottom bar alone it happens on every frame
 * the bar is moving. Whether it is worth it depends on the phone, and the phone
 * is the one thing this project cannot test on.
 *
 * So the profile is the setting most people should ever touch, and the numbers
 * below it are for whoever wants them. The order is deliberate: the cheapest
 * option first, because someone opening this page is usually here because
 * something felt slow.
 */
enum class GlassProfile(
    val key: String,
    @StringRes val label: Int,
    @StringRes val note: Int,
) {
    /**
     * No capture at all: a tinted film over the content, and nothing sampled.
     *
     * Not a degraded blur — a different material, and the one this app already
     * falls back to on phones that cannot run the shaders. Costs about as much
     * as drawing a rectangle, because that is what it is.
     */
    Performance("performance", R.string.glass_performance, R.string.glass_performance_note),

    /** The app's own look: what everything here was designed against. */
    Balanced("balanced", R.string.glass_balanced, R.string.glass_balanced_note),

    /**
     * Deeper frost, stronger refraction, and the dispersion at the edges.
     *
     * The parts that read as real glass in a still image and are the first to
     * cost frames on a phone with anything else going on.
     */
    Quality("quality", R.string.glass_quality, R.string.glass_quality_note);

    /** The material this profile means, before any of the sliders. */
    fun config(): GlassEffectConfig = when (this) {
        Performance -> GlassEffectConfig(
            style = GlassStyle.TRANSPARENT,
            surfaceOpacity = 0.55f,
        )

        Balanced -> GlassEffectConfig()

        Quality -> GlassEffectConfig(
            vibrancy = 1.4f,
            blurRadius = 4f,
            lensHeight = 0.5f,
            lensAmount = 0.8f,
            chromaticAberration = true,
        )
    }
}

/**
 * The glass, as the listener has it.
 *
 * A profile, plus whatever they have moved since choosing it. Overrides are
 * stored separately from the profile rather than flattened into it, so picking a
 * profile again is a way back: it clears them.
 */
class GlassStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(
        GlassProfile.entries.firstOrNull { it.key == prefs.getString(KEY_PROFILE, null) }
            ?: GlassProfile.Balanced,
    )
    val profile: StateFlow<GlassProfile> = _profile.asStateFlow()

    private val _config = MutableStateFlow(read())
    val config: StateFlow<GlassEffectConfig> = _config.asStateFlow()

    fun setProfile(profile: GlassProfile) {
        if (profile == _profile.value) return
        _profile.value = profile
        // The profile is the answer, so choosing one drops the tuning that was
        // sitting on top of the last: a screen where the profile says "fastest"
        // and the sliders say otherwise is a screen that is lying.
        prefs.edit()
            .clear()
            .putString(KEY_PROFILE, profile.key)
            .apply()
        _config.value = read()
        _barFolds.value = prefs.getBoolean(KEY_BAR_FOLDS, true)
    }

    fun setVibrancy(value: Float) = putFloat(KEY_VIBRANCY, value)

    fun setBlur(value: Float) = putFloat(KEY_BLUR, value)

    fun setLensHeight(value: Float) = putFloat(KEY_LENS_HEIGHT, value)

    fun setLensAmount(value: Float) = putFloat(KEY_LENS_AMOUNT, value)

    fun setSurfaceOpacity(value: Float) = putFloat(KEY_FILM, value)

    fun setHighlightOpacity(value: Float) = putFloat(KEY_RIM, value)

    fun setChromaticAberration(value: Boolean) = putBoolean(KEY_DISPERSION, value)

    fun setDepthEffect(value: Boolean) = putBoolean(KEY_DEPTH, value)

    fun setPlayerEnabled(value: Boolean) = putBoolean(KEY_PLAYER, value)

    fun setMiniPlayerEnabled(value: Boolean) = putBoolean(KEY_MINI_PLAYER, value)

    fun setNavBarEnabled(value: Boolean) = putBoolean(KEY_NAV_BAR, value)

    /** How strongly the selected tab is washed; see [read]. */
    fun setPuckOpacity(value: Float) = putFloat(KEY_PUCK, value)


    /**
     * Whether the bottom bar folds away as you scroll.
     *
     * Kept here rather than with the other UI preferences because it is the same
     * decision as the rest of this file: the fold is the app's most expensive
     * animation — a shared element travelling, a gooey layer, and glass whose
     * bounds change on every frame of it — and switching it off is the largest
     * single thing a phone that struggles can be given back.
     */
    fun setBarFolds(value: Boolean) = putBoolean(KEY_BAR_FOLDS, value)

    private val _barFolds = MutableStateFlow(prefs.getBoolean(KEY_BAR_FOLDS, true))
    val barFolds: StateFlow<Boolean> = _barFolds.asStateFlow()

    /** Back to the profile as it comes, with nothing moved. */
    fun reset() {
        prefs.edit()
            .clear()
            .putString(KEY_PROFILE, _profile.value.key)
            .apply()
        _config.value = read()
        _barFolds.value = prefs.getBoolean(KEY_BAR_FOLDS, true)
    }

    /** True while any of the numbers differ from the chosen profile's own. */
    val customised: Boolean
        get() = prefs.all.keys.any { it != KEY_PROFILE }

    private fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        _config.value = read()
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        _config.value = read()
        _barFolds.value = prefs.getBoolean(KEY_BAR_FOLDS, true)
    }

    /**
     * The profile's material with the stored overrides laid over it.
     *
     * Read in full on every change rather than patched, because the profile can
     * change underneath and a patched copy would carry the old one's numbers.
     */
    private fun read(): GlassEffectConfig {
        val base = _profile.value.config()
        return base.copy(
            vibrancy = prefs.getFloat(KEY_VIBRANCY, base.vibrancy),
            blurRadius = prefs.getFloat(KEY_BLUR, base.blurRadius),
            lensHeight = prefs.getFloat(KEY_LENS_HEIGHT, base.lensHeight),
            lensAmount = prefs.getFloat(KEY_LENS_AMOUNT, base.lensAmount),
            surfaceOpacity = prefs.getFloat(KEY_FILM, base.surfaceOpacity),
            highlightOpacity = prefs.getFloat(KEY_RIM, base.highlightOpacity),
            chromaticAberration = prefs.getBoolean(KEY_DISPERSION, base.chromaticAberration),
            depthEffect = prefs.getBoolean(KEY_DEPTH, base.depthEffect),
            playerEnabled = prefs.getBoolean(KEY_PLAYER, base.playerEnabled),
            miniPlayerEnabled = prefs.getBoolean(KEY_MINI_PLAYER, base.miniPlayerEnabled),
            navBarEnabled = prefs.getBoolean(KEY_NAV_BAR, base.navBarEnabled),
            // Square has one appearance, and it is dark glass over artwork. The
            // library's own default asks the Material scheme whether it is light
            // or dark and washes the selection puck accordingly, which here
            // answered "light" and put a solid white slab under the chosen tab.
            // A wash of light instead — but a wash you can see: at the 0.12 this
            // started on, over artwork with anything bright in it, the selected
            // tab was a guess.
            puckColor = androidx.compose.ui.graphics.Color.White,
            puckOpacity = prefs.getFloat(KEY_PUCK, DEFAULT_PUCK_OPACITY),
        )
    }

    private companion object {
        const val FILE_NAME = "square_glass"
        const val KEY_PROFILE = "profile"
        const val KEY_VIBRANCY = "vibrancy"
        const val KEY_BLUR = "blur"
        const val KEY_LENS_HEIGHT = "lens_height"
        const val KEY_LENS_AMOUNT = "lens_amount"
        const val KEY_FILM = "film"
        const val KEY_RIM = "rim"
        const val KEY_DISPERSION = "dispersion"
        const val KEY_DEPTH = "depth"
        const val KEY_PLAYER = "player"
        const val KEY_MINI_PLAYER = "mini_player"
        const val KEY_NAV_BAR = "nav_bar"
        const val KEY_BAR_FOLDS = "bar_folds"
        const val KEY_PUCK = "puck"

        /** Enough to read as a lit tab over a bright cover, short of a slab. */
        const val DEFAULT_PUCK_OPACITY = 0.26f
    }
}
