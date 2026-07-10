package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.XrayConfigImportParser
import com.poyka.ripdpi.data.subscription.XrayConfigImportResult
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.subscription.XrayUnparseableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit coverage for [XrayConfigImportParser] across real-world Xray config shapes
 * (xray-core JSON + share links): supported-outbound translation, the
 * unsupported-outbound skip path (with typed reasons), and unparseable input.
 */
class XrayConfigImportParserTest {
    private val groupId = "g1"
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val pbk = "AbCdEf0123456789AbCdEf0123456789AbCdEf01234"

    private fun translate(input: String): XrayConfigImportResult.Translated {
        val result = XrayConfigImportParser.parse(input, groupId)
        assertTrue("expected Translated, got $result", result is XrayConfigImportResult.Translated)
        return result as XrayConfigImportResult.Translated
    }

    @Test
    fun `vless reality json maps to VlessReality with stamped group id`() {
        val config =
            """
            { "outbounds": [ {
              "tag": "tokyo", "protocol": "vless",
              "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com",
                  "shortId": "ab12", "fingerprint": "chrome" } }
            } ] }
            """.trimIndent()
        val result = translate(config)
        assertTrue(result.skipped.isEmpty())
        val profile = result.profiles.single()
        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals(uuid, profile.uuid)
        assertEquals(pbk, profile.realityPublicKey)
        assertEquals("ab12", profile.realityShortId)
        assertEquals("www.cloudflare.com", profile.serverName)
        assertEquals("chrome", profile.fingerprint)
        assertEquals(groupId, profile.groupId)
        assertNull(profile.xhttpPath)
    }

    @Test
    fun `vless reality with xhttp transport carries path and host`() {
        val config =
            """
            { "outbounds": [ {
              "protocol": "vless",
              "settings": { "vnext": [ { "address": "cdn.example.com", "port": 443,
                "users": [ { "id": "$uuid" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk", "serverName": "cdn.example.com" },
                "xhttpSettings": { "path": "/tunnel", "host": "cdn.example.com", "mode": "auto" } }
            } ] }
            """.trimIndent()
        val profile = translate(config).profiles.single() as ProxyProfile.VlessReality
        assertEquals("/tunnel", profile.xhttpPath)
        assertEquals("cdn.example.com", profile.xhttpHost)
    }

    @Test
    fun `vless websocket and grpc transports are rejected with typed reasons`() {
        listOf("ws", "grpc").forEach { transport ->
            val config =
                """
                { "outbounds": [ { "protocol": "vless",
                  "settings": { "vnext": [ { "address": "edge.example", "port": 443,
                    "users": [ { "id": "$uuid" } ] } ] },
                  "streamSettings": { "network": "$transport", "security": "reality",
                    "realitySettings": { "publicKey": "$pbk", "serverName": "edge.example" } } } ] }
                """.trimIndent()

            val result = translate(config)

            assertTrue(result.profiles.isEmpty())
            assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
            assertEquals(transport, result.skipped.single().detail)
        }
    }

    @Test
    fun `unsupported vless fingerprint is rejected with a typed reason`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "edge.example", "port": 443,
                "users": [ { "id": "$uuid" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk", "fingerprint": "randomized" } } } ] }
            """.trimIndent()

        val skip = translate(config).skipped.single()

        assertEquals(XraySkipReason.UNSUPPORTED_FINGERPRINT, skip.reason)
        assertEquals("randomized", skip.detail)
    }

    @Test
    fun `empty xhttp and flow remain explicit`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "edge.example", "port": 443,
                "users": [ { "id": "$uuid", "flow": "" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk", "serverName": "edge.example" },
                "xhttpSettings": { "path": "", "host": "", "mode": "" } } } ] }
            """.trimIndent()

        val profile = translate(config).profiles.single() as ProxyProfile.VlessReality

        assertEquals("", profile.flow)
        assertEquals("", profile.xhttpPath)
        assertEquals("", profile.xhttpHost)
        assertEquals("", profile.xhttpMode)
    }

    @Test
    fun `trojan and shadowsocks json map to their native profiles`() {
        val config =
            """
            { "outbounds": [
              { "protocol": "trojan", "settings": { "servers": [
                { "address": "tj.example", "port": 443, "password": "tj-secret" } ] } },
              { "protocol": "shadowsocks", "settings": { "servers": [
                { "address": "ss.example", "port": 8388, "method": "aes-256-gcm", "password": "ss-secret" } ] } }
            ] }
            """.trimIndent()
        val result = translate(config)
        assertTrue(result.skipped.isEmpty())
        val trojan = result.profiles.filterIsInstance<ProxyProfile.Trojan>().single()
        assertEquals("tj.example", trojan.server)
        assertEquals("tj-secret", trojan.password)
        val ss = result.profiles.filterIsInstance<ProxyProfile.Shadowsocks>().single()
        assertEquals("aes-256-gcm", ss.method)
        assertEquals("ss-secret", ss.password)
    }

    @Test
    fun `wrapped trojan and shadowsocks transports are rejected`() {
        val config =
            """
            { "outbounds": [
              { "protocol": "trojan", "settings": { "servers": [
                { "address": "tj.example", "port": 443, "password": "test-value" } ] },
                "streamSettings": { "network": "grpc" } },
              { "protocol": "shadowsocks", "settings": { "servers": [
                { "address": "ss.example", "port": 8388, "method": "aes-256-gcm", "password": "test-value" } ] },
                "streamSettings": { "network": "websocket" } } ] }
            """.trimIndent()

        val result = translate(config)

        assertTrue(result.profiles.isEmpty())
        assertEquals(listOf("grpc", "websocket"), result.skipped.map { it.detail })
        assertTrue(result.skipped.all { it.reason == XraySkipReason.UNSUPPORTED_TRANSPORT })
    }

    @Test
    fun `vmess outbound is skipped as removed`() {
        val config =
            """{ "outbounds": [ { "tag": "old", "protocol": "vmess", "settings": {} } ] }"""
        val result = translate(config)
        assertTrue(result.profiles.isEmpty())
        assertEquals(XraySkipReason.VMESS_REMOVED, result.skipped.single().reason)
    }

    @Test
    fun `plain vless without reality is skipped as reality-required`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example", "port": 443,
                "users": [ { "id": "$uuid" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "tls" } } ] }
            """.trimIndent()
        val result = translate(config)
        assertTrue(result.profiles.isEmpty())
        assertEquals(XraySkipReason.VLESS_REQUIRES_REALITY, result.skipped.single().reason)
    }

    @Test
    fun `plain tls vless xhttp imports all supported identity fields`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless", "tag": "plain-xhttp",
              "settings": { "vnext": [ { "address": "203.0.113.4", "port": 443,
                "users": [ { "id": "$uuid", "flow": "" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "tls",
                "tlsSettings": { "serverName": "cdn.example", "fingerprint": "firefox" },
                "xhttpSettings": { "path": "", "host": "", "mode": "auto" } } } ] }
            """.trimIndent()

        val profile = translate(config).profiles.single() as ProxyProfile.Vless

        assertEquals("cdn.example", profile.serverName)
        assertEquals("", profile.flow)
        assertEquals("firefox", profile.fingerprint)
        assertEquals("", profile.xhttpPath)
        assertEquals("", profile.xhttpHost)
    }

    @Test
    fun `freedom outbound is skipped as non-proxy`() {
        val result = translate("""{ "outbounds": [ { "protocol": "freedom", "tag": "direct" } ] }""")
        assertEquals(XraySkipReason.NON_PROXY_OUTBOUND, result.skipped.single().reason)
    }

    @Test
    fun `unknown protocol is skipped as unsupported with detail`() {
        val result = translate("""{ "outbounds": [ { "protocol": "socks", "tag": "s" } ] }""")
        val skip = result.skipped.single()
        assertEquals(XraySkipReason.UNSUPPORTED_PROTOCOL, skip.reason)
        assertEquals("socks", skip.detail)
    }

    @Test
    fun `vless missing uuid is skipped as malformed`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example", "port": 443, "users": [ {} ] } ] },
              "streamSettings": { "security": "reality", "realitySettings": { "publicKey": "$pbk" } } } ] }
            """.trimIndent()
        assertEquals(XraySkipReason.MALFORMED, translate(config).skipped.single().reason)
    }

    @Test
    fun `mixed config imports supported and reports every skip`() {
        val config =
            """
            { "outbounds": [
              { "tag": "r", "protocol": "vless",
                "settings": { "vnext": [ { "address": "e.example", "port": 443,
                  "users": [ { "id": "$uuid" } ] } ] },
                "streamSettings": { "security": "reality", "realitySettings": { "publicKey": "$pbk", "serverName": "e.example" } } },
              { "tag": "t", "protocol": "trojan", "settings": { "servers": [ { "address": "t.example", "port": 443, "password": "p" } ] } },
              { "tag": "v", "protocol": "vmess", "settings": {} },
              { "tag": "d", "protocol": "freedom" }
            ] }
            """.trimIndent()
        val result = translate(config)
        assertEquals(2, result.profiles.size)
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun `single outbound object without outbounds array is accepted`() {
        val config =
            """
            { "protocol": "trojan", "settings": { "servers": [ { "address": "t.example", "port": 8443, "password": "x" } ] } }
            """.trimIndent()
        assertEquals(1, translate(config).profiles.size)
    }

    @Test
    fun `vless reality share link maps to VlessReality`() {
        val link =
            "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=www.cloudflare.com#tokyo"
        val profile = translate(link).profiles.single() as ProxyProfile.VlessReality
        assertEquals("edge.example.com", profile.server)
        assertEquals(groupId, profile.groupId)
    }

    @Test
    fun `plain tls xhttp share link maps to native Vless`() {
        val link =
            "vless://$uuid@203.0.113.4:443?security=tls&sni=cdn.example" +
                "&type=xhttp&path=&host=&flow=&fp=firefox#plain"

        val profile = translate(link).profiles.single() as ProxyProfile.Vless

        assertEquals("cdn.example", profile.serverName)
        assertEquals("", profile.flow)
        assertEquals("firefox", profile.fingerprint)
        assertEquals("", profile.xhttpPath)
        assertEquals("", profile.xhttpHost)
    }

    @Test
    fun `unsupported share fingerprint is typed`() {
        val link =
            "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk" +
                "&sni=edge.example.com&fp=randomized#node"

        val skip = translate(link).skipped.single()

        assertEquals(XraySkipReason.UNSUPPORTED_FINGERPRINT, skip.reason)
        assertEquals("randomized", skip.detail)
    }

    @Test
    fun `trojan and shadowsocks share links map to native profiles`() {
        val trojan = translate("trojan://pw@tj.example:443#t").profiles.single()
        assertTrue(trojan is ProxyProfile.Trojan)
        val ss =
            translate("ss://${base64("aes-256-gcm:secret")}@ss.example:8388#s").profiles.single()
        assertTrue(ss is ProxyProfile.Shadowsocks)
    }

    @Test
    fun `vmess share link is skipped as removed`() {
        val result = translate("vmess://eyJhZGQiOiJ4In0=")
        assertTrue(result.profiles.isEmpty())
        assertEquals(XraySkipReason.VMESS_REMOVED, result.skipped.single().reason)
    }

    @Test
    fun `base64 wrapped link list is decoded and translated`() {
        val links =
            "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=h#a\n" +
                "trojan://pw@tj.example:443#b"
        val payload = Base64.getEncoder().encodeToString(links.toByteArray())
        val result = translate(payload)
        assertEquals(2, result.profiles.size)
    }

    @Test
    fun `vless reality share link without public key is skipped as malformed`() {
        // security=reality but no pbk → ProxyUriCodec yields an empty-key VlessReality;
        // it cannot complete a REALITY handshake, so it must be skipped, not accepted.
        val result = translate("vless://$uuid@h.example:443?security=reality#node")
        assertTrue(result.profiles.isEmpty())
        assertEquals(XraySkipReason.MALFORMED, result.skipped.single().reason)
    }

    @Test
    fun `unsupported vless share transport is typed`() {
        val result = translate("vless://$uuid@h.example:443?security=reality&pbk=$pbk&type=grpc#node")

        assertTrue(result.profiles.isEmpty())
        assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
        assertEquals("grpc", result.skipped.single().detail)
    }

    @Test
    fun `base64 wrapped json config is decoded and translated`() {
        val config =
            """{ "outbounds": [ { "protocol": "trojan", "settings": { "servers": [
              { "address": "t.example", "port": 443, "password": "p" } ] } } ] }"""
        val payload = Base64.getEncoder().encodeToString(config.toByteArray())
        val result = translate(payload)
        assertTrue(result.profiles.single() is ProxyProfile.Trojan)
    }

    @Test
    fun `malformed json is unparseable`() {
        val result = XrayConfigImportParser.parse("{ not json", groupId)
        assertEquals(
            XrayUnparseableReason.MALFORMED_JSON,
            (result as XrayConfigImportResult.Unparseable).reason,
        )
    }

    @Test
    fun `empty and garbage inputs are unparseable`() {
        assertEquals(
            XrayUnparseableReason.EMPTY,
            (XrayConfigImportParser.parse("   ", groupId) as XrayConfigImportResult.Unparseable).reason,
        )
        assertEquals(
            XrayUnparseableReason.UNRECOGNISED_INPUT,
            (XrayConfigImportParser.parse("just some words", groupId) as XrayConfigImportResult.Unparseable).reason,
        )
    }

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
