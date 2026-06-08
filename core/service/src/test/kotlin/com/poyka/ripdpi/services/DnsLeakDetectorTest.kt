package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.data.SplitStrictResolverSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: DnsLeakDetector flags fallback to default-network DNS for proxied domains.
 */
class DnsLeakDetectorTest {
    private val policy =
        SplitStrictDnsPolicy(
            direct = SplitStrictResolverSpec.directDefault(),
            directAllowlist = listOf("ru", "local.test"),
        )

    private fun detector() = DnsLeakDetector(policy)

    private fun obs(
        domain: String,
        resolver: String = "8.8.8.8",
        viaDefault: Boolean = false,
    ) = DnsQueryObservation(
        domain = domain,
        resolverAddress = resolver,
        viaDefaultNetwork = viaDefault,
    )

    @Test
    fun `proxied domain resolved via interceptor is clean`() {
        val d = detector()
        val result = d.check(obs("google.com", resolver = "198.18.0.53", viaDefault = false))
        assertEquals(DnsLeakCheckResult.Clean, result)
    }

    @Test
    fun `proxied domain resolved via default network is a leak`() {
        val d = detector()
        val result = d.check(obs("google.com", resolver = "8.8.8.8", viaDefault = true))
        assertTrue(result is DnsLeakCheckResult.Leaked)
        val leaked = result as DnsLeakCheckResult.Leaked
        assertEquals("google.com", leaked.domain)
        assertEquals("8.8.8.8", leaked.resolverAddress)
    }

    @Test
    fun `direct domain resolved via default network is not a leak`() {
        val d = detector()
        // yandex.ru matches the directAllowlist suffix "ru"
        val result = d.check(obs("yandex.ru", resolver = "8.8.8.8", viaDefault = true))
        assertEquals(DnsLeakCheckResult.Clean, result)
    }

    @Test
    fun `recorded leaked observation appears in leakedObservations`() {
        val d = detector()
        val leakedObs = obs("google.com", viaDefault = true)
        d.record(leakedObs)
        d.record(obs("yandex.ru", viaDefault = true))
        val leaks = d.leakedObservations()
        assertEquals(1, leaks.size)
        assertEquals("google.com", leaks.first().domain)
    }

    @Test
    fun `clean observations do not appear in leakedObservations`() {
        val d = detector()
        d.record(obs("google.com", resolver = "198.18.0.53", viaDefault = false))
        assertTrue(d.leakedObservations().isEmpty())
    }

    @Test
    fun `reset clears all observations`() {
        val d = detector()
        d.record(obs("google.com", viaDefault = true))
        d.reset()
        assertTrue(d.leakedObservations().isEmpty())
    }

    @Test
    fun `multiple proxied leaks are all reported`() {
        val d = detector()
        d.record(obs("google.com", viaDefault = true))
        d.record(obs("facebook.com", viaDefault = true))
        d.record(obs("yandex.ru", viaDefault = true))
        val leaks = d.leakedObservations()
        assertEquals(2, leaks.size)
    }

    @Test
    fun `proxied domain not via default network has no leak even if resolver looks wrong`() {
        val d = detector()
        // viaDefaultNetwork=false means query went through the VPN
        val result = d.check(obs("secret.com", resolver = "1.1.1.1", viaDefault = false))
        assertFalse(result is DnsLeakCheckResult.Leaked)
    }

    @Test
    fun `allowlisted domain is a leak on default network when direct resolver is not configured`() {
        val d =
            DnsLeakDetector(
                SplitStrictDnsPolicy(
                    direct = null,
                    directAllowlist = listOf("ru"),
                ),
            )
        val result = d.check(obs("yandex.ru", resolver = "8.8.8.8", viaDefault = true))
        assertTrue(result is DnsLeakCheckResult.Leaked)
    }

    @Test
    fun `check publishes DNS leak indicator state`() {
        val store = InMemoryPrivacyThreatStateStore()
        val d =
            DnsLeakDetector(
                policy = policy,
                privacyThreatStateStore = store,
                clockMillis = { 42L },
            )

        d.check(obs("google.com", resolver = "8.8.8.8", viaDefault = true))

        assertEquals(PrivacyLeakIndicatorStatus.LEAK_DETECTED, store.snapshot.value.dnsLeak.status)
        assertEquals("google.com -> 8.8.8.8", store.snapshot.value.dnsLeak.detail)
        assertEquals(42L, store.snapshot.value.dnsLeak.updatedAtMillis)
    }

    @Test
    fun `clean DNS check publishes clear indicator state`() {
        val store = InMemoryPrivacyThreatStateStore()
        val d =
            DnsLeakDetector(
                policy = policy,
                privacyThreatStateStore = store,
                clockMillis = { 43L },
            )

        d.check(obs("google.com", resolver = "198.18.0.53", viaDefault = false))

        assertEquals(PrivacyLeakIndicatorStatus.CLEAR, store.snapshot.value.dnsLeak.status)
        assertEquals(null, store.snapshot.value.dnsLeak.detail)
        assertEquals(43L, store.snapshot.value.dnsLeak.updatedAtMillis)
    }
}
