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
