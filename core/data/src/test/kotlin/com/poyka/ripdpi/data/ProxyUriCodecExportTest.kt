package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export-side codec tests. The encoder lives beside [ProxyUriCodec.parse] so generated
 * links stay exactly in the formats the existing import path accepts.
 */
class ProxyUriCodecExportTest {
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

        val uri = ProxyUriCodec.encode(profile)

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

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

        assertTrue(parsed is ProxyProfile.Vless)
        parsed as ProxyProfile.Vless
        assertEquals("edge.example.com", parsed.server)
        assertEquals(8443, parsed.serverPort)
        assertEquals("abc-uuid", parsed.uuid)
    }

    @Test
    fun `vless reality xhttp profile round-trips through the shared codec`() {
        val profile =
            ProxyProfile.VlessReality(
                id = "p1",
                displayName = "Reality XHTTP",
                groupId = "g1",
                server = "edge.example.com",
                serverPort = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
                realityPublicKey = "public-key",
                realityShortId = "ab12",
                serverName = "front.example.com",
                flow = "xtls-rprx-vision",
                fingerprint = "chrome",
                xhttpPath = "/x",
                xhttpHost = "cdn.example.com",
            )

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

        assertTrue(parsed is ProxyProfile.VlessReality)
        parsed as ProxyProfile.VlessReality
        assertEquals("edge.example.com", parsed.server)
        assertEquals("public-key", parsed.realityPublicKey)
        assertEquals("/x", parsed.xhttpPath)
        assertEquals("cdn.example.com", parsed.xhttpHost)
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

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

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

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

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

        val uri = ProxyUriCodec.encode(profile)

        assertTrue(uri.startsWith("hysteria2://"))
        val parsed = ProxyUriCodec.parse(uri)
        assertTrue(parsed is ProxyProfile.Hysteria2)
    }

    @Test
    fun `anytls profile encodes and round-trips`() {
        val profile =
            ProxyProfile.AnyTls(
                id = "p1",
                displayName = "AnyTLS",
                groupId = "g1",
                server = "any.example.com",
                serverPort = 443,
                serverName = "front.example.com",
                password = "any-pass-fixture",
            )

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

        assertTrue(parsed is ProxyProfile.AnyTls)
        parsed as ProxyProfile.AnyTls
        assertEquals("front.example.com", parsed.serverName)
        assertEquals("any-pass-fixture", parsed.password)
    }

    @Test
    fun `mieru profile encodes to a uri that round-trips to Mieru`() {
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

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

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

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

        assertTrue("expected Mieru, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Mieru)
        parsed as ProxyProfile.Mieru
        assertEquals("user name+fixture", parsed.username)
        assertEquals(specialPassword, parsed.password)
        assertEquals("tcp", parsed.protocol)
        assertEquals("middle", parsed.multiplexing)
        assertEquals(1400, parsed.mtu)
    }

    @Test
    fun `ssh password profile round-trips through the shared codec`() {
        val profile =
            ProxyProfile.Ssh(
                id = "p1",
                displayName = "Bastion Host",
                groupId = "g1",
                server = "vps.example.com",
                serverPort = 22,
                username = "ssh-user",
                authType = "password",
                password = "ssh-pass-fixture",
                hostKeyFingerprint = "SHA256:abc123",
                strictHostKey = true,
            )

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

        assertTrue("expected Ssh, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Ssh)
        parsed as ProxyProfile.Ssh
        assertEquals("vps.example.com", parsed.server)
        assertEquals(22, parsed.serverPort)
        assertEquals("ssh-user", parsed.username)
        assertEquals("password", parsed.authType)
        assertEquals("ssh-pass-fixture", parsed.password)
        assertEquals("SHA256:abc123", parsed.hostKeyFingerprint)
        assertTrue(parsed.strictHostKey)
    }

    @Test
    fun `ssh private-key profile round-trips with multi-line pem in the query`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaA\nFIXTURE+key/data\n-----END OPENSSH PRIVATE KEY-----"
        val profile =
            ProxyProfile.Ssh(
                id = "p1",
                displayName = "Key Auth",
                groupId = "g1",
                server = "vps.example.com",
                serverPort = 2222,
                username = "ssh user+fixture",
                authType = "private_key",
                privateKey = pem,
                privateKeyPassphrase = "pp:phrase/fixture #1",
            )

        val parsed = ProxyUriCodec.parse(ProxyUriCodec.encode(profile))

        assertTrue("expected Ssh, got ${parsed?.javaClass?.simpleName}", parsed is ProxyProfile.Ssh)
        parsed as ProxyProfile.Ssh
        assertEquals("ssh user+fixture", parsed.username)
        assertEquals("private_key", parsed.authType)
        assertNull(parsed.password)
        assertEquals(pem, parsed.privateKey)
        assertEquals("pp:phrase/fixture #1", parsed.privateKeyPassphrase)
    }

    @Test
    fun `anytls uri advertising a fallback target is rejected`() {
        val base = "anytls://any-pass@front.example.com:8443?sni=front.example.com"
        val withFallback = "$base&fallback=real.example.com:443"
        val withoutFallback = base

        assertNull("fallback-bearing anytls node must be rejected", ProxyUriCodec.parse(withFallback))
        assertTrue(ProxyUriCodec.parse(withoutFallback) is ProxyProfile.AnyTls)
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

        val uri = ProxyUriCodec.encode(profile)

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

        assertEquals(rawUri, ProxyUriCodec.encode(profile))
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

        assertNull(ProxyUriCodec.encodeOrNull(profile))
    }
}
