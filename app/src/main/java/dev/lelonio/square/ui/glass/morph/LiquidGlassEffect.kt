// liquid_glass_widgets' render pass (see LiquidGlassShader.kt) as a link in the
// backdrop effect chain, for every surface that is drawn over a live page.
// MIT; see LICENSE-liquid-glass.txt beside this file.

package dev.lelonio.square.ui.glass.morph

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.util.fastCoerceAtMost
import dev.lelonio.square.ui.glass.backdrop.BackdropEffectScope
import dev.lelonio.square.ui.glass.backdrop.effects.effect
import dev.lelonio.square.ui.glass.backdrop.internal.RuntimeShaderEffect
import dev.lelonio.square.ui.glass.backdrop.isRuntimeShaderSupported
import kotlin.math.cos
import kotlin.math.sin

/**
 * Upstream's glass over the backdrop recorded so far, which arrives already
 * blurred: one shape, the surface's own rounded rectangle, with nothing to
 * blend it with.
 *
 * Its shadow is not drawn here: a RenderEffect cannot draw outside the layer
 * it runs on, and the surface's own shadow already does that part.
 */
fun BackdropEffectScope.liquidGlassMaterial(
    settings: LiquidGlassSettings,
    /** The finger, in the surface's own pixels at full resolution; null when untouched. */
    touchPosition: androidx.compose.ui.geometry.Offset? = null,
    /** Upstream's touch intensity: 0 at rest, 1 pressed. */
    touchIntensity: Float = 0f,
) {
    if (!isRuntimeShaderSupported() || !LiquidGlassRenderer.enabled) return
    if (size.width <= 0f || size.height <= 0f) return

    // The recorded layer's pixels per logical pixel.
    val dpr = density * contentScale
    val scale = dpr / 3f
    // A shape with no corner of its own (a capsule) is upstream's
    // borderRadius 9999, which the shader clamps to half the short side.
    val radius = (shape as? CornerBasedShape)
        ?.topStart?.toPx(size, this)
        ?.times(contentScale)
        ?.fastCoerceAtMost(size.minDimension / 2f)
        ?: (size.minDimension / 2f)

    val shader = runCatching {
        obtainRuntimeShader("LiquidGlassSingle", LiquidGlassShader.SOURCE_SINGLE)
    }.onFailure(LiquidGlassRenderer::unavailable).getOrNull() ?: return

    // The surface, as it sits in the recorded layer: inset by the padding
    // the blur asked for.
    val left = padding
    val top = padding
    val width = size.width
    val height = size.height
    val shapeData = FloatArray(LiquidGlassShader.MAX_SHAPES * LiquidGlassShader.SHAPE_STRIDE)
    shapeData[0] = LiquidShapeType.RoundedRectangle.shaderIndex
    shapeData[1] = left + width / 2f
    shapeData[2] = top + height / 2f
    shapeData[3] = width
    shapeData[4] = height
    shapeData[5] = radius
    shapeData[6] = radius
    // Upstream's matte, inflated for the anti-aliasing band (no blend here).
    val inflate = 3f
    val glass = settings.effectiveGlassColor

    shader.setFloatUniform("uSize", width + 2f * padding, height + 2f * padding)
    shader.setFloatUniform("uGeometryOffset", left - inflate, top - inflate)
    shader.setFloatUniform("uGeometrySize", width + 2f * inflate, height + 2f * inflate)
    shader.setFloatUniform("uGlassColor", glass.red, glass.green, glass.blue, glass.alpha)
    shader.setFloatUniform(
        "uOpticalProps",
        settings.effectiveRefractiveIndex,
        settings.effectiveChromaticAberration,
        settings.effectiveThickness * scale,
        1f,
    )
    shader.setFloatUniform(
        "uLightConfig",
        settings.effectiveLightIntensity,
        settings.effectiveAmbientStrength,
        settings.effectiveSaturation,
    )
    shader.setFloatUniform("uLightDirection", cos(settings.lightAngle), -sin(settings.lightAngle))
    shader.setFloatUniform("uWhiten", settings.effectiveWhitenStrength)
    shader.setFloatUniform("uWhitenGated", if (settings.whitenGated) 1f else 0f)
    shader.setFloatUniform("uPinchStrength", settings.pinchStrength)
    shader.setFloatUniform(
        "uEdgeConfig",
        settings.effectiveAmbientRim * scale,
        settings.effectiveFresnelStrength,
        scale,
        settings.effectiveEdgeAbsorption,
    )
    shader.setFloatUniform("uBodyMode", if (settings.bodyMode == GlassBodyMode.Clear) 1f else 0f)
    // Upstream's touch glint: the rim nearest the finger catches the light.
    // In the recorded layer's pixels, as the shader's coordinates are.
    if (touchPosition != null && touchIntensity > 0f) {
        shader.setFloatUniform(
            "uTouchPosition",
            left + touchPosition.x * contentScale,
            top + touchPosition.y * contentScale,
        )
        shader.setFloatUniform("uTouchIntensity", touchIntensity.coerceIn(0f, 1f))
    } else {
        shader.setFloatUniform("uTouchPosition", 0f, 0f)
        shader.setFloatUniform("uTouchIntensity", 0f)
    }
    shader.setFloatUniform(
        "uRimConfig",
        settings.effectiveRimShade,
        settings.effectiveRimLight,
        settings.rimShadeEnds,
    )
    shader.setFloatUniform("uLensModel", if (settings.lensModel == GlassLensModel.Paraxial) 1f else 0f)
    shader.setFloatUniform("uBodyShade", settings.effectiveBodyShade.coerceIn(0f, 1f))
    shader.setFloatUniform("uNumShapes", 1f)
    shader.setFloatUniform("uDpr", dpr)
    shader.setFloatUniform("uBlend", 0f)
    shader.setFloatUniform("uNativeEdge", if (settings.rimShade > 0f) 1f else 0f)
    shader.setFloatUniform("uShapeData", shapeData)
    shader.setFloatUniform("uOpacity", 1f)
    shader.setFloatUniform("uShadowA", 0f, 0f, 0f, 0f)
    shader.setFloatUniform("uShadowB", 0f, 0f, 0f, 0f)

    effect(RuntimeShaderEffect(shader, "content"))
}

/**
 * Upstream's blur pass takes a Gaussian sigma; Android's takes a radius it
 * turns into one as `0.57735 × radius + 0.5`. The radius that gives [sigmaPx].
 */
fun blurRadiusForSigma(sigmaPx: Float): Float =
    if (sigmaPx <= 0.5f) 0f else (sigmaPx - 0.5f) / 0.57735f

/**
 * Upstream's `AnimatedGlassIndicator.baseIndicatorSettings`: a clear lens,
 * no blur and no tint, a shallow curve, and no colour splitting.
 */
val IndicatorBaseSettings = LiquidGlassSettings(
    glassColor = androidx.compose.ui.graphics.Color(1f, 1f, 1f, 0f),
    thickness = 20f,
    refractiveIndex = 1.10f,
    lightIntensity = 2.0f,
    chromaticAberration = 0f,
    lightAngle = LiquidGlassSettings.DEFAULT_LIGHT_ANGLE,
    blur = 0f,
)

/**
 * An indicator's glass, as upstream's AnimatedGlassIndicator draws it on its
 * premium path: [progress] is the press, 0 at rest and 1 held; the glass comes
 * in with it (`visibility`), and the pinch through its lens follows the press
 * eased as `1 − (1 − p)²`, which steadies it while the spring settles.
 */
fun BackdropEffectScope.liquidGlassIndicator(
    progress: Float,
    settings: LiquidGlassSettings = IndicatorBaseSettings,
    pinchStrength: Float = 1f,
) {
    val fade = progress.coerceIn(0f, 1f)
    if (fade <= 0f) return
    val stablePinch = 1f - (1f - fade) * (1f - fade)
    liquidGlassMaterial(settings.copy(visibility = fade, pinchStrength = stablePinch * pinchStrength))
}

/**
 * How much of the resting indicator — the plain filled pill — still shows at
 * [progress]: upstream fades it out over the first 0.15 of the press.
 */
fun indicatorRestingAlpha(progress: Float): Float = (1f - progress / 0.15f).coerceIn(0f, 1f)

/**
 * Upstream's press springs for an indicator: `GlassSpring.snappy(150 ms)` in
 * and `GlassSpring.smooth(300 ms)` out. A spring given by duration and bounce
 * is stiffness (2π / duration)² and damping ratio 1 − bounce.
 */
fun <T> indicatorPressSpring(pressing: Boolean): androidx.compose.animation.core.SpringSpec<T> =
    if (pressing) {
        androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 1754.6f)
    } else {
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 438.6f)
    }
