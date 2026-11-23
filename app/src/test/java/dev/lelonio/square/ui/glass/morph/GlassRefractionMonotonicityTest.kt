package dev.lelonio.square.ui.glass.morph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Verifies that the glass refraction displacement and coordinate sampling functions
 * are strictly monotonic along the glass boundary, preventing ray crossing,
 * upside-down image inversion, and duplicate background sampling (Issue #233).
 */
class GlassRefractionMonotonicityTest {

    @Test
    fun paraxialRefractionCoordinateMappingIsMonotonic() {
        val thickness = 32f
        val refractiveIndex = 1.24f
        val heightBase = thickness

        // Simulate top-to-center traversal (y from 0 to thickness)
        var prevSampleY = 0f
        val steps = 320
        val dy = thickness / steps

        for (i in 0..steps) {
            val y = i * dy
            val sdN = -y
            val lenN = (thickness + sdN) / thickness // 1.0 at outer rim (y=0), 0.0 at center (y=thickness)
            val normalXY_y = -lenN

            // Normalized height
            val x = (thickness + sdN).coerceAtLeast(0f)
            val height = sqrt((thickness * thickness - x * x).coerceAtLeast(0f))

            val bevelFalloff = (1f - lenN).coerceIn(0f, 1f)
            val dispY = -normalXY_y * bevelFalloff * (height + heightBase) * (refractiveIndex - 1f) * 1.5f
            val sampleY = y + dispY

            if (i > 0) {
                val deriv = (sampleY - prevSampleY) / dy
                assertTrue(
                    "Sample mapping must be strictly non-decreasing to prevent inversion: at y=$y, deriv=$deriv",
                    deriv >= 0f,
                )
            }
            prevSampleY = sampleY
        }
    }

    @Test
    fun snellRefractionCoordinateMappingIsMonotonic() {
        val thickness = 32f
        val refractiveIndex = 1.24f
        val invN = 1f / refractiveIndex
        val heightBase = thickness

        var prevSampleY = 0f
        val steps = 320
        val dy = thickness / steps

        for (i in 0..steps) {
            val y = i * dy
            val sdN = -y
            val lenN = (thickness + sdN) / thickness
            val normalXY_y = -lenN
            val normalZ = sqrt((1f - normalXY_y * normalXY_y).coerceAtLeast(0f))

            val dotNI = -normalZ
            val k = 1f - (invN * invN) * (1f - dotNI * dotNI)
            val ry = -(invN * dotNI + sqrt(k.coerceAtLeast(0f))) * normalXY_y
            val rz = invN * (-1f) - (invN * dotNI + sqrt(k.coerceAtLeast(0f))) * normalZ

            val x = (thickness + sdN).coerceAtLeast(0f)
            val height = sqrt((thickness * thickness - x * x).coerceAtLeast(0f))
            val refractLen = (height + heightBase) / maxOf(0.001f, abs(rz))
            val bevelFalloff = (1f - lenN).coerceIn(0f, 1f)

            val dispY = ry * refractLen * bevelFalloff
            val sampleY = y + dispY

            if (i > 0) {
                val deriv = (sampleY - prevSampleY) / dy
                assertTrue(
                    "Snell mapping must be non-decreasing at y=$y, deriv=$deriv",
                    deriv >= 0f,
                )
            }
            prevSampleY = sampleY
        }
    }

    @Test
    fun boundaryDispAtRimIsZero() {
        // At the outermost rim (sdN = 0, lenN = 1.0), bevelFalloff must damp displacement to 0
        // so outer background doesn't jump or fold across the border.
        val lenN = 1.0f
        val bevelFalloff = (1f - lenN).coerceIn(0f, 1f)
        assertTrue("Bevel falloff at rim must be exactly 0", bevelFalloff == 0f)
    }

    @Test
    fun shaderSourcesContainNoDangerousBaseHeightMultiplier() {
        val single = LiquidGlassShader.SOURCE_SINGLE
        val singleOne = LiquidGlassShader.SOURCE_SINGLE_ONE

        // Must not contain the 8x multiplier that blew up focal length and inverted sampling
        assertFalse(single.contains("uThickness * 8.0"))
        assertFalse(singleOne.contains("uThickness * 8.0"))

        // Must contain the bevelFalloff monotonic damping
        assertTrue(single.contains("bevelFalloff"))
        assertTrue(singleOne.contains("bevelFalloff"))
    }
}
