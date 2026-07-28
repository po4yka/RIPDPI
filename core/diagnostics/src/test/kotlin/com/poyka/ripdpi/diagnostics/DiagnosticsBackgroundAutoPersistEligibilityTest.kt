package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsBackgroundAutoPersistEligibilityTest {
    @Test
    fun `high confidence strong coverage is eligible`() {
        assertEquals(DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible, eligibility())
    }

    @Test
    fun `not applicable candidates do not reduce measured coverage`() {
        val assessment =
            scanWorkflowAuditAssessment().copy(
                coverage =
                    scanWorkflowAuditAssessment().coverage.copy(
                        tcpCandidatesPlanned = 4,
                        tcpCandidatesExecuted = 2,
                        tcpCandidatesNotApplicable = 2,
                        quicCandidatesPlanned = 3,
                        quicCandidatesExecuted = 2,
                        quicCandidatesNotApplicable = 1,
                    ),
            )

        assertEquals(DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible, eligibility(assessment))
    }

    @Test
    fun `high confidence without control evidence is rejected`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                auditAssessment = scanWorkflowAuditAssessment(),
                pilotBucketLabels = emptyList(),
            )

        val eligibility =
            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
                requireNotNull(report.strategyProbeReport),
            )

        assertEquals(
            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(
                DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.MISSING_CONTROL_EVIDENCE,
            ),
            eligibility,
        )
    }

    @Test
    fun `missing audit assessment is rejected`() {
        assertRejected(
            reason = DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.MISSING_AUDIT_ASSESSMENT,
            actual = eligibility(assessment = null),
        )
    }

    @Test
    fun `medium confidence is rejected`() {
        val assessment = scanWorkflowAuditAssessment().withConfidence(StrategyProbeAuditConfidenceLevel.MEDIUM)
        assertRejected(
            DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.LOW_CONFIDENCE,
            eligibility(assessment),
        )
    }

    @Test
    fun `low confidence is rejected`() {
        val assessment = scanWorkflowAuditAssessment().withConfidence(StrategyProbeAuditConfidenceLevel.LOW)
        assertRejected(
            DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.LOW_CONFIDENCE,
            eligibility(assessment),
        )
    }

    @Test
    fun `insufficient matrix coverage is rejected`() {
        val assessment = scanWorkflowAuditAssessment().withCoverage(matrixCoveragePercent = 74)
        assertRejected(
            DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.INSUFFICIENT_MATRIX_COVERAGE,
            eligibility(assessment),
        )
    }

    @Test
    fun `insufficient winner coverage is rejected`() {
        val assessment = scanWorkflowAuditAssessment().withCoverage(winnerCoveragePercent = 49)
        assertRejected(
            DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.INSUFFICIENT_WINNER_COVERAGE,
            eligibility(assessment),
        )
    }

    @Test
    fun `missing winner target success is rejected`() {
        assertRejected(
            DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason.NO_WINNER_TARGET_SUCCESS,
            eligibility(tcpSucceededTargets = 0, quicSucceededTargets = 0),
        )
    }

    private fun eligibility(
        assessment: StrategyProbeAuditAssessment? = scanWorkflowAuditAssessment(),
        tcpSucceededTargets: Int = 1,
        quicSucceededTargets: Int = 1,
    ): DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                auditAssessment = assessment,
                tcpSucceededTargets = tcpSucceededTargets,
                quicSucceededTargets = quicSucceededTargets,
            )
        return DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(
            requireNotNull(report.strategyProbeReport),
        )
    }

    private fun assertRejected(
        reason: DiagnosticsScanWorkflow.BackgroundAutoPersistRejectionReason,
        actual: DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility,
    ) {
        assertEquals(DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected(reason), actual)
    }

    private fun StrategyProbeAuditAssessment.withConfidence(level: StrategyProbeAuditConfidenceLevel) =
        copy(confidence = confidence.copy(level = level))

    private fun StrategyProbeAuditAssessment.withCoverage(
        matrixCoveragePercent: Int = coverage.matrixCoveragePercent,
        winnerCoveragePercent: Int = coverage.winnerCoveragePercent,
    ) = copy(
        coverage =
            coverage.copy(
                matrixCoveragePercent = matrixCoveragePercent,
                winnerCoveragePercent = winnerCoveragePercent,
            ),
    )
}
