package dev.lelonio.square.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class EffectPresetTest {
    @Test
    fun required_builtins_are_real_dsp_configurations() {
        val names = BuiltInPresets.map { it.name }.toSet()
        assertTrue("Normal" in names)
        assertTrue("Bass Boost" in names)
        assertTrue("Vocal" in names)
        assertTrue("Rock" in names)
        assertTrue("Classical" in names)
        assertTrue("Night" in names)
        assertTrue("Podcast" in names)
        assertTrue("Slowed + Reverb" in names)
        assertTrue(BuiltInPresets.first { it.name == "Bass Boost" }.dsp.bassBoostDb > 0f)
        assertTrue(BuiltInPresets.first { it.name == "Rock" }.dsp.equalizer.isNotEmpty())
        assertTrue(BuiltInPresets.first { it.name == "Slowed + Reverb" }.dsp.reverb > 0f)
    }

    @Test
    fun preset_json_is_backward_compatible_with_older_shape() {
        val old = """{"id":"old","name":"Old","speed":1.0,"pitch":1.0,"reverbAmount":0.2,"builtIn":false}"""
        val restored = Json { ignoreUnknownKeys = true }
            .decodeFromString<EffectPreset>(old)
        assertEquals(0.2f, restored.reverbAmount)
        assertEquals(AdvancedDspConfig(), restored.dsp)
    }
}
