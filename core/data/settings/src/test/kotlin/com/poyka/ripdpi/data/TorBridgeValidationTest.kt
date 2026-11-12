package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorBridgeValidationTest {
    private val rsa = "0BAC39417268B96B9F514E7F63FA6FBA1A788955"
    private val broker = "url=https://broker.example/"
    private val cert = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMw"
    private val ed = "ed25519:dGhpcyBpcyBpbmNyZWRpYmx5IHNpbGx5ISEhISEhISE"

    @Test
    fun `Arti requires an RSA identity and accepts optional Ed25519 in either order`() {
        assertEquals("invalid", torBridgeValidationError("snowflake 192.0.2.1:80 $broker"))
        assertEquals("invalid", torBridgeValidationError("snowflake 192.0.2.1:80 $ed $broker"))
        listOf(rsa, "\$$rsa", "$rsa $ed", "$ed $rsa", "rsa:q83vq83vq83vq83vq83vEjRWeJA").forEach { identities ->
            assertNull(identities, torBridgeValidationError("snowflake 192.0.2.1:80 $identities $broker"))
        }
        listOf("$rsa $rsa", "$rsa $ed $ed", "$rsa ed25519:bad", "$rsa unknown:bad").forEach { identities ->
            assertEquals(identities, "invalid", torBridgeValidationError("snowflake 192.0.2.1:80 $identities $broker"))
        }
    }

    @Test
    fun `bridge endpoint rejects URL decorations and invalid ports`() {
        listOf("192.0.2.1:80#fragment", "192.0.2.1:80?query", "192.0.2.1:80/path", "192.0.2.1:999999").forEach {
            assertEquals(it, "invalid", torBridgeValidationError("snowflake $it $rsa $broker"))
        }
        assertNull(torBridgeValidationError("snowflake [2001:db8::1]:80 $rsa $broker"))
        assertNull(torBridgeValidationError("snowflake - $rsa $broker"))
        assertEquals("invalid", torBridgeValidationError("obfs4 - $rsa cert=$cert iat-mode=0"))
        assertEquals("invalid", torBridgeValidationError("webtunnel - $rsa url=https://bridge.example/secret"))
    }

    @Test
    fun `Snowflake accepts explicit HTTP AMP and SQS rendezvous without requiring ICE`() {
        // Credential format is checked by upstream; this validator checks rendezvous completeness.
        listOf(
            "url=http://broker.example/",
            "url=HTTPS://broker.example:8443/",
            "url=https://broker.example:8443/registration?test=1 front=front.example utls-imitate=none",
            "url=https://[2001:db8::1]/",
            "$broker ampcache=https://cache.example/",
            "sqsqueue=https://sqs.eu-west-1.amazonaws.com/123456789012/queue sqscreds=opaque",
        ).forEach { options -> assertNull(options, torBridgeValidationError("snowflake - $rsa $options")) }
    }

    @Test
    fun `Snowflake rejects missing incomplete and conflicting rendezvous`() {
        listOf(
            "",
            "front=front.example ice=stun:stun.example:3478",
            "ampcache=https://cache.example/",
            "sqsqueue=https://sqs.eu-west-1.amazonaws.com/123456789012/queue",
            "sqscreds=opaque",
            "sqsqueue=https://queue.example/123/queue sqscreds=opaque",
            "sqsqueue=https://sqs.eu-west-1.amazonaws.com.evil.example/123/queue sqscreds=opaque",
            "$broker sqscreds=opaque",
            "$broker sqsqueue=https://sqs.eu-west-1.amazonaws.com/123456789012/queue sqscreds=opaque",
            "ampcache=https://cache.example/ sqsqueue=https://sqs.eu-west-1.amazonaws.com/123/queue sqscreds=opaque",
        ).forEach { options ->
            assertEquals(options, "invalid", torBridgeValidationError("snowflake - $rsa $options"))
        }
    }

    @Test
    fun `Snowflake AMP rendezvous follows publisher and cache URL constraints`() {
        listOf(
            "url=HTTP://broker.example:80/ ampcache=HTTPS://cache.example/",
            "url=https://broker.example:443/ ampcache=https://cache.example:8443/",
            "$broker ampcache=https://cache.example/?#",
        ).forEach { options -> assertNull(options, torBridgeValidationError("snowflake - $rsa $options")) }
        listOf(
            "url=https://broker.example:8443/ ampcache=https://cache.example/",
            "url=https://broker.example:0443/ ampcache=https://cache.example/",
            "url=https://user@broker.example/ ampcache=https://cache.example/",
            "$broker ampcache=https://cache.example/?query=1",
            "$broker ampcache=https://cache.example/#fragment",
        ).forEach { options ->
            assertEquals(options, "invalid", torBridgeValidationError("snowflake - $rsa $options"))
        }
    }

    @Test
    fun `Snowflake rejects malformed rendezvous URLs`() {
        listOf("broker.example", "ftp://broker.example/", "https:///", "https://broker.example:999999/", "https://%/")
            .forEach { url ->
                listOf("url=$url", "$broker ampcache=$url", "sqsqueue=$url sqscreds=opaque").forEach { options ->
                    assertEquals(options, "invalid", torBridgeValidationError("snowflake - $rsa $options"))
                }
            }
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
    fun `obfs4 rejects malformed padded and wrong length certificates`() {
        listOf("fixture", "$cert=", "$cert==", "A".repeat(68), "A".repeat(71), "$cert!", "_".repeat(70))
            .forEach { invalidCert ->
                assertEquals(
                    invalidCert,
                    "invalid",
                    torBridgeValidationError("obfs4 192.0.2.1:443 $rsa cert=$invalidCert iat-mode=0"),
                )
            }
    }

    @Test
    fun `obfs4 accepts standard base64 certificates for every iat mode`() {
        // Lyrebird's Go decoder accepts nonzero unused pad bits as well as canonical encodings.
        listOf(cert, cert.dropLast(1) + "x", "/".repeat(69) + "w").forEach { validCert ->
            listOf("0", "1", "2").forEach { iatMode ->
                assertNull(
                    "$validCert iat-mode=$iatMode",
                    torBridgeValidationError("obfs4 192.0.2.1:443 $rsa cert=$validCert iat-mode=$iatMode"),
                )
            }
        }
    }

    @Test
    fun `obfs4 requires the options sent unchanged to Lyrebird`() {
        assertEquals("required", torBridgeValidationError(""))
        assertEquals("invalid", torBridgeValidationError("obfs4 192.0.2.1:443 $rsa cert=$cert"))
        assertEquals("invalid", torBridgeValidationError("obfs4 192.0.2.1:443 $rsa iat-mode=0"))
        assertNull(torBridgeValidationError("obfs4 192.0.2.1:443 $rsa cert=$cert iat-mode=0"))
    }
}
