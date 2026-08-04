package com.poyka.ripdpi.service.awg

import com.poyka.ripdpi.core.RipDpiAmneziaWgCarrierKind
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [DefaultAmneziaWgRuntimeConfigResolver]: the pure structural copy of the
 * app-reachable [AwgActivationRequest] into the engine-api `ResolvedRipDpiAmneziaWgConfig`.
 */
class AmneziaWgRuntimeConfigResolverTest {
    private val resolver = DefaultAmneziaWgRuntimeConfigResolver()

    private fun request() =
        AwgActivationRequest(
            profileId = "awg-uuid-1",
            privateKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            peerPublicKey = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
            presharedKey = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDA=",
            endpointHost = "vpn.example.org",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            interfaceAddressV6 = "fd00::2/128",
            mtu = 1280,
            persistentKeepalive = 25,
            obfuscation =
                AwgActivationObfuscation(
                    jc = 4,
                    jmin = 40,
                    jmax = 70,
                    s1 = 50,
                    s2 = 100,
                    s3 = 0,
                    s4 = 0,
                    h1 = 1_000_000_001L,
                    h4 = 1_000_000_004L,
                    i1 = "deadbeef",
                    i5 = "cafe",
                ),
        )

    @Test
    fun `identity, PSK and transport fields copy verbatim`() {
        val config = resolver.resolve(request())

        assertEquals(true, config.enabled)
        assertEquals("awg-uuid-1", config.profileId)
        assertEquals("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=", config.privateKey)
        assertEquals("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=", config.peerPublicKey)
        assertEquals("DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDA=", config.presharedKey)
        assertEquals("vpn.example.org", config.endpointHost)
        assertEquals(51820, config.endpointPort)
        assertEquals("10.8.0.2/32", config.interfaceAddressV4)
        assertEquals("fd00::2/128", config.interfaceAddressV6)
        assertEquals(1280, config.mtu)
        assertEquals(25, config.persistentKeepalive)
    }

    @Test
    fun `the safe obfuscation group including zero S3-S4 and I1-I5 copies through`() {
        val amnezia = resolver.resolve(request()).amnezia

        assertEquals(4, amnezia.jc)
        assertEquals(40, amnezia.jmin)
        assertEquals(70, amnezia.jmax)
        assertEquals(50, amnezia.s1)
        assertEquals(100, amnezia.s2)
        assertEquals(0, amnezia.s3)
        assertEquals(0, amnezia.s4)
        assertEquals(1_000_000_001L, amnezia.h1)
        assertEquals(1_000_000_004L, amnezia.h4)
        assertEquals("deadbeef", amnezia.i1)
        assertEquals("cafe", amnezia.i5)
    }

    @Test
    fun `the local SOCKS inbound binds to loopback`() {
        val config = resolver.resolve(request())

        assertEquals("127.0.0.1", config.localSocksHost)
        assertEquals(10808, config.localSocksPort)
    }

    @Test
    fun `a blank private key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(privateKey = ""))
        }
    }

    @Test
    fun `a URL-safe-only private key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(privateKey = "_".repeat(42) + "8="))
        }
    }

    @Test
    fun `an unpadded private key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(privateKey = request().privateKey.dropLast(1)))
        }
    }

    @Test
    fun `a noncanonical private key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(privateKey = "B".repeat(43) + "="))
        }
    }

    @Test
    fun `a whitespace preshared key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(presharedKey = "   "))
        }
    }

    @Test
    fun `a blank interface address is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(interfaceAddressV4 = ""))
        }
    }

    @Test
    fun `an invalid interface CIDR is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(interfaceAddressV4 = "not-a-cidr"))
        }
    }

    @Test
    fun `a non-positive endpoint port is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(endpointPort = 0))
        }
    }

    @Test
    fun `the carrier defaults to UDP`() {
        val config = resolver.resolve(request())

        assertEquals(RipDpiAmneziaWgCarrierKind.Udp, config.carrier)
        assertEquals("", config.carrierWsUrl)
    }

    @Test
    fun `a WS carrier with a URL maps to the WS kind`() {
        val config =
            resolver.resolve(
                request().copy(
                    carrier = AwgActivationRequest.CARRIER_WS,
                    carrierWsUrl = "wss://carrier.example.org:443/wg",
                ),
            )

        assertEquals(RipDpiAmneziaWgCarrierKind.Ws, config.carrier)
        assertEquals("wss://carrier.example.org:443/wg", config.carrierWsUrl)
    }

    @Test
    fun `a WS carrier without a URL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(request().copy(carrier = AwgActivationRequest.CARRIER_WS, carrierWsUrl = ""))
        }
    }

    @Test
    fun `non-zero S3 or S4 is rejected before runtime config creation`() {
        val s3Error =
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(request().copy(obfuscation = request().obfuscation.copy(s3 = 1)))
            }
        val s4Error =
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(request().copy(obfuscation = request().obfuscation.copy(s4 = 1)))
            }

        assertEquals(true, s3Error.message.orEmpty().contains("amneziawg-go#110"))
        assertEquals(true, s4Error.message.orEmpty().contains("amneziawg-go#110"))
    }
}
