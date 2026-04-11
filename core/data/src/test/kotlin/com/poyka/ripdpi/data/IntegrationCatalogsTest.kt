package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationCatalogsTest {
    @Test
    fun `relay preset catalog decodes russian mobile preset`() {
        val presets =
            decodeRelayPresetCatalog(
                """
                {
                  "presets": [
                    {
                      "id": "ru-mobile-relay",
                      "title": "Russian mobile relay",
                      "entryCountry": "RU",
                      "exitCountry": "EU",
                      "antiCorrelationSuggested": true,
                      "routeMode": "direct_for_domestic"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(1, presets.size)
        assertEquals("ru-mobile-relay", presets.single().id)
        assertTrue(presets.single().antiCorrelationSuggested)
    }

    @Test
    fun `relay preset suggestion activates on russian cellular snapshot`() {
        val presets =
            decodeRelayPresetCatalog(
                """
                {
                  "presets": [
                    { "id": "ru-mobile-relay", "title": "Russian mobile relay" }
                  ]
                }
                """.trimIndent(),
            )

        val suggestion =
            suggestRelayPreset(
                NativeNetworkSnapshot(
                    transport = "cellular",
                    cellular = NativeCellularSnapshot(operatorCode = "25001", generation = "4g"),
                ),
                presets,
            )

        assertNotNull(suggestion)
        assertEquals("ru-mobile-relay", suggestion?.preset?.id)
    }

    @Test
    fun `warp payloadgen presets decode and resolve`() {
        val presets = builtInWarpPayloadGenPresets()

        assertTrue(presets.any { it.id == WarpAmneziaPresetQuicImitation })
        assertTrue(presets.any { it.id == WarpAmneziaPresetTlsImitation })
        assertTrue(presets.any { it.id == WarpAmneziaPresetDnsImitation })

        val profile = resolveWarpAmneziaProfile(WarpAmneziaPresetTlsImitation, WarpAmneziaSettings())
        assertEquals(WarpAmneziaPresetTlsImitation, profile.preset)
        assertEquals(2, profile.settings.jc)
        assertEquals(24, profile.settings.jmin)
        assertEquals(96, profile.settings.jmax)
    }

    @Test
    fun `warp payloadgen suggestion prefers quic imitation on russian cellular`() {
        val suggestion =
            suggestWarpPayloadGenPreset(
                NativeNetworkSnapshot(
                    transport = "cellular",
                    cellular = NativeCellularSnapshot(operatorCode = "25020", generation = "5g"),
                ),
                builtInWarpPayloadGenPresets(),
            )

        assertNotNull(suggestion)
        assertEquals(WarpAmneziaPresetQuicImitation, suggestion?.preset?.id)
    }
}
