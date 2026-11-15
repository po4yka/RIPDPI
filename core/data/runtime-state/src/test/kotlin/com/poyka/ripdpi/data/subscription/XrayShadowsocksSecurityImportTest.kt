package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayShadowsocksSecurityImportTest {
    @Test
    fun `unsupported stream and outbound constraints do not become plain Shadowsocks`() {
        val constraints =
            listOf(
                """, "streamSettings":{"network":"tcp","security":"tls",
                    "tlsSettings":{"serverName":"front.example"}}""",
                """, "streamSettings":{"network":"raw","security":"reality"}""",
                """, "streamSettings":{"network":"tcp","tcpSettings":{"header":{"type":"http"}}}""",
                """, "streamSettings":{"network":"tcp","sockopt":{"mark":1}}""",
                """, "streamSettings":{"network":"tcp","security":{}}""",
                """, "streamSettings":[]""",
                """, "proxySettings":{"tag":"upstream"}""",
                """, "mux":{"enabled":true}""",
            )
        for (constraint in constraints) {
            val result = parse(constraint)

            assertTrue("constraint must not become a plain profile", result.profiles.isEmpty())
            assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
            assertTrue(
                result.skipped
                    .single()
                    .detail
                    .orEmpty()
                    .startsWith("Shadowsocks"),
            )
        }
    }

    @Test
    fun `plain raw and TCP Shadowsocks remain importable`() {
        val constraints =
            listOf(
                "",
                """, "streamSettings":{"network":"raw","security":"none"}""",
                """, "streamSettings":{"network":"tcp"}""",
                """, "streamSettings":{"method":"raw","network":"websocket"}""",
            )
        for (constraint in constraints) {
            val result = parse(constraint)

            assertTrue(result.skipped.isEmpty())
            val profile = result.profiles.single() as ProxyProfile.Shadowsocks
            assertEquals("ss.example", profile.server)
            assertEquals("aes-128-gcm", profile.method)
        }
    }

    private fun parse(constraint: String): XrayConfigImportResult.Translated =
        XrayConfigImportParser.parse(
            """{"protocol":"shadowsocks","settings":{"servers":[{"address":"ss.example","port":443,
            "method":"aes-128-gcm","password":"test-password"}]}$constraint}""",
            "group",
        ) as XrayConfigImportResult.Translated
}
