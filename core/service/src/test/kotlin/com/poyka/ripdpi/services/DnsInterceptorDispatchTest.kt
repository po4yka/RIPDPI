package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsResolverPlane.BLOCK
import com.poyka.ripdpi.data.DnsResolverPlane.DIRECT
import com.poyka.ripdpi.data.DnsResolverPlane.PROXY
import com.poyka.ripdpi.data.DomainClass
import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.data.SplitStrictResolverSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD: DnsInterceptorDispatcher routes queries to the correct plane
 * based on SplitStrictDnsPolicy and domain classification.
 */
class DnsInterceptorDispatchTest {
    private val proxyEndpoint = "https://dns.adguard-dns.com/dns-query"
    private val directEndpoint = "8.8.8.8"

    private fun policyWithDirect() =
        SplitStrictDnsPolicy(
            proxy = SplitStrictResolverSpec.proxyDefault().copy(endpoint = proxyEndpoint),
            direct = SplitStrictResolverSpec.directDefault().copy(endpoint = directEndpoint),
            directAllowlist = listOf("ru", "local.test"),
        )

    private fun policyWithoutDirect() =
        SplitStrictDnsPolicy(
            proxy = SplitStrictResolverSpec.proxyDefault().copy(endpoint = proxyEndpoint),
            direct = null,
            directAllowlist = listOf("ru", "local.test"),
        )

    @Test
    fun `proxied domain routes to PROXY plane`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect())
        val result = dispatcher.dispatch("example.com")
        assertEquals(PROXY, result.plane)
        assertEquals(proxyEndpoint, result.endpoint)
    }

    @Test
    fun `direct domain routes to DIRECT plane when direct spec is configured`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect())
        val result = dispatcher.dispatch("yandex.ru")
        assertEquals(DIRECT, result.plane)
        assertEquals(directEndpoint, result.endpoint)
    }

    @Test
    fun `exact allowlist match routes to DIRECT plane`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect())
        val result = dispatcher.dispatch("local.test")
        assertEquals(DIRECT, result.plane)
    }

    @Test
    fun `subdomain of allowlist entry routes to DIRECT plane`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect())
        val result = dispatcher.dispatch("cdn.local.test")
        assertEquals(DIRECT, result.plane)
    }

    @Test
    fun `direct domain falls back to PROXY plane when no direct spec configured`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithoutDirect())
        val result = dispatcher.dispatch("yandex.ru")
        assertEquals(PROXY, result.plane)
        assertEquals(proxyEndpoint, result.endpoint)
    }

    @Test
    fun `blocked domain routes to BLOCK plane with empty endpoint`() {
        val blockClassifier = DomainClassifier { DomainClass.BLOCK }
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect(), blockClassifier)
        val result = dispatcher.dispatch("blocked.example.com")
        assertEquals(BLOCK, result.plane)
        assertEquals("", result.endpoint)
    }

    @Test
    fun `proxy plane endpoint is always non-blank for proxied domains`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect())
        val result = dispatcher.dispatch("google.com")
        assertEquals(PROXY, result.plane)
        assertTrue("Proxy plane endpoint must be non-blank", result.endpoint.isNotBlank())
    }

    @Test
    fun `trailing dot in domain is ignored for classification`() {
        val dispatcher = DnsInterceptorDispatcher(policyWithDirect())
        val withDot = dispatcher.dispatch("yandex.ru.")
        val withoutDot = dispatcher.dispatch("yandex.ru")
        assertEquals(withoutDot.plane, withDot.plane)
    }
}

private fun assertTrue(
    message: String,
    value: Boolean,
) = org.junit.Assert.assertTrue(message, value)
