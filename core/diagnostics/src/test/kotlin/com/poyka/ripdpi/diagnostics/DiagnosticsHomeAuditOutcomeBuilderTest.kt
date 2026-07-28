package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun buildOutcome(
        tcpCandidates: List<StrategyProbeCandidateSummary>,
        quicCandidates: List<StrategyProbeCandidateSummary>,
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
            strategyProbe = strategyReport(tcpCandidates, quicCandidates),
            strategyApplied = null,
            strategyRecommendation = null,
            resolverRecommendation = null,
            resolverApplied = emptyList(),
            capabilityEvidence = emptyList(),
        )
    }

    private fun strategyReport(
        tcpCandidates: List<StrategyProbeCandidateSummary>,
        quicCandidates: List<StrategyProbeCandidateSummary>,
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
