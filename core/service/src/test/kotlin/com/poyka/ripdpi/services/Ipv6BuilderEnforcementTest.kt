package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Ipv6Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [Ipv6PolicyEnforcer] gates all IPv6 VPN-builder artefacts behind policy.
 *
 * Acceptance criteria verified here:
 * - IPv4-only profiles: no IPv6 address, no `::/0` route, no AAAA DNS, no allowFamily(AF_INET6).
 * - Dual-stack profiles: IPv6 TUN address, `::/0` route, AAAA DNS flag, allowFamily(AF_INET6).
 */
class Ipv6BuilderEnforcementTest {
    // ---------------------------------------------------------------------------
    // IPV4_ONLY
    // ---------------------------------------------------------------------------

    @Test
    fun ipv4OnlyPolicyYieldsNoIpv6TunnelAddress() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.IPV4_ONLY)
        assertFalse(
            "IPV4_ONLY must not add an IPv6 TUN address",
            decision.addIpv6TunnelAddress,
        )
    }

    @Test
    fun ipv4OnlyPolicyYieldsNoDefaultIpv6Route() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.IPV4_ONLY)
        assertFalse(
            "IPV4_ONLY must not add a ::/0 default route",
            decision.addDefaultIpv6Route,
        )
    }

    @Test
    fun ipv4OnlyPolicyYieldsNoAaaaDns() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.IPV4_ONLY)
        assertFalse(
            "IPV4_ONLY must not forward AAAA DNS through tunnel",
            decision.forwardAaaaDnsThroughTunnel,
        )
    }

    @Test
    fun ipv4OnlyPolicyYieldsNoAllowFamily() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.IPV4_ONLY)
        assertFalse(
            "IPV4_ONLY must not call allowFamily(AF_INET6)",
            decision.allowInet6Family,
        )
    }

    // ---------------------------------------------------------------------------
    // ALLOW (dual-stack)
    // ---------------------------------------------------------------------------

    @Test
    fun allowPolicyYieldsIpv6TunnelAddress() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.ALLOW)
        assertTrue(
            "ALLOW (dual-stack) must add an IPv6 TUN address",
            decision.addIpv6TunnelAddress,
        )
    }

    @Test
    fun allowPolicyYieldsDefaultIpv6Route() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.ALLOW)
        assertTrue(
            "ALLOW (dual-stack) must add a ::/0 default route",
            decision.addDefaultIpv6Route,
        )
    }

    @Test
    fun allowPolicyYieldsAaaaDns() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.ALLOW)
        assertTrue(
            "ALLOW (dual-stack) must forward AAAA DNS through tunnel",
            decision.forwardAaaaDnsThroughTunnel,
        )
    }

    @Test
    fun allowPolicyYieldsAllowFamily() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.ALLOW)
        assertTrue(
            "ALLOW (dual-stack) must call allowFamily(AF_INET6)",
            decision.allowInet6Family,
        )
    }

    // ---------------------------------------------------------------------------
    // BLOCK — still IPv4-only in terms of tunnel artefacts
    // ---------------------------------------------------------------------------

    @Test
    fun blockPolicyYieldsNoIpv6TunnelArtefacts() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.BLOCK)
        assertFalse(decision.addIpv6TunnelAddress)
        assertFalse(decision.addDefaultIpv6Route)
        assertFalse(decision.forwardAaaaDnsThroughTunnel)
        assertFalse(decision.allowInet6Family)
    }

    // ---------------------------------------------------------------------------
    // vpnTunnelRoutePlan integration: IPV4_ONLY → no IPv6 address or ::/0
    // ---------------------------------------------------------------------------

    @Test
    fun routePlanHasNoIpv6AddressForIpv4OnlyPolicy() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.IPV4_ONLY)
        val plan = RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = decision.addIpv6TunnelAddress)
        assertTrue(
            "Route plan must contain no IPv6 address for IPV4_ONLY",
            plan.addresses.none { it.address.contains(":") },
        )
    }

    @Test
    fun routePlanHasIpv6AddressForAllowPolicy() {
        val decision = Ipv6PolicyEnforcer.decide(Ipv6Policy.ALLOW)
        val plan = RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = decision.addIpv6TunnelAddress)
        assertTrue(
            "Route plan must contain fd00::1 for ALLOW (dual-stack)",
            plan.addresses.any { it.address == "fd00::1" },
        )
        assertTrue(
            "Route plan must contain ::/0 default route for ALLOW (dual-stack)",
            plan.routes.any { it.address == "::" && it.prefixLength == 0 },
        )
    }
}
