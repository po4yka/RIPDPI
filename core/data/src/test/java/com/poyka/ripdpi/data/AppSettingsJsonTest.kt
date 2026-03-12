package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
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
                .setFakeTlsUseOriginal(true)
                .setFakeTlsRandomize(true)
                .setFakeTlsDupSessionId(true)
                .setFakeTlsPadEncap(true)
                .setFakeTlsSize(192)
                .setFakeTlsSniMode(FakeTlsSniModeRandomized)
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
                .setHostMixedCase(true)
                .setDomainMixedCase(true)
                .setHostRemoveSpaces(true)
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
                .addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .setMidhostMarker("midsld")
                        .setFakeHostTemplate("googlevideo.com")
                        .build(),
                ).addTcpChainSteps(
                    com.poyka.ripdpi.proto.StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrandrec")
                        .setMarker("sniext+4")
                        .setFragmentCount(5)
                        .setMinFragmentSize(24)
                        .setMaxFragmentSize(48)
                        .build(),
                )
                .build()

        val decoded = appSettingsFromJson(settings.toJson())

        assertEquals(settings, decoded)
    }

    @Test
    fun `json uses stable lowercase enum values and includes format version`() {
        val json =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setRipdpiMode("proxy")
                .build()
                .toJson()

        assertTrue(json.contains("\"formatVersion\": 1"))
        assertTrue(json.contains("\"mode\": \"proxy\""))
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
        assertEquals(AppSettingsSerializer.defaultValue.proxyPort, decoded.proxyPort)
        assertEquals(AppSettingsSerializer.defaultValue.desyncMethod, decoded.desyncMethod)
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
