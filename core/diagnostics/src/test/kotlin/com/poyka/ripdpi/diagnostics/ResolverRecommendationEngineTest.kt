package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverRecommendationEngineTest {
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

    @Test
    fun `parse host and port handle bracketed and bare ipv6 endpoints`() {
        assertEquals("2001:db8::1", ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("[2001:db8::1]:853"))
        assertEquals(853, ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("[2001:db8::1]:853"))
        assertEquals("2001:db8::1", ResolverRecommendationEngine.parseHostFromEncryptedEndpoint("2001:db8::1"))
        assertNull(ResolverRecommendationEngine.parsePortFromEncryptedEndpoint("2001:db8::1"))
    }

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
}

class ResolverRecommendationEngineComputeTest {
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
    fun `compute recommends resolver for in path skipped udp block`() {
        val report =
            ScanReport(
                sessionId = "s1",
                profileId = "p1",
                pathMode = ScanPathMode.IN_PATH,
                startedAt = 0,
                finishedAt = 100,
                summary = "",
                results =
                    listOf(
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "example.com",
                            outcome = "udp_skipped_or_blocked",
                        ),
                        ProbeResult(
                            probeType = "dns_integrity",
                            target = "example.com",
                            outcome = "dns_match",
                            details =
                                listOf(
                                    ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                    ProbeDetail("encryptedAddresses", "1.2.3.4"),
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
        assertEquals(DnsProviderCloudflare, result!!.selectedResolverId)
        assertEquals("udp_skipped_or_blocked", result.triggerOutcome)
    }

    @Test
    fun `compute does not recommend resolver for inconclusive udp timeout`() {
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
                            outcome = "udp_timeout_transient",
                            details =
                                listOf(
                                    ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                    ProbeDetail("encryptedEndpoint", "https://cloudflare-dns.com/dns-query"),
                                    ProbeDetail("encryptedHost", "cloudflare-dns.com"),
                                    ProbeDetail("encryptedPort", "443"),
                                    ProbeDetail("encryptedAddresses", "1.2.3.4"),
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

        assertNull(result)
    }

    @Test
    fun `compute does not recommend resolver for raw path skipped udp`() {
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
                            outcome = "udp_skipped_or_blocked",
                            resolverId = DnsProviderCloudflare,
                            protocol = EncryptedDnsProtocolDoh,
                            host = "cloudflare-dns.com",
                            endpoint = "https://cloudflare-dns.com/dns-query",
                            bootstrapIps = "1.1.1.1",
                            encryptedAddresses = "93.184.216.34",
                            latencyMs = "5",
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
    fun `compute recommends resolver for corroborated suspicious divergence`() {
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
                            outcome = "dns_suspicious_divergence",
                            resolverId = DnsProviderCloudflare,
                            protocol = EncryptedDnsProtocolDoh,
                            host = "cloudflare-dns.com",
                            endpoint = "https://cloudflare-dns.com/dns-query",
                            bootstrapIps = "1.1.1.1",
                            encryptedAddresses = "93.184.216.34",
                            latencyMs = "5",
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

        assertEquals("dns_suspicious_divergence", result?.triggerOutcome)
        assertEquals(DnsProviderCloudflare, result?.selectedResolverId)
    }

    @Test
    fun `compute recommends resolver for nxdomain mismatch with encrypted answers`() {
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
                            outcome = "dns_nxdomain_mismatch",
                            details =
                                listOf(
                                    ProbeDetail("encryptedResolverId", DnsProviderCloudflare),
                                    ProbeDetail("encryptedProtocol", EncryptedDnsProtocolDoh),
                                    ProbeDetail("encryptedEndpoint", "https://cloudflare-dns.com/dns-query"),
                                    ProbeDetail("encryptedHost", "cloudflare-dns.com"),
                                    ProbeDetail("encryptedPort", "443"),
                                    ProbeDetail("encryptedAddresses", "1.2.3.4"),
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

        assertNotNull(result)
        assertEquals(DnsProviderCloudflare, result!!.selectedResolverId)
        assertEquals("dns_nxdomain_mismatch", result.triggerOutcome)
    }

    @Test
    fun `compute recommends resolver for system DNS failure with encrypted answers`() {
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
                            outcome = "dns_system_resolution_failed",
                            resolverId = DnsProviderCloudflare,
                            protocol = EncryptedDnsProtocolDoh,
                            host = "cloudflare-dns.com",
                            endpoint = "https://cloudflare-dns.com/dns-query",
                            bootstrapIps = "1.1.1.1",
                            encryptedAddresses = "1.2.3.4",
                            latencyMs = "25",
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
        assertEquals(DnsProviderCloudflare, result!!.selectedResolverId)
        assertEquals("dns_system_resolution_failed", result.triggerOutcome)
    }

    @Test
    fun `compute recommends resolver for sinkhole substitution with encrypted answers`() {
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
                                    ProbeDetail("encryptedEndpoint", "https://cloudflare-dns.com/dns-query"),
                                    ProbeDetail("encryptedHost", "cloudflare-dns.com"),
                                    ProbeDetail("encryptedPort", "443"),
                                    ProbeDetail("encryptedAddresses", "1.2.3.4"),
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

        assertNotNull(result)
        assertEquals(DnsProviderCloudflare, result!!.selectedResolverId)
        assertEquals("dns_sinkhole_substitution", result.triggerOutcome)
    }
}

class ResolverRecommendationEngineEvidenceTest {
    @Test
    fun `trusted encrypted answers for another question remain advisory`() {
        val failure = confirmedDnsRecovery().copy(details = emptyList())
        val unrelated = confirmedDnsRecovery().copy(target = "other.example", outcome = "dns_match")

        assertFalse(requireNotNull(computeRecoveryRecommendation(listOf(failure, unrelated))).persistable)
    }

    @Test
    fun `same question requires a completed oracle assessment for the selected resolver`() {
        val recovery = confirmedDnsRecovery()
        val cases =
            listOf(
                "oracleTrust" to "single_fallback",
                "oracleTermination" to "cancelled",
                "oracleSelectedResolverId" to DnsProviderGoogle,
                "encryptedBootstrapValidated" to "false",
                "encryptedAddresses" to "not-an-ip",
            )
        cases.forEach { (key, value) ->
            val weakened =
                recovery.copy(
                    details =
                        recovery.details.map {
                            if (it.key ==
                                key
                            ) {
                                ProbeDetail(key, value)
                            } else {
                                it
                            }
                        },
                )
            assertFalse(key, requireNotNull(computeRecoveryRecommendation(listOf(weakened))).persistable)
        }
    }

    @Test
    fun `confirmed same-question recovery outranks advisory candidates and requires a complete scan`() {
        val recovery = confirmedDnsRecovery()
        val unrelated = confirmedDnsRecovery().copy(target = "other.example", outcome = "dns_match")

        val recommendation = requireNotNull(computeRecoveryRecommendation(listOf(recovery, unrelated)))
        assertEquals(DnsProviderCloudflare, recommendation.selectedResolverId)
        assertTrue(recommendation.persistable)
        val partial = computeRecoveryRecommendation(listOf(recovery), ScanCompletionKind.PARTIAL_RESULTS)
        assertFalse(requireNotNull(partial).persistable)
    }

    @Test
    fun `system DNS failure requires repeated failed attempts without a recovered retry`() {
        val timeout = confirmedDnsRecovery().copy(outcome = "dns_system_resolution_failed")
        val repeatedTimeout =
            timeout.copy(
                details =
                    timeout.details +
                        listOf(
                            ProbeDetail("udpAttemptCount", "2"),
                            ProbeDetail("udpSuccessCount", "0"),
                            ProbeDetail("udpRetryRecovered", "false"),
                            ProbeDetail("udpCacheHit", "false"),
                        ),
            )
        val recovered =
            repeatedTimeout.copy(
                details =
                    repeatedTimeout.details.map {
                        if (it.key ==
                            "udpRetryRecovered"
                        ) {
                            it.copy(value = "true")
                        } else {
                            it
                        }
                    },
            )

        assertFalse(requireNotNull(computeRecoveryRecommendation(listOf(timeout))).persistable)
        assertTrue(requireNotNull(computeRecoveryRecommendation(listOf(repeatedTimeout))).persistable)
        assertFalse(requireNotNull(computeRecoveryRecommendation(listOf(recovered))).persistable)
    }
}

internal fun dnsIntegrityResult(
    outcome: String,
    resolverId: String,
    protocol: String,
    host: String,
    endpoint: String,
    bootstrapIps: String,
    encryptedAddresses: String,
    latencyMs: String,
    bootstrapValidated: Boolean = false,
): ProbeResult =
    ProbeResult(
        probeType = "dns_integrity",
        target = "example.com",
        outcome = outcome,
        details =
            buildList {
                add(ProbeDetail("encryptedResolverId", resolverId))
                add(ProbeDetail("encryptedProtocol", protocol))
                add(ProbeDetail("encryptedEndpoint", endpoint))
                add(ProbeDetail("encryptedHost", host))
                add(ProbeDetail("encryptedBootstrapIps", bootstrapIps))
                add(ProbeDetail("encryptedAddresses", encryptedAddresses))
                add(ProbeDetail("encryptedLatencyMs", latencyMs))
                if (bootstrapValidated) add(ProbeDetail("encryptedBootstrapValidated", "true"))
            },
    )

private fun confirmedDnsRecovery(): ProbeResult =
    dnsIntegrityResult(
        outcome = "dns_nxdomain_mismatch",
        resolverId = DnsProviderCloudflare,
        protocol = EncryptedDnsProtocolDoh,
        host = "cloudflare-dns.com",
        endpoint = "https://cloudflare-dns.com/dns-query",
        bootstrapIps = "1.1.1.1",
        encryptedAddresses = "192.0.2.10",
        latencyMs = "25",
        bootstrapValidated = true,
    ).let { result ->
        result.copy(
            details =
                result.details +
                    listOf(
                        ProbeDetail("oracleTrust", "trusted_agreement"),
                        ProbeDetail("oracleTermination", "quorum_reached"),
                        ProbeDetail("oracleSelectedResolverId", DnsProviderCloudflare),
                    ),
        )
    }

private fun computeRecoveryRecommendation(
    results: List<ProbeResult>,
    completionKind: ScanCompletionKind = ScanCompletionKind.NORMAL,
): ResolverRecommendation? =
    ResolverRecommendationEngine.compute(
        report =
            ScanReport(
                sessionId = "resolver",
                profileId = "default",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 100,
                summary = "",
                completionKind = completionKind,
                results = results,
            ),
        settings =
            com.poyka.ripdpi.proto.AppSettings
                .getDefaultInstance(),
        preferredPath = null,
    )
