package com.poyka.ripdpi.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HysteriaHoppingWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `hopping chain entry survives JSON and section round trips`() {
        val config = relayNativeThreeHopChainFixture()
        val hopping =
            config.chainHops.first().copy(
                kind = "hysteria2",
                hysteriaPassword = relayNativePlaceholder(50),
                hysteriaSalamanderKey = relayNativePlaceholder(51),
                hysteriaInsecure = true,
                hysteriaPinSha256 = "ab".repeat(32),
                hysteriaPortHopPorts = "20000-21000",
                hysteriaPortHopIntervalMillis = 5000L,
                hysteriaPortHopIntervalMaxMillis = 15000L,
            )
        val expected = config.copy(chainHops = listOf(hopping) + config.chainHops.drop(1), chainEntry = hopping)
        val wire = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), expected)
        val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), wire)
        assertEquals(expected, decoded.toSections().toResolvedConfig())
        assertEquals(hopping, decoded.chainHops.first())
        val directory = File("build/reports/hysteria-hopping-wire").apply { mkdirs() }
        File(directory, "chain.json").writeText(wire)
        val flat =
            relayNativeBaseFixture("hysteria2").copy(
                tlsFingerprintProfile = hopping.tlsFingerprintProfile,
                hysteriaPassword = hopping.hysteriaPassword,
                hysteriaSalamanderKey = hopping.hysteriaSalamanderKey,
                hysteriaInsecure = hopping.hysteriaInsecure,
                hysteriaPinSha256 = hopping.hysteriaPinSha256,
                hysteriaPortHopPorts = hopping.hysteriaPortHopPorts,
                hysteriaPortHopIntervalMillis = hopping.hysteriaPortHopIntervalMillis,
                hysteriaPortHopIntervalMaxMillis = hopping.hysteriaPortHopIntervalMaxMillis,
            )
        File(directory, "flat.json").writeText(json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), flat))
        val peerFixed =
            flat.copy(
                hysteriaPortHopPorts = "42071,42073,42079",
                hysteriaPortHopIntervalMillis = 5000L,
                hysteriaPortHopIntervalMaxMillis = null,
            )
        val peerRandom = peerFixed.copy(hysteriaPortHopIntervalMaxMillis = 5500L)
        for ((name, config) in listOf("peer-flat-fixed" to peerFixed, "peer-flat-random" to peerRandom)) {
            val peerWire = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
            assertEquals(config, json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), peerWire))
            File(directory, "$name.json").writeText(peerWire)
        }
    }
}
