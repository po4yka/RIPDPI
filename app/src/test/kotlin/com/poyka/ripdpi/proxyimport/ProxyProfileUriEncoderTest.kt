package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ProxyProfileUriEncoder]: the offline encoder that turns a saved
 * [ProxyProfile] back into a canonical per-protocol share URI. The encoder is the inverse
 * of [ProxyUriCodec]; round-tripping a profile through encode -> parse must preserve the
 * endpoint identity. No `sn://` universal scheme is ever emitted.
 */
class ProxyProfileUriEncoderTest {
    @Test
    fun `vless profile encodes to a canonical vless uri`() {
        val profile =
            ProxyProfile.Vless(
                id = "p1",
                displayName = "Tokyo",
                groupId = "g1",
                server = "example.com",
                serverPort = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        assertTrue(uri.startsWith("vless://"))
        assertTrue(uri.contains("11111111-2222-3333-4444-555555555555@example.com:443"))
    }

    @Test
    fun `vless uri round-trips through the shared codec`() {
        val profile =
            ProxyProfile.Vless(
                id = "p1",
                displayName = "Tokyo Edge",
                groupId = "g1",
                server = "edge.example.com",
                serverPort = 8443,
                uuid = "abc-uuid",
            )

        val parsed = ProxyUriCodec.parse(ProxyProfileUriEncoder.encode(profile))

        assertTrue(parsed is ProxyProfile.Vless)
        parsed as ProxyProfile.Vless
        assertEquals("edge.example.com", parsed.server)
        assertEquals(8443, parsed.serverPort)
        assertEquals("abc-uuid", parsed.uuid)
    }

    @Test
    fun `shadowsocks profile encodes to a SIP002 uri that round-trips`() {
        val profile =
            ProxyProfile.Shadowsocks(
                id = "p1",
                displayName = "SS Node",
                groupId = "g1",
                server = "ss.example.com",
                serverPort = 8388,
                method = "aes-256-gcm",
                password = "s3cret",
            )

        val parsed = ProxyUriCodec.parse(ProxyProfileUriEncoder.encode(profile))

        assertTrue(parsed is ProxyProfile.Shadowsocks)
        parsed as ProxyProfile.Shadowsocks
        assertEquals("ss.example.com", parsed.server)
        assertEquals(8388, parsed.serverPort)
        assertEquals("aes-256-gcm", parsed.method)
        assertEquals("s3cret", parsed.password)
    }

    @Test
    fun `trojan profile encodes and round-trips`() {
        val profile =
            ProxyProfile.Trojan(
                id = "p1",
                displayName = "Trojan",
                groupId = "g1",
                server = "trojan.example.com",
                serverPort = 443,
                password = "trojan-pass-fixture",
            )

        val parsed = ProxyUriCodec.parse(ProxyProfileUriEncoder.encode(profile))

        assertTrue(parsed is ProxyProfile.Trojan)
        parsed as ProxyProfile.Trojan
        assertEquals("trojan.example.com", parsed.server)
        assertEquals("trojan-pass-fixture", parsed.password)
    }

    @Test
    fun `hysteria2 profile encodes to the canonical hy2 scheme`() {
        val profile =
            ProxyProfile.Hysteria2(
                id = "p1",
                displayName = "Hy2",
                groupId = "g1",
                server = "hy2.example.com",
                serverPort = 443,
                password = "hy2-pass-fixture",
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        assertTrue(uri.startsWith("hysteria2://"))
        val parsed = ProxyUriCodec.parse(uri)
        assertTrue(parsed is ProxyProfile.Hysteria2)
    }

    @Test
    fun `vmess profile encodes to a standard uri that round-trips to Vmess`() {
        val profile =
            ProxyProfile.Vmess(
                id = "p1",
                displayName = "VMess Node",
                groupId = "g1",
                server = "vmess.example.com",
                serverPort = 443,
                uuid = "b831ebf8-1e3a-4f2b-8c4d-0e1f2a3b4c5d",
                security = "chacha20-poly1305",
                transport = "tcp",
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        assertTrue(uri.startsWith("vmess://"))
        val parsed = ProxyUriCodec.parse(uri)
        assertTrue("expected Vmess, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Vmess)
        parsed as ProxyProfile.Vmess
        assertEquals("vmess.example.com", parsed.server)
        assertEquals(443, parsed.serverPort)
        assertEquals("b831ebf8-1e3a-4f2b-8c4d-0e1f2a3b4c5d", parsed.uuid)
        assertEquals("chacha20-poly1305", parsed.security)
        assertEquals("tcp", parsed.transport)
    }

    @Test
    fun `vmess ws profile carries its transport params across encode-parse`() {
        val profile =
            ProxyProfile.Vmess(
                id = "p1",
                displayName = "VMess WS",
                groupId = "g1",
                server = "ws.example.com",
                serverPort = 8443,
                uuid = "11111111-2222-4333-8444-555555555555",
                security = "aes-128-gcm",
                transport = "ws",
                wsPath = "/ray",
                wsHost = "cdn.example.com",
            )

        val parsed = ProxyUriCodec.parse(ProxyProfileUriEncoder.encode(profile))

        assertTrue(parsed is ProxyProfile.Vmess)
        parsed as ProxyProfile.Vmess
        assertEquals("ws", parsed.transport)
        assertEquals("/ray", parsed.wsPath)
        assertEquals("cdn.example.com", parsed.wsHost)
    }

    @Test
    fun `vmess base64 json clash form parses to Vmess`() {
        val jsonNode =
            """{"v":"2","ps":"Clash Node","add":"clash.example.com","port":"10086",""" +
                """"id":"99999999-8888-4777-8666-555544443333","net":"ws","path":"/vm","host":"h.example.com","scy":"aes-128-gcm"}"""
        val encoded =
            java.util.Base64
                .getEncoder()
                .encodeToString(jsonNode.toByteArray(Charsets.UTF_8))

        val parsed = ProxyUriCodec.parse("vmess://$encoded")

        assertTrue("expected Vmess, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Vmess)
        parsed as ProxyProfile.Vmess
        assertEquals("clash.example.com", parsed.server)
        assertEquals(10086, parsed.serverPort)
        assertEquals("99999999-8888-4777-8666-555544443333", parsed.uuid)
        assertEquals("ws", parsed.transport)
        assertEquals("/vm", parsed.wsPath)
        assertEquals("h.example.com", parsed.wsHost)
    }

    @Test
    fun `trojan-go profile encodes to a standard uri that round-trips to TrojanGo`() {
        val profile =
            ProxyProfile.TrojanGo(
                id = "p1",
                displayName = "Trojan-Go Node",
                groupId = "g1",
                server = "tg.example.com",
                serverPort = 443,
                password = "trojan-go-pass-fixture",
                sni = "tg.example.com",
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        assertTrue(uri.startsWith("trojan-go://"))
        val parsed = ProxyUriCodec.parse(uri)
        assertTrue("expected TrojanGo, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.TrojanGo)
        parsed as ProxyProfile.TrojanGo
        assertEquals("tg.example.com", parsed.server)
        assertEquals(443, parsed.serverPort)
        assertEquals("trojan-go-pass-fixture", parsed.password)
        assertEquals("tg.example.com", parsed.sni)
        assertEquals("off", parsed.mux)
        assertEquals("none", parsed.innerCipher)
    }

    @Test
    fun `trojan-go ws and mux and inner-cipher round-trip across encode-parse`() {
        val profile =
            ProxyProfile.TrojanGo(
                id = "p1",
                displayName = "Trojan-Go Full",
                groupId = "g1",
                server = "tg.example.com",
                serverPort = 8443,
                password = "s3cr3t",
                sni = "edge.example.com",
                wsPath = "/tg",
                wsHost = "cdn.example.com",
                mux = "smux_v1",
                innerCipher = "aes-256-gcm",
            )

        val parsed = ProxyUriCodec.parse(ProxyProfileUriEncoder.encode(profile))

        assertTrue(parsed is ProxyProfile.TrojanGo)
        parsed as ProxyProfile.TrojanGo
        assertEquals("edge.example.com", parsed.sni)
        assertEquals("/tg", parsed.wsPath)
        assertEquals("cdn.example.com", parsed.wsHost)
        assertEquals("smux_v1", parsed.mux)
        assertEquals("aes-256-gcm", parsed.innerCipher)
    }

    @Test
    fun `mieru profile encodes to a standard uri that round-trips to Mieru`() {
        val profile =
            ProxyProfile.Mieru(
                id = "p1",
                displayName = "Mieru Node",
                groupId = "g1",
                server = "m.example.com",
                serverPort = 2096,
                username = "mieru-user",
                password = "mieru-pass-fixture",
                protocol = "udp",
                multiplexing = "high",
                mtu = 1380,
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        assertTrue(uri.startsWith("mieru://"))
        val parsed = ProxyUriCodec.parse(uri)
        assertTrue("expected Mieru, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Mieru)
        parsed as ProxyProfile.Mieru
        assertEquals("m.example.com", parsed.server)
        assertEquals(2096, parsed.serverPort)
        assertEquals("mieru-user", parsed.username)
        assertEquals("mieru-pass-fixture", parsed.password)
        assertEquals("udp", parsed.protocol)
        assertEquals("high", parsed.multiplexing)
        assertEquals(1380, parsed.mtu)
    }

    @Test
    fun `mieru password with special characters round-trips via percent-encoding`() {
        // The password contains URI-significant characters (':', '@', '/', '?',
        // '#', '&', '%', ' ') to prove userinfo percent-encoding both ways.
        val specialPassword = "p@ss:w/ord?#&% fixture"
        val profile =
            ProxyProfile.Mieru(
                id = "p1",
                displayName = "Mieru Special",
                groupId = "g1",
                server = "m.example.com",
                serverPort = 2096,
                username = "user name+fixture",
                password = specialPassword,
            )

        val uri = ProxyProfileUriEncoder.encode(profile)
        val parsed = ProxyUriCodec.parse(uri)

        assertTrue("expected Mieru, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Mieru)
        parsed as ProxyProfile.Mieru
        assertEquals("user name+fixture", parsed.username)
        assertEquals(specialPassword, parsed.password)
        // Absent query overrides default to the canonical Mieru values.
        assertEquals("tcp", parsed.protocol)
        assertEquals("middle", parsed.multiplexing)
        assertEquals(1400, parsed.mtu)
    }

    @Test
    fun `hysteria v1 profile encodes to a standard uri that round-trips to HysteriaV1`() {
        val profile =
            ProxyProfile.HysteriaV1(
                id = "p1",
                displayName = "Hysteria v1 Node",
                groupId = "g1",
                server = "h1.example.com",
                serverPort = 2096,
                authType = "string",
                authPayload = "hy1-auth-fixture",
                obfuscation = "hy1-obfs-fixture",
                protocol = "wechat-video",
                upMbps = 20,
                downMbps = 100,
                sni = "sni.example.com",
                alpn = "h3",
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        assertTrue("expected bare hysteria:// scheme, got $uri", uri.startsWith("hysteria://"))
        val parsed = ProxyUriCodec.parse(uri)
        assertTrue("expected HysteriaV1, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.HysteriaV1)
        parsed as ProxyProfile.HysteriaV1
        assertEquals("h1.example.com", parsed.server)
        assertEquals(2096, parsed.serverPort)
        assertEquals("string", parsed.authType)
        assertEquals("hy1-auth-fixture", parsed.authPayload)
        assertEquals("hy1-obfs-fixture", parsed.obfuscation)
        assertEquals("wechat-video", parsed.protocol)
        assertEquals(20, parsed.upMbps)
        assertEquals(100, parsed.downMbps)
        assertEquals("sni.example.com", parsed.sni)
        assertEquals("h3", parsed.alpn)
    }

    @Test
    fun `hysteria v1 base64 auth payload round-trips via percent-encoding`() {
        // A base64 auth payload contains URI-significant characters ('+', '/',
        // '=') that MUST be percent-encoded in the query both ways.
        val base64Auth = "YWJjZA+/aGVsbG8=fixture"
        val profile =
            ProxyProfile.HysteriaV1(
                id = "p1",
                displayName = "Hysteria v1 Base64",
                groupId = "g1",
                server = "h1.example.com",
                serverPort = 2096,
                authType = "base64",
                authPayload = base64Auth,
            )

        val uri = ProxyProfileUriEncoder.encode(profile)
        val parsed = ProxyUriCodec.parse(uri)

        assertTrue("expected HysteriaV1, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.HysteriaV1)
        parsed as ProxyProfile.HysteriaV1
        assertEquals("base64", parsed.authType)
        assertEquals(base64Auth, parsed.authPayload)
        // Absent query overrides default to the canonical Hysteria v1 values.
        assertEquals("udp", parsed.protocol)
        assertEquals(10, parsed.upMbps)
        assertEquals(50, parsed.downMbps)
        assertNull(parsed.obfuscation)
        assertNull(parsed.sni)
        assertNull(parsed.alpn)
    }

    @Test
    fun `display name is carried as a url-encoded fragment`() {
        val profile =
            ProxyProfile.Vless(
                id = "p1",
                displayName = "Tokyo Premium #1",
                groupId = "g1",
                server = "example.com",
                serverPort = 443,
                uuid = "uuid",
            )

        val uri = ProxyProfileUriEncoder.encode(profile)

        // The space and '#' must be percent-encoded so the fragment stays a single token.
        assertTrue(uri.contains("#"))
        assertTrue(uri.substringAfterLast('#').contains("%"))
        val parsed = ProxyUriCodec.parse(uri)
        assertEquals("Tokyo Premium #1", parsed?.displayName)
    }

    @Test
    fun `raw config profile that wraps a uri is emitted verbatim`() {
        val rawUri = "tuic://uuid:pass@tuic.example.com:443#TUIC"
        val profile =
            ProxyProfile.RawConfig(
                id = "p1",
                displayName = "TUIC",
                groupId = "g1",
                config = rawUri,
            )

        assertEquals(rawUri, ProxyProfileUriEncoder.encode(profile))
    }

    @Test
    fun `raw config profile that is not a uri yields null`() {
        val profile =
            ProxyProfile.RawConfig(
                id = "p1",
                displayName = "Opaque",
                groupId = "g1",
                config = "{\"outbounds\":[]}",
            )

        assertNull(ProxyProfileUriEncoder.encodeOrNull(profile))
    }
}
