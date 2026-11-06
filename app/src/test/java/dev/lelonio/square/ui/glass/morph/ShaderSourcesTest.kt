package dev.lelonio.square.ui.glass.morph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The programs the glass compiles, written out to build/shader-dump so they
 * can be put through a phone's own SkSL compiler; and checked for what the
 * single-input variants must not carry.
 */
class ShaderSourcesTest {
    private val programs = mapOf(
        "single" to LiquidGlassShader.SOURCE_SINGLE,
        "one" to LiquidGlassShader.SOURCE_SINGLE_ONE,
        "rows" to LiquidGlassShader.SOURCE_ROWS,
    )

    @Test
    fun singleInputProgramsCarryNoClipOrOtherShapes() {
        programs.forEach { (name, source) ->
            assertFalse("$name reads two backdrops", source.contains("sharpBackdrop"))
            assertFalse("$name still draws squircles", source.contains("sdfSquircleAsym(p - center"))
            assertTrue("$name lost texelAt", source.contains("float3 texelAt(float2 p)") || name == "rows")
        }
        assertFalse(LiquidGlassShader.SOURCE_SINGLE.contains("float centre = clipSDF"))
        assertTrue(LiquidGlassShader.SOURCE_ROWS.trimEnd().endsWith("}"))
    }

    @Test
    fun dump() {
        // Under the module's build directory, the tests' working directory.
        val dir = File("build/shader-dump")
        dir.mkdirs()
        programs.forEach { (name, source) -> File(dir, "$name.sksl").writeText(source) }
    }
}
