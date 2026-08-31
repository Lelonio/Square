package dev.lelonio.square.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class AdvancedDspTest {
    @Test
    fun config_roundTrips_without_losing_effects() {
        val config = AdvancedDspConfig(
            enabled = true,
            gainDb = -3f,
            bassBoostDb = 4f,
            equalizer = listOf(EqualizerBand(80f, 3f), EqualizerBand(2500f, -2f)),
            virtualizer = 0.5f,
            reverb = 0.4f,
            normalizationGainDb = -1.5f,
            limiterEnabled = true,
        )

        val restored = Json.decodeFromString<AdvancedDspConfig>(Json.encodeToString(config))
        assertEquals(config, restored)
    }

    @Test
    fun effect_ranges_are_bounded() {
        assertFailsWith<IllegalArgumentException> { EqualizerBand(0f, 0f) }
        assertFailsWith<IllegalArgumentException> { EqualizerBand(1000f, 13f) }
        assertFailsWith<IllegalArgumentException> { AdvancedDspConfig(gainDb = 13f) }
        assertFailsWith<IllegalArgumentException> { AdvancedDspConfig(bassBoostDb = 10f) }
        assertFailsWith<IllegalArgumentException> { AdvancedDspConfig(virtualizer = 1.1f) }
        assertFailsWith<IllegalArgumentException> { AdvancedDspConfig(reverb = -0.1f) }
        assertTrue(AdvancedDspConfig(normalizationGainDb = null).limiterEnabled)
    }
}
