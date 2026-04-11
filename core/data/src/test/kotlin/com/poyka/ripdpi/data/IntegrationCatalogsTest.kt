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
                      "id": "ru-mobile-tuic",
                      "title": "Russian mobile TUIC",
                      "entryCountry": "RU",
                      "exitCountry": "EU",
                      "antiCorrelationSuggested": true,
                      "routeMode": "direct_for_domestic",
                      "relayKind": "tuic_v5",
                      "udpEnabled": true,
                      "requiresQuic": true
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(1, presets.size)
        assertEquals("ru-mobile-tuic", presets.single().id)
        assertEquals(RelayKindTuicV5, presets.single().relayKind)
        assertTrue(presets.single().antiCorrelationSuggested)
    }

    @Test
    fun `relay preset suggestion activates on russian cellular snapshot`() {
        val presets =
            decodeRelayPresetCatalog(
                """
                {
                  "presets": [
                    { "id": "ru-mobile-tuic", "title": "Russian mobile TUIC", "relayKind": "tuic_v5", "routeMode": "direct_for_domestic", "requiresQuic": true, "requiresUdp": true, "udpEnabled": true },
                    { "id": "ru-mobile-naiveproxy", "title": "Russian mobile NaiveProxy", "relayKind": "naiveproxy", "routeMode": "direct_for_domestic", "requiresNaiveHttpsProxy": true },
                    { "id": "ru-mobile-relay", "title": "Russian mobile relay", "relayKind": "chain_relay", "routeMode": "direct_for_domestic" }
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
                capabilityRecords =
                    listOf(
                        ServerCapabilityRecord(
                            scope = "relay",
                            fingerprintHash = "fp",
                            authority = "relay.example",
                            quicUsable = true,
                            udpUsable = true,
                            multiplexReusable = true,
                        ),
                    ),
            )

        assertNotNull(suggestion)
        assertEquals("ru-mobile-tuic", suggestion?.preset?.id)
    }

    @Test
    fun `relay preset suggestion falls back to naive when quic is unavailable`() {
        val presets =
            decodeRelayPresetCatalog(
                """
                {
                  "presets": [
                    { "id": "ru-mobile-tuic", "title": "Russian mobile TUIC", "relayKind": "tuic_v5", "routeMode": "direct_for_domestic", "requiresQuic": true, "requiresUdp": true, "udpEnabled": true },
                    { "id": "ru-mobile-naiveproxy", "title": "Russian mobile NaiveProxy", "relayKind": "naiveproxy", "routeMode": "direct_for_domestic", "requiresNaiveHttpsProxy": true },
                    { "id": "ru-mobile-relay", "title": "Russian mobile relay", "relayKind": "chain_relay", "routeMode": "direct_for_domestic" }
                  ]
                }
                """.trimIndent(),
            )

        val suggestion =
            suggestRelayPreset(
                snapshot =
                    NativeNetworkSnapshot(
                        transport = "cellular",
                        cellular = NativeCellularSnapshot(operatorCode = "25099", generation = "5g"),
                    ),
                presets = presets,
                capabilityRecords =
                    listOf(
                        ServerCapabilityRecord(
                            scope = "relay",
                            fingerprintHash = "fp",
                            authority = "relay.example",
                            quicUsable = false,
                            udpUsable = false,
                            naiveHttpsProxyAccepted = true,
                            fallbackRequired = true,
                        ),
                    ),
            )

        assertNotNull(suggestion)
        assertEquals("ru-mobile-naiveproxy", suggestion?.preset?.id)
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
