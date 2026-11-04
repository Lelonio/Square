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
    /**
     * A blend group's shapes, in the surface's own pixels at full resolution,
     * in place of the surface's own outline: upstream's LiquidGlassBlendGroup,
     * whose shapes melt into one another like drops where they come within
     * [blendPx] of each other. At most [LiquidGlassShader.MAX_SHAPES].
     */
    groupShapes: List<LiquidShape>? = null,
    /** Upstream's `LiquidGlassBlendGroup.blend`, in the surface's pixels. */
    blendPx: Float = 0f,
    /**
     * Upstream's GlassShadow, drawn by the shader round the group's shapes.
     * Only for a group: a single surface's layer ends at its outline, and its
     * own shadow is drawn outside it.
     */
    groupShadow: Boolean = false,
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
    // Upstream's matte, inflated for the anti-aliasing band and the blend.
    val inflate = 3f + blendPx * contentScale * 0.25f
    var geometryLeft = left
    var geometryTop = top
    var geometryRight = left + width
    var geometryBottom = top + height
    val shapes = groupShapes?.take(LiquidGlassShader.MAX_SHAPES)
    if (shapes == null) {
        shapeData[0] = LiquidShapeType.RoundedRectangle.shaderIndex
        shapeData[1] = left + width / 2f
        shapeData[2] = top + height / 2f
        shapeData[3] = width
        shapeData[4] = height
        shapeData[5] = radius
        shapeData[6] = radius
    } else {
        if (shapes.isEmpty()) return
        geometryLeft = Float.POSITIVE_INFINITY
        geometryTop = Float.POSITIVE_INFINITY
        geometryRight = Float.NEGATIVE_INFINITY
        geometryBottom = Float.NEGATIVE_INFINITY
        shapes.forEachIndexed { index, s ->
            val l = left + s.bounds.left * contentScale
            val t = top + s.bounds.top * contentScale
            val w = s.bounds.width * contentScale
            val h = s.bounds.height * contentScale
            geometryLeft = minOf(geometryLeft, l)
            geometryTop = minOf(geometryTop, t)
            geometryRight = maxOf(geometryRight, l + w)
            geometryBottom = maxOf(geometryBottom, t + h)
            val base = index * LiquidGlassShader.SHAPE_STRIDE
            shapeData[base] = s.type.shaderIndex
            shapeData[base + 1] = l + w / 2f
            shapeData[base + 2] = t + h / 2f
            shapeData[base + 3] = w
            shapeData[base + 4] = h
            shapeData[base + 5] = (s.cornerRadius * contentScale).fastCoerceAtMost(minOf(w, h) / 2f)
            shapeData[base + 6] = (s.bottomCornerRadius * contentScale).fastCoerceAtMost(minOf(w, h) / 2f)
        }
    }
    val glass = settings.effectiveGlassColor

    shader.setFloatUniform("uSize", width + 2f * padding, height + 2f * padding)
    shader.setFloatUniform("uGeometryOffset", geometryLeft - inflate, geometryTop - inflate)
    shader.setFloatUniform(
        "uGeometrySize",
        geometryRight - geometryLeft + 2f * inflate,
        geometryBottom - geometryTop + 2f * inflate,
    )
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
    shader.setFloatUniform("uNumShapes", (shapes?.size ?: 1).toFloat())
    shader.setFloatUniform("uDpr", dpr)
    shader.setFloatUniform("uBlend", blendPx * contentScale)
    shader.setFloatUniform("uNativeEdge", if (settings.rimShade > 0f) 1f else 0f)
    shader.setFloatUniform("uShapeData", shapeData)
    shader.setFloatUniform("uOpacity", 1f)
    // GlassShadow.defaults, as the blend group's writeUniforms draws it on
    // light pages: an elevation and a contact shadow. A BoxShadow's sigma is
    // 0.57735 × its blur radius + 0.5.
    if (groupShadow) {
        fun sigma(blurRadius: Float) = (blurRadius * 0.57735f + 0.5f) * dpr
        shader.setFloatUniform("uShadowA", 15f / 255f, sigma(8f), 0f, 2f * dpr)
        shader.setFloatUniform("uShadowB", 5f / 255f, sigma(2f), 0f, 1f * dpr)
    } else {
        shader.setFloatUniform("uShadowA", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("uShadowB", 0f, 0f, 0f, 0f)
    }

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
