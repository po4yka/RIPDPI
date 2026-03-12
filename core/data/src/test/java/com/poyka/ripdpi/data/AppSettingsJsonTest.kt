package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.ActivationFilter
import com.poyka.ripdpi.proto.NumericRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsJsonTest {
    @Test
    fun `settings round trip through json`() {
        val settings =
            AppSettings
                .newBuilder()
                .setAppTheme("dark")
                .setRipdpiMode("proxy")
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModeDoh)
                .setDnsProviderId(DnsProviderQuad9)
                .setDnsDohUrl("https://dns.quad9.net/dns-query")
                .addAllDnsDohBootstrapIps(listOf("9.9.9.9", "149.112.112.112"))
                .setIpv6Enable(true)
                .setEnableCmdSettings(true)
                .setCmdArgs("--dpi-desync=fake")
                .setProxyIp("10.0.0.2")
                .setProxyPort(2080)
                .setMaxConnections(1024)
                .setBufferSize(32768)
                .setNoDomain(true)
                .setTcpFastOpen(true)
                .setDefaultTtl(64)
                .setCustomTtl(true)
                .setDesyncMethod("fake")
                .setSplitPosition(3)
                .setSplitAtHost(true)
                .setSplitMarker("host+3")
                .setFakeTtl(16)
                .setFakeSni("example.org")
                .setFakeOffset(4)
                .setFakeOffsetMarker("method+4")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(-1)
                .setAdaptiveFakeTtlMin(3)
                .setAdaptiveFakeTtlMax(12)
                .setAdaptiveFakeTtlFallback(11)
                .setFakeTlsUseOriginal(true)
                .setFakeTlsRandomize(true)
                .setFakeTlsDupSessionId(true)
                .setFakeTlsPadEncap(true)
                .setFakeTlsSize(192)
                .setFakeTlsSniMode(FakeTlsSniModeRandomized)
                .setHttpFakeProfile(HttpFakeProfileCloudflareGet)
                .setTlsFakeProfile(TlsFakeProfileGoogleChrome)
                .setOobData("payload")
                .setDropSack(true)
                .setDesyncHttp(false)
                .setDesyncHttps(true)
                .setDesyncUdp(true)
                .setHostsMode("whitelist")
                .setHostsBlacklist("blocked.test")
                .setHostsWhitelist("allowed.test")
                .setTlsrecEnabled(true)
                .setTlsrecPosition(2)
                .setTlsrecAtSni(true)
                .setTlsrecMarker("sniext+2")
                .setUdpFakeCount(5)
                .setUdpFakeProfile(UdpFakeProfileDnsQuery)
                .setHostMixedCase(true)
                .setDomainMixedCase(true)
                .setHostRemoveSpaces(true)
                .setHttpMethodEol(true)
                .setHttpUnixEol(true)
                .setOnboardingComplete(true)
                .setWebrtcProtectionEnabled(true)
                .setBiometricEnabled(true)
                .setBackupPin("1234")
                .setAppIconVariant("raven")
                .setAppIconStyle("plain")
                .setQuicInitialMode("route")
                .setQuicSupportV1(true)
                .setQuicSupportV2(false)
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example.test")
                .setHostAutolearnEnabled(true)
                .setHostAutolearnPenaltyTtlHours(12)
                .setHostAutolearnMaxHosts(2048)
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(1).setEnd(2))
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(2047))
                        .build(),
                )
                .addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .setMidhostMarker("midsld")
                        .setFakeHostTemplate("googlevideo.com")
                        .setActivationFilter(
                            ActivationFilter
                                .newBuilder()
                                .setRound(NumericRange.newBuilder().setStart(1).setEnd(1))
                                .setPayloadSize(NumericRange.newBuilder().setStart(32).setEnd(256))
                                .build(),
                        )
                        .build(),
                ).addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrandrec")
                        .setMarker("sniext+4")
                        .setFragmentCount(5)
                        .setMinFragmentSize(24)
                        .setMaxFragmentSize(48)
                        .setActivationFilter(
                            ActivationFilter
                                .newBuilder()
                                .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(1199))
                                .build(),
                        )
                        .build(),
                ).addUdpChainSteps(
                    com.poyka.ripdpi.proto.StrategyUdpStep
                        .newBuilder()
                        .setKind("fake_burst")
                        .setCount(5)
                        .setActivationFilter(
                            ActivationFilter
                                .newBuilder()
                                .setRound(NumericRange.newBuilder().setStart(1).setEnd(3))
                                .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(1199))
                                .build(),
                        )
                        .build(),
                )
                .build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(settings.toJson(), decoded.toJson())
    }

    @Test
    fun `json uses stable lowercase enum values and includes format version`() {
        val json =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setRipdpiMode("proxy")
                .build()
                .toJson()

        val parsed = Json.parseToJsonElement(json).jsonObject

        assertTrue(parsed.getValue("formatVersion").jsonPrimitive.content.toInt() >= 1)
        assertEquals("proxy", parsed.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `decoder fills missing quic fields from defaults`() {
        val decoded =
            appSettingsFromJson(
                """
                {
                  "formatVersion": 1,
                  "mode": "vpn"
                }
                """.trimIndent(),
            )

        assertEquals(AppSettingsSerializer.defaultValue.quicInitialMode, decoded.quicInitialMode)
        assertEquals(AppSettingsSerializer.defaultValue.quicSupportV1, decoded.quicSupportV1)
        assertEquals(AppSettingsSerializer.defaultValue.quicSupportV2, decoded.quicSupportV2)
        assertEquals(AppSettingsSerializer.defaultValue.quicFakeProfile, decoded.quicFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.quicFakeHost, decoded.quicFakeHost)
        assertEquals(AppSettingsSerializer.defaultValue.httpFakeProfile, decoded.httpFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.tlsFakeProfile, decoded.tlsFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.udpFakeProfile, decoded.udpFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.httpMethodEol, decoded.httpMethodEol)
        assertEquals(AppSettingsSerializer.defaultValue.httpUnixEol, decoded.httpUnixEol)
        assertEquals(AppSettingsSerializer.defaultValue.adaptiveFakeTtlEnabled, decoded.adaptiveFakeTtlEnabled)
        assertEquals(AppSettingsSerializer.defaultValue.adaptiveFakeTtlDelta, decoded.adaptiveFakeTtlDelta)
        assertEquals(AppSettingsSerializer.defaultValue.adaptiveFakeTtlMin, decoded.adaptiveFakeTtlMin)
        assertEquals(AppSettingsSerializer.defaultValue.adaptiveFakeTtlMax, decoded.adaptiveFakeTtlMax)
        assertEquals(AppSettingsSerializer.defaultValue.adaptiveFakeTtlFallback, decoded.adaptiveFakeTtlFallback)
    }

    @Test
    fun `adaptive markers round trip through json marker fields unchanged`() {
        val settings =
            AppSettings
                .newBuilder()
                .setSplitMarker(AdaptiveMarkerBalanced)
                .setTlsrecEnabled(true)
                .setTlsrecMarker(AdaptiveMarkerSniExt)
                .addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrec")
                        .setMarker(AdaptiveMarkerSniExt)
                        .build(),
                ).addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker(AdaptiveMarkerMethod)
                        .build(),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(AdaptiveMarkerBalanced, decoded.splitMarker)
        assertEquals(AdaptiveMarkerSniExt, decoded.tlsrecMarker)
        assertEquals(AdaptiveMarkerSniExt, decoded.tcpChainStepsList[0].marker)
        assertEquals(AdaptiveMarkerMethod, decoded.tcpChainStepsList[1].marker)
    }

    @Test
    fun `decoder ignores unknown keys and fills omitted values from defaults`() {
        val decoded =
            appSettingsFromJson(
                """
                {
                  "formatVersion": 1,
                  "mode": "proxy",
                  "dnsIp": "8.8.4.4",
                  "unknownFutureField": true
                }
                """.trimIndent(),
            )

        assertEquals("proxy", decoded.ripdpiMode)
        assertEquals("8.8.4.4", decoded.dnsIp)
        assertEquals(DnsModePlainUdp, decoded.dnsMode)
        assertEquals(DnsProviderCustom, decoded.dnsProviderId)
        assertEquals(AppSettingsSerializer.defaultValue.proxyPort, decoded.proxyPort)
        assertEquals(AppSettingsSerializer.defaultValue.desyncMethod, decoded.desyncMethod)
    }

    @Test
    fun `legacy built in dns ip migrates to doh resolver`() {
        val decoded =
            appSettingsFromJson(
                """
                {
                  "formatVersion": 1,
                  "dnsIp": "8.8.8.8"
                }
                """.trimIndent(),
            )

        assertEquals("8.8.8.8", decoded.dnsIp)
        assertEquals(DnsModeEncrypted, decoded.dnsMode)
        assertEquals(DnsProviderGoogle, decoded.dnsProviderId)
        assertEquals(EncryptedDnsProtocolDoh, decoded.encryptedDnsProtocol)
        assertEquals("dns.google", decoded.encryptedDnsHost)
        assertEquals(443, decoded.encryptedDnsPort)
        assertEquals("https://dns.google/dns-query", decoded.dnsDohUrl)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), decoded.dnsDohBootstrapIpsList)
    }

    @Test
    fun `custom dot resolver round trips through generic encrypted dns json fields`() {
        val settings =
            AppSettings
                .newBuilder()
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDot)
                .setEncryptedDnsHost("dot.example.test")
                .setEncryptedDnsPort(853)
                .setEncryptedDnsTlsServerName("dot.example.test")
                .addAllEncryptedDnsBootstrapIps(listOf("9.9.9.9", "149.112.112.112"))
                .build()

        val json = settings.toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject
        val decoded = appSettingsFromJson(json)

        assertEquals(settings.toJson(), decoded.toJson())
        assertEquals("", parsed.getValue("dnsDohUrl").jsonPrimitive.content)
        assertEquals(0, parsed.getValue("dnsDohBootstrapIps").jsonArray.size)
        assertEquals(EncryptedDnsProtocolDot, decoded.encryptedDnsProtocol)
        assertEquals("dot.example.test", decoded.encryptedDnsHost)
        assertEquals(853, decoded.encryptedDnsPort)
        assertEquals("dot.example.test", decoded.encryptedDnsTlsServerName)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), decoded.encryptedDnsBootstrapIpsList)
    }

    @Test
    fun `custom dnscrypt resolver round trips through generic encrypted dns json fields`() {
        val settings =
            AppSettings
                .newBuilder()
                .setDnsIp("8.8.8.8")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDnsCrypt)
                .setEncryptedDnsHost("dnscrypt.example.test")
                .setEncryptedDnsPort(5443)
                .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8", "8.8.4.4"))
                .setEncryptedDnsDnscryptProviderName("2.dnscrypt-cert.example.test")
                .setEncryptedDnsDnscryptPublicKey(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ).build()

        val json = settings.toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject
        val decoded = appSettingsFromJson(json)

        assertEquals(settings.toJson(), decoded.toJson())
        assertEquals("", parsed.getValue("dnsDohUrl").jsonPrimitive.content)
        assertEquals(0, parsed.getValue("dnsDohBootstrapIps").jsonArray.size)
        assertEquals(EncryptedDnsProtocolDnsCrypt, decoded.encryptedDnsProtocol)
        assertEquals("dnscrypt.example.test", decoded.encryptedDnsHost)
        assertEquals(5443, decoded.encryptedDnsPort)
        assertEquals("2.dnscrypt-cert.example.test", decoded.encryptedDnsDnscryptProviderName)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            decoded.encryptedDnsDnscryptPublicKey,
        )
    }

    @Test
    fun `v1 custom doh fields hydrate generic encrypted dns fields`() {
        val decoded =
            appSettingsFromJson(
                """
                {
                  "formatVersion": 1,
                  "dnsMode": "doh",
                  "dnsProviderId": "custom",
                  "dnsIp": "1.1.1.1",
                  "dnsDohUrl": "https://resolver.example.test/dns-query",
                  "dnsDohBootstrapIps": ["1.1.1.1", "1.0.0.1"]
                }
                """.trimIndent(),
            )

        assertEquals(DnsModeEncrypted, decoded.dnsMode)
        assertEquals(DnsProviderCustom, decoded.dnsProviderId)
        assertEquals(EncryptedDnsProtocolDoh, decoded.encryptedDnsProtocol)
        assertEquals("resolver.example.test", decoded.encryptedDnsHost)
        assertEquals(443, decoded.encryptedDnsPort)
        assertEquals("resolver.example.test", decoded.encryptedDnsTlsServerName)
        assertEquals("https://resolver.example.test/dns-query", decoded.encryptedDnsDohUrl)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), decoded.encryptedDnsBootstrapIpsList)
    }

    @Test
    fun `unsupported format version is rejected`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                appSettingsFromJson("""{"formatVersion": 99}""")
            }

        assertTrue(error.message.orEmpty().contains("Unsupported app settings format version"))
    }
}
