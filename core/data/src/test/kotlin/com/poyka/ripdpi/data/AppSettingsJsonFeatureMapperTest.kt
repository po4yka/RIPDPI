package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsJsonFeatureMapperTest {
    @Test
    fun `dns proxy and desync slices round trip independently`() {
        val settings =
            AppSettings
                .newBuilder()
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                .setEncryptedDnsHost("resolver.example")
                .setEncryptedDnsDohUrl("https://resolver.example/dns-query")
                .setProxyIp("127.0.0.9")
                .setProxyPort(2081)
                .setFakeTtl(7)
                .setFakeSni("front.example")
                .setDesyncAnyProtocol(true)
                .setStrategyChains(
                    tcpSteps = listOf(TcpChainStepModel(TcpChainStepKind.TlsRec, "sniext")),
                    udpSteps = listOf(UdpChainStepModel(count = 3, kind = UdpChainStepKind.FakeBurst)),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(DnsModeEncrypted, decoded.dnsMode)
        assertEquals(EncryptedDnsProtocolDoh, decoded.encryptedDnsProtocol)
        assertEquals("https://resolver.example/dns-query", decoded.encryptedDnsDohUrl)
        assertEquals("127.0.0.9", decoded.proxyIp)
        assertEquals(2081, decoded.proxyPort)
        assertEquals("front.example", decoded.fakeSni)
        assertTrue(decoded.desyncAnyProtocol)
        assertEquals("tlsrec", decoded.tcpChainStepsList.single().kind)
        assertEquals("fake_burst", decoded.udpChainStepsList.single().kind)
    }

    @Test
    fun `quic adaptive and runtime slices round trip independently`() {
        val settings =
            AppSettings
                .newBuilder()
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example")
                .setStrategyEvolution(true)
                .setEvolutionEpsilon(0.25)
                .setAdaptiveFallbackEnabled(true)
                .setAdaptiveFallbackAutoSort(true)
                .setWsTunnelEnabled(true)
                .setWsTunnelMode("always")
                .setWsTunnelWorkerUrl("https://worker.example/ws")
                .setWsTunnelWorkerCredentialRef("worker-production")
                .setOnboardingComplete(true)
                .setAppIconVariant("raven")
                .build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(QuicFakeProfileRealisticInitial, decoded.quicFakeProfile)
        assertEquals("video.example", decoded.quicFakeHost)
        assertTrue(decoded.strategyEvolution)
        assertEquals(0.25, decoded.evolutionEpsilon, 0.0)
        assertTrue(decoded.adaptiveFallbackEnabled)
        assertTrue(decoded.adaptiveFallbackAutoSort)
        assertEquals("always", decoded.wsTunnelMode)
        assertEquals("https://worker.example/ws", decoded.wsTunnelWorkerUrl)
        assertEquals("worker-production", decoded.wsTunnelWorkerCredentialRef)
        assertTrue(decoded.onboardingComplete)
        assertEquals("raven", decoded.appIconVariant)
    }

    @Test
    fun `restoring fake-SNI cover without acknowledgement clears the insecure cover`() {
        val settings =
            AppSettings
                .newBuilder()
                .setWsTunnelMode("always")
                .setWsTunnelFakeSni("yandex.ru")
                .setWsTunnelAllowInsecureSni(false)
                .build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals("", decoded.wsTunnelFakeSni)
        assertEquals(false, decoded.wsTunnelAllowInsecureSni)
    }

    @Test
    fun `restoring fake-SNI cover with acknowledgement preserves the cover`() {
        val settings =
            AppSettings
                .newBuilder()
                .setWsTunnelMode("always")
                .setWsTunnelFakeSni("yandex.ru")
                .setWsTunnelAllowInsecureSni(true)
                .build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals("yandex.ru", decoded.wsTunnelFakeSni)
        assertTrue(decoded.wsTunnelAllowInsecureSni)
    }

    @Test
    fun `host autolearn json exports knobs but excludes learned host store`() {
        val imported =
            appSettingsFromJson(
                """
                {
                  "formatVersion": 1,
                  "hostAutolearnEnabled": true,
                  "hostAutolearnPenaltyTtlHours": 12,
                  "hostAutolearnMaxHosts": 512,
                  "hostAutolearnStorePath": "/data/user/0/example/no_backup/ripdpi/host-autolearn-v2.json",
                  "learnedHosts": {
                    "example.org": {
                      "preferred_groups": [0]
                    }
                  }
                }
                """.trimIndent(),
            )
        val exported = imported.toJson()
        val exportedRoot = Json.parseToJsonElement(exported).jsonObject

        assertTrue(imported.hostAutolearnEnabled)
        assertEquals(12, imported.hostAutolearnPenaltyTtlHours)
        assertEquals(512, imported.hostAutolearnMaxHosts)
        assertEquals("true", exportedRoot.getValue("hostAutolearnEnabled").jsonPrimitive.content)
        assertEquals("12", exportedRoot.getValue("hostAutolearnPenaltyTtlHours").jsonPrimitive.content)
        assertEquals("512", exportedRoot.getValue("hostAutolearnMaxHosts").jsonPrimitive.content)
        assertFalse(exportedRoot.containsKey("hostAutolearnStorePath"))
        assertFalse(exportedRoot.containsKey("learnedHosts"))
        assertFalse(exported.contains("host-autolearn-v2.json"))
        assertFalse(exported.contains("example.org"))
    }

    @Test
    fun `warp relay and routing slices round trip independently`() {
        val settings =
            AppSettings
                .newBuilder()
                .setWarpEnabled(true)
                .setWarpRouteMode(WarpRouteModeRules)
                .setWarpRouteHosts("blocked.example")
                .setRelayEnabled(true)
                .setRelayKind(RelayKindMasque)
                .setRelayMasqueUrl("https://masque.example/proxy")
                .setRelayLocalSocksPort(2090)
                .setAppRoutingPolicyMode(AppRoutingPolicyModeOff)
                .addAllAppRoutingEnabledPresetIds(listOf("ru-main", "vk"))
                .setAntiCorrelationEnabled(true)
                .setDhtMitigationMode(DhtMitigationModeDropWarn)
                .build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertTrue(decoded.warpEnabled)
        assertEquals(WarpRouteModeRules, decoded.warpRouteMode)
        assertEquals("blocked.example", decoded.warpRouteHosts)
        assertTrue(decoded.relayEnabled)
        assertEquals(RelayKindMasque, decoded.relayKind)
        assertEquals("https://masque.example/proxy", decoded.relayMasqueUrl)
        assertEquals(2090, decoded.relayLocalSocksPort)
        assertEquals(AppRoutingPolicyModeOff, decoded.appRoutingPolicyMode)
        assertEquals(listOf("ru-main", "vk"), decoded.appRoutingEnabledPresetIdsList)
        assertTrue(decoded.antiCorrelationEnabled)
        assertEquals(DhtMitigationModeDropWarn, decoded.dhtMitigationMode)
    }
}
