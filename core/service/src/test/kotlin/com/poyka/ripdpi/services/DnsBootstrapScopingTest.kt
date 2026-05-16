package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.data.SplitStrictResolverSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: Bootstrap resolution uses a scoped allowlist resolver and cannot
 * re-enter the TUN tunnel.
 */
class DnsBootstrapScopingTest {
    private val defaultPolicy = SplitStrictDnsPolicy()

    @Test
    fun `pinnedBootstrapIp returns pinned IP from bootstrap spec`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        val ip = scope.pinnedBootstrapIp()
        assertNotNull(ip)
        assertEquals("94.140.14.14", ip)
    }

    @Test
    fun `pinnedBootstrapIp returns null when not configured`() {
        val policy =
            SplitStrictDnsPolicy(
                bootstrap =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.BOOTSTRAP,
                        pinnedBootstrapIp = "",
                        allowPlaintextFallback = true,
                    ),
            )
        val scope = DnsBootstrapScope(policy)
        assertNull(scope.pinnedBootstrapIp())
    }

    @Test
    fun `isSafeBootstrapAddress returns false for TUN-internal address`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        assertFalse(scope.isSafeBootstrapAddress("198.18.0.53"))
    }

    @Test
    fun `isSafeBootstrapAddress returns true for public address`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        assertTrue(scope.isSafeBootstrapAddress("94.140.14.14"))
    }

    @Test
    fun `assertNotTunAddress does not throw for safe address`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        scope.assertNotTunAddress("94.140.14.14")
    }

    @Test(expected = IllegalStateException::class)
    fun `assertNotTunAddress throws for TUN interceptor address`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        scope.assertNotTunAddress("198.18.0.53")
    }

    @Test(expected = IllegalStateException::class)
    fun `assertNotTunAddress throws for any 198-18 prefix address`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        scope.assertNotTunAddress("198.18.1.1")
    }

    @Test
    fun `pinned bootstrap IP is always safe for bootstrap use`() {
        val scope = DnsBootstrapScope(defaultPolicy)
        val ip = scope.pinnedBootstrapIp() ?: return
        assertTrue(
            "Pinned bootstrap IP $ip must be safe (not TUN-internal)",
            scope.isSafeBootstrapAddress(ip),
        )
    }

    @Test
    fun `bootstrap plane is distinct from proxy plane`() {
        val policy = defaultPolicy
        assertEquals(DnsResolverPlane.BOOTSTRAP, policy.bootstrap.plane)
        assertEquals(DnsResolverPlane.PROXY, policy.proxy.plane)
    }
}
