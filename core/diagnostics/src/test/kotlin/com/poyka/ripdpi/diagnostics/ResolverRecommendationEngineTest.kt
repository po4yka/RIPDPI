package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverRecommendationEngineTest {
    // -- parseHostFromEncryptedEndpoint --

    @Test
    fun `parseHost returns null for empty string`() {
        assertNull(ResolverRecommendationEngine.parseHostFromEncryptedEndpoint(""))
    }

    @Test
    fun `parseHost returns null for blank string`() {
        assertNull(ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("   "))
    }

    @Test
    fun `parseHost extracts host from https url`() {
        assertEquals(
            "dns.google",
            ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("https://dns.google/dns-query"),
        )
    }

    @Test
    fun `parseHost extracts host from http url`() {
        assertEquals(
            "example.com",
            ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("http://example.com:8080/path"),
        )
    }

    @Test
    fun `parseHost extracts host from colon-delimited endpoint`() {
        assertEquals(
            "dns.google",
            ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("dns.google:853"),
        )
    }

    @Test
    fun `parseHost returns host when no port specified`() {
        assertEquals(
            "dns.google",
            ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("dns.google"),
        )
    }

    // -- parsePortFromEncryptedEndpoint --

    @Test
    fun `parsePort returns null for empty string`() {
        assertNull(ResolverRecommendationEngine.parsePortFromEncryptedEndpoint(""))
    }

    @Test
    fun `parsePort returns null for blank string`() {
        assertNull(ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("   "))
    }

    @Test
    fun `parsePort extracts explicit port from https url`() {
        assertEquals(
            8443,
            ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("https://dns.google:8443/dns-query"),
        )
    }

    @Test
    fun `parsePort defaults to 443 for https url without port`() {
        assertEquals(
            443,
            ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("https://dns.google/dns-query"),
        )
    }

    @Test
    fun `parsePort defaults to 80 for http url without port`() {
        assertEquals(
            80,
            ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("http://example.com/path"),
        )
    }

    @Test
    fun `parsePort extracts port from colon-delimited endpoint`() {
        assertEquals(
            853,
            ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("dns.google:853"),
        )
    }

    @Test
    fun `parsePort returns null for host without port`() {
        assertNull(ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("dns.google"))
    }

    // -- parseResolverPathCandidate --

    @Test
    fun `parseCandidate returns null for non-doh protocol with blank host and doh url`() {
        val details =
            mapOf(
                "encryptedProtocol" to "dot",
                "encryptedHost" to "",
                "encryptedResolverId" to "unknown_provider",
            )
        assertNull(ResolverRecommendationEngine.parseResolverPathCandidate(details))
    }

    @Test
    fun `parseCandidate defaults to doh protocol when empty`() {
        val details =
            mapOf(
                "encryptedResolverId" to DnsProviderCloudflare,
            )
        val candidate = ResolverRecommendationEngine.parseResolverPathCandidate(details)
        assertNotNull(candidate)
        assertEquals(EncryptedDnsProtocolDoh, candidate!!.protocol)
    }

    @Test
    fun `parseCandidate defaults resolverId to custom when blank`() {
        val details =
            mapOf(
                "encryptedEndpoint" to "https://custom.example/dns-query",
            )
        val candidate = ResolverRecommendationEngine.parseResolverPathCandidate(details)
        assertNotNull(candidate)
        assertEquals(DnsProviderCustom, candidate!!.resolverId)
    }

    @Test
    fun `parseCandidate extracts bootstrap ips from pipe-separated values`() {
        val details =
            mapOf(
                "encryptedResolverId" to DnsProviderCloudflare,
                "encryptedBootstrapIps" to "1.1.1.1|1.0.0.1",
            )
        val candidate = ResolverRecommendationEngine.parseResolverPathCandidate(details)
        assertNotNull(candidate)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), candidate!!.bootstrapIps)
    }

    @Test
    fun `parseCandidate uses dot port default 853`() {
        val details =
            mapOf(
                "encryptedResolverId" to DnsProviderGoogle,
                "encryptedProtocol" to EncryptedDnsProtocolDot,
                "encryptedHost" to "dns.google",
            )
        val candidate = ResolverRecommendationEngine.parseResolverPathCandidate(details)
        assertNotNull(candidate)
        assertEquals(853, candidate!!.port)
    }

    @Test
    fun `parseCandidate clears tls server name for dnscrypt`() {
        val details =
            mapOf(
                "encryptedResolverId" to DnsProviderCloudflare,
                "encryptedProtocol" to EncryptedDnsProtocolDnsCrypt,
                "encryptedHost" to "some-host",
            )
        val candidate = ResolverRecommendationEngine.parseResolverPathCandidate(details)
        assertNotNull(candidate)
        assertEquals("", candidate!!.tlsServerName)
    }

    // -- toEncryptedDnsPathCandidate --

    @Test
    fun `toEncryptedDnsPathCandidate preserves dot settings`() {
        val recommendation =
            ResolverRecommendation(
                triggerOutcome = "dns_substitution",
                selectedResolverId = DnsProviderGoogle,
                selectedProtocol = EncryptedDnsProtocolDot,
                selectedEndpoint = "dns.google:853",
                selectedBootstrapIps = listOf("8.8.8.8"),
                selectedHost = "dns.google",
                selectedPort = 853,
                selectedTlsServerName = "dns.google",
                selectedDohUrl = "",
                selectedDnscryptProviderName = "",
                selectedDnscryptPublicKey = "",
                rationale = "test",
            )
        val candidate = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        assertEquals(DnsProviderGoogle, candidate.resolverId)
        assertEquals(EncryptedDnsProtocolDot, candidate.protocol)
        assertEquals("dns.google", candidate.host)
        assertEquals(853, candidate.port)
        assertEquals("dns.google", candidate.tlsServerName)
        assertEquals("", candidate.dohUrl)
    }

    @Test
    fun `toEncryptedDnsPathCandidate parses host from endpoint when selectedHost is blank`() {
        val recommendation =
            ResolverRecommendation(
                triggerOutcome = "dns_substitution",
                selectedResolverId = DnsProviderCloudflare,
                selectedProtocol = EncryptedDnsProtocolDoh,
                selectedEndpoint = "https://cloudflare-dns.com/dns-query",
                selectedBootstrapIps = emptyList(),
                selectedHost = "",
                selectedPort = 0,
                selectedTlsServerName = "",
                selectedDohUrl = "https://cloudflare-dns.com/dns-query",
                selectedDnscryptProviderName = "",
                selectedDnscryptPublicKey = "",
                rationale = "test",
            )
        val candidate = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        assertEquals("cloudflare-dns.com", candidate.host)
        assertEquals(443, candidate.port)
        assertEquals("https://cloudflare-dns.com/dns-query", candidate.dohUrl)
    }

    @Test
    fun `toEncryptedDnsPathCandidate clears tls for dnscrypt`() {
        val recommendation =
            ResolverRecommendation(
                triggerOutcome = "udp_blocked",
                selectedResolverId = DnsProviderCloudflare,
                selectedProtocol = EncryptedDnsProtocolDnsCrypt,
                selectedEndpoint = "some-host:443",
                selectedBootstrapIps = emptyList(),
                selectedHost = "some-host",
                selectedPort = 443,
                selectedTlsServerName = "",
                selectedDohUrl = "",
                selectedDnscryptProviderName = "provider",
                selectedDnscryptPublicKey = "pubkey",
                rationale = "test",
            )
        val candidate = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        assertEquals("", candidate.tlsServerName)
        assertEquals("", candidate.dohUrl)
        assertEquals("provider", candidate.dnscryptProviderName)
    }

    // -- compute --

    @Test
    fun `compute returns null when no dns integrity results`() {
        val report =
            ScanReport(
                sessionId = "s1",
                profileId = "p1",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 0,
                summary = "",
                results =
                    listOf(
                        ProbeResult(probeType = "tcp_connect", target = "example.com", outcome = "success"),
                    ),
            )
        assertNull(
            ResolverRecommendationEngine.compute(
                report = report,
                settings =
                    com.poyka.ripdpi.proto.AppSettings
                        .getDefaultInstance(),
                preferredPath = null,
            ),
        )
    }

    @Test
    fun `compute returns null when no trigger outcome among dns results`() {
        val report =
            ScanReport(
                sessionId = "s1",
                profileId = "p1",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 0,
                summary = "",
                results =
                    listOf(
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "example.com",
                            outcome = "dns_match",
                            details =
                                listOf(
                                    ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                ),
                        ),
                    ),
            )
        assertNull(
            ResolverRecommendationEngine.compute(
                report = report,
                settings =
                    com.poyka.ripdpi.proto.AppSettings
                        .getDefaultInstance(),
                preferredPath = null,
            ),
        )
    }

    @Test
    fun `compute selects best candidate by match count`() {
        val report =
            ScanReport(
                sessionId = "s1",
                profileId = "p1",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 100,
                summary = "",
                results =
                    listOf(
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "example.com",
                            outcome = "dns_substitution",
                            details =
                                listOf(
                                    ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                    ProbeDetail("encryptedAddresses", "1.2.3.4"),
                                ),
                        ),
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "example.com",
                            outcome = "dns_match",
                            details =
                                listOf(
                                    ProbeDetail("encryptedResolverId", DnsProviderGoogle),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDot),
                                    ProbeDetail("encryptedHost", "dns.google"),
                                    ProbeDetail("encryptedLatencyMs", "50"),
                                ),
                        ),
                    ),
            )
        val result =
            ResolverRecommendationEngine.compute(
                report = report,
                settings =
                    com.poyka.ripdpi.proto.AppSettings
                        .getDefaultInstance(),
                preferredPath = null,
            )
        assertNotNull(result)
        assertEquals(DnsProviderGoogle, result!!.selectedResolverId)
        assertEquals(EncryptedDnsProtocolDot, result.selectedProtocol)
        assertEquals("dns_substitution", result.triggerOutcome)
        assertTrue(result.persistable)
    }
}
