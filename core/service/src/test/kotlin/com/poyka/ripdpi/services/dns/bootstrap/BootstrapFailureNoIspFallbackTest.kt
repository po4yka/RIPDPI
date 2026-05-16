package com.poyka.ripdpi.services.dns.bootstrap

import com.poyka.ripdpi.data.Ipv6Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapFailureNoIspFallbackTest {
    private val failingSystem: (String, String) -> List<String>? = { _, _ -> null }

    @Test
    fun `name outside allowlist yields BootstrapDenied not ISP fallback`() {
        val registry = PinnedEndpointRegistry(emptyMap())
        val allowlist = BootstrapAllowlist(setOf("fixture-proxy.example"))
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        val resolver =
            ScopedBootstrapResolver(
                allowlist,
                registry,
                Ipv6BootstrapPolicy(Ipv6Policy.IPV4_ONLY),
                cache,
                failingSystem,
            )

        val result = resolver.resolve("fixture-isp-dns.example", "A")

        assertTrue(result is BootstrapResult.BootstrapDenied)
        assertFalse(result is BootstrapResult.Success)
    }

    @Test
    fun `system resolver returning null yields BootstrapFailure not ISP fallback`() {
        val registry = PinnedEndpointRegistry(emptyMap())
        val allowlist = BootstrapAllowlist(setOf("fixture-proxy.example"))
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        val resolver =
            ScopedBootstrapResolver(
                allowlist,
                registry,
                Ipv6BootstrapPolicy(Ipv6Policy.IPV4_ONLY),
                cache,
                failingSystem,
            )

        val result = resolver.resolve("fixture-proxy.example", "A")

        assertTrue(result is BootstrapResult.BootstrapFailure)
        assertFalse(result is BootstrapResult.Success)
    }

    @Test
    fun `AAAA rejected when IPv6 disabled yields BootstrapDenied`() {
        val registry = PinnedEndpointRegistry(emptyMap())
        val allowlist = BootstrapAllowlist(setOf("fixture-proxy.example"))
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        val resolver =
            ScopedBootstrapResolver(
                allowlist,
                registry,
                Ipv6BootstrapPolicy(Ipv6Policy.IPV4_ONLY),
                cache,
                failingSystem,
            )

        val result = resolver.resolve("fixture-proxy.example", "AAAA")

        assertTrue(result is BootstrapResult.BootstrapDenied)
        assertFalse(result is BootstrapResult.Success)
    }

    @Test
    fun `BootstrapResult has no ISP fallback variant`() {
        // Exhaustive when expression: only Success, BootstrapDenied, BootstrapFailure exist.
        val result: BootstrapResult = BootstrapResult.BootstrapFailure(reason = "fixture-reason")
        val hasNoIspFallback =
            when (result) {
                is BootstrapResult.Success -> false
                is BootstrapResult.BootstrapDenied -> false
                is BootstrapResult.BootstrapFailure -> true
            }
        assertTrue(hasNoIspFallback)
    }
}
