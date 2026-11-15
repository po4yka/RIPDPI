package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TuicUriCodecTest {
    @Test
    fun `malformed TUIC Unicode cannot be exported`() {
        val input = "tuic://11111111-2222-3333-4444-555555555555:fixture@h.example"
        val profile = ProxyUriCodec.parse(input) as ProxyProfile.Tuic

        for (malformed in listOf("fixture\uD800", "fixture\uDC00")) {
            for (invalid in listOf(
                profile.copy(password = malformed),
                profile.copy(displayName = malformed),
                profile.copy(serverName = malformed),
                profile.copy(alpn = malformed),
            )) {
                assertNull(ProxyUriCodec.encodeOrNull(invalid))
            }
        }
    }

    @Test
    fun `native TUIC options and credentials survive URI round trip`() {
        val parsed =
            ProxyUriCodec.parse(
                "TuIc://11111111-2222-3333-4444-555555555555:fixture%2Bpass%3Aword@[2001:db8::1]:8443/" +
                    "?sni=tls.example&alpn=tuic%2Ch3&congestion_control=new_reno" +
                    "&udp_relay_mode=native#%D0%A3%D0%B7%D0%B5%D0%BB",
            ) as ProxyProfile.Tuic

        assertEquals("fixture+pass:word", parsed.password)
        assertEquals("2001:db8::1", parsed.server)
        assertEquals(8443, parsed.serverPort)
        assertEquals("tls.example", parsed.serverName)
        assertEquals("new_reno", parsed.congestionControl)
        assertEquals("tuic,h3", parsed.alpn)
        assertEquals("Узел", parsed.displayName)
        val restored = ProxyUriCodec.parse(ProxyUriCodec.encode(parsed)) as ProxyProfile.Tuic
        assertEquals(parsed, restored.copy(id = parsed.id))
        assertFalse(parsed.toString().contains(parsed.password))
        assertFalse(parsed.toString().contains(parsed.uuid))
    }

    @Test
    fun `TUIC defaults and subscription group identity remain typed`() {
        val input = "tuic://11111111-2222-3333-4444-555555555555:fixture+password@h.example"
        val profile = Base64SubscriptionParser.parse(input, "group-a").profiles.single() as ProxyProfile.Tuic
        assertEquals(443, profile.serverPort)
        assertEquals("fixture+password", profile.password)
        assertEquals("bbr", profile.congestionControl)
        assertEquals("group-a", profile.groupId)
    }

    @Test
    fun `TUIC refuses malformed and unsupported runtime settings`() {
        val base = "tuic://11111111-2222-3333-4444-555555555555:fixture@h.example:443/"
        for (query in listOf(
            "allow_insecure=1",
            "disable_sni=true",
            "udp_relay_mode=quic",
            "reduce_rtt=1",
            "zero_rtt_handshake=true",
            "zero_rtt=1",
            "congestion_control=unknown",
            "alpn=h3%2C",
            "alpn=${"a".repeat(256)}",
            "sni=",
            "unsupported=value",
            "sni=a&s%6Ei=b",
        )) {
            assertNull(query.substringBefore('='), ProxyUriCodec.parse("$base?$query"))
        }
        for (input in listOf(
            "tuic://not-a-uuid:fixture@h.example:443",
            "tuic://11111111-2222-3333-4444-555555555555:@h.example:443",
            "tuic://11111111-2222-3333-4444-555555555555:fixture@h.example:65536",
            "$base/unexpected",
        )) {
            assertNull(ProxyUriCodec.parse(input))
        }
    }
}
