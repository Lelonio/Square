/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package dev.lelonio.square.ui.glass

import dev.lelonio.square.ui.glass.morph.liquidGlassMaterial
import dev.lelonio.square.ui.glass.morph.LiquidGlassRenderer
import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import dev.lelonio.square.ui.glass.backdrop.BackdropEffectScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import dev.lelonio.square.ui.glass.backdrop.isRenderEffectSupported
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.ui.glass.backdrop.effects.colorControls
import dev.lelonio.square.ui.glass.backdrop.effects.lens
import dev.lelonio.square.ui.glass.backdrop.highlight.Highlight
import dev.lelonio.square.ui.glass.backdrop.highlight.HighlightStyle
import dev.lelonio.square.ui.glass.backdrop.shadow.Shadow

/**
 * User-configurable parameters of the liquid glass effect, sourced from DataStore
 * preferences in the settings and distributed through
 * [LocalGlassEffectConfig].
 */
@Stable
data class GlassEffectConfig(
    val globalEnabled: Boolean = true,
    val vibrancy: Float = 1.2f,
    /** Blur in dp applied to glass pills. User requested 2dp for landscape fix. */
    val blurRadius: Float = 2f,
    /** 0..1, mapped to 0..[LENS_MAX_DP] dp of lens refraction height. 0.4 = 40%. */
    val lensHeight: Float = 0.4f,
    /** 0..1, mapped to 0..[LENS_MAX_DP] dp of lens refraction amount. 0.6 = 60%. */
    val lensAmount: Float = 0.6f,
    /**
     * The glass is liquid_glass_widgets' iOS 27 material, `ios27Light` or
     * `ios27Dark` with the page, rather than the sliders below.
     */
    val ios27: Boolean = true,
    /**
     * iOS 27's frost, its heavy cloud. Off, a plain blur stands in for it:
     * the frost costs about as much again as the rest of the glass.
     */
    val frost: Boolean = true,
    /**
     * The dark glass whatever the page: the player's, which sits on every
     * colour a record can have, and dark reads over all of them.
     */
    val darkGlass: Boolean = false,
    /**
     * A sheet's material: the frost all cloud, without the copy of the page
     * showing through it, under a denser tint, as a sheet's thick material is
     * natively. Over a whole page the copy let its covers and titles be read
     * through the sheet.
     */
    val sheet: Boolean = false,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    /**
     * [Color.Unspecified] means adaptive, which is the default.
     *
     * It used to default to a fixed dark grey, which meant the adaptive branch
     * below was dead code: the film stayed dark over a light page, so in the
     * system's light setting every pill in the app was a dark slab with dark
     * ink on it. A colour set by hand in the settings still wins over the
     * adaptive answer — that is what setting it is for.
     */
    val surfaceTintColor: Color = Color.Unspecified,
    /** Specular rim ("pluck") colour. [Color.Unspecified] keeps the default white. */
    val highlightColor: Color = Color.Unspecified,
    /**
     * A colour of the listener's choosing for the glass, as a hue in degrees.
     *
     * [NO_TINT] leaves the material as it is: a film that follows the page it
     * is on and a white rim. A hue puts that colour through both, by
     * [tintStrength], rather than replacing them — the film still knows
     * whether it is over something light or dark, and only its cast changes.
     * Asked for in #29.
     */
    val tintHue: Float = NO_TINT,
    /** How far the glass is carried towards [tintHue], 0..1. */
    val tintStrength: Float = 0.5f,
    /** Specular rim opacity, 0..1. */
    val highlightOpacity: Float = EdgeHighlightAlpha,
    /** Which rendering style every glass surface uses. */
    val style: GlassStyle = GlassStyle.LIQUID,
    /** Wash painted over the nav bar's selection puck. [Color.Unspecified] adapts
     *  to the theme: a dark wash on dark, a light one on light. */
    val puckColor: Color = Color.Unspecified,
    /** Puck wash opacity at rest. It eases toward clear while pressed. */
    val puckOpacity: Float = 0.8f,
    val surfaceOpacity: Float = 0.5f,
    val textColor: Color = Color.White,
    val playerEnabled: Boolean = true,
    val miniPlayerEnabled: Boolean = true,
    val navBarEnabled: Boolean = true,
    /** Tablet side panel — split from [navBarEnabled] so it can differ from the
     *  phone bottom bar's glass setting instead of always mirroring it. */
    val sidePanelEnabled: Boolean = true,
    /** Side panel gets its own effect tuning (unlike the other components,
     *  which all share [vibrancy]/[blurRadius]/[lensHeight]/[lensAmount]) —
     *  defaults match those shared values so it looks identical until
     *  explicitly customized. */
    val sidePanelVibrancy: Float = 1.2f,
    val sidePanelBlurRadius: Float = 2f,
    val sidePanelLensHeight: Float = 0.4f,
    val sidePanelLensAmount: Float = 0.6f,
    val sidePanelColor: Color = Color.Unspecified,
    val sidePanelSurfaceOpacity: Float = 0.5f,
    val sidePanelTextColor: Color = Color.White,
) {
    /** A copy of this config with the shared effect fields swapped for the
     *  side panel's own — pass this to Modifier.liquidGlass for the side
     *  panel instead of the raw config, since liquidGlass always reads the
     *  shared fields generically. */
    fun forSidePanel(): GlassEffectConfig = copy(
        vibrancy = sidePanelVibrancy,
        blurRadius = sidePanelBlurRadius,
        lensHeight = sidePanelLensHeight,
        lensAmount = sidePanelLensAmount,
        surfaceTintColor = sidePanelColor,
        surfaceOpacity = sidePanelSurfaceOpacity,
        textColor = sidePanelTextColor,
    )
    /**
     * Whether the glass effect should be rendered for [component], taking the master
     * switch and the per-component switch into account.
     */
    fun isEnabledFor(component: GlassComponent): Boolean =
        globalEnabled && when (component) {
            GlassComponent.PLAYER -> playerEnabled
            GlassComponent.MINI_PLAYER -> miniPlayerEnabled
            GlassComponent.NAV_BAR -> navBarEnabled
            GlassComponent.SIDE_PANEL -> sidePanelEnabled
        }

    /**
     * True when at least one surface would render glass. Used to decide whether
     * capturing the app backdrop is worth anything at all — with every component
     * off, nothing samples it and recording it is pure cost.
     */
    val anyComponentEnabled: Boolean
        get() = globalEnabled &&
            (playerEnabled || miniPlayerEnabled || navBarEnabled || sidePanelEnabled)
}

/** UI surfaces that can individually opt in or out of the liquid glass effect. */
/**
 * How a glass surface is rendered.
 *
 * The full liquid treatment costs a screen capture plus a RenderEffect chain per
 * surface. The cheaper styles exist both as a performance escape hatch and as a
 * look in their own right — and because with glass off entirely the chrome fell
 * back to an opaque fill, which reads as a black blob over content.
 */
enum class GlassStyle {
    /** Blur + saturation + lens refraction + specular rim. The default. */
    LIQUID,

    /** Blur and saturation only: no lens, no rim, no chromatic aberration.
     *  Still captures the backdrop, so it is frosted, just not refractive. */
    BLUR,

    /** No capture at all — a translucent tinted fill at the configured opacity.
     *  Cheapest by a wide margin: nothing is sampled, nothing is blurred. */
    TRANSPARENT,
}

/**
 * True when [Modifier.liquidGlass] should take its cheap translucent-tint path
 * (no backdrop capture, no RenderEffect) instead of the full liquid treatment:
 * either the user explicitly chose [GlassStyle.TRANSPARENT], or the platform
 * can't do the real blur at all ([renderEffectSupported] false, below Android
 * 12). The unsupported-platform case used to fall through to a hardcoded
 * opaque-black surface instead — the "solid black instead of any
 * transparency" bug reported on older/low-end devices. Pulled out as a plain
 * function so the fallback decision is unit-testable without a Composition.
 */
fun shouldUseTranslucentGlassFallback(style: GlassStyle, renderEffectSupported: Boolean): Boolean =
    style == GlassStyle.TRANSPARENT || !renderEffectSupported

enum class GlassComponent {
    PLAYER,
    MINI_PLAYER,
    NAV_BAR,
    SIDE_PANEL,
}

/**
 * Maximum lens refraction in dp when the 0..1 preference sliders are at 1. The
 * defaults (0.5) land on the 24dp height/amount used by the library author's
 * Apple-matched LiquidBottomTabs recipe.
 */
internal const val LENS_MAX_DP = 48f

/** How far the bend may go for each unit of depth; see the lens in liquidGlass. */
private const val MAX_BEND_PER_DEPTH = 2.5f

/**
 * How much of a pane's shorter side the lens may approach, per edge.
 *
 * Just under half, so the two edges' refraction meets nowhere: at exactly a
 * half they touch, and touching is the crease itself.
 */
private const val LENS_MAX_SHARE = 0.45f

/** Below this, in pixels, a surface is too small to be worth a lens. */
private const val LENS_MIN_PANE_PX = 24f


/**
 * The full screen player uses a much heavier blur than the glass pills, matching
 * Apple Music where the now playing background is a deep-blurred material while
 * only the small controls are clear liquid glass.
 */
internal const val PLAYER_BLUR_MULTIPLIER = 4f

/**
 * Lowest resolution fraction glass surfaces are rendered at (heavy blur hides it).
 *
 * Measured empirically: flattening this across all surfaces and pushing it to
 * 0.05 was tried and reverted. Two findings worth not re-deriving. First, the
 * scale is only safe in proportion to the blur — pixel-sized effect params are
 * pre-multiplied by it, so at 0.05 the default 2dp blur collapsed to ~0.3px on a
 * 3x screen and the 20x upscale had nothing smoothing it, which reads as
 * pixelation, not frost. Second, applying it at zero blur softens surfaces whose
 * whole look is clear content, for no gain: the surfaces where compression
 * actually pays (the full screen player, 12x the nav bar's area at 4x the blur)
 * already reach this floor through the ramp.
 *
 * Keep the ramp in [glassResolutionScale] rather than a flat value.
 */
internal const val MIN_GLASS_RESOLUTION_SCALE = 0.30f

/** Blur radius (dp) at or above which the minimum resolution scale is safe to use. */
internal const val FULL_QUALITY_BLUR_DP = 8f

/**
 * Resolution fraction at which a glass surface records and processes its backdrop.
 * Blur masks the upscaling, so the more blur, the lower the resolution can go: at
 * [FULL_QUALITY_BLUR_DP]+ dp of blur the surface renders at
 * [MIN_GLASS_RESOLUTION_SCALE]; with no blur it stays at full resolution so the
 * clear glass center remains crisp.
 */
fun glassResolutionScale(blurRadiusDp: Float): Float {
    val t = (blurRadiusDp / FULL_QUALITY_BLUR_DP).coerceIn(0f, 1f)
    return 1f - t * (1f - MIN_GLASS_RESOLUTION_SCALE)
}

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], which is available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/**
 * Devices Android flags as low-RAM ([ActivityManager.isLowRamDevice]) can't push the
 * RenderEffect/RuntimeShader chain (blur + lens + vibrancy, several offscreen layers
 * per glass surface) without dropped frames, so glass is skipped there in favor of the
 * translucent film and rim [liquidGlass] draws where the blur is not available.
 */
@Composable
fun isLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    }
}

/** Combines the API-level check with the low-RAM device check; use this to gate glass. */
@Composable
fun isGlassAllowed(): Boolean = isGlassSupported() && !isLowRamDevice()

/**
 * Maps the user-facing vibrancy preference (0..2, default 1) to a saturation multiplier.
 * A value of 1 matches the library's built-in vibrancy effect (saturation x1.5), 0 leaves
 * colors untouched and 2 doubles the saturation.
 */
fun glassSaturation(vibrancy: Float): Float = 1f + 0.5f * vibrancy.coerceIn(0f, 2f)

/**
 * A content colour guaranteed to read against the glass it sits on.
 *
 * Picking purely from the app theme is not enough: what a glass pill actually
 * shows is the content behind it, tinted by [tint] at [opacity]. A dark tint at
 * high opacity is dark even in light mode, and a light tint is light even in
 * dark mode — so theme-derived text goes invisible at exactly the settings a
 * user is most likely to reach for. This composites the same two layers the
 * surface draws and picks from the result.
 *
 * @param behind the colour behind the glass — the theme surface, or the screen's
 * artwork tint where one is known.
 * @param tint the glass surface tint; [Color.Unspecified] means untinted.
 * @param opacity how strongly [tint] covers [behind], matching surfaceOpacity.
 */
fun glassContentColorFor(behind: Color, tint: Color, opacity: Float): Color {
    val effective = if (tint.isSpecified) {
        lerp(behind, tint, opacity.coerceIn(0f, 1f))
    } else {
        behind
    }
    // Same targets the rest of the app uses for on-surface text, so glass chrome
    // matches ordinary content rather than being its own special case.
    return if (effective.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
}

/**
 * Apple's floating pills have a thin, subtle specular line along the top edge — a
 * hint of light, not a bold ring. The library's [Highlight.Default] (0.5dp, white @
 * 0.5 alpha) reads as near-invisible at typical density, but a wide bright rim
 * overshoots in the other direction, so this lands narrower and dimmer than that.
 */
/**
 * How bright what is behind the glass has to be before the film darkens, and
 * where it has finished darkening. See the use of it in [liquidGlass].
 */
private const val BRIGHT_FROM = 0.42f
private const val BRIGHT_TO = 0.72f

/**
 * How bright the thing behind this app's glass is, 0 to 1.
 *
 * Provided once, near the top of the tree, from the palette of the artwork
 * playing. Anything drawn in glass can read it; nothing has to measure it.
 */
val LocalBackdropLuminance = androidx.compose.runtime.compositionLocalOf { 0f }

private val EdgeHighlightWidth = 0.8f.dp
private const val EdgeHighlightAlpha = 0.55f

/**
 * Real Liquid Glass isn't a highlight painted on at a fixed angle: Apple describes
 * the material as dynamically bending and shaping light "in real time, every single
 * frame," and community shader recreations implement this as one or more soft light
 * sources drifting across the surface over time (e.g. animated via sin/cos of the
 * frame clock). A perfectly static 45° rim reads as flat and synthetic by
 * comparison, so the highlight's light angle slowly drifts instead of sitting still.
 */
private const val HighlightAngleMin = 25f
private const val HighlightAngleMax = 65f
private const val HighlightDriftMillis = 7000

/** Midpoint of the [HighlightAngleMin]..[HighlightAngleMax] sweep — see the use site. */
private const val HighlightAngleFrozen = (HighlightAngleMin + HighlightAngleMax) / 2f

val LocalGlassEffectConfig = staticCompositionLocalOf { GlassEffectConfig() }

/**
 * While this returns true, every glass surface in scope holds the last thing it
 * sampled instead of recording the screen again.
 *
 * Provided app-wide from the page's scroll state. A surface's capture is only
 * worth taking when what is behind it has settled: during a scroll it is a
 * full-screen recording plus a blur and a lens, per surface, per frame, for a
 * refraction nobody can read while the page is flying past underneath.
 */
val LocalGlassFrozen = staticCompositionLocalOf<() -> Boolean> { { false } }

/** The backdrop content (app UI) that glass surfaces sample from. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

/**
 * When set, glass surfaces in scope pool one recorded+effect-processed layer
 * per bucket this returns instead of a single reused layer — see
 * `drawBackdrop`'s `loopBucket` parameter. Meant for surfaces sampling a
 * backdrop whose source loops (a looping canvas video): a bucket's visual
 * output is identical every repeat, so it only needs computing once, ever.
 * Null (default) keeps every surface's existing single-layer behavior.
 */
val LocalBackdropLoopBucket = staticCompositionLocalOf<(() -> Int)?> { null }

/**
 * Whether the Apple Music-styled UI (iOS 26/27 liquid glass look, SF-style tab icons,
 * denser glass) is active. Read by [com.convx.music.ui.screens.Screens] consumers to pick
 * between the classic and iOS icon sets.
 */
val LocalAppleMusicUi = staticCompositionLocalOf { false }

/**
 * Renders this composable as a liquid glass surface sampling [LocalAppBackdrop].
 *
 * Applies the configured vibrancy, blur and lens refraction effects, then draws the
 * surface tint (theme-adaptive unless the user picked a color). Effects whose
 * parameters make them a no-op are skipped entirely to keep the RenderEffect chain
 * as short as possible, and the backdrop is processed at [glassResolutionScale] of
 * the surface resolution. Returns the receiver unchanged on devices without
 * RenderEffect support.
 *
 * [applyEdgeEffects] controls the edge treatment that makes small pills read as
 * physical glass: lens refraction, the specular highlight rim and the drop shadow.
 * It should be false for large surfaces such as the full screen player, where the
 * rim renders as a stray band of light.
 *
 * [blurRadiusDp] overrides the configured blur; the full screen player passes a
 * heavier value ([PLAYER_BLUR_MULTIPLIER]x) than the clear glass pills.
 *
 * [shape] is restricted to [CornerBasedShape] because the backdrop lens effect throws
 * [UnsupportedOperationException] for any other shape type.
 */
@Composable
fun Modifier.liquidGlass(
    config: GlassEffectConfig,
    shape: CornerBasedShape = RoundedCornerShape(0.dp),
    applyEdgeEffects: Boolean = true,
    blurRadiusDp: Float = config.blurRadius,
    // The nav bar and mini player want a dimmer specular rim than the default.
    highlightAlpha: Float = EdgeHighlightAlpha,
    // Fraction of the surface resolution the backdrop is recorded at. Defaults to
    // [glassResolutionScale] for the blur radius (cheap, and the blur masks the
    // upscaling) — pass 1f for a crisp full-resolution backdrop, e.g. the small
    // glass buttons whose whole look is clear content, not heavy frost.
    backdropScale: Float = glassResolutionScale(blurRadiusDp),
    // Forwarded to drawBackdrop: while this returns true the surface holds its
    // last capture instead of re-recording the screen. Used by the floating tab
    // bar for the frames of its inline/expanded/search transition, where the
    // bar's bounds animate and would otherwise force a full-screen re-capture
    // plus effect chain on every frame.
    frozen: () -> Boolean = LocalGlassFrozen.current,
    /**
     * Hold the capture even while this surface is moving.
     *
     * For a surface that is changing shape: see the use site in
     * `DrawBackdropModifier`. Off everywhere else, where a moved surface must
     * take a fresh capture or it reflects the place it used to be.
     */
    heldWhileMoving: () -> Boolean = { false },
    // Forwarded to drawBackdrop: see LocalBackdropLoopBucket's doc. Defaults to
    // whatever's provided in composition, so player control pills over a
    // looping video pick this up without every call site naming it explicitly.
    loopBucket: (() -> Int)? = LocalBackdropLoopBucket.current,
    // LOCAL CHANGE: what this samples, for surfaces that hold their own.
    // Upstream always reads [LocalAppBackdrop] because everything glassy there
    // is app chrome over the page. Here the player is a sheet with a backdrop of
    // its own — the artwork behind it, not the page under that — and its panes
    // still have to be made of the same material as the bar. Null keeps the
    // upstream behaviour, and is read after the guard above so a call outside
    // any provider is only a problem when it actually needs one.
    ownBackdrop: Backdrop? = null,
    // LOCAL CHANGE: the two things a surface still owns after the material is
    // shared. [layerBlock] is how a control moves — the press squash and the
    // pull towards the finger the catalog's own buttons have — and [onDrawTint]
    // is a colour of its own drawn over the shared film, for a control that is
    // active rather than merely present. Neither says anything about what the
    // glass IS, which is the part that now has one answer.
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawTint: (DrawScope.() -> Unit)? = null,
    /**
     * Where a finger is on this surface and how far it is pressed, for
     * upstream's touch glint: the rim nearest the finger lights up. Read
     * while the frame is drawn; null for a surface nobody presses.
     */
    touch: (() -> GlassTouch?)? = null,
    /**
     * A blend group's shapes in place of this surface's own outline: see
     * GlassBlendGroup. Read while the frame is drawn. Only where upstream's
     * material runs; elsewhere the members draw their own glass.
     */
    group: (() -> GlassGroupFrame)? = null,
): Modifier {
    val density = LocalDensity.current
    // Upstream's material draws its edge, its lighting and its anti-aliasing
    // in the shader, at the layer's own resolution: recorded at a fraction
    // and stretched back up, the rim came out soft and doubled. It is drawn
    // at full resolution, as upstream draws it.
    val upstreamCapable = LiquidGlassRenderer.enabled &&
        config.style != GlassStyle.BLUR && applyEdgeEffects
    val resolutionScale = if (upstreamCapable) 1f else backdropScale.coerceIn(0.05f, 1f)
    // A surface that names its own condition (the tab bar, mid-fold) still
    // obeys the app-wide one: either reason to hold the last capture is enough.
    val appFrozen = LocalGlassFrozen.current
    val heldStill: () -> Boolean = remember(frozen, appFrozen) {
        if (frozen === appFrozen) frozen else ({ frozen() || appFrozen() })
    }
    // Pixel-sized effect parameters operate on the backdrop layer at its recorded
    // resolution, so they are pre-multiplied by the resolution scale to keep the
    // same visual size.
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * resolutionScale
    val saturation = glassSaturation(config.vibrancy)
    val lensHeightPx = with(density) { (config.lensHeight * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val lensAmountPx = with(density) { (config.lensAmount * LENS_MAX_DP).dp.toPx() } * resolutionScale
    // Apple's Liquid Glass is a bright, reflective material: even in dark mode it
    // reads as a distinctly lighter "frosted" surface, not a near-black rectangle
    // that blends into an OLED-black background. Honor an explicit user color,
    // otherwise use a proper adaptive glass gray rather than matching the theme
    // surface color 1:1 (which made the bar invisible over pure-black content).
    // The film follows what is actually behind the glass, not only the theme.
    //
    // A pane over a dark page wants a light film to read as glass at all; the
    // same film over a bright cover turns the whole control into a pale smear
    // with white text on it. What decides is the artwork playing, which the app
    // already reads a palette from — no per-frame readback of the screen, which
    // is how the library's own adaptive-luminance demo does it and why that
    // demo costs a capture and a pixel copy every frame.
    val backdropLuminance = LocalBackdropLuminance.current
    val surfaceTintColor = tinted(if (config.surfaceTintColor.isSpecified) {
        config.surfaceTintColor
    } else if (dev.lelonio.square.ui.theme.LocalInkOverride.current != null) {
        // A surface whose ink was chosen for it takes the opposite film.
        //
        // On the player and on a record's page the ink is decided by what is
        // behind the glass rather than by the phone's setting, and a film that
        // followed the setting anyway put light glyphs on a light pane over a
        // dark Canvas.
        val ink = dev.lelonio.square.ui.theme.LocalInkOverride.current
        if ((ink?.luminance() ?: 1f) > 0.5f) Color(0xFF23232A) else Color(0xFFFAFAFA)
    } else if (dev.lelonio.square.ui.theme.LocalLightTheme.current) {
        Color(0xFFFAFAFA)
    } else {
        // Between the two across the middle of the range rather than at a line,
        // so a cover that is merely light does not flip the chrome.
        val bright = ((backdropLuminance - BRIGHT_FROM) / (BRIGHT_TO - BRIGHT_FROM))
            .coerceIn(0f, 1f)
        lerp(Color(0xFF4A4A4E), Color(0xFF23232A), bright)
    }, config)

    // Translucent style short-circuits everything below: no backdrop is sampled and
    // no RenderEffect runs, so there is nothing to configure. Content behind shows
    // through the tint directly, which is the point — the alternative when glass is
    // off is an opaque fill, and that reads as a black blob sitting on the content.
    //
    // Also taken when the platform can't do the real blur (below API 31):
    // this used to fall through to a hardcoded opaque-black surface instead
    // (reasoning: unblurred sharp content behind a translucent tint "reads as
    // broken, not glass"), which is exactly the "solid black instead of any
    // transparency" bug reported on older/low-end devices. This same
    // translucent-tint path is already the app's own answer to "can't do a
    // real blur, need something cheap that still respects the user's tint
    // and opacity settings" — so route the unsupported case here too instead
    // of a separate hardcoded fallback.
    // Also taken where glass is not allowed at all, on a phone Android calls
    // low on memory. That case used to return the modifier untouched, which
    // is no film and no rim: the tab bar and the mini player were text
    // floating over the page.
    if (shouldUseTranslucentGlassFallback(config.style, isRenderEffectSupported()) ||
        !isGlassAllowed()
    ) {
        // The film and the rim, which is what a pane of this app's glass looks
        // like once you take the refraction away. Without the rim a flat surface
        // beside a real one reads as a hole rather than as the same material:
        // the edge light is most of what says "glass" at a glance, and it costs
        // a stroked outline.
        val flatRim = (highlightAlpha * (config.highlightOpacity / EdgeHighlightAlpha))
            .coerceIn(0f, 1f)
        val flatRimColor = tintedRim(
            if (config.highlightColor.isSpecified) config.highlightColor else Color.White,
            config,
        )
        return this
            .clip(shape)
            .background(surfaceTintColor.copy(alpha = config.surfaceOpacity.coerceIn(0f, 1f)))
            // What this control adds to the shared film, which this path used
            // to drop on the floor.
            //
            // Everything that reaches here is a flat pane — every chip in the
            // app, since those are drawn without sampling the screen — and for
            // all of them the tint was simply not run: the chosen chip was not
            // being filled any harder than the rest, and only its label being
            // brighter said which one it was. Between the film and the rim, so
            // it sits inside the material rather than over its edge.
            .then(
                if (onDrawTint != null) {
                    Modifier.drawBehind { onDrawTint.invoke(this) }
                } else {
                    Modifier
                },
            )
            .border(EdgeHighlightWidth, flatRimColor.copy(alpha = flatRim), shape)
    }
    val backdrop = ownBackdrop ?: LocalAppBackdrop.current

    // ponytail: frozen mid-sweep. The drift described at [HighlightAngleMin] was a
    // rememberInfiniteTransition, and because glass surfaces (the nav bar above all)
    // are on screen permanently, it re-emitted every frame forever — a measured
    // ~45fps of full-screen capture plus three blur passes and ~400mA, at idle,
    // with nothing on screen moving. Slowing the tween does NOT help: an infinite
    // transition emits per frame regardless of duration.
    //
    // The original note here blamed LayerBackdropModifier recording the screen
    // unconditionally, and proposed gating that. That was a misdiagnosis. The
    // consumer side was already gated (DrawBackdropNode re-records only on a
    // backdrop version/offset/size change). The real path was surfaceDirty: the
    // angle was read during COMPOSITION, so every frame rebuilt this function's
    // lambdas, made the drawBackdrop element unequal, and update() set
    // surfaceDirty unconditionally — forcing a full re-capture. Both halves of
    // that are now fixed (the lambdas below are remembered; update() only dirties
    // the capture when capture params change).
    //
    // Remaining upgrade path, now much smaller: hold the angle as a State and read
    // it INSIDE the highlight lambda, which HighlightNode already supports (its
    // highlight param is a lambda and it sets shouldAutoInvalidate = false). The
    // animation then invalidates only that node's draw — no recomposition, no
    // element churn, no re-capture. Worth measuring before/after rather than
    // assuming, since the numbers above were measured and these are not.
    val animatedHighlightAngle = HighlightAngleFrozen

    // Every lambda below is remembered on exactly the values it reads. Freshly
    // allocated lambdas make the drawBackdrop element unequal, which runs its
    // update() — and an `effects` change there invalidates the captured backdrop,
    // costing a full re-sample plus saturation/blur/lens. Since glass sits on
    // permanently-visible chrome, that turned any unrelated recomposition of the
    // nav bar into a whole-screen re-capture. Stable identities let the element
    // compare equal and skip the work entirely.
    val shapeBlock: () -> Shape = remember(shape) { { shape } }

    // BLUR keeps the frosted capture but drops the refraction and the rim, which
    // are the parts that read as "liquid". Cheaper too: the lens is the most
    // expensive link in the effect chain.
    val plainBlur = config.style == GlassStyle.BLUR

    // liquid_glass_widgets' material, where the phone can run it: its
    // refraction, tint, saturation and lit edge, in its own shader, from the
    // listener's settings (see toLiquidGlassSettings). The film the bar has
    // always worn is its glass colour, so a pane still follows what is behind
    // it. Below Android 13 the chain below is the app's own, as before.
    val upstream = upstreamCapable
    val lightPage = !config.darkGlass && isLightGlassPage()
    val upstreamSettings = if (config.ios27) {
        ios27Settings(lightPage, config)
    } else {
        config.toLiquidGlassSettings().copy(
            blur = blurRadiusDp,
            glassColor = surfaceTintColor.copy(alpha = config.surfaceOpacity.coerceIn(0f, 1f)),
        )
    }
    // Upstream's blur is a sigma in logical pixels; Android's takes a radius.
    // Under a frost a blur the shader can draw itself has no pass of its own.
    val upstreamBlurPx = if (
        upstreamSettings.effectiveFrost > 0f &&
        upstreamSettings.effectiveBlur * density.density <=
        dev.lelonio.square.ui.glass.morph.LiquidGlassSettings.FROST_GHOST_MAX_SIGMA
    ) {
        0f
    } else {
        dev.lelonio.square.ui.glass.morph.blurRadiusForSigma(
            with(density) { upstreamSettings.effectiveBlur.dp.toPx() },
        ) * resolutionScale
    }

    // Where the library's shader cannot run (a GPU driver that refuses to
    // compile it, or below Android 13) the chain below is the app's own, and
    // the iOS 27 switch changed nothing on it. There the material is
    // approximated with what the chain has: the frost's blur, or the blur that
    // stands in for it, the preset's saturation and its tint in place of the
    // app's film.
    val approx27 = !upstream && config.ios27 && !plainBlur
    val chainSaturation = if (approx27) upstreamSettings.effectiveSaturation else saturation
    val chainBlurPx = if (approx27) {
        dev.lelonio.square.ui.glass.morph.blurRadiusForSigma(
            with(density) { maxOf(upstreamSettings.effectiveFrost, upstreamSettings.effectiveBlur).dp.toPx() },
        ) * resolutionScale
    } else {
        blurPx
    }
    val chainTint = if (approx27) upstreamSettings.glassColor else surfaceTintColor.copy(alpha = config.surfaceOpacity)

    val effectsBlock: BackdropEffectScope.() -> Unit = remember(
        chainSaturation,
        chainBlurPx,
        applyEdgeEffects,
        plainBlur,
        lensHeightPx,
        lensAmountPx,
        config.depthEffect,
        config.chromaticAberration,
        upstream,
        upstreamSettings,
        upstreamBlurPx,
        touch,
        group,
        lightPage,
    ) {
        if (upstream) {
            {
                if (upstreamBlurPx > 0f) blur(upstreamBlurPx)
                if (group != null) {
                    val frame = group()
                    liquidGlassMaterial(
                        upstreamSettings,
                        frame.touch?.position,
                        frame.touch?.intensity ?: 0f,
                        groupShapes = frame.shapes,
                        blendPx = frame.blendPx,
                        groupShadow = lightPage,
                    )
                } else {
                    val pressed = touch?.invoke()
                    liquidGlassMaterial(upstreamSettings, pressed?.position, pressed?.intensity ?: 0f)
                }
            }
        } else {
        {
            if (chainSaturation != 1f) {
                colorControls(saturation = chainSaturation)
            }
            if (chainBlurPx > 0f) {
                blur(chainBlurPx)
            }
            if (!plainBlur &&
                applyEdgeEffects &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (lensHeightPx > 0f || lensAmountPx > 0f) &&
                // A pane too small to hold a lens is drawn without one: the
                // shader's cost climbs as its reach approaches the surface,
                // and on a few pixels there is nothing to see anyway.
                minOf(size.width, size.height) > LENS_MIN_PANE_PX
            ) {
                // Never past the middle of the pane. The depth is a length in
                // dp, and on a surface shorter than twice that length the lens
                // rolling in from the top edge meets the one from the bottom:
                // where they meet is a crease, and on the tab bar, barely
                // taller than this setting's own maximum, it read as a line
                // across it.
                //
                // A hard limit, not a curve. Easing the numbers into what was
                // left kept the whole slider meaningful and hung the app: the
                // refraction shader, handed a depth a hair under what the pane
                // could hold, took the main thread for minutes at a time. Past
                // the limit the slider stops doing anything on short surfaces,
                // and goes on working on every tall one.
                val reach = minOf(size.width, size.height) * LENS_MAX_SHARE
                val depth = lensHeightPx.coerceAtMost(reach)
                lens(
                    refractionHeight = depth,
                    // And no further than the band it bends in can carry. A
                    // bend many times the depth reads its light from well past
                    // the recorded layer, where there is only the layer's edge
                    // repeated: white streaks across the bar, and the page
                    // smeared through it while scrolling (#133). The defaults
                    // sit at one and a half; this only stops the extremes.
                    refractionAmount = lensAmountPx
                        .coerceAtMost(reach)
                        .coerceAtMost(depth * MAX_BEND_PER_DEPTH),
                    depthEffect = config.depthEffect,
                    chromaticAberration = config.chromaticAberration,
                )
            }
        }
        }
    }

    // The rim's own colour, independent of the surface tint: on a tinted pill a
    // white rim is what reads as "lit glass", but a coloured one is the difference
    // between chrome and neon, so it is worth exposing rather than assuming.
    val rimColor = tintedRim(
        if (config.highlightColor.isSpecified) config.highlightColor else Color.White,
        config,
    )
    // Call sites pass a per-component alpha (the nav bar wants a dimmer rim than a
    // button). Scale the user's setting by it rather than replacing it, so both the
    // per-component tuning and the preference still mean something.
    val rimAlpha = (highlightAlpha * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)

    val highlightBlock: (() -> Highlight?)? = remember(applyEdgeEffects, plainBlur, rimColor, rimAlpha, animatedHighlightAngle, upstream) {
        // Upstream's shader lights its own edge.
        if (applyEdgeEffects && !plainBlur && !upstream) {
            {
                Highlight(
                    width = EdgeHighlightWidth,
                    style = HighlightStyle.Default(
                        color = rimColor.copy(alpha = rimAlpha),
                        angle = animatedHighlightAngle,
                    ),
                )
            }
        } else {
            null
        }
    }

    val shadowBlock: (() -> Shadow?)? = remember(applyEdgeEffects, upstream, lightPage, group, config.ios27, upstreamSettings) {
        when {
            !applyEdgeEffects -> null
            // A group's shader draws the shadow round its own shapes.
            upstream && group != null -> null
            // The material's own shadow (upstream's effectiveShadow, its
            // first layer): ios27Light's long faint one, none on ios27Dark.
            upstream && config.ios27 -> if (lightPage) {
                upstreamSettings.effectiveShadow.firstOrNull()?.let { layer ->
                    {
                        Shadow(
                            radius = layer.blurRadius.dp,
                            offset = androidx.compose.ui.unit.DpOffset(layer.offsetX.dp, layer.offsetY.dp),
                            color = Color.Black.copy(alpha = layer.alpha),
                        )
                    }
                }
            } else {
                null
            }
            // Upstream's GlassShadow.elevation, on light pages only, as
            // upstream draws it: 0x0F black, an 8 px blur, 2 px down.
            upstream -> if (lightPage) {
                {
                    Shadow(
                        radius = 8.dp,
                        offset = androidx.compose.ui.unit.DpOffset(0.dp, 2.dp),
                        color = Color.Black.copy(alpha = 15f / 255f),
                    )
                }
            } else {
                null
            }
            else -> ({ Shadow.Default })
        }
    }

    // RenderEffect is guaranteed supported past this point — the unsupported
    // case already returned the translucent-tint fallback above.
    val surfaceBlock: DrawScope.() -> Unit = remember(chainTint, onDrawTint, upstream) {
        {
            // Upstream's shader tints the glass itself.
            if (chainTint.alpha > 0f && !upstream) {
                drawRect(
                    color = chainTint,
                    size = size,
                )
            }
            onDrawTint?.invoke(this)
        }
    }

    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeBlock,
        effects = effectsBlock,
        highlight = highlightBlock,
        shadow = shadowBlock,
        onDrawSurface = surfaceBlock,
        layerBlock = layerBlock,
        // These three were computed here and then not passed on, which quietly
        // undid what they are for: every surface recorded the whole screen at
        // full resolution — while the effect parameters had already been
        // pre-multiplied for a smaller one, so the blur and the lens were also
        // rendering at a fraction of their intended size — and the tab bar's
        // `frozen`, the thing that stops the bar re-capturing the screen on
        // every frame of its fold, was accepted and dropped.
        backdropScale = resolutionScale,
        frozen = heldStill,
        heldWhileMoving = heldWhileMoving,
        loopBucket = loopBucket,
    )
}

/** A hue nobody asked for; see [GlassEffectConfig.tintHue]. */
const val NO_TINT = -1f

/**
 * [base] carried towards the listener's hue, keeping its own lightness.
 *
 * The film is what says whether the glass is over something light or dark, so
 * the tint moves its colour rather than replacing it: at full strength a dark
 * film becomes a dark film of that colour, not a flat slab of it, and the text
 * on the pane goes on being readable.
 */
private fun tinted(base: Color, config: GlassEffectConfig): Color {
    if (config.tintHue < 0f) return base
    val strength = config.tintStrength.coerceIn(0f, 1f)
    if (strength <= 0f) return base
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    val saturation = (hsv[1] + (TINT_SATURATION - hsv[1]) * strength).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(config.tintHue, saturation, hsv[2])))
        .copy(alpha = base.alpha)
}

/** The rim, which goes the whole way: it is the part that reads as lit. */
private fun tintedRim(base: Color, config: GlassEffectConfig): Color {
    if (config.tintHue < 0f) return base
    val strength = config.tintStrength.coerceIn(0f, 1f)
    if (strength <= 0f) return base
    val hue = Color(
        android.graphics.Color.HSVToColor(floatArrayOf(config.tintHue, TINT_RIM_SATURATION, 1f)),
    )
    return lerp(base, hue, strength).copy(alpha = base.alpha)
}

/** How coloured the film and the rim go at full strength. */
private const val TINT_SATURATION = 0.55f
private const val TINT_RIM_SATURATION = 0.85f

/**
 * The listener's glass, as liquid_glass_widgets' material takes it.
 *
 * Through upstream's own translation of designer parameters
 * (`LiquidGlassSettings.figma`): the bend, as a percentage, sets the
 * refractive index at `1 + bend × 0.2`; the edge depth is the bevel's
 * thickness; colour splitting is a dispersion; the frost is the blur's sigma;
 * the rim light is the light's intensity. Saturation is this app's vibrancy,
 * the film is the one the bar wears, and the depth switch is upstream's edge
 * absorption at the strength of its iOS 27 material. The optics — how these
 * bend, tint and light the glass — are upstream's, in its shader.
 *
 * [blurScale] thickens the frost for the surfaces that need more than a pill,
 * and [minBlur] is the least they may have, in logical pixels.
 */
@Composable
fun GlassEffectConfig.toLiquidGlassSettings(
    blurScale: Float = 1f,
    minBlur: Float = 0f,
): dev.lelonio.square.ui.glass.morph.LiquidGlassSettings {
    val light = dev.lelonio.square.ui.theme.LocalLightTheme.current
    val film = tinted(
        when {
            surfaceTintColor.isSpecified -> surfaceTintColor
            light -> Color(0xFFFAFAFA)
            else -> Color(0xFF23232A)
        },
        this,
    ).copy(alpha = surfaceOpacity.coerceIn(0f, 1f))
    val liquid = style != GlassStyle.BLUR
    return dev.lelonio.square.ui.glass.morph.LiquidGlassSettings(
        refractiveIndex = if (liquid) 1f + lensAmount.coerceIn(0f, 1f) * 0.2f else 1f,
        thickness = if (liquid) (lensHeight * LENS_MAX_DP).coerceAtLeast(1f) else 1f,
        chromaticAberration = if (liquid && chromaticAberration) 4f * DISPERSION_SHARE else 0f,
        lightIntensity = if (liquid) highlightOpacity.coerceIn(0f, 1f) else 0f,
        blur = (blurRadius * blurScale).coerceAtLeast(minBlur),
        ambientStrength = 0.1f,
        saturation = glassSaturation(vibrancy),
        glassColor = film,
        edgeAbsorption = if (depthEffect) 0.035f else 0f,
    )
}

/** The dispersion colour splitting stands for, as a share of upstream's 0–100 %. */
private const val DISPERSION_SHARE = 0.10f

/** A finger on a glass surface: where, in its own pixels, and how far pressed (0..1). */
@androidx.compose.runtime.Immutable
data class GlassTouch(val position: androidx.compose.ui.geometry.Offset, val intensity: Float)

/**
 * liquid_glass_widgets' iOS 27 material for the page, `ios27Light` or
 * `ios27Dark`, with the listener's sliders laid over it: each moves its term
 * of the material by how far it sits from its own default, so at the
 * defaults this is the preset exactly. Without the frost, a blur of a
 * quarter of its sigma stands in for it.
 */
fun ios27Settings(light: Boolean, config: GlassEffectConfig): dev.lelonio.square.ui.glass.morph.LiquidGlassSettings {
    val base = if (light) {
        dev.lelonio.square.ui.glass.morph.LiquidGlassSettings.Ios27Light
    } else {
        dev.lelonio.square.ui.glass.morph.LiquidGlassSettings.Ios27Dark
    }
    val d = GlassEffectConfig()
    fun ratio(value: Float, default: Float) = if (default > 0f) (value / default).coerceAtLeast(0f) else 1f
    val frost = base.frost * ratio(config.blurRadius, d.blurRadius)
    val film = base.glassColor.alpha * ratio(config.surfaceOpacity, d.surfaceOpacity)
    val tuned = base.copy(
        saturation = 1f + (base.saturation - 1f) * ratio(config.vibrancy, d.vibrancy),
        frost = frost,
        thickness = (base.thickness * ratio(config.lensHeight, d.lensHeight)).coerceAtLeast(1f),
        refractiveIndex = 1f + (base.refractiveIndex - 1f) * ratio(config.lensAmount, d.lensAmount),
        glassColor = tinted(base.glassColor, config).copy(alpha = film.coerceIn(0f, 1f)),
        rimLight = base.rimLight * ratio(config.highlightOpacity, d.highlightOpacity),
        chromaticAberration = if (config.chromaticAberration) 4f * DISPERSION_SHARE else 0f,
        edgeAbsorption = base.edgeAbsorption * (if (config.depthEffect) 2f else 1f),
    )
    val material = if (config.sheet) {
        tuned.copy(
            frostOpacity = 1f,
            glassColor = (if (light) Color(0xFFF2F2F7) else Color(0xFF1C1C1E)).copy(alpha = 0.55f),
        )
    } else {
        tuned
    }
    if (config.frost) return material
    return material.copy(frost = 0f, blur = maxOf(material.blur, frost * 0.25f))
}

/**
 * Whether the glass here sits on a light page. A page with ink of its own
 * (the player, a playlist over its artwork) says what it is by that ink:
 * light letters sit on a dark page. Elsewhere, the app's theme.
 */
@Composable
fun isLightGlassPage(): Boolean =
    !LocalGlassEffectConfig.current.darkGlass &&
    (dev.lelonio.square.ui.theme.LocalInkOverride.current
        ?.let { it.luminance() < 0.5f }
        ?: dev.lelonio.square.ui.theme.LocalLightTheme.current)
