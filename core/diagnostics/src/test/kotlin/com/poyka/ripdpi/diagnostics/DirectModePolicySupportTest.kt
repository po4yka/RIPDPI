package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectModePolicySupportTest {
    @Test
    fun `dns poisoning selects encrypted dns mode while preserving typed classifier`() {
        val observations =
            collectDirectPathCapabilityObservations(
                reportWithResults(
                    ProbeResult(
                        probeType = "dns_integrity",
                        target = "Example.org",
                        outcome = "dns_sinkhole_substitution",
                        details =
                            listOf(
                                ProbeDetail("dnsClassification", "ECH_CAPABLE"),
                                ProbeDetail("dnsAnswerClass", "POISONED"),
                                ProbeDetail("dnsSelectedResolverRole", "secondary"),
                            ),
                    ),
                ),
            )

        val observation = observations.getValue("Example.org")

        assertEquals(DirectDnsClassification.ECH_CAPABLE, observation.dnsClassification)
        assertEquals(DnsMode.DOH_SECONDARY, observation.transportPolicy?.dnsMode)
    }

    @Test
    fun `clean dns result keeps system dns mode`() {
        val observations =
            collectDirectPathCapabilityObservations(
                reportWithResults(
                    ProbeResult(
                        probeType = "dns_integrity",
                        target = "example.org",
                        outcome = "dns_match",
                        details =
                            listOf(
                                ProbeDetail("dnsClassification", "CLEAN"),
                                ProbeDetail("dnsAnswerClass", "CLEAN"),
                                ProbeDetail("dnsSelectedResolverRole", "primary"),
                            ),
                    ),
                ),
            )

        val observation = observations.getValue("example.org")

        assertEquals(DirectDnsClassification.CLEAN, observation.dnsClassification)
        assertEquals(DnsMode.SYSTEM, observation.transportPolicy?.dnsMode)
    }

    @Test
    fun `tls handshake failure without server hello evidence does not claim post client hello`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "strategy_https",
                    target = "example.org",
                    outcome = "tls_handshake_failed",
                    details =
                        listOf(
                            ProbeDetail("candidateId", "baseline_plain_direct"),
                            ProbeDetail("targetHost", "example.org"),
                            ProbeDetail("tlsError", "Error with reply: Host unreachable."),
                            ProbeDetail("tlsServerHelloReceived", "false"),
                        ),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, observation.transportClass)
        assertEquals(DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE, observation.reasonCode)
        assertNotNull(observation.cooldownUntil)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE, verdict?.reasonCode)
        assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, verdict?.transportClass)
        assertEquals(
            "No direct solution for this authority",
            report.copy(directModeVerdict = verdict).displaySummary(),
        )
    }

    @Test
    fun `tls handshake failure with server hello evidence confirms post client hello`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "strategy_https",
                    target = "example.org",
                    outcome = "tls_handshake_failed",
                    details =
                        listOf(
                            ProbeDetail("candidateId", "baseline_plain_direct"),
                            ProbeDetail("targetHost", "example.org"),
                            ProbeDetail("tlsServerHelloReceived", "true"),
                        ),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, observation.reasonCode)
        assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, verdict?.reasonCode)
    }

    @Test
    fun `non baseline strategy success cannot override baseline failure`() {
        val report =
            reportWithResults(
                strategyHttpsProbe(
                    candidateId = "baseline_plain_direct",
                    outcome = "tls_handshake_failed",
                ),
                strategyHttpsProbe(
                    candidateId = "tlsrec_split",
                    outcome = "tls_ok",
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
    }

    @Test
    fun `plain direct baseline success can establish transparent direct mode`() {
        val report =
            reportWithResults(
                strategyHttpsProbe(
                    candidateId = "baseline_plain_direct",
                    outcome = "tls_ok",
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.TRANSPARENT_OK, observation.transportPolicy?.outcome)
        assertEquals(DirectModeVerdictResult.TRANSPARENT_WORKS, verdict?.result)
    }

    @Test
    fun `current configured strategy success is not transparent direct evidence`() {
        val report =
            reportWithResults(
                strategyHttpsProbe(
                    candidateId = "baseline_current",
                    outcome = "tls_ok",
                ),
            )

        assertTrue(collectDirectPathCapabilityObservations(report).isEmpty())
        assertNull(deriveDirectModeVerdict(report))
    }

    @Test
    fun `all failed quic authority returns honest no direct solution verdict`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "strategy_quic",
                    target = "example.org",
                    outcome = "quic_error",
                    details =
                        listOf(
                            ProbeDetail("candidateId", "baseline_plain_direct"),
                            ProbeDetail("targetHost", "example.org"),
                        ),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, observation.transportClass)
        assertEquals(DirectModeReasonCode.QUIC_BLOCKED, observation.reasonCode)
        assertNotNull(observation.cooldownUntil)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.QUIC_BLOCKED, verdict?.reasonCode)
        assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, verdict?.transportClass)
    }

    @Test
    fun `rejected strategy audit suppresses definitive direct mode verdict`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                auditAssessment =
                    scanWorkflowAuditAssessment().copy(
                        confidence =
                            scanWorkflowAuditAssessment().confidence.copy(
                                level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                            ),
                    ),
            ).copy(
                results =
                    listOf(
                        strategyHttpsProbe(
                            candidateId = "baseline_plain_direct",
                            outcome = "tls_handshake_failed",
                        ),
                        ProbeResult(
                            probeType = "service_gateway",
                            target = "example.org",
                            outcome = "unreachable",
                            details = listOf(ProbeDetail("targetHost", "example.org")),
                        ),
                    ),
            )

        assertNull(deriveDirectModeVerdict(report))
    }

    @Test
    fun `missing strategy assessment suppresses definitive direct mode verdict`() {
        val report =
            reportWithResults(
                strategyHttpsProbe(
                    candidateId = "baseline_plain_direct",
                    outcome = "tls_handshake_failed",
                ),
            ).copy(strategyProbeReport = null)

        assertNull(deriveDirectModeVerdict(report))
    }

    @Test
    fun `partial dpi full quic error creates neither verdict nor cooldown`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "quic_reachability",
                    target = "example.org",
                    outcome = "quic_error",
                    details = listOf(ProbeDetail("quicHost", "example.org")),
                ),
            ).copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS)

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")

        assertNull(deriveDirectModeVerdict(report))
        assertNull(observation.transportPolicy)
        assertNull(observation.cooldownUntil)
    }

    @Test
    fun `mixed controls suppress no direct solution`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "service_gateway",
                    target = "example.org",
                    outcome = "unreachable",
                    details =
                        listOf(
                            ProbeDetail("targetHost", "example.org"),
                            ProbeDetail("controlOutcome", "mixed"),
                        ),
                ),
            )

        assertNull(deriveDirectModeVerdict(report))
    }

    @Test
    fun `complete accepted audit preserves definitive direct verdict`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                auditAssessment = scanWorkflowAuditAssessment(),
            ).copy(
                results = listOf(strategyHttpsProbe("baseline_plain_direct", "tls_ok")),
            )

        assertEquals(DirectModeVerdictResult.TRANSPARENT_WORKS, deriveDirectModeVerdict(report)?.result)
    }

    @Test
    fun `complete accepted audit preserves negative direct verdict and cooldown`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                auditAssessment = scanWorkflowAuditAssessment(),
            ).copy(
                results =
                    listOf(
                        strategyHttpsProbe("baseline_plain_direct", "tls_handshake_failed"),
                        ProbeResult(
                            probeType = "service_gateway",
                            target = "example.org",
                            outcome = "unreachable",
                            details = listOf(ProbeDetail("targetHost", "example.org")),
                        ),
                    ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")

        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, deriveDirectModeVerdict(report)?.result)
        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertNotNull(observation.cooldownUntil)
    }

    @Test
    fun `deadline exceeded quic error creates neither verdict nor cooldown`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "quic_reachability",
                    target = "example.org",
                    outcome = "quic_error",
                    details = listOf(ProbeDetail("quicHost", "example.org")),
                ),
            ).copy(
                completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                terminationReason = ScanTerminationReason.DEADLINE_EXCEEDED,
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")

        assertNull(deriveDirectModeVerdict(report))
        assertNull(observation.transportPolicy)
        assertNull(observation.cooldownUntil)
    }

    @Test
    fun `post deadline and local setup samples suppress direct mode verdict`() {
        val postDeadline =
            reportWithResults(
                ProbeResult(
                    probeType = "service_gateway",
                    target = "example.org",
                    outcome = "unreachable",
                    details =
                        listOf(
                            ProbeDetail("targetHost", "example.org"),
                            ProbeDetail("postDeadline", "true"),
                        ),
                ),
            )
        val localSetupFailure =
            postDeadline.copy(
                results =
                    listOf(
                        ProbeResult(
                            probeType = "service_gateway",
                            target = "example.org",
                            outcome = "unreachable",
                            details =
                                listOf(
                                    ProbeDetail("targetHost", "example.org"),
                                    ProbeDetail("failureClass", "local_setup_failure"),
                                ),
                        ),
                    ),
            )

        assertNull(deriveDirectModeVerdict(postDeadline))
        assertNull(deriveDirectModeVerdict(localSetupFailure))
    }

    @Test
    fun `unreachable authority remains the ip block suspect path`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "service_gateway",
                    target = "example.org",
                    outcome = "unreachable",
                    details = listOf(ProbeDetail("targetHost", "example.org")),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, observation.transportClass)
        assertEquals(DirectModeReasonCode.IP_BLOCKED, observation.reasonCode)
        assertNotNull(observation.cooldownUntil)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.IP_BLOCKED, verdict?.reasonCode)
        assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, verdict?.transportClass)
    }

    @Test
    fun `fat header failures do not create no direct solution when domain tls is healthy`() {
        val report =
            reportWithResults(
                healthyDomainTlsProbe("youtube.com"),
                healthyDomainTlsProbe("discord.com"),
                healthyDomainTlsProbe("proton.me"),
                fatHeaderProbe(target = "172.67.70.222:443 (Cloudflare)", outcome = "tcp_16kb_blocked"),
                fatHeaderProbe(target = "8.8.8.8:443 (Google DNS)", outcome = "tcp_reset"),
                fatHeaderProbe(target = "9.9.9.9:443 (Quad9)", outcome = "tcp_reset"),
            )

        val observations = collectDirectPathCapabilityObservations(report)
        val verdict = deriveDirectModeVerdict(report)

        assertNull(verdict)
        assertFalse(observations.containsKey("172.67.70.222:443"))
        assertFalse(observations.containsKey("8.8.8.8:443"))
        assertFalse(observations.containsKey("9.9.9.9:443"))
        assertTrue(observations.values.all { it.transportPolicy?.outcome != DirectModeOutcome.NO_DIRECT_SOLUTION })
        assertEquals(DirectPathHealthState.DIRECT_PATH_HEALTHY_WITH_SYNTHETIC_ATTENTION, report.directPathHealthState())
    }

    @Test
    fun `fat header failures still create no direct solution without enough healthy domain tls coverage`() {
        val report =
            reportWithResults(
                healthyDomainTlsProbe("youtube.com"),
                healthyDomainTlsProbe("discord.com"),
                fatHeaderProbe(target = "172.67.70.222:443 (Cloudflare)", outcome = "tcp_16kb_blocked"),
                fatHeaderProbe(target = "8.8.8.8:443 (Google DNS)", outcome = "tcp_reset"),
            )

        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.IP_BLOCKED, verdict?.reasonCode)
        assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, verdict?.transportClass)
    }

    private fun healthyDomainTlsProbe(target: String): ProbeResult =
        ProbeResult(
            probeType = "domain_reachability",
            target = target,
            outcome = "tls_ok",
            details = listOf(ProbeDetail("targetHost", target)),
        )

    private fun strategyHttpsProbe(
        candidateId: String,
        outcome: String,
    ): ProbeResult =
        ProbeResult(
            probeType = "strategy_https",
            target = "Candidate · example.org",
            outcome = outcome,
            details =
                listOf(
                    ProbeDetail("candidateId", candidateId),
                    ProbeDetail("targetHost", "example.org"),
                ),
        )

    private fun fatHeaderProbe(
        target: String,
        outcome: String,
    ): ProbeResult =
        ProbeResult(
            probeType = "tcp_fat_header",
            target = target,
            outcome = outcome,
        )

    private fun reportWithResults(vararg results: ProbeResult): ScanReport =
        ScanReport(
            sessionId = "session",
            profileId = "profile",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "summary",
            results = results.toList(),
            strategyProbeReport =
                scanReportWithStrategyProbe(
                    proxyConfigJson = validRecommendedProxyConfigJson(),
                    tcpFamily = "hostfake",
                    quicFamily = "quic_realistic_burst",
                    auditAssessment = scanWorkflowAuditAssessment(),
                ).strategyProbeReport,
        )
}
