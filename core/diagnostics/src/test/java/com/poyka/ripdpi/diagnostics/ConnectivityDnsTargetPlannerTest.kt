package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.builtInEncryptedDnsPathCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityDnsTargetPlannerTest {

    private fun plainUdpDns(): ActiveDnsSettings =
        activeDnsSettings(
            dnsMode = DnsModePlainUdp,
            dnsProviderId = DnsProviderCustom,
            dnsIp = "",
            dnsDohUrl = "",
            dnsDohBootstrapIps = emptyList(),
        )

    @Test
    fun `explicit encrypted targets pass through unchanged`() {
        val target = DnsTarget(
            domain = "example.com",
            encryptedResolverId = DnsProviderCloudflare,
            encryptedProtocol = EncryptedDnsProtocolDoh,
            encryptedHost = "cloudflare-dns.com",
        )
        val result = ConnectivityDnsTargetPlanner.expandTargets(
            targets = listOf(target),
            activeDns = plainUdpDns(),
            preferredPath = null,
        )
        assertEquals(1, result.size)
        assertEquals(target, result.first())
    }

    @Test
    fun `target with only encryptedBootstrapIps passes through unchanged`() {
        val target = DnsTarget(
            domain = "example.com",
            encryptedBootstrapIps = listOf("1.1.1.1"),
        )
        val result = ConnectivityDnsTargetPlanner.expandTargets(
            targets = listOf(target),
            activeDns = plainUdpDns(),
            preferredPath = null,
        )
        assertEquals(1, result.size)
        assertEquals(target, result.first())
    }

    @Test
    fun `generic target expands to diversified candidates`() {
        val target = DnsTarget(domain = "example.com")
        val result = ConnectivityDnsTargetPlanner.expandTargets(
            targets = listOf(target),
            activeDns = plainUdpDns(),
            preferredPath = null,
        )
        val expectedCount = builtInEncryptedDnsPathCandidates().size
        assertEquals(expectedCount, result.size)
        assertTrue(result.all { it.domain == "example.com" })
        assertTrue(result.all { !it.encryptedResolverId.isNullOrBlank() })
        assertTrue(result.any { it.encryptedProtocol == EncryptedDnsProtocolDoh })
        assertTrue(result.any { it.encryptedProtocol == EncryptedDnsProtocolDot })
    }

    @Test
    fun `mixed explicit and generic targets preserve ordering`() {
        val explicit = DnsTarget(
            domain = "explicit.com",
            encryptedResolverId = DnsProviderCloudflare,
        )
        val generic = DnsTarget(domain = "generic.com")
        val result = ConnectivityDnsTargetPlanner.expandTargets(
            targets = listOf(explicit, generic),
            activeDns = plainUdpDns(),
            preferredPath = null,
        )
        assertEquals("explicit.com", result.first().domain)
        assertEquals(DnsProviderCloudflare, result.first().encryptedResolverId)
        assertTrue(result.size > 2)
        assertTrue(result.drop(1).all { it.domain == "generic.com" })
    }

    @Test
    fun `preferred path leads expanded targets`() {
        val preferred = EncryptedDnsPathCandidate(
            resolverId = "google",
            resolverLabel = "Google",
            protocol = EncryptedDnsProtocolDot,
            host = "dns.google",
            port = 853,
            tlsServerName = "dns.google",
            bootstrapIps = listOf("8.8.8.8"),
        )
        val target = DnsTarget(domain = "example.com")
        val result = ConnectivityDnsTargetPlanner.expandTargets(
            targets = listOf(target),
            activeDns = plainUdpDns(),
            preferredPath = preferred,
        )
        assertNotNull(result.firstOrNull())
        assertEquals(EncryptedDnsProtocolDot, result.first().encryptedProtocol)
        assertEquals("google", result.first().encryptedResolverId)
    }
}
