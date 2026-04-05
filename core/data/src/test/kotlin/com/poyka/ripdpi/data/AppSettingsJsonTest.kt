package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.ActivationFilter
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.NumericRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsJsonTest {
    @Test
    fun `settings round trip through current json format`() {
        val settings =
            AppSettings
                .newBuilder()
                .setAppTheme("dark")
                .setRipdpiMode("proxy")
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDoq)
                .setEncryptedDnsHost("resolver.example.test")
                .setEncryptedDnsPort(8853)
                .setEncryptedDnsTlsServerName("resolver.example.test")
                .addAllEncryptedDnsBootstrapIps(listOf("9.9.9.9", "149.112.112.112"))
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
                .setQuicBindLowPort(true)
                .setQuicMigrateAfterHandshake(true)
                .setStrategyEvolution(true)
                .setEvolutionEpsilon(0.2)
                .setEntropyPaddingTargetPermil(3500)
                .setEntropyPaddingMax(384)
                .setEntropyMode(entropyModeToProto(EntropyModeShannon))
                .setShannonEntropyTargetPermil(7920)
                .setStrategyPackChannel(StrategyPackChannelBeta)
                .setStrategyPackPinnedId("ru-mobile")
                .setStrategyPackPinnedVersion("2026.04.0")
                .setStrategyPackRefreshPolicy(StrategyPackRefreshPolicyManual)
                .setHostAutolearnEnabled(true)
                .setHostAutolearnPenaltyTtlHours(12)
                .setHostAutolearnMaxHosts(2048)
                .setWarpEnabled(true)
                .setWarpRouteMode("rules")
                .setWarpRouteHosts("example.org\nexample.net")
                .setWarpBuiltinRulesEnabled(true)
                .setWarpProfileId("zt-office")
                .setWarpAccountKind(WarpAccountKindZeroTrust)
                .setWarpZeroTrustOrg("acme")
                .setWarpSetupState(WarpSetupStateProvisioned)
                .setWarpLastScannerMode(WarpScannerModeRescan)
                .setWarpEndpointSelectionMode(WarpEndpointSelectionManual)
                .setWarpManualEndpointHost("engage.cloudflareclient.com")
                .setWarpManualEndpointV4("162.159.192.1")
                .setWarpManualEndpointV6("2606:4700:d0::a29f:c001")
                .setWarpManualEndpointPort(2408)
                .setWarpScannerEnabled(true)
                .setWarpScannerParallelism(12)
                .setWarpScannerMaxRttMs(1200)
                .setWarpAmneziaEnabled(true)
                .setWarpAmneziaJc(3)
                .setWarpAmneziaJmin(40)
                .setWarpAmneziaJmax(80)
                .setWarpAmneziaH1(10)
                .setWarpAmneziaH2(20)
                .setWarpAmneziaH3(30)
                .setWarpAmneziaH4(40)
                .setWarpAmneziaS1(50)
                .setWarpAmneziaS2(120)
                .setWarpAmneziaS3(160)
                .setWarpAmneziaS4(220)
                .setRelayEnabled(true)
                .setRelayKind(RelayKindMasque)
                .setRelayProfileId("edge")
                .setRelayServer("relay.example.test")
                .setRelayServerPort(8443)
                .setRelayServerName("cdn.example.test")
                .setRelayRealityPublicKey("reality-public-key")
                .setRelayRealityShortId("abcd1234")
                .setRelayChainEntryServer("ru-hop.example.test")
                .setRelayChainEntryPort(9443)
                .setRelayChainEntryServerName("ru-hop.example.test")
                .setRelayChainEntryPublicKey("entry-key")
                .setRelayChainEntryShortId("entry01")
                .setRelayChainExitServer("global-hop.example.test")
                .setRelayChainExitPort(10443)
                .setRelayChainExitServerName("global-hop.example.test")
                .setRelayChainExitPublicKey("exit-key")
                .setRelayChainExitShortId("exit01")
                .setRelayMasqueUrl("https://masque.example.test/.well-known/masque/ip")
                .setRelayMasqueUseHttp2Fallback(true)
                .setRelayMasqueCloudflareMode(true)
                .setRelayLocalSocksHost("127.0.0.5")
                .setRelayLocalSocksPort(2090)
                .setRelayUdpEnabled(true)
                .setRelayTcpFallbackEnabled(true)
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(1).setEnd(2))
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(2047))
                        .build(),
                ).setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(
                                kind = TcpChainStepKind.TlsRec,
                                marker = "sniext+2",
                            ),
                            TcpChainStepModel(
                                kind = TcpChainStepKind.TlsRandRec,
                                marker = "sniext+4",
                                fragmentCount = 5,
                                minFragmentSize = 24,
                                maxFragmentSize = 48,
                                activationFilter =
                                    ActivationFilterModel(
                                        streamBytes = NumericRangeModel(start = 0, end = 1199),
                                    ),
                            ),
                            TcpChainStepModel(
                                kind = TcpChainStepKind.HostFake,
                                marker = "endhost+8",
                                midhostMarker = "midsld",
                                fakeHostTemplate = "googlevideo.com",
                                activationFilter =
                                    ActivationFilterModel(
                                        round = NumericRangeModel(start = 1, end = 1),
                                        payloadSize = NumericRangeModel(start = 32, end = 256),
                                    ),
                            ),
                        ),
                    udpSteps =
                        listOf(
                            UdpChainStepModel(
                                count = 5,
                                activationFilter =
                                    ActivationFilterModel(
                                        round = NumericRangeModel(start = 1, end = 3),
                                        streamBytes = NumericRangeModel(start = 0, end = 1199),
                                    ),
                            ),
                        ),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(settings.toJson(), decoded.toJson())
    }

    @Test
    fun `json uses stable lowercase enum values and includes current format version`() {
        val json =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setRipdpiMode("proxy")
                .build()
                .toJson()

        val parsed = Json.parseToJsonElement(json).jsonObject

        assertEquals("1", parsed.getValue("formatVersion").jsonPrimitive.content)
        assertEquals("proxy", parsed.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `decoder fills missing values from defaults in current format`() {
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
        assertEquals(AppSettingsSerializer.defaultValue.quicBindLowPort, decoded.quicBindLowPort)
        assertEquals(
            AppSettingsSerializer.defaultValue.quicMigrateAfterHandshake,
            decoded.quicMigrateAfterHandshake,
        )
        assertEquals(AppSettingsSerializer.defaultValue.httpFakeProfile, decoded.httpFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.tlsFakeProfile, decoded.tlsFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.udpFakeProfile, decoded.udpFakeProfile)
        assertEquals(AppSettingsSerializer.defaultValue.adaptiveFakeTtlFallback, decoded.adaptiveFakeTtlFallback)
        assertEquals(AppSettingsSerializer.defaultValue.strategyEvolution, decoded.strategyEvolution)
        assertEquals(AppSettingsSerializer.defaultValue.evolutionEpsilon, decoded.evolutionEpsilon, 0.0)
        assertEquals(
            AppSettingsSerializer.defaultValue.entropyPaddingTargetPermil,
            decoded.entropyPaddingTargetPermil,
        )
        assertEquals(AppSettingsSerializer.defaultValue.entropyPaddingMax, decoded.entropyPaddingMax)
        assertEquals(AppSettingsSerializer.defaultValue.entropyMode, decoded.entropyMode)
        assertEquals(
            AppSettingsSerializer.defaultValue.shannonEntropyTargetPermil,
            decoded.shannonEntropyTargetPermil,
        )
        assertEquals(AppSettingsSerializer.defaultValue.strategyPackChannel, decoded.strategyPackChannel)
        assertEquals(AppSettingsSerializer.defaultValue.strategyPackPinnedId, decoded.strategyPackPinnedId)
        assertEquals(
            AppSettingsSerializer.defaultValue.strategyPackPinnedVersion,
            decoded.strategyPackPinnedVersion,
        )
        assertEquals(
            AppSettingsSerializer.defaultValue.strategyPackRefreshPolicy,
            decoded.strategyPackRefreshPolicy,
        )
        assertEquals(AppSettingsSerializer.defaultValue.relayEnabled, decoded.relayEnabled)
        assertEquals(AppSettingsSerializer.defaultValue.relayKind, decoded.relayKind)
        assertEquals(AppSettingsSerializer.defaultValue.relayProfileId, decoded.relayProfileId)
        assertEquals(AppSettingsSerializer.defaultValue.warpProfileId, decoded.warpProfileId)
        assertEquals(AppSettingsSerializer.defaultValue.warpAccountKind, decoded.warpAccountKind)
        assertEquals(AppSettingsSerializer.defaultValue.warpSetupState, decoded.warpSetupState)
        assertEquals(AppSettingsSerializer.defaultValue.warpLastScannerMode, decoded.warpLastScannerMode)
        assertEquals(AppSettingsSerializer.defaultValue.relayLocalSocksPort, decoded.relayLocalSocksPort)
        assertTrue(decoded.effectiveTcpChainSteps().isEmpty())
    }

    @Test
    fun `adaptive markers round trip through current chain json fields unchanged`() {
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(TcpChainStepKind.TlsRec, AdaptiveMarkerSniExt),
                            TcpChainStepModel(TcpChainStepKind.Split, AdaptiveMarkerMethod),
                        ),
                    udpSteps = emptyList(),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(AdaptiveMarkerMethod, decoded.effectiveSplitMarker())
        assertEquals(AdaptiveMarkerSniExt, decoded.effectiveTlsRecordMarker())
        assertEquals(AdaptiveMarkerSniExt, decoded.tcpChainStepsList[0].marker)
        assertEquals(AdaptiveMarkerMethod, decoded.tcpChainStepsList[1].marker)
    }

    @Test
    fun `seqovl fields round trip through current json format`() {
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                            TcpChainStepModel(
                                kind = TcpChainStepKind.SeqOverlap,
                                marker = "midsld",
                                overlapSize = 14,
                                fakeMode = SeqOverlapFakeModeRand,
                            ),
                        ),
                    udpSteps = emptyList(),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(settings.toJson(), decoded.toJson())
        assertEquals("seqovl", decoded.tcpChainStepsList[1].kind)
        assertEquals(14, decoded.tcpChainStepsList[1].overlapSize)
        assertEquals(SeqOverlapFakeModeRand, decoded.tcpChainStepsList[1].fakeMode)
    }

    @Test
    fun `multidisorder chain settings round trip through current json format`() {
        val settings =
            AppSettings
                .newBuilder()
                .setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                            TcpChainStepModel(TcpChainStepKind.MultiDisorder, "sniext"),
                            TcpChainStepModel(TcpChainStepKind.MultiDisorder, "host"),
                        ),
                    udpSteps = emptyList(),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(settings.toJson(), decoded.toJson())
        assertEquals("multidisorder", decoded.tcpChainStepsList[1].kind)
        assertEquals("multidisorder", decoded.tcpChainStepsList[2].kind)
        assertEquals(
            "tcp: tlsrec(extlen) -> multidisorder(sniext) -> multidisorder(host)",
            decoded.effectiveChainSummary(),
        )
    }

    @Test
    fun `ipfrag chain settings round trip through current json format`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setStrategyChains(
                    tcpSteps =
                        listOf(
                            TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                            TcpChainStepModel(TcpChainStepKind.IpFrag2, "host+2"),
                        ),
                    udpSteps =
                        listOf(
                            UdpChainStepModel(
                                count = 0,
                                kind = UdpChainStepKind.IpFrag2Udp,
                                splitBytes = 5,
                            ),
                        ),
                ).build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(settings.toJson(), decoded.toJson())
        assertEquals("ipfrag2", decoded.tcpChainStepsList[1].kind)
        assertEquals("ipfrag2_udp", decoded.udpChainStepsList[0].kind)
        assertEquals(0, decoded.udpChainStepsList[0].count)
        assertEquals(8, decoded.udpChainStepsList[0].splitBytes)
        assertEquals(
            listOf(UdpChainStepModel(count = 0, kind = UdpChainStepKind.IpFrag2Udp, splitBytes = 8)),
            decoded.effectiveUdpChainSteps(),
        )
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
        assertEquals(canonicalDefaultEncryptedDnsSettings(), decoded.activeDnsSettings())
        assertEquals(AppSettingsSerializer.defaultValue.proxyPort, decoded.proxyPort)
        assertEquals("none", primaryDesyncMethod(decoded.effectiveTcpChainSteps()))
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
        assertTrue("dnsDohUrl" !in parsed)
        assertTrue("dnsDohBootstrapIps" !in parsed)
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
        assertTrue("dnsDohUrl" !in parsed)
        assertTrue("dnsDohBootstrapIps" !in parsed)
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
    fun `unsupported format version is rejected`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                appSettingsFromJson("""{"formatVersion": 2}""")
            }

        assertTrue(error.message.orEmpty().contains("Unsupported app settings format version"))
    }
}
