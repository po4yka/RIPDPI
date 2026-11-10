package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorBridgeValidationTest {
    private val rsa = "0BAC39417268B96B9F514E7F63FA6FBA1A788955"
    private val ed = "ed25519:dGhpcyBpcyBpbmNyZWRpYmx5IHNpbGx5ISEhISEhISE"

    @Test
    fun `Arti requires an RSA identity and accepts optional Ed25519 in either order`() {
        assertEquals("invalid", torBridgeValidationError("snowflake 192.0.2.1:80"))
        assertEquals("invalid", torBridgeValidationError("snowflake 192.0.2.1:80 $ed"))
        listOf(rsa, "\$$rsa", "$rsa $ed", "$ed $rsa", "rsa:q83vq83vq83vq83vq83vEjRWeJA").forEach { identities ->
            assertNull(identities, torBridgeValidationError("snowflake 192.0.2.1:80 $identities"))
        }
        listOf("$rsa $rsa", "$rsa $ed $ed", "$rsa ed25519:bad", "$rsa unknown:bad").forEach { identities ->
            assertEquals(identities, "invalid", torBridgeValidationError("snowflake 192.0.2.1:80 $identities"))
        }
    }

    @Test
    fun `bridge endpoint rejects URL decorations and invalid ports`() {
        listOf("192.0.2.1:80#fragment", "192.0.2.1:80?query", "192.0.2.1:80/path", "192.0.2.1:999999").forEach {
            assertEquals(it, "invalid", torBridgeValidationError("snowflake $it $rsa"))
        }
        assertNull(torBridgeValidationError("snowflake [2001:db8::1]:80 $rsa"))
        assertNull(torBridgeValidationError("snowflake - $rsa"))
        assertEquals("invalid", torBridgeValidationError("obfs4 - $rsa cert=fixture iat-mode=0"))
        assertEquals("invalid", torBridgeValidationError("webtunnel - $rsa url=https://bridge.example/secret"))
    }

    @Test
    fun `WebTunnel follows URL scheme case and port rules`() {
        listOf("https://bridge.example/secret", "HTTPS://bridge.example/secret", "https://bridge.example:443/secret")
            .forEach { url -> assertNull(url, torBridgeValidationError("webtunnel 192.0.2.1:443 $rsa url=$url")) }
        listOf("https://bridge.example:999999/secret", "https://bridge.example/", "http://bridge.example/secret")
            .forEach { url ->
                assertEquals(url, "invalid", torBridgeValidationError("webtunnel 192.0.2.1:443 $rsa url=$url"))
            }
    }

    @Test
    fun `obfs4 requires the options sent unchanged to Lyrebird`() {
        assertEquals("required", torBridgeValidationError(""))
        assertEquals("invalid", torBridgeValidationError("obfs4 192.0.2.1:443 $rsa cert=fixture"))
        assertEquals("invalid", torBridgeValidationError("obfs4 192.0.2.1:443 $rsa iat-mode=0"))
        assertNull(torBridgeValidationError("obfs4 192.0.2.1:443 $rsa cert=fixture iat-mode=0"))
    }
}
