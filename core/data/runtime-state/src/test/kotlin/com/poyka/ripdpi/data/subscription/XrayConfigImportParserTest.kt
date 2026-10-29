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
    fun `plain tls xhttp share link maps to native Vless`() {
        val link =
            "vless://$uuid@203.0.113.4:443?security=tls&sni=cdn.example" +
                "&type=xhttp&path=&host=&flow=&fp=firefox&mode=stream-up#plain"

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
    fun `unsupported vless share transport is typed`() {
        val result = translate("vless://$uuid@h.example:443?security=reality&pbk=$pbk&type=grpc#node")

        assertTrue(result.profiles.isEmpty())
        assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
        assertEquals("grpc", result.skipped.single().detail)
    }

    @Test
    fun `vless reality over xhttp json without flow imports with no flow`() {
        val config =
            """
            { "outbounds": [ { "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example", "port": 443,
                "users": [ { "id": "$uuid" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk" }, "xhttpSettings": { "path": "/x" } } } ] }
            """.trimIndent()
        val profile = translate(config).profiles.single() as ProxyProfile.VlessReality
        assertEquals("", profile.flow)
        assertEquals("/x", profile.xhttpPath)
    }

    @Test
    fun `trojan json keeps the tls serverName`() {
        val config =
            """{ "outbounds": [ { "protocol": "trojan",
              "settings": { "servers": [ { "address": "203.0.113.5", "port": 443, "password": "p" } ] },
              "streamSettings": { "network": "tcp", "security": "tls",
                "tlsSettings": { "serverName": "cdn.example.com" } } } ] }"""
        val profile = translate(config).profiles.single() as ProxyProfile.Trojan
        assertEquals("cdn.example.com", profile.serverName)
    }

    @Test
    fun `unsupported native VLESS options are rejected without exposing values`() {
        val fixtureValue = "sensitive-import-value"
        val base = supportedRealityConfig()
        val variants =
            listOf(
                base.replace("\"publicKey\":", "\"mldsa65Verify\":\"$fixtureValue\",\"publicKey\":"),
                base.replace("\"publicKey\":", "\"spiderX\":\"/$fixtureValue\",\"publicKey\":"),
                base.replace("\"publicKey\":", "\"newAuthOption\":\"$fixtureValue\",\"publicKey\":"),
                base.replace("\"encryption\":\"none\"", "\"encryption\":\"mlkem768x25519plus.$fixtureValue\""),
                base.replace("\"id\":", "\"newUserOption\":\"$fixtureValue\",\"id\":"),
                base.replace("\"mode\":", "\"extra\":{\"downloadSettings\":{\"address\":\"$fixtureValue\"}},\"mode\":"),
                base.replace("\"mode\":", "\"downloadSettings\":{\"address\":\"$fixtureValue\"},\"mode\":"),
                base.replace("\"mode\":", "\"newTransportOption\":\"$fixtureValue\",\"mode\":"),
                base.replace("\"network\":", "\"sockopt\":{\"dialerProxy\":\"$fixtureValue\"},\"network\":"),
                base.replace("\"protocol\":", "\"mux\":{\"enabled\":true},\"protocol\":"),
                base.replace("\"mode\":\"stream-one\"", "\"mode\":9"),
                base.replace("\"show\":false", "\"show\":true"),
                base.replace("\"security\":\"reality\"", "\"security\":\"tls\""),
            )
        variants.forEachIndexed { index, config ->
            val result = translate(config)
            assertTrue("variant $index must not produce a downgraded profile", result.profiles.isEmpty())
            assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
            assertTrue(
                result.skipped
                    .single()
                    .detail
                    .orEmpty()
                    .isNotBlank(),
            )
            assertTrue(!result.skipped.toString().contains(fixtureValue))
        }
    }

    @Test
    fun `supported native VLESS settings and label still import`() {
        val profile = translate(supportedRealityConfig()).profiles.single() as ProxyProfile.VlessReality
        assertEquals("supported node", profile.displayName)
        assertEquals("", profile.flow)
        assertEquals("/supported", profile.xhttpPath)
        assertEquals("cdn.example", profile.xhttpHost)
        assertEquals("stream-one", profile.xhttpMode)
        assertEquals("abcd", profile.realityShortId)
        assertEquals("firefox", profile.fingerprint)
    }

    @Test
    fun `method selects XHTTP alone and overrides legacy network`() {
        for (legacy in listOf(null, "tcp", "grpc")) {
            val transport = "\"method\":\"xhttp\"" + (legacy?.let { ",\"network\":\"$it\"" } ?: "")
            val config = supportedRealityConfig().replace("\"network\":\"xhttp\"", transport)
            val profile = translate(config).profiles.single() as ProxyProfile.VlessReality
            assertEquals("/supported", profile.xhttpPath)
            assertEquals("cdn.example", profile.xhttpHost)
            assertEquals("stream-one", profile.xhttpMode)
            assertEquals("", profile.flow)
        }
    }

    @Test
    fun `method raw overrides legacy XHTTP without adding an HTTP transport`() {
        val config =
            """{ "outbounds":[{"protocol":"vless",
              "settings":{"vnext":[{"address":"h.example","port":443,"users":[{"id":"$uuid"}]}]},
              "streamSettings":{"method":"raw","network":"xhttp","security":"reality",
                "realitySettings":{"publicKey":"$pbk"}}}]}"""
        val profile = translate(config).profiles.single() as ProxyProfile.VlessReality
        assertNull(profile.xhttpPath)
        assertNull(profile.xhttpHost)
        assertEquals("xtls-rprx-vision", profile.flow)
    }

    @Test
    fun `unsupported method rejects instead of falling back to network without exposing its value`() {
        val fixtureValue = "sensitive-transport-value"
        for (method in listOf("grpc", "", fixtureValue)) {
            val config =
                """{ "outbounds":[{"protocol":"vless",
                  "settings":{"vnext":[{"address":"h.example","port":443,"users":[{"id":"$uuid"}]}]},
                  "streamSettings":{"method":"$method","network":"tcp","security":"reality",
                    "realitySettings":{"publicKey":"$pbk"}}}]}"""
            val result = translate(config)
            assertTrue(result.profiles.isEmpty())
            assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
            assertTrue(!result.skipped.toString().contains(fixtureValue))
        }
    }

    @Test
    fun `flat password settings retain all supported native fields`() {
        val legacy = translate(supportedRealityConfig()).profiles.single() as ProxyProfile.VlessReality
        val modern = translate(flatRealityConfig()).profiles.single() as ProxyProfile.VlessReality

        assertEquals(legacy.copy(id = modern.id), modern)
    }

    @Test
    fun `modern raw REALITY and TLS XHTTP settings retain endpoint and transport`() {
        val reality =
            translate(
                """{"outbounds":[{"protocol":"vless",
                  "settings":{"address":"edge.example","port":8443,"id":"$uuid","flow":"xtls-rprx-vision"},
                  "streamSettings":{"method":"raw","security":"reality",
                    "realitySettings":{"password":"$pbk","serverName":"target.example","shortId":"abcd"}}}]}""",
            ).profiles.single() as ProxyProfile.VlessReality
        assertEquals("edge.example", reality.server)
        assertEquals(8443, reality.serverPort)
        assertEquals("target.example", reality.serverName)
        assertEquals("xtls-rprx-vision", reality.flow)
        assertNull(reality.xhttpPath)

        val tls =
            translate(
                """{"outbounds":[{"protocol":"vless",
                  "settings":{"address":"edge.example","port":8443,"id":"$uuid","flow":"","encryption":"none"},
                  "streamSettings":{"method":"xhttp","security":"tls","tlsSettings":{"serverName":"target.example"},
                    "xhttpSettings":{"path":"/tls","host":"cdn.example","mode":"stream-up"}}}]}""",
            ).profiles.single() as ProxyProfile.Vless
        assertEquals("edge.example", tls.server)
        assertEquals(8443, tls.serverPort)
        assertEquals("target.example", tls.serverName)
        assertEquals("/tls", tls.xhttpPath)
        assertEquals("cdn.example", tls.xhttpHost)
        assertEquals("stream-up", tls.xhttpMode)
        assertEquals("", tls.flow)
    }

    @Test
    fun `equal REALITY key aliases import in either settings shape`() {
        for (config in listOf(supportedRealityConfig(), flatRealityConfig())) {
            val both =
                if (config.contains("\"password\":")) {
                    config.replace("\"password\":", "\"publicKey\":\"$pbk\",\"password\":")
                } else {
                    config.replace("\"publicKey\":", "\"password\":\"$pbk\",\"publicKey\":")
                }
            val profile = translate(both).profiles.single() as ProxyProfile.VlessReality
            assertEquals(pbk, profile.realityPublicKey)
        }
    }

    @Test
    fun `ambiguous or malformed modern settings reject without exposing imported values`() {
        val base = flatRealityConfig()
        val fixtureValue = "private-test-value"
        val variants =
            listOf(
                base.replace("\"password\":", "\"publicKey\":\"$fixtureValue\",\"password\":"),
                base.replace("\"password\":\"$pbk\"", "\"password\":\"\""),
                base.replace("\"password\":\"$pbk\"", "\"password\":9"),
                base.replace("\"password\":", "\"publicKey\":\"\",\"password\":"),
                base.replace("\"address\":", "\"vnext\":[],\"address\":"),
                base.replace("\"address\":", "\"newOption\":\"$fixtureValue\",\"address\":"),
                base.replace("\"id\":\"$uuid\"", "\"id\":23"),
                base.replace("\"encryption\":\"none\"", "\"encryption\":\"$fixtureValue\""),
                base.replace("\"password\":", "\"mldsa65Verify\":\"$fixtureValue\",\"password\":"),
            )
        variants.forEachIndexed { index, config ->
            val result = translate(config)
            assertTrue("variant $index must not produce a downgraded profile", result.profiles.isEmpty())
            assertEquals(1, result.skipped.size)
            assertTrue(!result.skipped.toString().contains(fixtureValue))
        }
    }

    private fun flatRealityConfig(): String =
        """{ "outbounds": [ { "tag":"supported node", "protocol":"vless",
          "settings":{"address":"h.example","port":443,"id":"$uuid","encryption":"none","flow":"","level":0,"email":"label@example"},
          "streamSettings":{"method":"xhttp","security":"reality",
            "realitySettings":{"password":"$pbk","serverName":"cdn.example","shortId":"abcd","fingerprint":"firefox","show":false},
            "xhttpSettings":{"path":"/supported","host":"cdn.example","mode":"stream-one"}}}]}"""

    private fun supportedRealityConfig(): String =
        """{ "outbounds": [ { "tag":"supported node", "protocol":"vless",
          "settings":{"vnext":[{"address":"h.example","port":443,
            "users":[{"id":"$uuid","encryption":"none","flow":"","level":0,"email":"label@example"}]}]},
          "streamSettings":{"network":"xhttp","security":"reality",
            "realitySettings":{"publicKey":"$pbk","serverName":"cdn.example","shortId":"abcd","fingerprint":"firefox","show":false},
            "xhttpSettings":{"path":"/supported","host":"cdn.example","mode":"stream-one"}}}]}"""
}
