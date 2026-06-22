package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Characterization tests for [XrayConfigImportParser]. These pin the parser's
 * observable behaviour (the [XrayConfigImportResult] contract, the typed
 * [XraySkipReason] / [XrayUnparseableReason] outcomes, and every mapped
 * [ProxyProfile] field) so the object can be refactored to satisfy detekt
 * without any behavioural drift.
 *
 * This lives in `:core:data:runtime-state` (where the parser is defined) and is
 * intentionally exhaustive across the JSON, single-outbound, base64-wrapped,
 * and share-link code paths.
 */
private const val testGroupId = "g1"
private const val testUuid = "550e8400-e29b-41d4-a716-446655440000"
private const val testPbk = "AbCdEf0123456789AbCdEf0123456789AbCdEf01234"

private fun translate(input: String): XrayConfigImportResult.Translated {
    val result = XrayConfigImportParser.parse(input, testGroupId)
    assertTrue("expected Translated, got $result", result is XrayConfigImportResult.Translated)
    return result as XrayConfigImportResult.Translated
}

private fun unparseable(input: String): XrayUnparseableReason {
    val result = XrayConfigImportParser.parse(input, testGroupId)
    assertTrue("expected Unparseable, got $result", result is XrayConfigImportResult.Unparseable)
    return (result as XrayConfigImportResult.Unparseable).reason
}

private fun ssUserInfo(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

class XrayConfigImportParserTest {
    private val groupId = testGroupId
    private val uuid = testUuid
    private val pbk = testPbk

    // ── Unparseable inputs ────────────────────────────────────────────────────

    @Test
    fun `empty and blank inputs are EMPTY`() {
        assertEquals(XrayUnparseableReason.EMPTY, unparseable(""))
        assertEquals(XrayUnparseableReason.EMPTY, unparseable("   \n  "))
    }

    @Test
    fun `json-shaped but invalid is MALFORMED_JSON`() {
        assertEquals(XrayUnparseableReason.MALFORMED_JSON, unparseable("{ not json"))
        assertEquals(XrayUnparseableReason.MALFORMED_JSON, unparseable("[ 1, 2"))
    }

    @Test
    fun `valid json with no outbounds is NO_OUTBOUNDS`() {
        assertEquals(XrayUnparseableReason.NO_OUTBOUNDS, unparseable("""{ "log": { "level": "warning" } }"""))
    }

    @Test
    fun `non-json non-link garbage is UNRECOGNISED_INPUT`() {
        assertEquals(XrayUnparseableReason.UNRECOGNISED_INPUT, unparseable("just some words"))
    }

    // ── JSON shapes: outbounds array / single outbound / base64-wrapped ───────

    @Test
    fun `outbounds array maps every node and reports every skip`() {
        val config =
            """
            { "outbounds": [
              { "tag": "r", "protocol": "vless",
                "settings": { "vnext": [ { "address": "e.example", "port": 443,
                  "users": [ { "id": "$uuid" } ] } ] },
                "streamSettings": { "security": "reality",
                  "realitySettings": { "publicKey": "$pbk", "serverName": "e.example" } } },
              { "tag": "t", "protocol": "trojan",
                "settings": { "servers": [ { "address": "t.example", "port": 443, "password": "p" } ] } },
              { "tag": "v", "protocol": "vmess", "settings": {} },
              { "tag": "d", "protocol": "freedom" }
            ] }
            """.trimIndent()
        val result = translate(config)
        assertEquals(2, result.profiles.size)
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun `top-level json array is treated as an outbounds list`() {
        val config =
            """[ { "protocol": "trojan",
              "settings": { "servers": [ { "address": "t.example", "port": 8443, "password": "x" } ] } } ]"""
        assertEquals(1, translate(config).profiles.size)
    }

    @Test
    fun `single outbound object without outbounds array is accepted`() {
        val config =
            """{ "protocol": "trojan",
              "settings": { "servers": [ { "address": "t.example", "port": 8443, "password": "x" } ] } }"""
        val profile = translate(config).profiles.single()
        assertTrue(profile is ProxyProfile.Trojan)
    }

    @Test
    fun `base64-wrapped json config is decoded and translated`() {
        val config =
            """{ "outbounds": [ { "protocol": "trojan", "settings": { "servers": [
              { "address": "t.example", "port": 443, "password": "p" } ] } } ] }"""
        val payload = Base64.getEncoder().encodeToString(config.toByteArray())
        assertTrue(translate(payload).profiles.single() is ProxyProfile.Trojan)
    }

    // ── Per-protocol JSON mapping ─────────────────────────────────────────────

    @Test
    fun `vless reality json maps all fields and stamps group id`() {
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
        val profile = result.profiles.single() as ProxyProfile.VlessReality
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals(uuid, profile.uuid)
        assertEquals(pbk, profile.realityPublicKey)
        assertEquals("ab12", profile.realityShortId)
        assertEquals("www.cloudflare.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("chrome", profile.fingerprint)
        assertEquals("tokyo", profile.displayName)
        assertEquals(groupId, profile.groupId)
        assertNull(profile.xhttpPath)
        assertNull(profile.xhttpHost)
    }

    @Test
    fun `vless reality json applies defaults when optional fields are absent`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example", "port": 443,
                "users": [ { "id": "$uuid" } ] } ] },
              "streamSettings": { "security": "reality",
                "realitySettings": { "publicKey": "$pbk" } } } ] }
            """.trimIndent()
        val profile = translate(config).profiles.single() as ProxyProfile.VlessReality
        // serverName defaults to address, shortId to empty, flow to vision, fingerprint null,
        // displayName to address when no tag.
        assertEquals("h.example", profile.serverName)
        assertEquals("", profile.realityShortId)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertNull(profile.fingerprint)
        assertEquals("h.example", profile.displayName)
    }

    @Test
    fun `vless reality json with xhttp transport carries path and host`() {
        val config =
            """
            { "outbounds": [ {
              "protocol": "vless",
              "settings": { "vnext": [ { "address": "cdn.example.com", "port": 443,
                "users": [ { "id": "$uuid" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk", "serverName": "cdn.example.com" },
                "xhttpSettings": { "path": "/tunnel", "host": "cdn.example.com", "mode": "stream-one" } }
            } ] }
            """.trimIndent()
        val profile = translate(config).profiles.single() as ProxyProfile.VlessReality
        assertEquals("/tunnel", profile.xhttpPath)
        assertEquals("cdn.example.com", profile.xhttpHost)
        assertEquals("stream-one", profile.xhttpMode)
    }

    @Test
    fun `plain vless without reality is VLESS_REQUIRES_REALITY`() {
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
    fun `vless missing uuid is MALFORMED`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example", "port": 443, "users": [ {} ] } ] },
              "streamSettings": { "security": "reality", "realitySettings": { "publicKey": "$pbk" } } } ] }
            """.trimIndent()
        assertEquals(XraySkipReason.MALFORMED, translate(config).skipped.single().reason)
    }

    @Test
    fun `trojan and shadowsocks json map to native profiles`() {
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
        assertEquals(443, trojan.serverPort)
        assertEquals("tj-secret", trojan.password)
        val ss = result.profiles.filterIsInstance<ProxyProfile.Shadowsocks>().single()
        assertEquals("ss.example", ss.server)
        assertEquals(8388, ss.serverPort)
        assertEquals("aes-256-gcm", ss.method)
        assertEquals("ss-secret", ss.password)
    }

    @Test
    fun `trojan missing password and shadowsocks missing method are MALFORMED`() {
        val trojan =
            """{ "outbounds": [ { "protocol": "trojan",
              "settings": { "servers": [ { "address": "t.example", "port": 443 } ] } } ] }"""
        assertEquals(XraySkipReason.MALFORMED, translate(trojan).skipped.single().reason)
        val ss =
            """{ "outbounds": [ { "protocol": "shadowsocks",
              "settings": { "servers": [ { "address": "s.example", "port": 8388, "password": "p" } ] } } ] }"""
        assertEquals(XraySkipReason.MALFORMED, translate(ss).skipped.single().reason)
    }

    @Test
    fun `vmess non-proxy and unknown protocols carry their typed skip reasons`() {
        val vmess = translate("""{ "outbounds": [ { "tag": "old", "protocol": "vmess", "settings": {} } ] }""")
        assertEquals(XraySkipReason.VMESS_REMOVED, vmess.skipped.single().reason)
        val freedom = translate("""{ "outbounds": [ { "protocol": "freedom", "tag": "direct" } ] }""")
        assertEquals(XraySkipReason.NON_PROXY_OUTBOUND, freedom.skipped.single().reason)
        assertEquals("freedom", freedom.skipped.single().detail)
        val socks = translate("""{ "outbounds": [ { "protocol": "socks", "tag": "s" } ] }""")
        assertEquals(XraySkipReason.UNSUPPORTED_PROTOCOL, socks.skipped.single().reason)
        assertEquals("socks", socks.skipped.single().detail)
    }

    @Test
    fun `outbound missing protocol is MALFORMED`() {
        assertEquals(
            XraySkipReason.MALFORMED,
            translate("""{ "outbounds": [ { "tag": "x", "settings": {} } ] }""").skipped.single().reason,
        )
    }

    // ── Share links (vless / trojan / ss / anytls), plus skips and base64 ─────

    @Test
    fun `vless trojan ss anytls share links map to native profiles`() {
        val vlessLink = "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=www.cloudflare.com#tokyo"
        val vless = translate(vlessLink).profiles.single() as ProxyProfile.VlessReality
        assertEquals("edge.example.com", vless.server)
        assertEquals(groupId, vless.groupId)

        val trojan = translate("trojan://pw@tj.example:443#t").profiles.single()
        assertTrue(trojan is ProxyProfile.Trojan)
        assertEquals(groupId, (trojan as ProxyProfile.Trojan).groupId)

        val ss = translate("ss://${ssUserInfo("aes-256-gcm:secret")}@ss.example:8388#s").profiles.single()
        assertTrue(ss is ProxyProfile.Shadowsocks)

        val anytls = translate("anytls://any-pass@front.example:8443?sni=front.example#a").profiles.single()
        assertTrue(anytls is ProxyProfile.AnyTls)
        assertEquals(groupId, (anytls as ProxyProfile.AnyTls).groupId)
    }

    @Test
    fun `vless reality share link without public key is MALFORMED and vmess link is removed`() {
        val noKey = translate("vless://$uuid@h.example:443?security=reality#node")
        assertTrue(noKey.profiles.isEmpty())
        assertEquals(XraySkipReason.MALFORMED, noKey.skipped.single().reason)

        val vmess = translate("vmess://eyJhZGQiOiJ4In0=")
        assertTrue(vmess.profiles.isEmpty())
        assertEquals(XraySkipReason.VMESS_REMOVED, vmess.skipped.single().reason)
    }

    @Test
    fun `base64-wrapped link list is decoded and translated`() {
        val links =
            "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=h#a\n" +
                "trojan://pw@tj.example:443#b"
        val payload = Base64.getEncoder().encodeToString(links.toByteArray())
        assertEquals(2, translate(payload).profiles.size)
    }
}
