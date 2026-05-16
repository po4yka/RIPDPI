package com.poyka.ripdpi.services.leak

import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.services.DnsLeakCheckResult
import com.poyka.ripdpi.services.DnsLeakDetector
import com.poyka.ripdpi.services.DnsQueryObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matrix: proxied domains must not resolve via the ISP/default-network DNS resolver.
 *
 * Uses [FakeNetworkPlane] as the JVM fake and [DnsLeakDetector] as the oracle.
 */
class DnsLeakMatrixTest {
    private val policy = SplitStrictDnsPolicy(directAllowlist = listOf("ru", "local.test"))
    private val detector = DnsLeakDetector(policy)
    private val plane = FakeNetworkPlane()

    private fun obs(
        domain: String,
        viaDefault: Boolean,
        resolver: String = if (viaDefault) FakeNetworkPlane.IspResolver else FakeNetworkPlane.VpnResolver,
    ) = DnsQueryObservation(domain = domain, resolverAddress = resolver, viaDefaultNetwork = viaDefault)

    @Test
    fun proxiedDomainViaTunnelIsClean() {
        plane.dnsViaTunnel = true
        plane.query("example.com")
        val result = detector.check(obs("example.com", viaDefault = false))
        assertEquals(DnsLeakCheckResult.Clean, result)
    }

    @Test
    fun proxiedDomainViaIspResolverIsALeak() {
        plane.dnsViaTunnel = false
        plane.query("secret.example.com")
        val result = detector.check(obs("secret.example.com", viaDefault = true))
        assertTrue(result is DnsLeakCheckResult.Leaked)
    }

    @Test
    fun directDomainViaIspResolverIsNotALeak() {
        plane.dnsViaTunnel = false
        plane.query("yandex.ru")
        val result = detector.check(obs("yandex.ru", viaDefault = true))
        assertEquals(DnsLeakCheckResult.Clean, result)
    }

    @Test
    fun multiplexedProxiedDomainsAllLeakWhenTunnelAbsent() {
        val proxiedDomains = listOf("google.com", "github.com", "cloudflare.com")
        proxiedDomains.forEach { domain ->
            detector.record(obs(domain, viaDefault = true))
        }
        val leaks = detector.leakedObservations()
        assertEquals(proxiedDomains.size, leaks.size)
    }

    @Test
    fun fakeNetworkPlaneRecordsResolverCorrectly() {
        plane.dnsViaTunnel = true
        val resolver = plane.query("check.example.com")
        assertEquals(FakeNetworkPlane.VpnResolver, resolver)
        assertEquals(1, plane.dnsQueries.size)
        assertEquals(false, plane.dnsQueries.first().viaDefaultNetwork)
    }

    @Test
    fun fakeNetworkPlaneDetectsDnsLeakWhenTunnelOff() {
        plane.dnsViaTunnel = false
        plane.query("leak.example.com")
        assertTrue(plane.hasDnsLeak())
    }

    @Test
    fun resetClearsAllPlaneState() {
        plane.dnsViaTunnel = false
        plane.query("leak.example.com")
        plane.reset()
        assertTrue(plane.dnsQueries.isEmpty())
    }
}
