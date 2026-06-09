package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [Ipv6LeakDetector] flags direct public IPv6 traffic while the VPN is connected.
 *
 * Acceptance criterion: leak tests fail if an IPv6-capable network exposes a direct
 * public IPv6 address while the VPN is connected.
 *
 * A "leak" is defined as: the device has a public (non-link-local, non-ULA) IPv6
 * address on the default (non-VPN) interface while the VPN tunnel is active.
 */
class Ipv6LeakDetectorTest {
    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun obs(
        sourceAddress: String,
        viaDefaultNetwork: Boolean,
        vpnActive: Boolean = true,
    ) = Ipv6TrafficObservation(
        sourceAddress = sourceAddress,
        viaDefaultNetwork = viaDefaultNetwork,
        vpnActive = vpnActive,
    )

    private fun detector() = Ipv6LeakDetector()

    // ---------------------------------------------------------------------------
    // Clean cases
    // ---------------------------------------------------------------------------

    @Test
    fun linkLocalIpv6ViaDefaultNetworkIsNotALeak() {
        val d = detector()
        // fe80::/10 link-local addresses are never routable and never a leak
        val result = d.check(obs("fe80::1", viaDefaultNetwork = true))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun ulaIpv6ViaDefaultNetworkIsNotALeak() {
        val d = detector()
        // fc00::/7 ULA addresses are private and never a leak
        val result = d.check(obs("fd00::1", viaDefaultNetwork = true))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun publicIpv6ViaTunnelIsNotALeak() {
        val d = detector()
        // Public IPv6 routed through the VPN tunnel is fine
        val result = d.check(obs("2001:db8::1", viaDefaultNetwork = false))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    @Test
    fun anyAddressWhenVpnNotActiveIsNotALeak() {
        val d = detector()
        // When VPN is not running there is no leak by definition
        val result = d.check(obs("2001:db8::1", viaDefaultNetwork = true, vpnActive = false))
        assertEquals(Ipv6LeakCheckResult.Clean, result)
    }

    // ---------------------------------------------------------------------------
    // Leak cases
    // ---------------------------------------------------------------------------

    @Test
    fun publicIpv6ViaDefaultNetworkWhileVpnActiveIsALeak() {
        val d = detector()
        val result = d.check(obs("2001:db8::1", viaDefaultNetwork = true, vpnActive = true))
        assertTrue(
            "Public IPv6 via default network while VPN is active must be flagged as a leak",
            result is Ipv6LeakCheckResult.Leaked,
        )
        val leaked = result as Ipv6LeakCheckResult.Leaked
        assertEquals("2001:db8::1", leaked.sourceAddress)
    }

    @Test
    fun globalUnicastIpv6ViaDefaultNetworkWhileVpnActiveIsALeak() {
        val d = detector()
        // 2606:4700:: is Cloudflare's public range — clearly a leak
        val result = d.check(obs("2606:4700::1111", viaDefaultNetwork = true, vpnActive = true))
        assertTrue(result is Ipv6LeakCheckResult.Leaked)
    }

    // ---------------------------------------------------------------------------
    // Record / leakedObservations
    // ---------------------------------------------------------------------------

    @Test
    fun recordedPublicLeakAppearsInLeakedObservations() {
        val d = detector()
        val leakObs = obs("2001:db8::1", viaDefaultNetwork = true, vpnActive = true)
        d.record(leakObs)
        d.record(obs("fe80::1", viaDefaultNetwork = true, vpnActive = true))

        val leaks = d.leakedObservations()
        assertEquals(1, leaks.size)
        assertEquals("2001:db8::1", leaks.first().sourceAddress)
    }

    @Test
    fun cleanObservationsDoNotAppearInLeakedObservations() {
        val d = detector()
        d.record(obs("fe80::1", viaDefaultNetwork = true, vpnActive = true))
        d.record(obs("2001:db8::1", viaDefaultNetwork = false, vpnActive = true))
        assertTrue(d.leakedObservations().isEmpty())
    }

    @Test
    fun resetClearsAllObservations() {
        val d = detector()
        d.record(obs("2001:db8::1", viaDefaultNetwork = true, vpnActive = true))
        d.reset()
        assertTrue(d.leakedObservations().isEmpty())
    }

    @Test
    fun multiplePublicLeaksAreAllReported() {
        val d = detector()
        d.record(obs("2001:db8::1", viaDefaultNetwork = true, vpnActive = true))
        d.record(obs("2606:4700::1", viaDefaultNetwork = true, vpnActive = true))
        d.record(obs("fe80::1", viaDefaultNetwork = true, vpnActive = true))

        assertEquals(2, d.leakedObservations().size)
    }

    @Test
    fun hasLeakReturnsTrueWhenAtLeastOneLeakObserved() {
        val d = detector()
        assertFalse(d.hasLeak())
        d.record(obs("2001:db8::1", viaDefaultNetwork = true, vpnActive = true))
        assertTrue(d.hasLeak())
    }
}
