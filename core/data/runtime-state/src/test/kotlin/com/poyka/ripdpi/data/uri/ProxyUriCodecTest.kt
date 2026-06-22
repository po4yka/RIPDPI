package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyUriCodecTest {
    @Test
    fun `vless reality uri without pbk is rejected`() {
        assertNull(
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=reality&sni=cdn.example#bad",
            ),
        )
    }

    @Test
    fun `vless reality uri with blank pbk is rejected`() {
        assertNull(
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=reality&pbk=%20#bad",
            ),
        )
    }

    @Test
    fun `vless reality uri with pbk imports reality profile`() {
        val profile =
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=reality&pbk=PUBLICKEY123&sni=cdn.example#ok",
            )

        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        assertEquals("PUBLICKEY123", profile.realityPublicKey)
        assertEquals("cdn.example", profile.serverName)
    }

    @Test
    fun `plain vless uri remains supported`() {
        val profile =
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443#plain",
            )

        assertTrue(profile is ProxyProfile.Vless)
    }

    @Test
    fun `vless reality ipv6 literal host is stored without brackets`() {
        val profile =
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443?security=reality&pbk=PUBLICKEY123&sni=cdn.example#v6",
            )

        assertTrue(profile is ProxyProfile.VlessReality)
        profile as ProxyProfile.VlessReality
        // java.net.URI.getHost() returns the bracketed form `[2001:db8::1]`; the
        // native connect path parses `server` with IpAddr::parse, which rejects
        // brackets. The stored value must be the bare literal.
        assertEquals("2001:db8::1", profile.server)
    }

    @Test
    fun `vless reality ipv6 host round-trips through encode and parse`() {
        val original =
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443?security=reality&pbk=PUBLICKEY123&sni=cdn.example#v6",
            )
        assertTrue(original is ProxyProfile.VlessReality)

        val encoded = ProxyUriCodec.encode(original as ProxyProfile)
        // The IPv6 authority must be re-bracketed so the share URI is unambiguous.
        assertTrue(encoded.contains("@[2001:db8::1]:443"))

        val reparsed = ProxyUriCodec.parse(encoded)
        assertTrue(reparsed is ProxyProfile.VlessReality)
        assertEquals("2001:db8::1", (reparsed as ProxyProfile.VlessReality).server)
    }

    @Test
    fun `plain vless ipv6 host debrackets on import and re-brackets on export`() {
        val profile =
            ProxyUriCodec.parse(
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443#v6plain",
            )
        assertTrue(profile is ProxyProfile.Vless)
        assertEquals("2001:db8::1", (profile as ProxyProfile.Vless).server)

        // Exercises the shared userInfoUri encode path (Vless / Trojan / Hysteria2).
        assertTrue(ProxyUriCodec.encode(profile).contains("@[2001:db8::1]:443"))
    }
}
