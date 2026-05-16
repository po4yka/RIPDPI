package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DeviceProfile
import com.poyka.ripdpi.data.Ipv6Policy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD: Fresh [DeviceProfile] defaults to [Ipv6Policy.IPV4_ONLY] — the secure default.
 *
 * An IPv4-only policy means no IPv6 address, route, DNS, or allowFamily(AF_INET6) is
 * programmed into the VPN tunnel unless the operator explicitly opts in to dual-stack.
 */
class Ipv6PolicyDefaultTest {
    @Test
    fun freshProfileDefaultsToIpv4Only() {
        val profile = DeviceProfile()
        assertEquals(
            "A freshly constructed DeviceProfile must default to IPV4_ONLY",
            Ipv6Policy.IPV4_ONLY,
            profile.ipv6Policy,
        )
    }

    @Test
    fun ipv4OnlyPolicyIsDistinctFromAllowAndBlock() {
        // IPV4_ONLY is the new secure variant — it is not the same as ALLOW or BLOCK
        val ipv4Only = Ipv6Policy.IPV4_ONLY
        assert(ipv4Only != Ipv6Policy.ALLOW) { "IPV4_ONLY must not equal ALLOW" }
        assert(ipv4Only != Ipv6Policy.BLOCK) { "IPV4_ONLY must not equal BLOCK" }
    }
}
