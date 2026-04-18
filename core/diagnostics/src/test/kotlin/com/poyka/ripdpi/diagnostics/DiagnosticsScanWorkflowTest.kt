package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxEchStable
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScanWorkflowTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val json = diagnosticsTestJson()

    @Test
    fun `valid recommendation config yields derived families and strategy signature`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        val strategySignature = requireNotNull(recommendation.strategySignature)
        val activeDns = settings.activeDnsSettings()
        assertEquals("tcp-1", recommendation.tcpCandidateId)
        assertEquals("quic-1", recommendation.quicCandidateId)
        assertEquals("hostfake", recommendation.tcpCandidateFamily)
        assertEquals("quic_realistic_burst", recommendation.quicCandidateFamily)
        assertEquals(activeDns.strategyFamily(), recommendation.dnsStrategyFamily)
        assertEquals(activeDns.strategyLabel(), recommendation.dnsStrategyLabel)
        assertEquals("hostfake", strategySignature.tcpStrategyFamily)
        assertEquals("quic_realistic_burst", strategySignature.quicStrategyFamily)
        assertEquals(activeDns.strategyFamily(), strategySignature.dnsStrategyFamily)
    }

    @Test
    fun `valid seqovl recommendation config yields derived tlsrec seqovl family`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validSeqovlRecommendedProxyConfigJson(),
                tcpFamily = "tlsrec_seqovl",
                quicFamily = "quic_realistic_burst",
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        val strategySignature = requireNotNull(recommendation.strategySignature)

        assertEquals("tlsrec_seqovl", recommendation.tcpCandidateFamily)
        assertEquals("tlsrec_seqovl", strategySignature.tcpStrategyFamily)
    }

    @Test
    fun `proxy mode flags browser-native ECH suppression on enriched recommendation`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "ech_tlsrec",
                quicFamily = "quic_realistic_burst",
            )
        val proxyEchSettings =
            settings
                .toBuilder()
                .setRipdpiMode(Mode.Proxy.preferenceValue)
                .setTlsFingerprintProfile(TlsFingerprintProfileFirefoxEchStable)
                .build()

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = proxyEchSettings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        assertTrue(recommendation.tlsPathSuppressed)
        assertEquals("proxy_mode_browser_native_ech_suppressed", recommendation.tlsPathSuppressionReason)
        assertEquals(
            "Proxy mode leaves browser-originated TLS and ECH under the browser/OS stack; " +
                "the selected ECH-aware template applies only to traffic the app originates itself.",
            recommendation.tlsPathSuppressionSummary,
        )
    }

    @Test
    fun `vpn mode does not flag browser-native suppression`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        assertFalse(recommendation.tlsPathSuppressed)
        assertNull(recommendation.tlsPathSuppressionReason)
        assertNull(recommendation.tlsPathSuppressionSummary)
    }

    @Test
    fun `legacy recommended proxy config leaves derived metadata null`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson =
                    """
                    {
                      "kind":"ui",
                      "ip":"127.0.0.1",
                      "port":1080,
                      "desyncMethod":"disorder"
                    }
                    """.trimIndent(),
                tcpFamily = "split",
                quicFamily = "quic_burst",
                auditAssessment = auditAssessment(),
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        assertEquals("tcp-1", recommendation.tcpCandidateId)
        assertEquals("TCP candidate", recommendation.tcpCandidateLabel)
        assertEquals("quic-1", recommendation.quicCandidateId)
        assertEquals("QUIC candidate", recommendation.quicCandidateLabel)
        assertEquals("best path", recommendation.rationale)
        assertNull(recommendation.tcpCandidateFamily)
        assertNull(recommendation.quicCandidateFamily)
        assertNull(recommendation.dnsStrategyFamily)
        assertNull(recommendation.dnsStrategyLabel)
        assertNull(recommendation.strategySignature)
        assertNotNull(requireNotNull(enriched.strategyProbeReport).auditAssessment)
    }

    @Test
    fun `mismatched config fails validation and is not remembered`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "split",
                quicFamily = "quic_burst",
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        assertEquals("tcp-1", recommendation.tcpCandidateId)
        assertEquals("quic-1", recommendation.quicCandidateId)
        assertEquals("best path", recommendation.rationale)
        assertNull(recommendation.tcpCandidateFamily)
        assertNull(recommendation.quicCandidateFamily)
        assertNull(recommendation.dnsStrategyFamily)
        assertNull(recommendation.dnsStrategyLabel)
        assertNull(recommendation.strategySignature)

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = networkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `full matrix recommendation remains non persisted when valid`() {
        val report =
            scanReportWithStrategyProbe(
                suiteId = "full_matrix_v1",
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
            )

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = networkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `background auto persist eligibility accepts high confidence strong coverage`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment = auditAssessment(),
                    ).strategyProbeReport,
                ),
            )

        assertEquals(DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible, eligibility)
    }

    @Test
    fun `background auto persist eligibility accepts strong coverage with not applicable candidates`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment =
                            auditAssessment().copy(
                                coverage =
                                    auditAssessment().coverage.copy(
                                        tcpCandidatesPlanned = 4,
                                        tcpCandidatesExecuted = 2,
                                        tcpCandidatesNotApplicable = 2,
                                        quicCandidatesPlanned = 3,
                                        quicCandidatesExecuted = 2,
                                        quicCandidatesNotApplicable = 1,
                                        matrixCoveragePercent = 100,
                                    ),
                            ),
                    ).strategyProbeReport,
                ),
            )

        assertEquals(DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible, eligibility)
    }

    @Test
    fun `background auto persist eligibility rejects missing audit assessment`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment = null,
                    ).strategyProbeReport,
                ),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.MISSING_AUDIT_ASSESSMENT,
            ),
            eligibility,
        )
    }

    @Test
    fun `background auto persist eligibility rejects medium confidence`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment =
                            auditAssessment().copy(
                                confidence =
                                    auditAssessment().confidence.copy(
                                        level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                    ),
                            ),
                    ).strategyProbeReport,
                ),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.LOW_CONFIDENCE,
            ),
            eligibility,
        )
    }

    @Test
    fun `background auto persist eligibility rejects low confidence`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment =
                            auditAssessment().copy(
                                confidence =
                                    auditAssessment().confidence.copy(
                                        level = StrategyProbeAuditConfidenceLevel.LOW,
                                    ),
                            ),
                    ).strategyProbeReport,
                ),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.LOW_CONFIDENCE,
            ),
            eligibility,
        )
    }

    @Test
    fun `background auto persist eligibility rejects insufficient matrix coverage`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment =
                            auditAssessment().copy(
                                coverage =
                                    auditAssessment().coverage.copy(
                                        matrixCoveragePercent = 74,
                                    ),
                            ),
                    ).strategyProbeReport,
                ),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.INSUFFICIENT_MATRIX_COVERAGE,
            ),
            eligibility,
        )
    }

    @Test
    fun `background auto persist eligibility rejects insufficient winner coverage`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment =
                            auditAssessment().copy(
                                coverage =
                                    auditAssessment().coverage.copy(
                                        winnerCoveragePercent = 49,
                                    ),
                            ),
                    ).strategyProbeReport,
                ),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.INSUFFICIENT_WINNER_COVERAGE,
            ),
            eligibility,
        )
    }

    @Test
    fun `background auto persist eligibility rejects missing winner target success`() {
        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(
                    scanReportWithStrategyProbe(
                        proxyConfigJson = validRecommendedProxyConfigJson(),
                        tcpFamily = "hostfake",
                        quicFamily = "quic_realistic_burst",
                        auditAssessment = auditAssessment(),
                        tcpSucceededTargets = 0,
                        quicSucceededTargets = 0,
                    ).strategyProbeReport,
                ),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.NO_WINNER_TARGET_SUCCESS,
            ),
            eligibility,
        )
    }

    @Test
    fun `temporary resolver override applied when no strategy probe and conditions met`() {
        val report = scanReportWithResolverRecommendation()
        val plainUdpSettings = settingsWithPlainUdpDns()

        assertTrue(
            DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(
                report = report,
                settings = plainUdpSettings,
                serviceStatus = AppStatus.Running,
                serviceMode = Mode.VPN,
                pathMode = ScanPathMode.RAW_PATH,
            ),
        )
    }

    @Test
    fun `temporary resolver override blocked when strategy probe completed normally`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.NORMAL,
            ).copy(resolverRecommendation = resolverRecommendation())
        val plainUdpSettings = settingsWithPlainUdpDns()

        assertFalse(
            DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(
                report = report,
                settings = plainUdpSettings,
                serviceStatus = AppStatus.Running,
                serviceMode = Mode.VPN,
                pathMode = ScanPathMode.RAW_PATH,
            ),
        )
    }

    @Test
    fun `temporary resolver override applied when strategy probe dns short circuited`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            ).copy(resolverRecommendation = resolverRecommendation())
        val plainUdpSettings = settingsWithPlainUdpDns()

        assertTrue(
            DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(
                report = report,
                settings = plainUdpSettings,
                serviceStatus = AppStatus.Running,
                serviceMode = Mode.VPN,
                pathMode = ScanPathMode.RAW_PATH,
            ),
        )
    }

    @Test
    fun `temporary resolver override applied on raw path when service is halted`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK,
            ).copy(resolverRecommendation = resolverRecommendation())
        val plainUdpSettings = settingsWithPlainUdpDns()

        assertTrue(
            DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(
                report = report,
                settings = plainUdpSettings,
                serviceStatus = AppStatus.Halted,
                serviceMode = Mode.VPN,
                pathMode = ScanPathMode.RAW_PATH,
            ),
        )
    }

    @Test
    fun `temporary resolver override skipped when resolver recommendation is null`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            )
        val plainUdpSettings = settingsWithPlainUdpDns()

        assertFalse(
            DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(
                report = report,
                settings = plainUdpSettings,
                serviceStatus = AppStatus.Running,
                serviceMode = Mode.VPN,
                pathMode = ScanPathMode.RAW_PATH,
            ),
        )
    }

    @Test
    fun `reprobe recommended when DNS short-circuited and override applied on RAW_PATH`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            )

        assertTrue(
            DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                report = report,
                pathMode = ScanPathMode.RAW_PATH,
                resolverOverrideApplied = true,
            ),
        )
    }

    @Test
    fun `reprobe recommended when DNS fallback completed and override applied on RAW_PATH`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK,
            )

        assertTrue(
            DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                report = report,
                pathMode = ScanPathMode.RAW_PATH,
                resolverOverrideApplied = true,
            ),
        )
    }

    @Test
    fun `reprobe not recommended when path mode is IN_PATH`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            )

        assertFalse(
            DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                report = report,
                pathMode = ScanPathMode.IN_PATH,
                resolverOverrideApplied = true,
            ),
        )
    }

    @Test
    fun `reprobe not recommended when resolver override was not applied`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            )

        assertFalse(
            DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                report = report,
                pathMode = ScanPathMode.RAW_PATH,
                resolverOverrideApplied = false,
            ),
        )
    }

    @Test
    fun `reprobe not recommended when completion is normal`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                completionKind = StrategyProbeCompletionKind.NORMAL,
            )

        assertFalse(
            DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                report = report,
                pathMode = ScanPathMode.RAW_PATH,
                resolverOverrideApplied = true,
            ),
        )
    }

    @Test
    fun `reprobe not recommended when no strategy probe report`() {
        val report = scanReportWithResolverRecommendation()

        assertFalse(
            DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                report = report,
                pathMode = ScanPathMode.RAW_PATH,
                resolverOverrideApplied = true,
            ),
        )
    }
}

private fun settingsWithPlainUdpDns() =
    defaultDiagnosticsAppSettings()
        .toBuilder()
        .setDnsMode(DnsModePlainUdp)
        .build()

private fun scanReportWithResolverRecommendation(): ScanReport =
    ScanReport(
        sessionId = "session-1",
        profileId = "automatic-probing",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "connectivity scan",
        resolverRecommendation = resolverRecommendation(),
    )

private fun resolverRecommendation(): ResolverRecommendation =
    ResolverRecommendation(
        triggerOutcome = "dns_substitution",
        selectedResolverId = "cloudflare",
        selectedProtocol = "doh",
        selectedEndpoint = "https://cloudflare-dns.com/dns-query",
        rationale = "DNS tampering detected",
    )

private fun scanReportWithStrategyProbe(
    proxyConfigJson: String,
    tcpFamily: String,
    quicFamily: String,
    suiteId: String = "quick_v1",
    auditAssessment: StrategyProbeAuditAssessment? = null,
    completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
    tcpSucceededTargets: Int = 1,
    quicSucceededTargets: Int = 1,
): ScanReport =
    ScanReport(
        sessionId = "session-1",
        profileId = "automatic-probing",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "strategy probe",
        strategyProbeReport =
            StrategyProbeReport(
                suiteId = suiteId,
                tcpCandidates =
                    listOf(
                        strategyCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = tcpFamily,
                            succeededTargets = tcpSucceededTargets,
                        ),
                    ),
                quicCandidates =
                    listOf(
                        strategyCandidateSummary(
                            id = "quic-1",
                            label = "QUIC candidate",
                            family = quicFamily,
                            succeededTargets = quicSucceededTargets,
                        ),
                    ),
                recommendation =
                    StrategyProbeRecommendation(
                        tcpCandidateId = "tcp-1",
                        tcpCandidateLabel = "TCP candidate",
                        quicCandidateId = "quic-1",
                        quicCandidateLabel = "QUIC candidate",
                        rationale = "best path",
                        recommendedProxyConfigJson = proxyConfigJson,
                    ),
                completionKind = completionKind,
                auditAssessment = auditAssessment,
            ),
    )

private fun strategyCandidateSummary(
    id: String,
    label: String,
    family: String,
    succeededTargets: Int = 1,
): StrategyProbeCandidateSummary =
    StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        outcome = "success",
        rationale = "best",
        succeededTargets = succeededTargets,
        totalTargets = 1,
        weightedSuccessScore = 10,
        totalWeight = 10,
        qualityScore = 10,
    )

private fun validRecommendedProxyConfigJson(): String =
    RipDpiProxyUIPreferences(
        protocols = RipDpiProtocolConfig(desyncUdp = true),
        chains =
            RipDpiChainConfig(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.HostFake,
                            marker = "midhost+1",
                        ),
                    ),
                udpSteps = listOf(UdpChainStepModel(count = 4)),
            ),
        quic = RipDpiQuicConfig(fakeProfile = "realistic_initial"),
    ).toNativeConfigJson()

private fun validSeqovlRecommendedProxyConfigJson(): String =
    RipDpiProxyUIPreferences(
        protocols = RipDpiProtocolConfig(desyncUdp = true),
        chains =
            RipDpiChainConfig(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRec,
                            marker = "extlen",
                        ),
                        TcpChainStepModel(
                            kind = TcpChainStepKind.SeqOverlap,
                            marker = "midsld",
                            overlapSize = 12,
                            fakeMode = "profile",
                        ),
                    ),
                udpSteps = listOf(UdpChainStepModel(count = 4)),
            ),
        quic = RipDpiQuicConfig(fakeProfile = "realistic_initial"),
    ).toNativeConfigJson()

private fun auditAssessment(): StrategyProbeAuditAssessment =
    StrategyProbeAuditAssessment(
        dnsShortCircuited = false,
        coverage =
            StrategyProbeAuditCoverage(
                tcpCandidatesPlanned = 2,
                tcpCandidatesExecuted = 2,
                tcpCandidatesSkipped = 0,
                tcpCandidatesNotApplicable = 0,
                quicCandidatesPlanned = 2,
                quicCandidatesExecuted = 2,
                quicCandidatesSkipped = 0,
                quicCandidatesNotApplicable = 0,
                tcpWinnerSucceededTargets = 1,
                tcpWinnerTotalTargets = 1,
                quicWinnerSucceededTargets = 1,
                quicWinnerTotalTargets = 1,
                matrixCoveragePercent = 100,
                winnerCoveragePercent = 100,
            ),
        confidence =
            StrategyProbeAuditConfidence(
                level = StrategyProbeAuditConfidenceLevel.HIGH,
                score = 100,
                rationale = "Matrix coverage and winner strength are consistent",
            ),
    )

private fun networkFingerprint(): NetworkFingerprint =
    NetworkFingerprint(
        transport = "wifi",
        networkValidated = true,
        captivePortalDetected = false,
        privateDnsMode = "system",
        dnsServers = listOf("1.1.1.1"),
        wifi =
            WifiNetworkIdentityTuple(
                ssid = "ripdpi-lab",
                bssid = "aa:bb:cc:dd:ee:ff",
                gateway = "192.0.2.1",
            ),
    )
