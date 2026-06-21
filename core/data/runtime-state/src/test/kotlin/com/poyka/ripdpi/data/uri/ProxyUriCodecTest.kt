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
}
