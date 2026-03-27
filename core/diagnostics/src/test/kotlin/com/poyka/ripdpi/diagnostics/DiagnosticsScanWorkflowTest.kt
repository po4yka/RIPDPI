package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsScanWorkflowTest {
    @Test
    fun `legacy recommended proxy config yields null derived strategy signature`() {
        val report =
            ScanReport(
                sessionId = "session-1",
                profileId = "automatic-probing",
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "strategy probe",
                strategyProbeReport =
                    StrategyProbeReport(
                        suiteId = "quick_v1",
                        tcpCandidates =
                            listOf(
                                StrategyProbeCandidateSummary(
                                    id = "tcp-1",
                                    label = "TCP candidate",
                                    family = "split",
                                    outcome = "success",
                                    rationale = "best",
                                    succeededTargets = 1,
                                    totalTargets = 1,
                                    weightedSuccessScore = 10,
                                    totalWeight = 10,
                                    qualityScore = 10,
                                ),
                            ),
                        quicCandidates =
                            listOf(
                                StrategyProbeCandidateSummary(
                                    id = "quic-1",
                                    label = "QUIC candidate",
                                    family = "quic_burst",
                                    outcome = "success",
                                    rationale = "best",
                                    succeededTargets = 1,
                                    totalTargets = 1,
                                    weightedSuccessScore = 10,
                                    totalWeight = 10,
                                    qualityScore = 10,
                                ),
                            ),
                        recommendation =
                            StrategyProbeRecommendation(
                                tcpCandidateId = "tcp-1",
                                tcpCandidateLabel = "TCP candidate",
                                quicCandidateId = "quic-1",
                                quicCandidateLabel = "QUIC candidate",
                                rationale = "best path",
                                recommendedProxyConfigJson =
                                    """
                                    {
                                      "kind":"ui",
                                      "ip":"127.0.0.1",
                                      "port":1080,
                                      "desyncMethod":"disorder"
                                    }
                                    """.trimIndent(),
                            ),
                        auditAssessment =
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
                            ),
                    ),
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report = report,
                settings = defaultDiagnosticsAppSettings(),
                preferredDnsPath = null,
            )

        val recommendation = requireNotNull(enriched.strategyProbeReport).recommendation
        assertNull(recommendation.strategySignature)
        assertEquals("split", recommendation.tcpCandidateFamily)
        assertEquals("quic_burst", recommendation.quicCandidateFamily)
        assertNotNull(enriched.strategyProbeReport?.auditAssessment)
    }
}
