package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverRecommendationEngineCandidateSelectionTest {
    @Test
    fun `compute ignores resolver candidates with oracle error address tokens`() {
        for (encryptedAddresses in listOf("dns_oracle_unavailable", "dns_oracle_disagreement", "[]")) {
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
                                outcome = "udp_blocked",
                                details =
                                    listOf(
                                        ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                        ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                        ProbeDetail("encryptedEndpoint", "https://cloudflare-dns.com/dns-query"),
                                        ProbeDetail("encryptedHost", "cloudflare-dns.com"),
                                        ProbeDetail("encryptedPort", "443"),
                                        ProbeDetail("encryptedAddresses", encryptedAddresses),
                                        ProbeDetail("encryptedLatencyMs", "25"),
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

            assertNull("expected no recommendation for encryptedAddresses=$encryptedAddresses", result)
        }
    }

    @Test
    fun `compute ignores preferred sinkhole candidate when encrypted answers are oracle errors`() {
        val preferredPath =
            EncryptedDnsPathCandidate(
                resolverId = DnsProviderCloudflare,
                resolverLabel = "Cloudflare",
                protocol = EncryptedDnsProtocolDoh,
                host = "cloudflare-dns.com",
                port = 443,
                tlsServerName = "cloudflare-dns.com",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query",
            )
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
                        dnsIntegrityResult(
                            outcome = "dns_sinkhole_substitution",
                            resolverId = DnsProviderCloudflare,
                            protocol = EncryptedDnsProtocolDoh,
                            host = "cloudflare-dns.com",
                            endpoint = "https://cloudflare-dns.com/dns-query",
                            bootstrapIps = "1.1.1.1|1.0.0.1",
                            encryptedAddresses = "dns_oracle_unavailable",
                            latencyMs = "5",
                        ),
                        dnsIntegrityResult(
                            outcome = "dns_nxdomain_mismatch",
                            resolverId = DnsProviderGoogle,
                            protocol = EncryptedDnsProtocolDot,
                            host = "dns.google",
                            endpoint = "dns.google:853",
                            bootstrapIps = "8.8.8.8|8.8.4.4",
                            encryptedAddresses = "93.184.216.34",
                            latencyMs = "90",
                        ),
                    ),
            )

        val result =
            ResolverRecommendationEngine.compute(
                report = report,
                settings =
                    com.poyka.ripdpi.proto.AppSettings
                        .getDefaultInstance(),
                preferredPath = preferredPath,
            )

        assertNotNull(result)
        assertEquals(DnsProviderGoogle, result!!.selectedResolverId)
        assertEquals("dns_sinkhole_substitution", result.triggerOutcome)
    }

    @Test
    fun `compute demotes blocked bootstrap path before preferred path under sinkhole chaos`() {
        val preferredPath =
            EncryptedDnsPathCandidate(
                resolverId = DnsProviderCloudflare,
                resolverLabel = "Cloudflare",
                protocol = EncryptedDnsProtocolDoh,
                host = "cloudflare-dns.com",
                port = 443,
                tlsServerName = "cloudflare-dns.com",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query",
            )
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
                            probeType = "tcp_fat_header",
                            target = "1.1.1.1:443",
                            outcome = "tcp_timeout",
                        ),
                        dnsIntegrityResult(
                            outcome = "dns_sinkhole_substitution",
                            resolverId = DnsProviderCloudflare,
                            protocol = EncryptedDnsProtocolDoh,
                            host = "cloudflare-dns.com",
                            endpoint = "https://cloudflare-dns.com/dns-query",
                            bootstrapIps = "1.1.1.1|1.0.0.1",
                            encryptedAddresses = "93.184.216.34",
                            latencyMs = "5",
                            bootstrapValidated = true,
                        ),
                        dnsIntegrityResult(
                            outcome = "dns_nxdomain_mismatch",
                            resolverId = DnsProviderGoogle,
                            protocol = EncryptedDnsProtocolDot,
                            host = "dns.google",
                            endpoint = "dns.google:853",
                            bootstrapIps = "8.8.8.8|8.8.4.4",
                            encryptedAddresses = "93.184.216.34",
                            latencyMs = "80",
                            bootstrapValidated = true,
                        ),
                    ),
            )

        val result =
            ResolverRecommendationEngine.compute(
                report = report,
                settings =
                    com.poyka.ripdpi.proto.AppSettings
                        .getDefaultInstance(),
                preferredPath = preferredPath,
            )

        assertNotNull(result)
        assertEquals(DnsProviderGoogle, result!!.selectedResolverId)
        assertFalse(result.rationale.contains("blocked by DPI"))
    }

    @Test
    fun `compute avoids reset ipv6 bootstrap and names it blocked by DPI`() {
        for (target in listOf("[2606:4700::1111]:443", "2606:4700::1111", "[2606:4700:0:0:0:0:0:1111]:443")) {
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
                            ProbeResult(probeType = "tcp_fat_header", target = target, outcome = "tcp_reset"),
                            dnsIntegrityResult(
                                outcome = "dns_sinkhole_substitution",
                                resolverId = DnsProviderCloudflare,
                                protocol = EncryptedDnsProtocolDoh,
                                host = "cloudflare-dns.com",
                                endpoint = "https://cloudflare-dns.com/dns-query",
                                bootstrapIps = "2606:4700::1111",
                                encryptedAddresses = "93.184.216.34",
                                latencyMs = "5",
                                bootstrapValidated = true,
                            ),
                            dnsIntegrityResult(
                                outcome = "dns_nxdomain_mismatch",
                                resolverId = DnsProviderGoogle,
                                protocol = EncryptedDnsProtocolDot,
                                host = "dns.google",
                                endpoint = "dns.google:853",
                                bootstrapIps = "8.8.8.8",
                                encryptedAddresses = "93.184.216.34",
                                latencyMs = "80",
                                bootstrapValidated = true,
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

            assertEquals(target, DnsProviderGoogle, result?.selectedResolverId)
            assertTrue(target, result!!.rationale.contains("Avoided bootstrap IPs blocked by DPI: 2606:4700::1111."))
        }
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
                            outcome = "dns_sinkhole_substitution",
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
        assertEquals("dns_sinkhole_substitution", result.triggerOutcome)
        assertFalse(result.persistable)
    }

    @Test
    fun `compute returns null when primary dns is healthy and secondary encrypted resolver is slow`() {
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
                            outcome = "dns_match",
                            details =
                                listOf(
                                    ProbeDetail("dnsSelectedResolverRole", "primary"),
                                    ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                    ProbeDetail("encryptedAddresses", "93.184.216.34"),
                                    ProbeDetail("encryptedLatencyMs", "30"),
                                ),
                        ),
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "example.com",
                            outcome = "dns_sinkhole_substitution",
                            details =
                                listOf(
                                    ProbeDetail("dnsSelectedResolverRole", "secondary"),
                                    ProbeDetail("encryptedResolverId", DnsProviderGoogle),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                    ProbeDetail("encryptedHost", "dns.google"),
                                    ProbeDetail("encryptedAddresses", "203.0.113.44"),
                                    ProbeDetail("encryptedLatencyMs", "6500"),
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

        assertNull(result)
    }

    @Test
    fun `compute considers later primary trigger after suppressed secondary trigger`() {
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
                            "dns_integrity",
                            "healthy.example",
                            "dns_sinkhole_substitution",
                            listOf(ProbeDetail("dnsSelectedResolverRole", "secondary")),
                        ),
                        ProbeResult(
                            "dns_integrity",
                            "healthy.example",
                            "dns_match",
                            listOf(ProbeDetail("dnsSelectedResolverRole", "primary")),
                        ),
                        ProbeResult(
                            "dns_integrity",
                            "blocked.example",
                            "dns_nxdomain_mismatch",
                            listOf(
                                ProbeDetail("dnsSelectedResolverRole", "primary"),
                                ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                ProbeDetail("encryptedAddresses", "93.184.216.34"),
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
        assertEquals("dns_nxdomain_mismatch", result!!.triggerOutcome)
    }
}
