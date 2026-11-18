package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyUriCodecTrojanAndHysteria2Test {
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
    fun `trojan uri rejects reality plaintext and insecure nodes`() {
        listOf(
            "security=reality&pbk=abc",
            "security=none",
            "allowInsecure=1",
            "insecure=true",
        ).forEach { query ->
            assertNull(query, ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443?$query#t"))
        }
        val tls = ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443?security=tls&allowInsecure=0#t")
        assertTrue(tls is ProxyProfile.Trojan)
    }

    @Test
    fun `trojan uri reads the legacy peer alias as sni after sni`() {
        val peer =
            ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443?peer=mask.example.com#t") as ProxyProfile.Trojan
        assertEquals("mask.example.com", peer.serverName)
        val both =
            ProxyUriCodec.parse("trojan://fixture-pw@1.2.3.4:443?peer=b.example&sni=a.example#t") as ProxyProfile.Trojan
        assertEquals("a.example", both.serverName)
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
    fun `hysteria2 uri rejects salamander without a usable key`() {
        val base = "hysteria2://fixture-pw@h.example:8443?obfs=salamander"
        assertNull(ProxyUriCodec.parse("$base#n"))
        assertNull(ProxyUriCodec.parse("$base&obfs-password=#n"))
        assertNull(ProxyUriCodec.parse("$base&obfs-password=abc#n"))
        assertTrue(ProxyUriCodec.parse("$base&obfs-password=abcd#n") is ProxyProfile.Hysteria2)
    }

    @Test
    fun `hysteria2 uri ignores obfs-password without obfs`() {
        val parsed = ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:8443?obfs-password=fixture-obfs#n")
        assertTrue(parsed is ProxyProfile.Hysteria2)
        assertNull((parsed as ProxyProfile.Hysteria2).obfsPassword)
    }

    @Test
    fun `hysteria2 uri rejects ech it cannot honour`() {
        val base = "hysteria2://fixture-pw@h.example:8443"
        assertNull(ProxyUriCodec.parse("$base?ech=AEn%2BDQ#n"))
        assertTrue(ProxyUriCodec.parse("$base?ech=#n") is ProxyProfile.Hysteria2)
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
        assertNull(ProxyUriCodec.parse("$base#n"))
        assertNull(ProxyUriCodec.parse("${base}abcd#n"))
        assertNull(ProxyUriCodec.parse("hy2://fixture-pw@h.example:443?insecure=1&pinSHA256"))
        assertNull(ProxyUriCodec.parse("hy2://fixture-pw@h.example:443?insecure=1&insecure=0"))
        val pin = "ac".repeat(32)
        assertNull(ProxyUriCodec.parse("$base$pin&PINSHA256=$pin#n"))
        assertNull(ProxyUriCodec.parse("$base${"zz".repeat(32)}#n"))
    }

    @Test
    fun `hysteria2 uri without a pin leaves pinSha256 null`() {
        val parsed = ProxyUriCodec.parse("hysteria2://fixture-pw@h.example:443?insecure=1#n") as ProxyProfile.Hysteria2

        assertNull(parsed.pinSha256)
        assertTrue(!ProxyUriCodec.encode(parsed).contains("pinSHA256"))
    }

    private companion object {
        val normalizedPin = (0 until 32).joinToString("") { "%02x".format(it * 7 + 3) }
        val colonUpperPin = normalizedPin.uppercase().chunked(2).joinToString(":")
    }
}
