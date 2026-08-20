package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsHomeAuditOutcomeBuilderTest {
    private val builder = DiagnosticsHomeAuditOutcomeBuilder()

    @Test
    fun `quic success prevents all-candidates-failed outcome when tcp fails`() {
        val outcome =
            buildOutcome(
                tcpCandidates = listOf(candidate(id = "tcp", succeededTargets = 0)),
                quicCandidates = listOf(candidate(id = "quic", succeededTargets = 1)),
            )

        assertNull(outcome.strategyAdequacy)
        assertEquals("Analysis complete, but no settings were applied", outcome.headline)
    }

    @Test
    fun `failed executed candidates across both lanes produce all-candidates-failed outcome`() {
        val outcome =
            buildOutcome(
                tcpCandidates = listOf(candidate(id = "tcp", succeededTargets = 0)),
                quicCandidates = listOf(candidate(id = "quic", succeededTargets = 0)),
            )

        assertEquals(StrategyAdequacy.ALL_CANDIDATES_FAILED, outcome.strategyAdequacy)
        assertEquals("All bypass strategies failed on this network", outcome.headline)
    }

    @Test
    fun `skipped candidates do not produce all-candidates-failed outcome`() {
        val outcome =
            buildOutcome(
                tcpCandidates = listOf(candidate(id = "tcp", succeededTargets = 0, skipped = true)),
                quicCandidates = listOf(candidate(id = "quic", succeededTargets = 0, skipped = true)),
            )

        assertNull(outcome.strategyAdequacy)
        assertFalse(outcome.actionable)
    }

    @Test
    fun `weak strategy evidence is distinguished from applied DNS settings`() {
        val resolverSetting = DiagnosticsAppliedSetting(label = "Resolver", value = "adguard")
        val outcome =
            buildOutcome(
                tcpCandidates = listOf(candidate(id = "tcp", succeededTargets = 1)),
                quicCandidates = listOf(candidate(id = "quic", succeededTargets = 1)),
                resolverApplied = listOf(resolverSetting),
                strategyRecommendation =
                    StrategyRecommendation(
                        triggerOutcomes = listOf("tls_blocked"),
                        recommendedFamily = "tlsrec_split",
                        blockingPattern = "sni_tls_suspect",
                        rationale = "Prefer TLS record split on this network",
                    ),
                auditAssessment = weakAuditAssessment(),
                completionKind = StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK,
            )

        assertTrue(outcome.actionable)
        assertEquals(StrategyAdequacy.DNS_ONLY_STRATEGY_UNVERIFIED, outcome.strategyAdequacy)
        assertEquals("DNS settings applied, but bypass strategy needs more evidence", outcome.headline)
        assertEquals(listOf(resolverSetting), outcome.appliedSettings)
    }

    private fun buildOutcome(
        tcpCandidates: List<StrategyProbeCandidateSummary>,
        quicCandidates: List<StrategyProbeCandidateSummary>,
        resolverApplied: List<DiagnosticsAppliedSetting> = emptyList(),
        strategyRecommendation: StrategyRecommendation? = null,
        auditAssessment: StrategyProbeAuditAssessment? = null,
        completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
    ): DiagnosticsHomeAuditOutcome {
        val report =
            ScanReport(
                sessionId = "session",
                profileId = "automatic-audit",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 1L,
                finishedAt = 2L,
                summary = "Audit complete",
            )
        return builder.build(
            sessionId = report.sessionId,
            fingerprintHash = null,
            session =
                diagnosticsSession(
                    id = report.sessionId,
                    profileId = report.profileId,
                    pathMode = report.pathMode.name,
                    summary = report.summary,
                ),
            report = report,
            strategyProbe =
                strategyReport(
                    tcpCandidates = tcpCandidates,
                    quicCandidates = quicCandidates,
                    auditAssessment = auditAssessment,
                    completionKind = completionKind,
                ),
            strategyApplied = null,
            strategyRecommendation = strategyRecommendation,
            resolverRecommendation = null,
            resolverApplied = resolverApplied,
            capabilityEvidence = emptyList(),
        )
    }

    private fun strategyReport(
        tcpCandidates: List<StrategyProbeCandidateSummary>,
        quicCandidates: List<StrategyProbeCandidateSummary>,
        auditAssessment: StrategyProbeAuditAssessment?,
        completionKind: StrategyProbeCompletionKind,
    ) = StrategyProbeReport(
        suiteId = "quick_v1",
        tcpCandidates = tcpCandidates,
        quicCandidates = quicCandidates,
        recommendation =
            StrategyProbeRecommendation(
                tcpCandidateId = "tcp",
                tcpCandidateLabel = "TCP",
                quicCandidateId = "quic",
                quicCandidateLabel = "QUIC",
                rationale = "Test recommendation",
                recommendedProxyConfigJson = "{}",
            ),
        completionKind = completionKind,
        auditAssessment = auditAssessment,
    )

    private fun weakAuditAssessment() =
        StrategyProbeAuditAssessment(
            coverage =
                StrategyProbeAuditCoverage(
                    tcpCandidatesPlanned = 1,
                    tcpCandidatesExecuted = 1,
                    tcpCandidatesSkipped = 0,
                    tcpCandidatesNotApplicable = 0,
                    quicCandidatesPlanned = 1,
                    quicCandidatesExecuted = 1,
                    quicCandidatesSkipped = 0,
                    quicCandidatesNotApplicable = 0,
                    tcpWinnerSucceededTargets = 1,
                    tcpWinnerTotalTargets = 2,
                    quicWinnerSucceededTargets = 1,
                    quicWinnerTotalTargets = 2,
                    matrixCoveragePercent = 90,
                    winnerCoveragePercent = 37,
                    tcpWinnerCoveragePercent = 40,
                    quicWinnerCoveragePercent = 33,
                ),
            confidence =
                StrategyProbeAuditConfidence(
                    level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                    score = 75,
                    rationale = "Partial winner evidence",
                ),
        )

    private fun candidate(
        id: String,
        succeededTargets: Int,
        skipped: Boolean = false,
    ) = StrategyProbeCandidateSummary(
        id = id,
        label = id,
        family = id,
        outcome = if (succeededTargets > 0) "ok" else "failed",
        rationale = "test",
        succeededTargets = succeededTargets,
        totalTargets = 1,
        weightedSuccessScore = succeededTargets * 100,
        totalWeight = 100,
        qualityScore = succeededTargets * 100,
        skipped = skipped,
    )
}
