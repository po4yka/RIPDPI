package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayPasswordCredentialImportTest {
    @Test
    fun `flat Trojan credentials retain the configured endpoint and password`() {
        val result = parse("trojan", flat)
        val profile = result.profiles.single() as ProxyProfile.Trojan

        assertTrue(result.skipped.isEmpty())
        assertEquals("flat.example", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("flat-password", profile.password)
    }

    @Test
    fun `flat Shadowsocks credentials retain the configured cipher`() {
        val result = parse("shadowsocks", flat)
        val profile = result.profiles.single() as ProxyProfile.Shadowsocks

        assertTrue(result.skipped.isEmpty())
        assertEquals("flat.example", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("flat-password", profile.password)
        assertEquals("aes-256-gcm", profile.method)
    }

    @Test
    fun `flat credentials override the legacy server for both password protocols`() {
        val settings = flat.dropLast(1) + """, "servers":[$legacy]}"""
        val trojan = parse("trojan", settings).profiles.single() as ProxyProfile.Trojan
        val ss = parse("shadowsocks", settings).profiles.single() as ProxyProfile.Shadowsocks

        assertEquals("flat.example", trojan.server)
        assertEquals("flat-password", trojan.password)
        assertEquals("flat.example", ss.server)
        assertEquals("flat-password", ss.password)
        assertEquals("aes-256-gcm", ss.method)
    }

    @Test
    fun `invalid flat credentials never fall back to a valid legacy server`() {
        val invalid =
            listOf(
                """{"address":"flat.example","password":"p","servers":[$legacy]}""",
                """{"address":false,"port":443,"password":"p","servers":[$legacy]}""",
                """{"address":"flat.example","port":"443","password":"p","servers":[$legacy]}""",
                """{"address":"flat.example","port":65536,"password":"p","servers":[$legacy]}""",
                """{"address":"flat.example","port":443,"password":false,"servers":[$legacy]}""",
            )
        for (protocol in listOf("trojan", "shadowsocks")) {
            for (settings in invalid) {
                val result = parse(protocol, settings)
                assertTrue(result.profiles.isEmpty())
                assertEquals(XraySkipReason.MALFORMED, result.skipped.single().reason)
            }
        }
    }

    @Test
    fun `legacy credentials require exactly one server`() {
        for (protocol in listOf("trojan", "shadowsocks")) {
            val single = parse(protocol, """{"servers":[$legacy]}""")
            assertEquals(1, single.profiles.size)
            assertTrue(single.skipped.isEmpty())
            for (settings in listOf("""{"servers":[]}""", """{"servers":[$legacy,$legacy]}""")) {
                val result = parse(protocol, settings)
                assertTrue(result.profiles.isEmpty())
                assertEquals(XraySkipReason.MALFORMED, result.skipped.single().reason)
            }
        }
    }

    @Test
    fun `Trojan rejects removed or malformed flow in the selected credential record`() {
        for (flow in listOf("\"xtls-rprx-vision\"", "false", "{}", "null")) {
            val selected = flat.dropLast(1) + """, "flow":$flow}"""
            for (settings in listOf(selected, """{"servers":[$selected]}""")) {
                val result = parse("trojan", settings)

                assertTrue(result.profiles.isEmpty())
                assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
                assertEquals("Trojan flow", result.skipped.single().detail)
            }
        }
    }

    @Test
    fun `flat Trojan flow governs mixed credential records`() {
        val removedLegacy = legacy.dropLast(1) + """, "flow":"xtls-rprx-vision"}"""
        val supportedFlat = flat.dropLast(1) + """, "flow":"", "servers":[$removedLegacy]}"""
        val accepted = parse("trojan", supportedFlat)

        assertTrue(accepted.skipped.isEmpty())
        assertEquals("flat.example", (accepted.profiles.single() as ProxyProfile.Trojan).server)
        val removedFlat = flat.dropLast(1) + """, "flow":"xtls-rprx-vision", "servers":[$legacy]}"""
        val rejected = parse("trojan", removedFlat)

        assertTrue(rejected.profiles.isEmpty())
        assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, rejected.skipped.single().reason)
    }

    private val flat =
        """{"address":"flat.example","port":8443,"password":"flat-password","method":"aes-256-gcm"}"""
    private val legacy =
        """{"address":"legacy.example","port":443,"password":"legacy-password","method":"aes-128-gcm"}"""

    private fun parse(
        protocol: String,
        settings: String,
    ): XrayConfigImportResult.Translated {
        val stream = if (protocol == "trojan") """, "streamSettings":{"security":"tls"}""" else ""
        return XrayConfigImportParser.parse(
            """{"protocol":"$protocol","settings":$settings$stream}""",
            "group",
        ) as XrayConfigImportResult.Translated
    }
}
