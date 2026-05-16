package com.poyka.ripdpi.services.dns.bootstrap

import com.poyka.ripdpi.data.Ipv6Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedEndpointPreferenceTest {
    private val systemCallCount = mutableListOf<String>()
    private val fakeSystem: (String, String) -> List<String>? = { name, _ ->
        systemCallCount.add(name)
        listOf("1.2.3.4")
    }

    @Test
    fun `when pinned IP present system resolver is not consulted`() {
        val registry = PinnedEndpointRegistry(mapOf("fixture-proxy.example" to "94.140.14.14"))
        val allowlist = BootstrapAllowlist(setOf("fixture-proxy.example"))
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        val resolver =
            ScopedBootstrapResolver(allowlist, registry, Ipv6BootstrapPolicy(Ipv6Policy.IPV4_ONLY), cache, fakeSystem)

        val result = resolver.resolve("fixture-proxy.example", "A")

        assertTrue(result is BootstrapResult.Success)
        assertEquals(listOf("94.140.14.14"), (result as BootstrapResult.Success).addresses)
        assertTrue("System resolver must not be called when pinned IP present", systemCallCount.isEmpty())
    }

    @Test
    fun `when no pinned IP system resolver is consulted`() {
        val registry = PinnedEndpointRegistry(emptyMap())
        val allowlist = BootstrapAllowlist(setOf("fixture-proxy.example"))
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        val resolver =
            ScopedBootstrapResolver(allowlist, registry, Ipv6BootstrapPolicy(Ipv6Policy.IPV4_ONLY), cache, fakeSystem)

        resolver.resolve("fixture-proxy.example", "A")

        assertTrue("System resolver must be called when no pinned IP", systemCallCount.isNotEmpty())
    }
}
