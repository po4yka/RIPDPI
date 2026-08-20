package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsStrategyRecommendationWorkflowInvariantTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val json = diagnosticsTestJson()

    @Test
    fun `valid recommendation config yields derived families and strategy signature`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
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
    fun `legacy recommended proxy config leaves derived metadata null`() {
        val report =
            invariantScanReportWithStrategyProbe(
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
                auditAssessment = invariantAuditAssessment(),
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
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
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "split",
                quicFamily = "quic_burst",
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
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
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `full matrix recommendation remains non persisted when valid`() {
        val report =
            invariantScanReportWithStrategyProbe(
                suiteId = "full_matrix_v1",
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
            )

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `skipped selected candidate is not treated as a valid safe recommendation`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                tcpOutcome = "not_applicable",
                tcpSkipped = true,
                tcpSucceededTargets = 0,
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
        assertNull(recommendation.tcpCandidateFamily)
        assertNull(recommendation.quicCandidateFamily)
        assertNull(recommendation.strategySignature)

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `failed selected candidate is not treated as a valid safe recommendation`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                tcpOutcome = "failed",
                tcpSucceededTargets = 0,
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
        assertNull(recommendation.tcpCandidateFamily)
        assertNull(recommendation.quicCandidateFamily)
        assertNull(recommendation.strategySignature)

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `eliminated selected candidate is not treated as a valid safe recommendation`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                tcpOutcome = "eliminated",
                tcpSucceededTargets = 1,
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
        assertNull(recommendation.tcpCandidateFamily)
        assertNull(recommendation.quicCandidateFamily)
        assertNull(recommendation.strategySignature)

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `rooted selected candidate is not safe for non-root settings`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                tcpEmitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                tcpExactEmitterRequiresRoot = true,
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = settings,
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation)
        assertNull(recommendation.tcpCandidateFamily)
        assertNull(recommendation.quicCandidateFamily)
        assertNull(recommendation.strategySignature)
    }

    @Test
    fun `low confidence selected candidate is not remembered as safe path`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                auditAssessment =
                    invariantAuditAssessment().copy(
                        confidence =
                            invariantAuditAssessment().confidence.copy(
                                level = StrategyProbeAuditConfidenceLevel.LOW,
                                score = 30,
                                rationale = "partial and ambiguous",
                            ),
                    ),
            )

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = settings,
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `downgraded exact-root selected candidate is not remembered even with matching config`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                tcpEmitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                tcpExactEmitterRequiresRoot = true,
                tcpEmitterDowngraded = true,
                auditAssessment = invariantAuditAssessment(),
            )

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = rootEnabledSettings(),
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNull(rememberedPolicy)
    }

    @Test
    fun `root enabled settings can remember exact-root candidate only when audit is strong`() {
        val report =
            invariantScanReportWithStrategyProbe(
                proxyConfigJson = invariantValidRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                tcpEmitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                tcpExactEmitterRequiresRoot = true,
                auditAssessment = invariantAuditAssessment(),
            )

        val rememberedPolicy =
            DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = requireNotNull(report.strategyProbeReport),
                settings = rootEnabledSettings(),
                fingerprint = invariantNetworkFingerprint(),
                hostAutolearnStorePath = null,
                json = json,
            )

        assertNotNull(rememberedPolicy)
    }
}

private fun invariantScanReportWithStrategyProbe(
    proxyConfigJson: String,
    tcpFamily: String,
    quicFamily: String,
    suiteId: String = "quick_v1",
    auditAssessment: StrategyProbeAuditAssessment? = null,
    tcpOutcome: String = "success",
    tcpSkipped: Boolean = false,
    tcpSucceededTargets: Int = 1,
    tcpEmitterTier: StrategyEmitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
    tcpExactEmitterRequiresRoot: Boolean = false,
    tcpEmitterDowngraded: Boolean = false,
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
                        invariantStrategyCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = tcpFamily,
                            outcome = tcpOutcome,
                            skipped = tcpSkipped,
                            succeededTargets = tcpSucceededTargets,
                            emitterTier = tcpEmitterTier,
                            exactEmitterRequiresRoot = tcpExactEmitterRequiresRoot,
                            emitterDowngraded = tcpEmitterDowngraded,
                        ),
                    ),
                quicCandidates =
                    listOf(
                        invariantStrategyCandidateSummary(
                            id = "quic-1",
                            label = "QUIC candidate",
                            family = quicFamily,
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
                auditAssessment = auditAssessment,
                pilotBucketLabels = listOf("control:neutral:success"),
            ),
    )

private fun invariantStrategyCandidateSummary(
    id: String,
    label: String,
    family: String,
    outcome: String = "success",
    skipped: Boolean = false,
    succeededTargets: Int = 1,
    emitterTier: StrategyEmitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
    exactEmitterRequiresRoot: Boolean = false,
    emitterDowngraded: Boolean = false,
): StrategyProbeCandidateSummary =
    StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = family,
        emitterTier = emitterTier,
        exactEmitterRequiresRoot = exactEmitterRequiresRoot,
        emitterDowngraded = emitterDowngraded,
        outcome = outcome,
        rationale = "best",
        succeededTargets = succeededTargets,
        totalTargets = 1,
        weightedSuccessScore = 10,
        totalWeight = 10,
        qualityScore = 10,
        skipped = skipped,
        domainOutcomes =
            listOf(
                StrategyProbeDomainOutcome(
                    domain = "control.example",
                    succeeded = true,
                    isControl = true,
                ),
            ),
    )

private fun invariantValidRecommendedProxyConfigJson(): String =
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

private fun invariantAuditAssessment(): StrategyProbeAuditAssessment =
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

private fun invariantNetworkFingerprint(): NetworkFingerprint =
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

private fun rootEnabledSettings(): AppSettings =
    defaultDiagnosticsAppSettings()
        .toBuilder()
        .setRootModeEnabled(true)
        .build()
