// The settings and the uniforms they become are ported from liquid_glass_widgets,
// MIT (lib/src/engine/liquid_glass_settings.dart,
// lib/src/engine/rendering/liquid_glass_render_object.dart,
// lib/src/engine/liquid_glass_blend_group.dart, lib/constants/glass_shadow.dart;
// https://github.com/sdegenaar/liquid_glass_widgets, commit 59118973).
// Copyright (c) 2024–2026 Sebastian Degenaar; engine derived from
// liquid_glass_renderer, Copyright 2025 Tim Lehmann for whynotmake.it.
// See LICENSE-liquid-glass.txt beside this file.

package dev.lelonio.square.ui.glass.morph

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import kotlin.math.cos
import kotlin.math.sin

/** Upstream's `GlassLensModel`. */
enum class GlassLensModel { Spherical, Paraxial }

/** Upstream's `GlassBodyMode`. */
enum class GlassBodyMode { Adaptive, Clear }

/**
 * Upstream's `LiquidGlassSettings`, the fields its render pass reads, with
 * upstream's defaults and its `effective*` getters.
 */
@Immutable
data class LiquidGlassSettings(
    val visibility: Float = 1f,
    val glassColor: Color = Color(255, 255, 255, 0),
    val thickness: Float = 20f,
    val blur: Float = 5f,
    val chromaticAberration: Float = 0.01f,
    val lightAngle: Float = DEFAULT_LIGHT_ANGLE,
    val lightIntensity: Float = 0.5f,
    val ambientStrength: Float = 0f,
    val ambientRim: Float = 0f,
    val fresnelStrength: Float = 1f,
    val refractiveIndex: Float = 1.2f,
    val saturation: Float = 1.5f,
    val shadowElevation: Float = 1f,
    val whitenStrength: Float = 0f,
    val whitenGated: Boolean = true,
    val edgeAbsorption: Float = 0f,
    val rimShade: Float = 0f,
    val rimShadeEnds: Float = 0.2f,
    val rimLight: Float = 0f,
    val bodyShade: Float = 0f,
    val lensModel: GlassLensModel = GlassLensModel.Spherical,
    val bodyMode: GlassBodyMode = GlassBodyMode.Adaptive,
    val pinchStrength: Float = 0f,
) {
    val effectiveGlassColor: Color get() = glassColor.copy(alpha = glassColor.alpha * visibility)
    val effectiveThickness: Float get() = thickness * visibility
    val effectiveBlur: Float get() = blur * visibility
    val effectiveChromaticAberration: Float get() = chromaticAberration * visibility
    val effectiveLightIntensity: Float get() = lightIntensity * visibility
    val effectiveFresnelStrength: Float get() = fresnelStrength * visibility
    val effectiveAmbientRim: Float get() = ambientRim * visibility
    val effectiveAmbientStrength: Float get() = ambientStrength * visibility
    val effectiveShadowElevation: Float get() = shadowElevation * visibility
    val effectiveWhitenStrength: Float get() = whitenStrength * visibility
    val effectiveEdgeAbsorption: Float get() = edgeAbsorption * visibility
    val effectiveRimShade: Float get() = rimShade * visibility
    val effectiveRimLight: Float get() = rimLight * visibility
    val effectiveBodyShade: Float get() = bodyShade * visibility
    val effectiveSaturation: Float get() = 1f + (saturation - 1f) * visibility
    val effectiveRefractiveIndex: Float get() = 1f + (refractiveIndex - 1f) * visibility

    companion object {
        /** Upstream's `GlassDefaults.lightAngle`: 0.75π. */
        const val DEFAULT_LIGHT_ANGLE = (0.75 * 3.14159265358979).toFloat()

        /**
         * What upstream's GlassMenu falls back to with no theme above it: the
         * menu's own glass.
         */
        val Menu = LiquidGlassSettings(
            blur = 10f,
            thickness = 10f,
            glassColor = Color(255, 255, 255, (0.12f * 255).toInt()),
            lightAngle = DEFAULT_LIGHT_ANGLE,
            lightIntensity = 0.7f,
            ambientStrength = 0.4f,
            saturation = 1.2f,
            refractiveIndex = 0.7f,
            chromaticAberration = 0f,
        )
    }
}

/** Upstream's `LiquidRoundedRectangle`, `LiquidRoundedSuperellipse` and `LiquidOval` as shader types. */
enum class LiquidShapeType(val shaderIndex: Float) { Squircle(1f), Oval(2f), RoundedRectangle(3f) }

/** One shape of a blend group, in the surface's own logical pixels. */
@Immutable
data class LiquidShape(
    val type: LiquidShapeType,
    val bounds: Rect,
    val cornerRadius: Float,
    val bottomCornerRadius: Float = cornerRadius,
)

/** One frame of a blend group: its shapes, their blend, and the group's opacity. */
@Immutable
data class LiquidGlassFrame(
    val shapes: List<LiquidShape>,
    /** Upstream's `LiquidGlassBlendGroup.blend`, in logical pixels. */
    val blend: Float,
    /** Upstream wraps the whole layer in an `Opacity`; this is that. */
    val opacity: Float = 1f,
)

/**
 * The page under a blend group, captured once: sharp, and blurred the way the
 * upstream blur pass blurs it.
 *
 * Upstream reads a live backdrop every frame; here the page is read when the
 * group appears, since nothing under a modal menu moves while it is open, and
 * every frame after that only moves the shapes.
 */
@Stable
class LiquidGlassCapture internal constructor(
    internal val page: GraphicsLayer,
    internal val blurred: GraphicsLayer,
) {
    internal var coordinates: LayoutCoordinates? = null
    internal var recordedSize: IntSize? = null
    internal var recordedBlur: Float = -1f
    internal var generation by mutableIntStateOf(0)
    internal var sharpBitmap by mutableStateOf<ImageBitmap?>(null)
    internal var blurredBitmap by mutableStateOf<ImageBitmap?>(null)
    internal var bitmapsFor by mutableIntStateOf(-1)
    internal var sharpShader: android.graphics.Shader? = null
    internal var blurredShader: android.graphics.Shader? = null

    internal val shader: android.graphics.RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.graphics.RuntimeShader(LiquidGlassShader.SOURCE)
        } else {
            null
        }
    internal val paint = android.graphics.Paint()
    internal val shapeData = FloatArray(LiquidGlassShader.MAX_SHAPES * LiquidGlassShader.SHAPE_STRIDE)

    /** Whether the page has been read and the shader can draw from it. */
    val ready: Boolean
        get() = sharpBitmap != null && blurredBitmap != null && bitmapsFor == generation

    /** Forgets the page, so the next appearance reads it again. */
    fun reset() {
        recordedSize = null
        sharpBitmap = null
        blurredBitmap = null
        sharpShader = null
        blurredShader = null
        bitmapsFor = -1
    }
}

@Composable
fun rememberLiquidGlassCapture(): LiquidGlassCapture {
    val page = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val capture = remember(page, blurred) { LiquidGlassCapture(page, blurred) }
    // The pictures the shader reads, taken once the layers have been drawn.
    LaunchedEffect(capture) {
        snapshotFlow { capture.generation }.collect { generation ->
            if (generation == 0) return@collect
            val sharp = runCatching { capture.page.toImageBitmap() }
                .onFailure { android.util.Log.w("SquareGlass", "sharp failed", it) }.getOrNull()
            val blurredImage = runCatching { capture.blurred.toImageBitmap() }
                .onFailure { android.util.Log.w("SquareGlass", "blurred failed", it) }.getOrNull()
            if (generation == capture.generation && sharp != null && blurredImage != null) {
                // Made into shaders once, here, rather than on every frame.
                capture.sharpShader = nearest(sharp)
                capture.blurredShader = nearest(blurredImage)
                capture.sharpBitmap = sharp
                capture.blurredBitmap = blurredImage
                capture.bitmapsFor = generation
            }
        }
    }
    return capture
}

/**
 * A blend group of liquid glass shapes, drawn over [backdrop] with upstream's
 * render pass.
 *
 * Covers the whole surface it is put on and draws only where [frame]'s shapes
 * (and their shadows) are. Light-mode shadows follow upstream: none on a dark
 * page. Below Android 13 there is no runtime shader, and nothing is drawn.
 */
fun Modifier.liquidGlassGroup(
    backdrop: Backdrop,
    capture: LiquidGlassCapture,
    settings: LiquidGlassSettings,
    darkPage: Boolean,
    frame: () -> LiquidGlassFrame,
): Modifier = this
    .onGloballyPositioned { capture.coordinates = it }
    .drawBehind {
        val shader = capture.shader ?: return@drawBehind
        val pixels = IntSize(size.width.toInt(), size.height.toInt())
        if (pixels.width <= 0 || pixels.height <= 0) return@drawBehind
        val dpr = density

        // The page, read once for this size and this blur.
        if (capture.recordedSize != pixels || capture.recordedBlur != settings.effectiveBlur) {
            capture.page.record(pixels) {
                with(backdrop) {
                    drawBackdrop(density = this@record, coordinates = capture.coordinates)
                }
            }
            capture.blurred.renderEffect = blurEffect(settings.effectiveBlur * dpr)
            capture.blurred.record(pixels) {
                with(backdrop) {
                    drawBackdrop(density = this@record, coordinates = capture.coordinates)
                }
            }
            capture.recordedSize = pixels
            capture.recordedBlur = settings.effectiveBlur
            capture.generation += 1
        }
        if (!capture.ready) return@drawBehind
        val sharp = capture.sharpShader ?: return@drawBehind
        val blurred = capture.blurredShader ?: return@drawBehind

        val current = frame()
        if (current.opacity <= 0f || current.shapes.isEmpty()) return@drawBehind

        val bounds = writeUniforms(shader, capture, settings, darkPage, current, dpr, size.width, size.height)
        shader.setInputShader("sharpBackdrop", sharp)
        shader.setInputShader("blurredBackdrop", blurred)
        capture.paint.shader = shader
        // Only where the glass or its shadow can be: everywhere else the
        // shader would only work out that it draws nothing.
        drawIntoCanvas {
            it.nativeCanvas.drawRect(
                bounds.left.coerceAtLeast(0f),
                bounds.top.coerceAtLeast(0f),
                bounds.right.coerceAtMost(size.width),
                bounds.bottom.coerceAtMost(size.height),
                capture.paint,
            )
        }
    }

/**
 * Upstream's blur pass: `ImageFilter.blur(sigma, mirror)`, where [sigmaPx] is
 * the blur in physical pixels. Android's blur takes a radius and turns it into
 * a sigma as `0.57735 * radius + 0.5`; this undoes that, so the sigma is the
 * same.
 */
private fun blurEffect(sigmaPx: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || sigmaPx <= 0.5f) return null
    val radius = (sigmaPx - 0.5f) / 0.57735f
    return android.graphics.RenderEffect
        .createBlurEffect(radius, radius, android.graphics.Shader.TileMode.MIRROR)
        .asComposeRenderEffect()
}

/** A picture as a child shader, sampled texel by texel: the shader filters it itself. */
private fun nearest(image: ImageBitmap): android.graphics.Shader {
    val shader = android.graphics.BitmapShader(
        image.asAndroidBitmap().let {
            if (it.config == android.graphics.Bitmap.Config.HARDWARE) {
                it.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            } else {
                it
            }
        },
        android.graphics.Shader.TileMode.CLAMP,
        android.graphics.Shader.TileMode.CLAMP,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        shader.filterMode = android.graphics.BitmapShader.FILTER_MODE_NEAREST
    }
    return shader
}

/** Upstream's uniforms, from `LiquidGlassRenderObject` and `LiquidGlassBlendGroup`. */
private fun writeUniforms(
    shader: android.graphics.RuntimeShader,
    capture: LiquidGlassCapture,
    settings: LiquidGlassSettings,
    darkPage: Boolean,
    frame: LiquidGlassFrame,
    dpr: Float,
    width: Float,
    height: Float,
): Rect {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Rect.Zero
    // The visual thickness was tuned at 3x; scale keeps it the same logical width.
    val scale = dpr / 3f
    val lightDirX = cos(settings.lightAngle)
    val lightDirY = -sin(settings.lightAngle)

    shader.setFloatUniform("uSize", width, height)

    // Upstream's matte: the shapes' union, inflated for the anti-aliasing band.
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    val shapes = frame.shapes.take(LiquidGlassShader.MAX_SHAPES)
    shapes.forEachIndexed { index, shape ->
        left = minOf(left, shape.bounds.left)
        top = minOf(top, shape.bounds.top)
        right = maxOf(right, shape.bounds.right)
        bottom = maxOf(bottom, shape.bounds.bottom)
        val base = index * LiquidGlassShader.SHAPE_STRIDE
        capture.shapeData[base] = shape.type.shaderIndex
        capture.shapeData[base + 1] = shape.bounds.center.x * dpr
        capture.shapeData[base + 2] = shape.bounds.center.y * dpr
        capture.shapeData[base + 3] = shape.bounds.width * dpr
        capture.shapeData[base + 4] = shape.bounds.height * dpr
        capture.shapeData[base + 5] = shape.cornerRadius * dpr
        capture.shapeData[base + 6] = shape.bottomCornerRadius * dpr
    }
    val inflate = frame.blend * 0.25f + 3f / dpr
    shader.setFloatUniform("uGeometryOffset", (left - inflate) * dpr, (top - inflate) * dpr)
    shader.setFloatUniform(
        "uGeometrySize",
        (right - left + 2 * inflate) * dpr,
        (bottom - top + 2 * inflate) * dpr,
    )

    val glass = settings.effectiveGlassColor
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
    shader.setFloatUniform("uLightDirection", lightDirX, lightDirY)
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
    shader.setFloatUniform("uTouchPosition", 0f, 0f)
    shader.setFloatUniform("uTouchIntensity", 0f)
    shader.setFloatUniform(
        "uRimConfig",
        settings.effectiveRimShade,
        settings.effectiveRimLight,
        settings.rimShadeEnds,
    )
    shader.setFloatUniform("uLensModel", if (settings.lensModel == GlassLensModel.Paraxial) 1f else 0f)
    shader.setFloatUniform("uBodyShade", settings.effectiveBodyShade.coerceIn(0f, 1f))

    shader.setFloatUniform("uNumShapes", shapes.size.toFloat())
    shader.setFloatUniform("uDpr", dpr)
    shader.setFloatUniform("uBlend", frame.blend * dpr)
    shader.setFloatUniform("uNativeEdge", if (settings.rimShade > 0f) 1f else 0f)
    shader.setFloatUniform("uShapeData", capture.shapeData)

    shader.setFloatUniform("uOpacity", frame.opacity.coerceIn(0f, 1f))

    // GlassShadow.scaled(elevation): an elevation and a contact shadow, light
    // pages only. A BoxShadow's sigma is 0.57735 × its blur radius + 0.5.
    val elevation = settings.effectiveShadowElevation
    if (darkPage || elevation <= 0f) {
        shader.setFloatUniform("uShadowA", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("uShadowB", 0f, 0f, 0f, 0f)
    } else {
        fun sigma(blurRadius: Float) = (blurRadius * 0.57735f + 0.5f) * dpr
        // At 1, GlassShadow.defaults itself: 0x0F and 0x05 black.
        val elevationAlpha = if (elevation == 1f) 15f / 255f else (0.06f * elevation).coerceIn(0f, 1f)
        val contactAlpha = if (elevation == 1f) 5f / 255f else (0.02f * elevation).coerceIn(0f, 1f)
        shader.setFloatUniform(
            "uShadowA",
            elevationAlpha, sigma(8f * elevation), 0f, 2f * elevation * dpr,
        )
        shader.setFloatUniform(
            "uShadowB",
            contactAlpha, sigma(2f * elevation), 0f, 1f * elevation * dpr,
        )
    }

    // What the draw has to cover: the matte, and the elevation shadow's reach
    // (two sigmas past its offset; see the shader's shadowEnd).
    val shadowReach = if (darkPage || elevation <= 0f) 0f else
        ((8f * elevation * 0.57735f + 0.5f) * 2f + 2f * elevation)
    return Rect(
        (left - inflate - shadowReach) * dpr,
        (top - inflate - shadowReach) * dpr,
        (right + inflate + shadowReach) * dpr,
        (bottom + inflate + shadowReach) * dpr,
    )
}

/** Upstream's `lerpDouble`. */
internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

