// Ported line for line from liquid_glass_widgets, MIT:
//
//   lib/utils/glass_spring.dart
//   lib/src/engine/glass_drag_builder.dart
//   lib/src/engine/stretch.dart
//   lib/src/engine/glass_glow.dart
//   lib/widgets/interactive/glass_button.dart (the ambient lift)
//   https://github.com/sdegenaar/liquid_glass_widgets (commit 5911897)
//
//   Copyright (c) 2024–2026 Sebastian Degenaar; engine derived from
//   liquid_glass_renderer, Copyright 2024-2025 Tim Lehmann for whynotmake.it
//
// See morph/LICENSE-liquid-glass.txt. Constants, springs and the order of
// every step are upstream's; only the language changed. Upstream's logical
// pixels are dp here.
//
// What a pressed glass control does upstream, all in one place so every
// control that presses the same way (a button, an icon button, a chip) shares
// it: it inflates by 17 dp along its longest side on a snappy spring and
// undershoots once on release; it leans a little towards a dragging finger,
// stretched from its far edge; a soft light follows the finger across it and
// lights the rim nearest it; and its whole surface brightens while it is held.

package dev.lelonio.square.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Upstream's `GlassSpring` presets, which are SwiftUI's: a spring named by how
 * long it takes and how much it bounces. Flutter's `withDurationAndBounce` is
 * stiffness (2π / duration)² at unit mass, and a damping ratio of 1 − bounce
 * (or 1 / (1 + bounce) for a negative one).
 */
object GlassSpring {
    fun <T> of(durationMs: Int, bounce: Float, visibilityThreshold: T? = null): SpringSpec<T> {
        val seconds = durationMs / 1000f
        val omega = 2f * PI.toFloat() / seconds
        val ratio = if (bounce >= 0f) 1f - bounce else 1f / (1f + bounce)
        return spring(dampingRatio = ratio, stiffness = omega * omega, visibilityThreshold = visibilityThreshold)
    }

    fun <T> bouncy(durationMs: Int = 500, extraBounce: Float = 0f, visibilityThreshold: T? = null) =
        of(durationMs, 0.3f + extraBounce, visibilityThreshold)

    fun <T> snappy(durationMs: Int = 500, extraBounce: Float = 0f, visibilityThreshold: T? = null) =
        of(durationMs, 0.15f + extraBounce, visibilityThreshold)

    fun <T> smooth(durationMs: Int = 500, extraBounce: Float = 0f, visibilityThreshold: T? = null) =
        of(durationMs, 0f + extraBounce, visibilityThreshold)

    fun <T> interactive(durationMs: Int = 150, extraBounce: Float = 0f, visibilityThreshold: T? = null) =
        of(durationMs, 0.14f + extraBounce, visibilityThreshold)
}

/** Flutter's `Curves.easeOut`. */
private val FlutterEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Upstream's `AnchorStretchSettings`. */
data class AnchorStretchSettings(
    val intensity: Float = 0.5f,
    val squashFactor: Float = 0.3f,
    val translationDamping: Float = 0.15f,
    val bounciness: Float = 0.15f,
) {
    companion object {
        /** What a GlassButton leans with by default: a tremor, not a pull. */
        val NativeTremor = AnchorStretchSettings(
            intensity = 0.1f,
            squashFactor = 0.1f,
            translationDamping = 0.1f,
            bounciness = 0f,
        )
    }
}

/** Upstream's `GlassDefaults` that a pressed control reads. */
object GlassPressDefaults {
    /** `LiquidStretch.nativePressGrowth`: the longest side grows by this much. */
    const val NativePressGrowthDp = 17f
    /**
     * LOCAL CHANGE: upstream lifts a held button by 0.3 white on a light page.
     * Over this app's light film, which is already near white, that turned a
     * held button into a plain white disc. The same lift a dark page gets is
     * enough to say it is held.
     */
    const val AmbientBaseLight = 0.14f
    const val AmbientBaseLightDark = 0.14f
    const val AmbientLiftMs = 150
    const val AmbientLiftReverseMs = 60

    /** GlassGlowLayer's touch deadband and GlassDragBuilder's slop, in dp. */
    const val TouchSlopDp = 3f

    /** The native specular sheen: ~10 % white on light pages, ~7 % on dark. */
    val GlowLight = Color(0x1AFFFFFF)
    val GlowDark = Color(0x12FFFFFF)
    const val GlowRadius = 1.6f
    const val GlowBlurSigmaDp = 16f
}

/**
 * Everything a pressed glass control animates: upstream's LiquidStretch (the
 * inflation and the lean), GlassGlowLayer (the light under the finger) and
 * GlassButton's ambient lift.
 *
 * The press is read from the raw pointer stream, as upstream's `Listener`
 * reads it: a press lasts as long as the finger is down, wherever it goes,
 * and a list scrolling under the finger does not end it.
 */
@Stable
class GlassPressState internal constructor(
    private val scope: CoroutineScope,
    private val density: Float,
) {
    /** Null: a fixed [interactionScale] instead of the native growth. */
    var pressGrowthDp: Float? = GlassPressDefaults.NativePressGrowthDp
    var interactionScale: Float = 1f
    var stretch: Float = 0.5f
    var resistance: Float = 0.01f
    var anchorStretch: Boolean = true
    var anchor: AnchorStretchSettings = AnchorStretchSettings.NativeTremor
    var enabled: Boolean = true

    var pressed by mutableStateOf(false)
        private set

    private val press = Animatable(0f)
    private val drag = Animatable(Offset.Zero, Offset.VectorConverter)
    private val lift = Animatable(0f)
    private val glowAlpha = Animatable(0f)
    private val glowRadius = Animatable(1.2f)
    private var glowPosition by mutableStateOf(Offset.Zero)

    private var size = Size.Zero
    private var raw = Offset.Zero
    private var engaged = false

    /** Where the finger is on the surface, in its own pixels. */
    val touchPosition: Offset get() = glowPosition

    /** How lit the surface is by the finger: upstream's touch intensity. */
    val touchIntensity: Float get() = glowAlpha.value.coerceIn(0f, 1f)

    /** The ambient lift, eased: 0 at rest, 1 held. */
    val liftProgress: Float get() = FlutterEaseOut.transform(lift.value.coerceIn(0f, 1f))

    private fun onDown(position: Offset) {
        if (!enabled) return
        pressed = true
        raw = Offset.Zero
        engaged = false
        val growth = pressGrowthDp != null
        scope.launch {
            press.animateTo(1f, if (growth) GlassSpring.snappy(250, 0.1f) else GlassSpring.smooth(300))
        }
        scope.launch { drag.animateTo(Offset.Zero, dragSpring(true)) }
        scope.launch { lift.animateTo(1f, tween(GlassPressDefaults.AmbientLiftMs, easing = LinearEasing)) }
        // The light lands where the finger does, then fades in there.
        glowPosition = clampToSize(toLocal(position))
        scope.launch { glowAlpha.animateTo(1f, GlassSpring.interactive()) }
        scope.launch { glowRadius.animateTo(1f, GlassSpring.interactive()) }
    }

    private fun onMove(deltaPx: Offset, position: Offset) {
        if (!pressed) return
        raw += deltaPx / density
        if (!engaged) {
            if (raw.getDistanceSquared() >= SLOP * SLOP) engaged = true else return
        }
        val distance = raw.getDistance()
        val exposed = if (distance <= SLOP) Offset.Zero else raw * (1f - SLOP / distance)
        val target = exposed.withResistance(resistance)
        scope.launch { drag.animateTo(target, dragSpring(true)) }

        val local = clampToSize(toLocal(position))
        val slopPx = SLOP * density
        if ((local - glowPosition).getDistanceSquared() >= slopPx * slopPx) {
            glowPosition = local
        }
    }

    private fun onUp() {
        if (!pressed) return
        pressed = false
        val growth = pressGrowthDp != null
        scope.launch {
            press.animateTo(0f, if (growth) GlassSpring.bouncy(280, 0.15f) else GlassSpring.smooth(300))
        }
        scope.launch { drag.animateTo(Offset.Zero, dragSpring(false)) }
        scope.launch { lift.animateTo(0f, tween(GlassPressDefaults.AmbientLiftReverseMs, easing = LinearEasing)) }
        scope.launch { glowAlpha.animateTo(0f, GlassSpring.smooth()) }
        scope.launch { glowRadius.animateTo(1.2f, GlassSpring.smooth()) }
    }

    private fun dragSpring(dragging: Boolean): SpringSpec<Offset> = when {
        !dragging -> GlassSpring.bouncy(extraBounce = anchor.bounciness)
        anchor.bounciness == 0f -> GlassSpring.smooth(150)
        else -> GlassSpring.interactive(extraBounce = anchor.bounciness - 0.14f)
    }

    private fun clampToSize(p: Offset) = Offset(
        p.x.coerceIn(0f, size.width.coerceAtLeast(0f)),
        p.y.coerceIn(0f, size.height.coerceAtLeast(0f)),
    )

    /**
     * The pointer is read outside the transform, the light is drawn inside
     * it: upstream's GlassGlow sits under the stretch and sees the finger in
     * the stretched surface's own coordinates.
     */
    private fun toLocal(p: Offset): Offset {
        val t = transform(size)
        return Offset(
            if (t.scaleX != 0f) (p.x - t.translationX) / t.scaleX else p.x,
            if (t.scaleY != 0f) (p.y - t.translationY) / t.scaleY else p.y,
        )
    }

    private class Affine(val scaleX: Float, val scaleY: Float, val translationX: Float, val translationY: Float)

    /**
     * RenderRawLiquidStretch's matrix, which is affine and axis-aligned, so
     * it folds into one scale and one translation about the top-left corner.
     */
    private fun transform(size: Size): Affine {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Affine(1f, 1f, 0f, 0f)

        val growth = pressGrowthDp
        val pressScale = if (growth != null) {
            1f + press.value * growth * density / max(w, h)
        } else {
            // A fixed factor: upstream springs the scale itself from 1 to it.
            1f + press.value * (interactionScale - 1f)
        }

        val stretchPx = drag.value * (stretch * density)
        var sx = 1f
        var sy = 1f
        var pivotX = w / 2f
        var pivotY = h / 2f
        var dx = 0f
        var dy = 0f
        if (stretchPx != Offset.Zero) {
            if (anchorStretch) {
                val absDx = abs(stretchPx.x)
                val absDy = abs(stretchPx.y)
                if (absDx > 0.001f || absDy > 0.001f) {
                    val refDim = max(w, h)
                    val relativeX = absDx / refDim
                    val relativeY = absDy / refDim
                    val aspectRatio = w / h
                    val xDamping = if (aspectRatio > 1f) 1f / aspectRatio else 1f
                    val yDamping = if (aspectRatio < 1f) aspectRatio else 1f
                    val stretchX = 1f + (relativeX * anchor.intensity * xDamping).coerceIn(0f, 0.3f)
                    val stretchY = 1f + (relativeY * anchor.intensity * yDamping).coerceIn(0f, 0.3f)
                    val squashX = 1f - (relativeY * anchor.squashFactor * xDamping).coerceIn(0f, 0.15f)
                    val squashY = 1f - (relativeX * anchor.squashFactor * yDamping).coerceIn(0f, 0.15f)
                    sx = (stretchX * squashX).coerceAtLeast(0.01f)
                    sy = (stretchY * squashY).coerceAtLeast(0.01f)
                    val edge = 20f * density
                    pivotX = w * (0.5f - signedSmoothStep(stretchPx.x, edge) * 0.5f)
                    pivotY = h * (0.5f - signedSmoothStep(stretchPx.y, edge) * 0.5f)
                    if (anchor.translationDamping > 0f) {
                        dx = stretchPx.x * anchor.translationDamping * xDamping
                        dy = stretchPx.y * anchor.translationDamping * yDamping
                    }
                }
            } else {
                val scale = volumeScale(stretchPx, w, h)
                sx = scale.x
                sy = scale.y
                dx = stretchPx.x
                dy = stretchPx.y
            }
        }

        // x' = c + p·(pivot + s·(x + d − pivot) − c)
        val cx = w / 2f
        val cy = h / 2f
        return Affine(
            scaleX = pressScale * sx,
            scaleY = pressScale * sy,
            translationX = pressScale * (sx * (dx - pivotX) + pivotX - cx) + cx,
            translationY = pressScale * (sy * (dy - pivotY) + pivotY - cy) + cy,
        )
    }

    /**
     * The surface's outline as the stretch leaves it, in its untransformed
     * coordinates: what a blend group draws in its place. Reads the springs,
     * so a group drawing it follows the press frame by frame.
     */
    fun stretchedBounds(): androidx.compose.ui.geometry.Rect {
        val t = transform(size)
        return androidx.compose.ui.geometry.Rect(
            t.translationX,
            t.translationY,
            t.translationX + t.scaleX * size.width,
            t.translationY + t.scaleY * size.height,
        )
    }

    /** A point on the stretched surface, back in its untransformed coordinates. */
    fun mapToParent(p: Offset): Offset {
        val t = transform(size)
        return Offset(t.scaleX * p.x + t.translationX, t.scaleY * p.y + t.translationY)
    }

    /** For the control's layerBlock: the stretch, applied to the whole surface. */
    fun applyTo(scope: GraphicsLayerScope) {
        size = scope.size
        val t = transform(scope.size)
        scope.transformOrigin = TransformOrigin(0f, 0f)
        scope.scaleX = t.scaleX
        scope.scaleY = t.scaleY
        scope.translationX = t.translationX
        scope.translationY = t.translationY
    }

    /** The raw pointer stream, read before anything below can consume it. */
    val gestureModifier: Modifier = Modifier
        .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(this) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onDown(down.position)
                val id = down.id
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - change.previousPosition
                        if (delta != Offset.Zero) onMove(delta, change.position)
                    }
                } finally {
                    onUp()
                }
            }
        }

    /**
     * GlassButton's ambient lift: the even brightening a held button keeps
     * wherever the finger goes, under the label so the label stays crisp.
     * Drawn as part of the surface, which is clipped to the control's shape.
     */
    fun DrawScope.drawAmbientLift(light: Boolean, baseLight: Float? = null) {
        val opacity = liftProgress * (baseLight ?: if (light) GlassPressDefaults.AmbientBaseLight else GlassPressDefaults.AmbientBaseLightDark)
        if (opacity <= 0f) return
        drawRect(Color.White.copy(alpha = opacity.coerceIn(0f, 1f)))
    }

    /**
     * GlassGlowLayer's finger glare: a radial light following the touch,
     * added over the content, inside the surface's clip.
     */
    fun DrawScope.drawGlow(color: Color, radius: Float = GlassPressDefaults.GlowRadius, blurSigmaDp: Float = GlassPressDefaults.GlowBlurSigmaDp) {
        val alpha = color.alpha * glowAlpha.value
        if (alpha <= 0f || radius <= 0f) return
        val r = radius * glowRadius.value * min(size.width, size.height)
        if (r <= 0f) return
        val c = color.copy(alpha = alpha.coerceIn(0f, 1f))
        val center = glowPosition
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.shader = android.graphics.RadialGradient(
                center.x,
                center.y,
                r,
                intArrayOf(c.toArgb(), c.copy(alpha = 0f).toArgb()),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            if (blurSigmaDp > 0f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                paint.maskFilter = android.graphics.BlurMaskFilter(
                    dev.lelonio.square.ui.glass.morph.blurRadiusForSigma(blurSigmaDp * density),
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
            canvas.nativeCanvas.drawCircle(center.x, center.y, r, paint)
        }
    }

    /** [drawGlow] over a control's content, as a modifier inside its surface. */
    fun glowModifier(
        color: Color,
        radius: Float = GlassPressDefaults.GlowRadius,
        blurSigmaDp: Float = GlassPressDefaults.GlowBlurSigmaDp,
    ): Modifier = Modifier.drawWithContent {
        drawContent()
        drawGlow(color, radius, blurSigmaDp)
    }

    private companion object {
        const val SLOP = GlassPressDefaults.TouchSlopDp

        fun signedSmoothStep(x: Float, edge: Float): Float {
            val t = (x / edge).coerceIn(-1f, 1f)
            val a = abs(t)
            val smooth = a * a * (3f - 2f * a)
            return if (t >= 0f) smooth else -smooth
        }

        fun Offset.withResistance(resistance: Float): Offset {
            if (resistance == 0f) return this
            val magnitude = getDistance()
            if (magnitude == 0f) return Offset.Zero
            val resisted = magnitude / (1f + magnitude * resistance)
            return this * (resisted / magnitude)
        }

        /** RenderRawLiquidStretch.getScale: a stretch that keeps its volume. */
        fun volumeScale(stretchPx: Offset, w: Float, h: Float): Offset {
            val relativeX = abs(stretchPx.x) / w
            val relativeY = abs(stretchPx.y) / h
            val baseX = 1f + relativeX
            val baseY = 1f + relativeY
            val magnitude = sqrt(relativeX * relativeX + relativeY * relativeY)
            val targetVolume = 1f + magnitude * 0.5f
            val correction = sqrt(targetVolume / (baseX * baseY))
            return Offset(
                (baseX * correction).coerceAtLeast(0.01f),
                (baseY * correction).coerceAtLeast(0.01f),
            )
        }
    }
}

@Composable
fun rememberGlassPressState(): GlassPressState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(scope, density) { GlassPressState(scope, density) }
}

/** The native glow colour for the page the control sits on. */
fun glassGlowColor(lightPage: Boolean): Color =
    if (lightPage) GlassPressDefaults.GlowLight else GlassPressDefaults.GlowDark
