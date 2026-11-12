// Ported line for line from liquid_glass_widgets, MIT:
//
//   lib/utils/liquid_morph_physics.dart
//   lib/utils/glass_morph_controller.dart
//   https://github.com/sdegenaar/liquid_glass_widgets (commit 59118973)
//
//   Copyright (c) 2024–2026 Sebastian Degenaar; engine derived from
//   liquid_glass_renderer, Copyright 2025 Tim Lehmann for whynotmake.it
//
// See LICENSE-liquid-glass.txt beside this file. Constants, curves and the
// order of every step are upstream's; only the language changed. Where a
// Flutter curve is named, its exact cubic is reproduced below.

package dev.lelonio.square.ui.glass.morph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Upstream's `MorphPhase`. */
enum class MorphPhase { Idle, Detaching, Travelling, Arriving, Settled }

/** Upstream's `LiquidMorphState`: everything one frame of a morph needs. */
data class LiquidMorphState(
    val pathT: Float,
    val sizeT: Float,
    val currentDx: Float,
    val currentDy: Float,
    val pushDx: Float,
    val pushDy: Float,
    val anchorScale: Float,
    val blend: Float,
    val containerScale: Float,
    val phase: MorphPhase,
)

/** A spring as upstream describes it: mass, stiffness, damping. */
data class SpringDescription(val mass: Float, val stiffness: Float, val damping: Float) {
    /** Compose's parameters for the same spring. */
    val dampingRatio: Float get() = damping / (2f * sqrt(stiffness * mass))
}

object LiquidMorphPhysics {

    val openSpring = SpringDescription(mass = 1f, stiffness = 125f, damping = 17.5f)
    val closeSpring = SpringDescription(mass = 1f, stiffness = 190f, damping = 22.5f)
    const val closeVelocityHint = -2.6f

    private const val backOutAmplitude = 2.0f
    private const val minBackOutAmplitude = 0.7f
    private const val adaptiveThresholdDy = 100f
    private const val anchorEaseDuration = 0.4f
    private const val blendMultiplier = 150f
    private const val maxBlend = 28f
    private const val closeProximityThreshold = 0.6f
    private const val maxPushTravelPx = 80f

    fun computeScaleDelta(sourceW: Float, sourceH: Float, targetW: Float, targetH: Float): Float {
        val sourceArea = sourceW * sourceH
        val targetArea = targetW * targetH
        if (sourceArea <= 0f) return 1f
        return sqrt(targetArea / sourceArea)
    }

    fun computeAdaptiveBackOutAmplitude(finalDy: Float, baseAmplitude: Float = backOutAmplitude): Float {
        val absDy = abs(finalDy)
        if (absDy <= adaptiveThresholdDy) return baseAmplitude
        return (baseAmplitude * adaptiveThresholdDy / absDy).coerceIn(minBackOutAmplitude, baseAmplitude)
    }

    fun computeBlendAttenuation(scaleDelta: Float): Float {
        if (scaleDelta <= 3f) return 1f
        return (3f / scaleDelta).coerceIn(0.2f, 1f)
    }

    fun compute(
        rawValue: Float,
        finalDx: Float,
        finalDy: Float,
        horizontalOffset: Float = 0f,
        verticalOffset: Float = 0f,
        scaleDelta: Float = 1f,
        adaptiveDamping: Boolean? = null,
        isClosing: Boolean = false,
    ): LiquidMorphState {
        val clampedValue = rawValue.coerceIn(0f, 1f)
        val closeUndershoot = if (rawValue < 0f) rawValue else 0f
        val adaptive = adaptiveDamping ?: (scaleDelta > 1.5f)
        val amplitude = if (adaptive) computeAdaptiveBackOutAmplitude(finalDy) else backOutAmplitude

        val pathT = if (isClosing) {
            clampedValue + closeUndershoot
        } else {
            backOut(amplitude, clampedValue) + closeUndershoot
        }

        val rawSizeT = when {
            isClosing -> clampedValue.pow(1.4f)
            adaptive && scaleDelta > 3f -> smootherStep(clampedValue)
            else -> FlutterCurves.linearToEaseOut(clampedValue)
        }
        val sizeT = rawSizeT + closeUndershoot

        val pushFactor = if (adaptive) {
            val travelDx = abs(finalDx + horizontalOffset)
            val travelDy = abs(finalDy + verticalOffset)
            val travelMag = sqrt(travelDx * travelDx + travelDy * travelDy)
            if (travelMag > 0f) (maxPushTravelPx / travelMag).coerceIn(0f, 1f) else 1f
        } else {
            1f
        }
        val pushDx = if (rawValue < 0f) (finalDx + horizontalOffset) * rawValue * pushFactor else 0f
        val pushDy = if (rawValue < 0f) (finalDy + verticalOffset) * rawValue * pushFactor else 0f

        val currentDx = finalDx * pathT
        val currentDy = finalDy * pathT

        val anchorScale = if (isClosing) {
            1f - smootherStep((clampedValue - 0.45f) / 0.40f)
        } else {
            1f - smootherStep(clampedValue / anchorEaseDuration)
        }

        val blendAttenuation = computeBlendAttenuation(scaleDelta)
        val blend = if (isClosing) {
            val proximityT = (1f - clampedValue / closeProximityThreshold).coerceIn(0f, 1f)
            val eased = FlutterCurves.easeOut(proximityT)
            val settle = smootherStep(clampedValue / 0.15f)
            (eased * maxBlend * blendAttenuation * settle).coerceIn(0f, maxBlend)
        } else {
            val separation = abs(pathT - sizeT)
            val neck = (separation * blendMultiplier * blendAttenuation).coerceIn(0f, maxBlend)
            maxBlend * smootherStep(neck / maxBlend)
        }

        val containerScale = when {
            rawValue > 1f -> 1f + (rawValue - 1f) * 0.10f
            rawValue < 0f -> 1f + rawValue * 0.55f
            else -> 1f
        }

        return LiquidMorphState(
            pathT = pathT,
            sizeT = sizeT,
            currentDx = currentDx,
            currentDy = currentDy,
            pushDx = pushDx,
            pushDy = pushDy,
            anchorScale = anchorScale,
            blend = blend,
            containerScale = containerScale,
            phase = derivePhase(rawValue, clampedValue),
        )
    }

    private fun derivePhase(rawValue: Float, clampedValue: Float): MorphPhase = when {
        rawValue < 0f -> MorphPhase.Detaching
        clampedValue < 0.001f -> MorphPhase.Idle
        clampedValue < 0.4f -> MorphPhase.Detaching
        clampedValue < 0.8f -> MorphPhase.Travelling
        clampedValue < 0.999f -> MorphPhase.Arriving
        else -> MorphPhase.Settled
    }

    private fun smootherStep(t: Float): Float {
        val tc = t.coerceIn(0f, 1f)
        return tc * tc * tc * (tc * (tc * 6f - 15f) + 10f)
    }

    /** Upstream's `_BackOutCurve.transformInternal`. */
    private fun backOut(amplitude: Float, t0: Float): Float {
        val t = t0 - 1f
        return t * t * ((amplitude + 1f) * t + amplitude) + 1f
    }
}

/**
 * Flutter's curves, as Flutter defines them.
 *
 * A [Cubic] in Flutter is a CSS-style Bézier through (0,0) and (1,1), solved
 * for x by bisection to within 0.001 — reproduced here with the same
 * tolerance, so the values agree with Flutter's.
 */
object FlutterCurves {
    fun linearToEaseOut(t: Float) = cubic(0.35f, 0.91f, 0.33f, 0.97f, t)
    fun easeOut(t: Float) = cubic(0.0f, 0.0f, 0.58f, 1.0f, t)
    fun easeInExpo(t: Float) = cubic(0.95f, 0.05f, 0.795f, 0.035f, t)

    private fun evaluate(a: Float, b: Float, m: Float): Float =
        3f * a * (1f - m) * (1f - m) * m + 3f * b * (1f - m) * m * m + m * m * m

    private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        if (t == 0f || t == 1f) return t
        var start = 0f
        var end = 1f
        while (true) {
            val midpoint = (start + end) / 2f
            val estimate = evaluate(a, c, midpoint)
            if (abs(t - estimate) < 0.001f) return evaluate(b, d, midpoint)
            if (estimate < t) start = midpoint else end = midpoint
        }
    }
}

/**
 * Upstream's `GlassMorphController`, on a Compose [Animatable].
 *
 * The spring is the same spring: Compose takes it as a damping ratio and a
 * stiffness, and with a mass of 1 those are exactly upstream's damping and
 * stiffness. Opening starts from rest, closing with upstream's push, each from
 * wherever the value is, as upstream's `animateWith` does.
 */
@Stable
class GlassMorphController internal constructor() {
    internal val spring = Animatable(0f)

    var isClosing by mutableStateOf(false)
        internal set
    var hasHandedOff by mutableStateOf(false)
        internal set
    internal var settled by mutableStateOf(true)

    val value: Float get() = spring.value
    val velocity: Float get() = spring.velocity

    /** Upstream's `isShowing`: until the closing spring has come to rest. */
    val isShowing: Boolean get() = spring.value > 0.001f || (isClosing && !settled)

    fun computeState(
        finalDx: Float,
        finalDy: Float,
        horizontalOffset: Float = 0f,
        verticalOffset: Float = 0f,
        scaleDelta: Float = 1f,
        adaptiveDamping: Boolean? = null,
    ): LiquidMorphState = LiquidMorphPhysics.compute(
        rawValue = spring.value,
        finalDx = finalDx,
        finalDy = finalDy,
        horizontalOffset = horizontalOffset,
        verticalOffset = verticalOffset,
        scaleDelta = scaleDelta,
        adaptiveDamping = adaptiveDamping,
        isClosing = isClosing,
    )
}

@Composable
fun rememberGlassMorphController(open: Boolean, disableAnimations: Boolean = false): GlassMorphController {
    val morph = remember { GlassMorphController() }
    LaunchedEffect(open) {
        val description = when {
            disableAnimations -> SpringDescription(mass = 1f, stiffness = 500f, damping = 32.4f)
            open -> LiquidMorphPhysics.openSpring
            else -> LiquidMorphPhysics.closeSpring
        }
        if (open) {
            morph.isClosing = false
            morph.hasHandedOff = false
        } else {
            if (morph.spring.value <= 0.001f && morph.settled) return@LaunchedEffect
            morph.isClosing = true
        }
        morph.settled = false
        morph.spring.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = spring(
                dampingRatio = description.dampingRatio,
                stiffness = description.stiffness,
                visibilityThreshold = 0.001f,
            ),
            initialVelocity = if (open) 0f else LiquidMorphPhysics.closeVelocityHint,
        ) {
            if (morph.isClosing && value <= 0f && !morph.hasHandedOff) morph.hasHandedOff = true
        }
        morph.settled = true
    }
    return morph
}
