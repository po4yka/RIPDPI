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
