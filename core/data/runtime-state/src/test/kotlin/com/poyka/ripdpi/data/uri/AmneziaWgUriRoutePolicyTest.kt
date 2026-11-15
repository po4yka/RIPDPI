package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.toWgQuickConf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class AmneziaWgUriRoutePolicyTest {
    @Test
    fun `explicit empty route lists are rejected before conf conversion`() {
        listOf("", ",", " , , ", "\t ").forEach { routes ->
            val encoded = URLEncoder.encode(routes, "UTF-8")

            assertNull(routes, AmneziaWgUriCodec.decode("$BaseUri&allowed_ips=$encoded"))
        }
    }

    @Test
    fun `omitted route policy remains omitted for activation defaults`() {
        val profile = requireNotNull(AmneziaWgUriCodec.decode(BaseUri))
        val peer = WireGuardConfParser.parse(profile.toWgQuickConf()).peers.single()

        assertTrue(profile.allowedIps.isEmpty())
        assertFalse(peer.allowedIpsSpecified)
    }

    @Test
    fun `nonempty route policy survives conf conversion without expansion`() {
        val routes = URLEncoder.encode("10.0.0.0/8, 2001:db8::/32", "UTF-8")
        val profile = requireNotNull(AmneziaWgUriCodec.decode("$BaseUri&allowed_ips=$routes"))
        val peer = WireGuardConfParser.parse(profile.toWgQuickConf()).peers.single()

        assertEquals(listOf("10.0.0.0/8", "2001:db8::/32"), profile.allowedIps)
        assertEquals(listOf("10.0.0.0/8", "2001:db8::/32"), peer.allowedIps)
        assertTrue(peer.allowedIpsSpecified)
    }

    private companion object {
        const val BaseUri =
            "amneziawg://test-private@192.0.2.9:51820" +
                "?wire_profile=awg-3.1-ranges-v1&public_key=test-public"
    }
}
