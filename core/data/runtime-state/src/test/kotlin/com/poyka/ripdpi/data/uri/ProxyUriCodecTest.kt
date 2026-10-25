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
                "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                    "?security=reality&pbk=PUBLICKEY123&sni=cdn.example#ok",
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
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443" +
                    "?security=reality&pbk=PUBLICKEY123&sni=cdn.example#v6",
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
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443" +
                    "?security=reality&pbk=PUBLICKEY123&sni=cdn.example#v6",
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

    @Test
    fun `shadowsocks ipv6 host debrackets on import and round-trips`() {
        // Shadowsocks parses its host via splitHostPort, not URI.getHost, so it
        // needs the same unbracketing as the URI.getHost parsers.
        val original =
            ProxyProfile.Shadowsocks(
                id = "ss",
                displayName = "ss v6",
                groupId = "",
                server = "2001:db8::1",
                serverPort = 8388,
                method = "aes-256-gcm",
                password = "fixture-password",
            )

        val encoded = ProxyUriCodec.encode(original)
        assertTrue(encoded.contains("@[2001:db8::1]:8388"))

        val reparsed = ProxyUriCodec.parse(encoded)
        assertTrue(reparsed is ProxyProfile.Shadowsocks)
        reparsed as ProxyProfile.Shadowsocks
        assertEquals("2001:db8::1", reparsed.server)
        assertEquals(8388, reparsed.serverPort)
        assertEquals("aes-256-gcm", reparsed.method)
        assertEquals("fixture-password", reparsed.password)
    }

    @Test
    fun `shadowsocks uri with a SIP003 plugin is rejected (P1-6)`() {
        // obfs-local / v2ray-plugin needs an out-of-process transport RIPDPI does
        // not bundle; importing as plain ss would silently fail to connect.
        assertNull(
            ProxyUriCodec.parse(
                "ss://aes-256-gcm:fixture-pw@host.example.com:8388?plugin=obfs-local%3Bobfs%3Dtls#node",
            ),
        )
    }

    @Test
    fun `shadowsocks uri without a plugin still parses`() {
        val parsed = ProxyUriCodec.parse("ss://aes-256-gcm:fixture-pw@host.example.com:8388#node")
        assertTrue(parsed is ProxyProfile.Shadowsocks)
    }

    @Test
    fun `trojan uri preserves sni and round-trips (P1-4)`() {
        val parsed = ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443?sni=mask.example.com#t")
        assertTrue(parsed is ProxyProfile.Trojan)
        parsed as ProxyProfile.Trojan
        assertEquals("mask.example.com", parsed.serverName)

        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(parsed))
        assertTrue(reparsed is ProxyProfile.Trojan)
        assertEquals("mask.example.com", (reparsed as ProxyProfile.Trojan).serverName)
    }

    @Test
    fun `trojan uri without sni leaves serverName null`() {
        val parsed = ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443#t")
        assertTrue(parsed is ProxyProfile.Trojan)
        assertNull((parsed as ProxyProfile.Trojan).serverName)
    }

    @Test
    fun `trojan uri advertising a ws transport is rejected (P1-5)`() {
        // The native trojan backend is TLS-only; a ws/grpc node must fail loudly.
        assertNull(ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443?type=ws&path=/ws&host=cdn.example.com#t"))
    }

    @Test
    fun `hysteria2 uri preserves salamander obfs-password and round-trips (P1-8)`() {
        val parsed =
            ProxyUriCodec.parse(
                "hysteria2://fixture-pw@h.example:8443?obfs=salamander&obfs-password=fixture-obfs#n",
            )
        assertTrue(parsed is ProxyProfile.Hysteria2)
        parsed as ProxyProfile.Hysteria2
        assertEquals("fixture-obfs", parsed.obfsPassword)

        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(parsed))
        assertTrue(reparsed is ProxyProfile.Hysteria2)
        assertEquals("fixture-obfs", (reparsed as ProxyProfile.Hysteria2).obfsPassword)
    }

    @Test
    fun `hysteria2 uri without obfs leaves obfsPassword null`() {
        val parsed = ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:8443#n")
        assertTrue(parsed is ProxyProfile.Hysteria2)
        assertNull((parsed as ProxyProfile.Hysteria2).obfsPassword)
    }

    @Test
    fun `hysteria2 uri preserves the insecure flag and round-trips (P1-9)`() {
        val parsed = ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:8443?insecure=1#n")
        assertTrue(parsed is ProxyProfile.Hysteria2)
        assertEquals(true, (parsed as ProxyProfile.Hysteria2).insecure)

        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(parsed))
        assertTrue(reparsed is ProxyProfile.Hysteria2)
        assertEquals(true, (reparsed as ProxyProfile.Hysteria2).insecure)
    }

    @Test
    fun `hy2 uri keeps the pinSHA256 certificate pin next to insecure (REL-3)`() {
        val parsed =
            ProxyUriCodec.parse("hy2://fixture-pw@h.example:443?insecure=1&pinSHA256=$colonUpperPin#n")
                as ProxyProfile.Hysteria2

        assertEquals(true, parsed.insecure)
        assertEquals(normalizedPin, parsed.pinSha256)
    }

    @Test
    fun `hysteria2 pin accepts plain lowercase hex and a case-insensitive key`() {
        val parsed =
            ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:443?insecure=1&pinsha256=$normalizedPin#n")
                as ProxyProfile.Hysteria2

        assertEquals(normalizedPin, parsed.pinSha256)
    }

    @Test
    fun `hysteria2 pin round-trips through the share uri`() {
        val parsed =
            ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:443?insecure=1&pinSHA256=$colonUpperPin#n")
                as ProxyProfile.Hysteria2

        val reparsed = ProxyUriCodec.parse(ProxyUriCodec.encode(parsed)) as ProxyProfile.Hysteria2

        assertEquals(true, reparsed.insecure)
        assertEquals(normalizedPin, reparsed.pinSha256)
    }

    @Test
    fun `hysteria2 uri with a malformed pin is rejected instead of importing accept-any`() {
        val base = "hysteria2://fixture-pw@h.example:443?insecure=1&pinSHA256="
        assertNull(ProxyUriCodec.parse("${base}abcd#n"))
        assertNull(ProxyUriCodec.parse("$base${"zz".repeat(32)}#n"))
    }

    @Test
    fun `hysteria2 uri without a pin leaves pinSha256 null`() {
        val parsed = ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:443?insecure=1#n") as ProxyProfile.Hysteria2

        assertNull(parsed.pinSha256)
        assertTrue(!ProxyUriCodec.encode(parsed).contains("pinSHA256"))
    }

    @Test
    fun `shadowsocks plain userinfo is percent-decoded per SIP002`() {
        // SIP022 example link: the base64 PSK is percent-encoded in plain-form userinfo.
        val sip022FixtureLink =
            "ss://2022-blake3-aes-256-gcm:" +
                "YctPZ6U7xPPcU%2Bgp3u%2B0tx%2FtRizJN9K8y%2BuKlW2qjlI%3D@192.168.100.1:8888#Example3" // fixture
        val sip022FixturePsk = "YctPZ6U7xPPcU+gp3u+0tx/tRizJN9K8y+uKlW2qjlI="

        val parsed = ProxyUriCodec.parse(sip022FixtureLink) as ProxyProfile.Shadowsocks

        assertEquals("2022-blake3-aes-256-gcm", parsed.method)
        assertEquals(sip022FixturePsk, parsed.password)
    }

    @Test
    fun `shadowsocks plain userinfo keeps a literal plus sign`() {
        // RFC 3986 userinfo decoding: '+' is data, never a space.
        val parsed =
            ProxyUriCodec.parse("ss://aes-256-gcm:fixture+pass%20word@192.0.2.1:8388") as ProxyProfile.Shadowsocks

        assertEquals("fixture+pass word", parsed.password)
    }

    private companion object {
        val normalizedPin = (0 until 32).joinToString("") { "%02x".format(it * 7 + 3) }
        val colonUpperPin = normalizedPin.uppercase().chunked(2).joinToString(":")
    }
}
