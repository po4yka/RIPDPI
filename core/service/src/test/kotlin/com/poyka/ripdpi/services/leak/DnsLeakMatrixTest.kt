package com.poyka.ripdpi.services.leak

import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.data.SplitStrictResolverSpec
import com.poyka.ripdpi.services.DnsInterceptorDispatcher
import com.poyka.ripdpi.services.DnsLeakCheckResult
import com.poyka.ripdpi.services.DnsLeakDetector
import com.poyka.ripdpi.services.DnsQueryObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matrix: proxied domains must not resolve via the ISP/default-network DNS resolver.
 *
 * Uses [FakeNetworkPlane] as the JVM fake and [DnsLeakDetector] as the oracle.
 */
class DnsLeakMatrixTest {
    private val policy =
        SplitStrictDnsPolicy(
            direct = SplitStrictResolverSpec.directDefault(),
            directAllowlist = listOf("ru", "local.test"),
        )
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
    fun policyAwarePlaneLeakCheckAllowsOnlyDispatchedDirectDomainsOnDefaultNetwork() {
        plane.dnsViaTunnel = false
        plane.query("yandex.ru")
        plane.query("secret.example.com")

        val leaks = plane.leakedDnsQueries(policy)

        assertEquals(listOf("secret.example.com"), leaks.map { it.domain })
        assertTrue(plane.hasDnsLeak(policy))
    }

    @Test
    fun interceptorDrivenSplitDnsFlowDoesNotLeakProxiedDomainsToDefaultNetwork() {
        val dispatcher = DnsInterceptorDispatcher(policy)

        routeThroughInterceptor(dispatcher, "secret.example.com")
        routeThroughInterceptor(dispatcher, "yandex.ru")

        assertEquals(
            listOf(false, true),
            plane.dnsQueries.map { it.viaDefaultNetwork },
        )
        assertFalse(plane.hasDnsLeak(policy))
    }

    @Test
    fun allowlistedDomainWithoutDirectResolverIsStillALeakOnDefaultNetwork() {
        val proxyOnlyPolicy = policy.copy(direct = null)
        val dispatcher = DnsInterceptorDispatcher(proxyOnlyPolicy)
        val dispatch = dispatcher.dispatch("yandex.ru")

        plane.dnsViaTunnel = false
        plane.query("yandex.ru")

        assertEquals(DnsResolverPlane.PROXY, dispatch.plane)
        assertTrue(plane.hasDnsLeak(proxyOnlyPolicy))
    }

    @Test
    fun resetClearsAllPlaneState() {
        plane.dnsViaTunnel = false
        plane.query("leak.example.com")
        plane.reset()
        assertTrue(plane.dnsQueries.isEmpty())
    }

    private fun routeThroughInterceptor(
        dispatcher: DnsInterceptorDispatcher,
        domain: String,
    ) {
        val dispatch = dispatcher.dispatch(domain)
        when (dispatch.plane) {
            DnsResolverPlane.DIRECT -> {
                plane.dnsViaTunnel = false
                plane.query(domain)
            }

            DnsResolverPlane.PROXY -> {
                plane.dnsViaTunnel = true
                plane.query(domain)
            }

            DnsResolverPlane.BLOCK,
            DnsResolverPlane.BOOTSTRAP,
            -> Unit
        }
    }
}
