package com.poyka.ripdpi.services.leak

import com.poyka.ripdpi.services.Ipv6LeakCheckResult
import com.poyka.ripdpi.services.Ipv6LeakDetector
import com.poyka.ripdpi.services.Ipv6TrafficObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matrix: IPv4-only mode must not expose direct IPv6 on IPv6-capable networks.
 *
 * Uses [FakeNetworkPlane] to simulate an IPv6-capable underlying network and
 * [Ipv6LeakDetector] as the oracle.
 */
class Ipv6LeakMatrixTest {
    private val detector = Ipv6LeakDetector()
    private val plane = FakeNetworkPlane()

    private fun obs(
        address: String,
        viaDefault: Boolean,
        vpnActive: Boolean = true,
    ) = Ipv6TrafficObservation(sourceAddress = address, viaDefaultNetwork = viaDefault, vpnActive = vpnActive)

    @Test
    fun ipv4OnlyModeOnIpv6CapableNetworkHasNoLeak() {
        plane.ipv6Capable = true
        plane.dnsViaTunnel = true
        plane.sendIpv6("2001:db8::1")
        // When tunnel is active, viaDefaultNetwork is false in the plane
        val result = detector.check(obs("2001:db8::1", viaDefault = false))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun publicIpv6ViaDefaultNetworkWhileVpnActiveIsALeak() {
        plane.ipv6Capable = true
        plane.dnsViaTunnel = false
        plane.sendIpv6("2001:db8::1")
        assertTrue(plane.hasIpv6Leak())
        val result = detector.check(obs("2001:db8::1", viaDefault = true))
        assertTrue(result is Ipv6LeakCheckResult.Leaked)
    }

    @Test
    fun linkLocalIpv6ViaDefaultNetworkIsNotALeak() {
        plane.ipv6Capable = true
        plane.dnsViaTunnel = false
        plane.sendIpv6("fe80::1")
        assertFalse(plane.hasIpv6Leak())
        val result = detector.check(obs("fe80::1", viaDefault = true))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun ulaIpv6AddressIsNotALeak() {
        val result = detector.check(obs("fd00::1", viaDefault = true))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun ipv6NotActiveNoLeak() {
        val result = detector.check(obs("2001:db8::1", viaDefault = true, vpnActive = false))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun multiplePublicIpv6LeaksAllCaptured() {
        detector.record(obs("2001:db8::1", viaDefault = true))
        detector.record(obs("2606:4700::1", viaDefault = true))
        detector.record(obs("fe80::1", viaDefault = true))
        assertEquals(2, detector.leakedObservations().size)
    }

    @Test
    fun resetClearsIpv6LeakHistory() {
        detector.record(obs("2001:db8::1", viaDefault = true))
        detector.reset()
        assertFalse(detector.hasLeak())
    }
}
