package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.ClashSubscriptionParser
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused round-trip tests for [ProxyProfile.VlessReality] import.
 *
 * Covers:
 * - sing-box JSON vless+reality outbound → [ProxyProfile.VlessReality] with all fields
 * - sing-box JSON vless without reality → [ProxyProfile.Vless] (regression guard)
 * - `vless://...?security=reality&pbk=...` URI → [ProxyProfile.VlessReality] with all fields
 * - `vless://...?pbk=...` URI (no explicit security=reality) → [ProxyProfile.VlessReality]
 * - plain `vless://` URI → [ProxyProfile.Vless] (regression guard)
 * - Clash.Meta YAML vless+reality-opts → [ProxyProfile.VlessReality] with all fields
 * - xhttp transport fields are preserved when present
 */
class VlessRealityImportTest {
    private val groupId = "test-group"

    // --- SingBox parser ----------------------------------------------------------------

    @Test
    fun `singbox vless with reality tls produces VlessReality with all fields`() {
        val json =
            """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "reality-node",
                  "server": "edge.example.com",
                  "server_port": 443,
                  "uuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "flow": "xtls-rprx-vision",
                  "tls": {
                    "enabled": true,
                    "server_name": "target.example.com",
                    "utls": { "fingerprint": "chrome" },
                    "reality": {
                      "enabled": true,
                      "public_key": "PUBLICKEY1234567890abcdefghijklmn",
                      "short_id": "abcd1234"
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        val result = SingBoxSubscriptionParser.parse(json, groupId)
        assertTrue("expected success, got $result", result is SingBoxParseResult.Success)
        val profiles = (result as SingBoxParseResult.Success).profiles

        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertTrue("expected VlessReality, got ${profile::class.simpleName}", profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality

        assertEquals("reality-node", profile.displayName)
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", profile.uuid)
        assertEquals("PUBLICKEY1234567890abcdefghijklmn", profile.realityPublicKey)
        assertEquals("abcd1234", profile.realityShortId)
        assertEquals("target.example.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("chrome", profile.fingerprint)
        assertNull(profile.xhttpPath)
        assertNull(profile.xhttpHost)
        assertEquals(groupId, profile.groupId)
    }

    @Test
    fun `singbox vless with reality detected by non-empty public_key even without enabled flag`() {
        val json =
            """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "reality-no-flag",
                  "server": "edge2.example.com",
                  "server_port": 443,
                  "uuid": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "tls": {
                    "server_name": "sni.example.com",
                    "reality": {
                      "public_key": "ANOTHERKEY0987654321zyxwvutsrqpon",
                      "short_id": "ff001122"
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        val result = SingBoxSubscriptionParser.parse(json, groupId)
        val profiles = (result as SingBoxParseResult.Success).profiles
        val profile = profiles.single()
        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("ANOTHERKEY0987654321zyxwvutsrqpon", profile.realityPublicKey)
        assertEquals("ff001122", profile.realityShortId)
        assertEquals("sni.example.com", profile.serverName)
        // flow defaults to xtls-rprx-vision when omitted
        assertEquals("xtls-rprx-vision", profile.flow)
        assertNull(profile.fingerprint)
    }

    @Test
    fun `singbox vless without reality produces plain Vless not VlessReality`() {
        val json =
            """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "plain-vless",
                  "server": "plain.example.com",
                  "server_port": 443,
                  "uuid": "cccccccc-cccc-cccc-cccc-cccccccccccc"
                }
              ]
            }
            """.trimIndent()

        val result = SingBoxSubscriptionParser.parse(json, groupId)
        val profiles = (result as SingBoxParseResult.Success).profiles
        val profile = profiles.single()
        assertTrue("expected Vless, got ${profile::class.simpleName}", profile is ProxyProfile.Vless)
    }

    @Test
    fun `singbox plain vless xhttp preserves tls identity and transport`() {
        val json =
            """
            { "type": "vless", "tag": "plain-xhttp", "server": "203.0.113.4", "server_port": 443,
              "uuid": "cccccccc-cccc-cccc-cccc-cccccccccccc", "flow": "",
              "tls": { "enabled": true, "server_name": "cdn.example", "utls": { "fingerprint": "firefox" } },
              "transport": { "type": "xhttp", "path": "", "host": "", "mode": "auto" } }
            """.trimIndent()

        val profile =
            (SingBoxSubscriptionParser.parse(json, groupId) as SingBoxParseResult.Success)
                .profiles
                .single() as ProxyProfile.Vless

        assertEquals("cdn.example", profile.serverName)
        assertEquals("", profile.flow)
        assertEquals("firefox", profile.fingerprint)
        assertEquals("", profile.xhttpPath)
        assertEquals("", profile.xhttpHost)
    }

    @Test
    fun `singbox vless reality with xhttp transport preserves path and host`() {
        val json =
            """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "xhttp-node",
                  "server": "cdn.example.com",
                  "server_port": 443,
                  "uuid": "dddddddd-dddd-dddd-dddd-dddddddddddd",
                  "flow": "xtls-rprx-vision",
                  "tls": {
                    "server_name": "cdn.example.com",
                    "reality": {
                      "enabled": true,
                      "public_key": "XHTTPKEY1234567890abcdefghijklmn",
                      "short_id": "cafe0001"
                    }
                  },
                  "transport": {
                    "type": "xhttp",
                    "path": "/tunnel",
                    "host": "cdn.example.com"
                  }
                }
              ]
            }
            """.trimIndent()

        val result = SingBoxSubscriptionParser.parse(json, groupId)
        val profile = (result as SingBoxParseResult.Success).profiles.single() as ProxyProfile.VlessReality
        assertEquals("/tunnel", profile.xhttpPath)
        assertEquals("cdn.example.com", profile.xhttpHost)
    }

    @Test
    fun `empty xhttp and empty flow survive uri round trip`() {
        val uri =
            "vless://dddddddd-dddd-dddd-dddd-dddddddddddd@cdn.example.com:443" +
                "?security=reality&pbk=XHTTPKEY1234567890abcdefghijklmn&sni=cdn.example.com" +
                "&type=xhttp&path=&host=&flow=#empty-xhttp"

        val profile = ProxyUriCodec.parse(uri) as ProxyProfile.VlessReality
        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile)) as ProxyProfile.VlessReality

        assertEquals("", reparsed.flow)
        assertEquals("", reparsed.xhttpPath)
        assertEquals("", reparsed.xhttpHost)
    }

    @Test
    fun `hysteria identity and authentication survive uri round trip`() {
        val passwordFixture = "test-value"
        val obfsPasswordFixture = "obfs-test-value"
        val source =
            ProxyProfile.Hysteria2(
                id = "id",
                displayName = "hy2",
                groupId = groupId,
                server = "203.0.113.9",
                serverPort = 443,
                password = passwordFixture,
                serverName = "hy.example",
                obfsPassword = obfsPasswordFixture,
                insecure = true,
            )

        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(source)) as ProxyProfile.Hysteria2

        assertEquals("hy.example", reparsed.serverName)
        assertEquals(obfsPasswordFixture, reparsed.obfsPassword)
        assertEquals(true, reparsed.insecure)
    }

    // --- URI codec -------------------------------------------------------------------

    @Test
    fun `vless URI with security=reality and pbk produces VlessReality with all fields`() {
        val uri =
            "vless://aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa@edge.example.com:443" +
                "?security=reality&pbk=PUBLICKEY1234567890abcdefghijklmn" +
                "&sid=abcd1234&sni=target.example.com&flow=xtls-rprx-vision&fp=chrome" +
                "#reality-prod"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue("expected VlessReality, got ${profile?.javaClass?.simpleName}", profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", profile.uuid)
        assertEquals("PUBLICKEY1234567890abcdefghijklmn", profile.realityPublicKey)
        assertEquals("abcd1234", profile.realityShortId)
        assertEquals("target.example.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("chrome", profile.fingerprint)
        assertEquals("reality-prod", profile.displayName)
        assertNull(profile.xhttpPath)
        assertNull(profile.xhttpHost)
    }

    @Test
    fun `vless URI with pbk but no explicit security=reality produces VlessReality`() {
        val uri =
            "vless://bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb@edge2.example.com:443" +
                "?pbk=ANOTHERKEY0987654321zyxwvutsrqpon&sid=ff001122&sni=sni.example.com" +
                "#pbk-only"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("ANOTHERKEY0987654321zyxwvutsrqpon", profile.realityPublicKey)
        assertEquals("ff001122", profile.realityShortId)
        assertEquals("sni.example.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertNull(profile.fingerprint)
    }

    @Test
    fun `plain vless URI without reality params produces Vless not VlessReality`() {
        val uri = "vless://cccccccc-cccc-cccc-cccc-cccccccccccc@plain.example.com:443?type=tcp#plain"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue("expected Vless, got ${profile?.javaClass?.simpleName}", profile is ProxyProfile.Vless)
    }

    @Test
    fun `plain vless xhttp uri preserves identity and empty transport`() {
        val uri =
            "vless://cccccccc-cccc-cccc-cccc-cccccccccccc@203.0.113.4:443" +
                "?security=tls&sni=cdn.example&type=xhttp&path=&host=&flow=&fp=firefox#plain"

        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(ProxyUriCodec.parse(uri)!!)) as ProxyProfile.Vless

        assertEquals("cdn.example", reparsed.serverName)
        assertEquals("", reparsed.flow)
        assertEquals("firefox", reparsed.fingerprint)
        assertEquals("", reparsed.xhttpPath)
        assertEquals("", reparsed.xhttpHost)
    }

    @Test
    fun `vless URI with xhttp transport and reality preserves path and host`() {
        val uri =
            "vless://dddddddd-dddd-dddd-dddd-dddddddddddd@cdn.example.com:443" +
                "?security=reality&pbk=XHTTPKEY1234567890abcdefghijklmn&sid=cafe0001" +
                "&sni=cdn.example.com&type=xhttp&path=%2Ftunnel&host=cdn.example.com&mode=stream-one" +
                "#xhttp-node"

        val profile = ProxyUriCodec.parse(uri)

        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("/tunnel", profile.xhttpPath)
        assertEquals("cdn.example.com", profile.xhttpHost)
        assertEquals("stream-one", profile.xhttpMode)
    }

    // --- Clash parser ----------------------------------------------------------------

    @Test
    fun `clash vless with reality-opts produces VlessReality with all fields`() {
        val yaml =
            """
            proxies:
              - name: clash-reality-node
                type: vless
                server: edge.example.com
                port: 443
                uuid: aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
                flow: xtls-rprx-vision
                servername: target.example.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: PUBLICKEY1234567890abcdefghijklmn
                  short-id: abcd1234
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)
        assertTrue("errors: ${result.errors}", result.errors.isEmpty())
        val profile = result.profiles.single()
        assertTrue("expected VlessReality, got ${profile::class.simpleName}", profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality

        assertEquals("clash-reality-node", profile.displayName)
        assertEquals("edge.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", profile.uuid)
        assertEquals("PUBLICKEY1234567890abcdefghijklmn", profile.realityPublicKey)
        assertEquals("abcd1234", profile.realityShortId)
        assertEquals("target.example.com", profile.serverName)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("chrome", profile.fingerprint)
        assertEquals(groupId, profile.groupId)
    }

    @Test
    fun `clash vless without reality-opts produces plain Vless`() {
        val yaml =
            """
            proxies:
              - name: plain-clash-vless
                type: vless
                server: plain.example.com
                port: 443
                uuid: cccccccc-cccc-cccc-cccc-cccccccccccc
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)
        assertTrue(result.errors.isEmpty())
        val profile = result.profiles.single()
        assertTrue("expected Vless, got ${profile::class.simpleName}", profile is ProxyProfile.Vless)
    }
}
