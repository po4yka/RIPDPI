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
                .setFakeTtl(16)
                .setFakeSni("example.org")
                .setFakeOffset(4)
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
