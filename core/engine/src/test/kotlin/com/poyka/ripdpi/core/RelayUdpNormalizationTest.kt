package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessFlowVision
import com.poyka.ripdpi.data.RelayVlessFlowVisionUdp443
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayUdpNormalizationTest {
    @Test
    fun `runtime projection preserves all seven UDP kinds in typed and remembered JSON preferences`() {
        listOf(
            RelayKindVlessReality,
            RelayKindHysteria2,
            RelayKindMasque,
            RelayKindAnyTls,
            RelayKindShadowsocks,
            RelayKindTrojan,
            RelayKindTuicV5,
        ).forEach { kind ->
            listOf(true, false).forEach { enabled ->
                val selected = relay(kind).copy(udpEnabled = enabled)
                val preferences = RipDpiProxyUIPreferences()
                listOf(preferences, RipDpiProxyJsonPreferences(preferences.toNativeConfigJson())).forEach { input ->
                    val output = input.withRelayRuntimeSelection(selected, "127.0.0.1", 19005)
                    assertEquals("$kind enabled=$enabled", enabled, encodedUdp(output))
                }
            }
        }
    }

    @Test
    fun `Reality normalization preserves both Vision flows including whitespace`() {
        listOf(RelayVlessFlowVision, RelayVlessFlowVisionUdp443).forEach { flow ->
            listOf(flow, " $flow ").forEach { supplied ->
                val preferences =
                    RipDpiProxyUIPreferences(relay = relay(RelayKindVlessReality).copy(vlessFlow = supplied))
                assertEquals(true, encodedUdp(preferences))
            }
        }
    }

    @Test
    fun `normalization retains unsupported VLESS requests for explicit admission rejection`() {
        val original = relay(RelayKindVlessReality)
        val unsupported =
            listOf(RelayVlessTransportXhttp, "", "unknown").map { original.copy(vlessTransport = it) } +
                listOf("", " ", "unsupported-flow").map { original.copy(vlessFlow = it) }
        unsupported.forEach { config ->
            val preferences = RipDpiProxyUIPreferences(relay = config)
            assertEquals(true, preferences.relay.udpEnabled)
            assertEquals(true, encodedUdp(preferences))
            assertEquals(config.vlessFlow.trim(), preferences.relay.vlessFlow)
            if (config.vlessTransport == "unknown") assertEquals("unknown", preferences.relay.vlessTransport)
        }
    }

    @Test
    fun `explicit blank flow stays blank over XHTTP and Reality TCP`() {
        val xhttp = relay(RelayKindVlessReality).copy(vlessTransport = RelayVlessTransportXhttp, vlessFlow = " ")
        val tcp = relay(RelayKindVlessReality).copy(vlessTransport = RelayVlessTransportRealityTcp, vlessFlow = "")

        assertEquals("", RipDpiProxyUIPreferences(relay = xhttp).relay.vlessFlow)
        assertEquals("", RipDpiProxyUIPreferences(relay = tcp).relay.vlessFlow)
    }

    @Test
    fun `TCP only and unknown kinds remain disabled after normalization`() {
        listOf(RelayKindSsh, RelayKindMieru, "unknown").forEach { kind ->
            val preferences = RipDpiProxyUIPreferences(relay = relay(kind))
            assertFalse(kind, preferences.relay.udpEnabled)
            assertFalse(kind, encodedUdp(preferences))
        }
    }

    private fun relay(kind: String) =
        RipDpiRelayConfig(
            enabled = true,
            kind = kind,
            profileId = "selected",
            server = "relay.example",
            serverPort = 443,
            serverName = "relay.example",
            udpEnabled = true,
            vlessTransport = RelayVlessTransportRealityTcp,
            vlessFlow = RelayVlessFlowVision,
        )

    private fun encodedUdp(preferences: RipDpiProxyPreferences): Boolean {
        val relay = Json.parseToJsonElement(preferences.toNativeConfigJson()).jsonObject["upstreamRelay"]
        return relay != null && relay != JsonNull &&
            relay.jsonObject
                .getValue("udpEnabled")
                .jsonPrimitive.boolean
    }
}
