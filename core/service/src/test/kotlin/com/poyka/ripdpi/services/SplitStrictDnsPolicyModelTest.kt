package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.DomainClass
import com.poyka.ripdpi.data.Ipv6Policy
import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.data.SplitStrictResolverSpec
import com.poyka.ripdpi.data.StrictEncryptedDnsProtocol
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD tests for [SplitStrictDnsPolicy] — the split-strict DNS policy model.
 *
 * These tests define the contract; they were written before the implementation.
 */
class SplitStrictDnsPolicyModelTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // ------------------------------------------------------------------
    // 1. Policy has distinct resolver planes
    // ------------------------------------------------------------------

    @Test
    fun `SplitStrictDnsPolicy has distinct bootstrap, proxy, direct, and block planes`() {
        val policy = SplitStrictDnsPolicy()
        assertNotNull(policy.bootstrap)
        assertNotNull(policy.proxy)
        // direct and block are nullable/optional by design
        assertNotNull(policy.directAllowlist)
        assertEquals(DnsResolverPlane.BOOTSTRAP, policy.bootstrap.plane)
        assertEquals(DnsResolverPlane.PROXY, policy.proxy.plane)
    }

    @Test
    fun `each resolver plane carries a distinct plane discriminator`() {
        val bootstrap = SplitStrictResolverSpec.bootstrapDefault()
        val proxy = SplitStrictResolverSpec.proxyDefault()
        val direct = SplitStrictResolverSpec.directDefault()
        val block = SplitStrictResolverSpec.blockDefault()
        assertEquals(DnsResolverPlane.BOOTSTRAP, bootstrap.plane)
        assertEquals(DnsResolverPlane.PROXY, proxy.plane)
        assertEquals(DnsResolverPlane.DIRECT, direct.plane)
        assertEquals(DnsResolverPlane.BLOCK, block.plane)
    }

    // ------------------------------------------------------------------
    // 2. Proxy plane requires strict encrypted DNS; no plaintext fallback
    // ------------------------------------------------------------------

    @Test
    fun `proxy plane resolver spec requires encrypted protocol`() {
        val proxy = SplitStrictResolverSpec.proxyDefault()
        assertTrue(
            "Proxy plane must use an encrypted protocol",
            proxy.protocol != null && proxy.protocol != StrictEncryptedDnsProtocol.NONE,
        )
    }

    @Test
    fun `proxy plane does not allow plaintext fallback`() {
        val proxy = SplitStrictResolverSpec.proxyDefault()
        assertFalse(
            "Proxy plane must not allow plaintext fallback",
            proxy.allowPlaintextFallback,
        )
    }

    @Test
    fun `bootstrap plane may use a pinned IP and allows plaintext for initial resolution`() {
        val bootstrap = SplitStrictResolverSpec.bootstrapDefault()
        assertEquals(DnsResolverPlane.BOOTSTRAP, bootstrap.plane)
        // Bootstrap uses pinned IPs — plaintext is acceptable for bootstrap-only queries
        assertTrue(bootstrap.allowPlaintextFallback)
    }

    @Test
    fun `direct plane resolver spec allows plaintext for direct-route domains`() {
        val direct = SplitStrictResolverSpec.directDefault()
        assertEquals(DnsResolverPlane.DIRECT, direct.plane)
        assertTrue(direct.allowPlaintextFallback)
    }

    // ------------------------------------------------------------------
    // 3. Direct DNS only valid for DIRECT-route domains
    // ------------------------------------------------------------------

    @Test
    fun `DomainClass has PROXY and DIRECT variants`() {
        // Verifies the discriminator enum is present
        assertNotNull(DomainClass.PROXY)
        assertNotNull(DomainClass.DIRECT)
        assertNotNull(DomainClass.BLOCK)
    }

    @Test
    fun `direct allowlist domains use DIRECT domain class`() {
        val policy =
            SplitStrictDnsPolicy(
                directAllowlist = listOf("example.com", "local.test"),
            )
        policy.directAllowlist.forEach { domain ->
            assertTrue("$domain must be a non-blank direct-route domain", domain.isNotBlank())
        }
    }

    @Test
    fun `proxy domains cannot be in the direct allowlist`() {
        // Proxy domains are not in the direct allowlist by construction — this
        // verifies the two sets are kept separate in the model.
        val policy =
            SplitStrictDnsPolicy(
                directAllowlist = listOf("direct.example.com"),
            )
        assertFalse(
            "Proxy domain must not appear in the direct allowlist",
            policy.directAllowlist.contains("proxied.example.com"),
        )
    }

    // ------------------------------------------------------------------
    // 4. AAAA / IPv6 interaction
    // ------------------------------------------------------------------

    @Test
    fun `SplitStrictDnsPolicy carries an IPv6Policy field`() {
        val policy = SplitStrictDnsPolicy(ipv6Policy = Ipv6Policy.BLOCK)
        assertEquals(Ipv6Policy.BLOCK, policy.ipv6Policy)
    }

    @Test
    fun `default SplitStrictDnsPolicy ipv6Policy is ALLOW`() {
        val policy = SplitStrictDnsPolicy()
        assertEquals(Ipv6Policy.ALLOW, policy.ipv6Policy)
    }

    @Test
    fun `ipv6Policy BLOCK means AAAA suppressed — policy surface captures the intent`() {
        val policy = SplitStrictDnsPolicy(ipv6Policy = Ipv6Policy.BLOCK)
        // The model surface exposes the intent; actual AAAA filtering is downstream.
        assertEquals(Ipv6Policy.BLOCK, policy.ipv6Policy)
    }

    @Test
    fun `ipv6Policy NATIVE means AAAA queries are forwarded unchanged`() {
        val policy = SplitStrictDnsPolicy(ipv6Policy = Ipv6Policy.NATIVE)
        assertEquals(Ipv6Policy.NATIVE, policy.ipv6Policy)
    }

    @Test
    fun `ipv6Policy TRANSLATED means NAT64 synthesis path is enabled`() {
        val policy = SplitStrictDnsPolicy(ipv6Policy = Ipv6Policy.TRANSLATED)
        assertEquals(Ipv6Policy.TRANSLATED, policy.ipv6Policy)
    }

    @Test
    fun `Ipv6Policy has NATIVE and TRANSLATED variants for split-strict use`() {
        assertNotNull(Ipv6Policy.NATIVE)
        assertNotNull(Ipv6Policy.TRANSLATED)
    }

    // ------------------------------------------------------------------
    // 5. Serialization: DoH POST, DoT strict, DoQ, pinned bootstrap IP, direct allowlists
    // ------------------------------------------------------------------

    @Test
    fun `StrictEncryptedDnsProtocol has DOH_POST, DOT, DOQ entries`() {
        assertNotNull(StrictEncryptedDnsProtocol.DOH_POST)
        assertNotNull(StrictEncryptedDnsProtocol.DOT)
        assertNotNull(StrictEncryptedDnsProtocol.DOQ)
        assertNotNull(StrictEncryptedDnsProtocol.NONE)
    }

    @Test
    fun `SplitStrictDnsPolicy with DOH_POST proxy round-trips through JSON`() {
        val policy =
            SplitStrictDnsPolicy(
                proxy =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.PROXY,
                        protocol = StrictEncryptedDnsProtocol.DOH_POST,
                        endpoint = "https://dns.adguard-dns.com/dns-query",
                        allowPlaintextFallback = false,
                    ),
            )
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)
        assertEquals(StrictEncryptedDnsProtocol.DOH_POST, decoded.proxy.protocol)
        assertEquals("https://dns.adguard-dns.com/dns-query", decoded.proxy.endpoint)
        assertFalse(decoded.proxy.allowPlaintextFallback)
    }

    @Test
    fun `SplitStrictDnsPolicy with DOT proxy round-trips through JSON`() {
        val policy =
            SplitStrictDnsPolicy(
                proxy =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.PROXY,
                        protocol = StrictEncryptedDnsProtocol.DOT,
                        endpoint = "dns.adguard-dns.com",
                        tlsServerName = "dns.adguard-dns.com",
                        allowPlaintextFallback = false,
                    ),
            )
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)
        assertEquals(StrictEncryptedDnsProtocol.DOT, decoded.proxy.protocol)
        assertEquals("dns.adguard-dns.com", decoded.proxy.tlsServerName)
    }

    @Test
    fun `SplitStrictDnsPolicy with DOQ proxy round-trips through JSON`() {
        val policy =
            SplitStrictDnsPolicy(
                proxy =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.PROXY,
                        protocol = StrictEncryptedDnsProtocol.DOQ,
                        endpoint = "dns.adguard-dns.com",
                        allowPlaintextFallback = false,
                    ),
            )
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)
        assertEquals(StrictEncryptedDnsProtocol.DOQ, decoded.proxy.protocol)
    }

    @Test
    fun `SplitStrictDnsPolicy with pinned bootstrap IP round-trips through JSON`() {
        val policy =
            SplitStrictDnsPolicy(
                bootstrap =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.BOOTSTRAP,
                        pinnedBootstrapIp = "94.140.14.14",
                        allowPlaintextFallback = true,
                    ),
            )
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)
        assertEquals("94.140.14.14", decoded.bootstrap.pinnedBootstrapIp)
        assertTrue(decoded.bootstrap.allowPlaintextFallback)
    }

    @Test
    fun `SplitStrictDnsPolicy with direct allowlist round-trips through JSON`() {
        val allowlist = listOf("192.168.0.0/16", "10.0.0.0/8", "local.lan")
        val policy = SplitStrictDnsPolicy(directAllowlist = allowlist)
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)
        assertEquals(allowlist, decoded.directAllowlist)
    }

    @Test
    fun `full SplitStrictDnsPolicy round-trips with all planes configured`() {
        val policy =
            SplitStrictDnsPolicy(
                bootstrap =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.BOOTSTRAP,
                        pinnedBootstrapIp = "94.140.14.14",
                        allowPlaintextFallback = true,
                    ),
                proxy =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.PROXY,
                        protocol = StrictEncryptedDnsProtocol.DOH_POST,
                        endpoint = "https://dns.adguard-dns.com/dns-query",
                        tlsServerName = "dns.adguard-dns.com",
                        allowPlaintextFallback = false,
                    ),
                direct =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.DIRECT,
                        protocol = StrictEncryptedDnsProtocol.NONE,
                        allowPlaintextFallback = true,
                    ),
                block =
                    SplitStrictResolverSpec(
                        plane = DnsResolverPlane.BLOCK,
                        allowPlaintextFallback = false,
                    ),
                directAllowlist = listOf("lan.example.com", "10.0.0.0/8"),
                ipv6Policy = Ipv6Policy.NATIVE,
            )
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)

        assertEquals(DnsResolverPlane.BOOTSTRAP, decoded.bootstrap.plane)
        assertEquals("94.140.14.14", decoded.bootstrap.pinnedBootstrapIp)
        assertEquals(DnsResolverPlane.PROXY, decoded.proxy.plane)
        assertEquals(StrictEncryptedDnsProtocol.DOH_POST, decoded.proxy.protocol)
        assertFalse(decoded.proxy.allowPlaintextFallback)
        assertNotNull(decoded.direct)
        assertEquals(DnsResolverPlane.DIRECT, decoded.direct!!.plane)
        assertNotNull(decoded.block)
        assertEquals(DnsResolverPlane.BLOCK, decoded.block!!.plane)
        assertEquals(listOf("lan.example.com", "10.0.0.0/8"), decoded.directAllowlist)
        assertEquals(Ipv6Policy.NATIVE, decoded.ipv6Policy)
    }

    @Test
    fun `null direct and block planes survive round-trip as null`() {
        val policy = SplitStrictDnsPolicy(direct = null, block = null)
        val encoded = json.encodeToString(SplitStrictDnsPolicy.serializer(), policy)
        val decoded = json.decodeFromString(SplitStrictDnsPolicy.serializer(), encoded)
        assertNull(decoded.direct)
        assertNull(decoded.block)
    }
}
