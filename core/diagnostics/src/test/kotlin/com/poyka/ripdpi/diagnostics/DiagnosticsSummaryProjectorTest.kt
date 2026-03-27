package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSummaryProjectorTest {
    @Test
    fun `strategy probe audit assessment is included in report metadata`() {
        val document =
            DiagnosticsSummaryProjector().project(
                session = null,
                report =
                    DiagnosticsSessionProjection(
                        strategyProbeReport =
                            StrategyProbeReport(
                                suiteId = "full_matrix_v1",
                                tcpCandidates = emptyList(),
                                quicCandidates = emptyList(),
                                recommendation =
                                    StrategyProbeRecommendation(
                                        tcpCandidateId = "tcp-1",
                                        tcpCandidateLabel = "TCP candidate",
                                        quicCandidateId = "quic-1",
                                        quicCandidateLabel = "QUIC candidate",
                                        rationale = "best path",
                                        recommendedProxyConfigJson = "{}",
                                    ),
                                auditAssessment =
                                    StrategyProbeAuditAssessment(
                                        dnsShortCircuited = false,
                                        coverage =
                                            StrategyProbeAuditCoverage(
                                                tcpCandidatesPlanned = 11,
                                                tcpCandidatesExecuted = 8,
                                                tcpCandidatesSkipped = 1,
                                                tcpCandidatesNotApplicable = 0,
                                                quicCandidatesPlanned = 2,
                                                quicCandidatesExecuted = 2,
                                                quicCandidatesSkipped = 0,
                                                quicCandidatesNotApplicable = 0,
                                                tcpWinnerSucceededTargets = 3,
                                                tcpWinnerTotalTargets = 3,
                                                quicWinnerSucceededTargets = 1,
                                                quicWinnerTotalTargets = 1,
                                                matrixCoveragePercent = 77,
                                                winnerCoveragePercent = 100,
                                            ),
                                        confidence =
                                            StrategyProbeAuditConfidence(
                                                level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                                score = 75,
                                                rationale = "Audit rationale",
                                            ),
                                    ),
                                targetSelection =
                                    StrategyProbeTargetSelection(
                                        cohortId = "media-messaging",
                                        cohortLabel = "Media and messaging",
                                        domainHosts = listOf("meduza.io", "telegram.org", "signal.org"),
                                        quicHosts = listOf("discord.com", "www.whatsapp.com"),
                                    ),
                            ),
                    ),
                latestSnapshotModel = null,
                latestContextModel = null,
                latestTelemetry = null,
                selectedResults = emptyList(),
                warnings = emptyList(),
            )

        assertTrue(document.reportMetadata.lines.contains("strategyTargetCohort=media-messaging"))
        assertTrue(document.reportMetadata.lines.contains("strategyTargetDomains=meduza.io|telegram.org|signal.org"))
        assertTrue(document.reportMetadata.lines.contains("strategyTargetQuicHosts=discord.com|www.whatsapp.com"))
        assertTrue(document.reportMetadata.lines.contains("strategyConfidence=MEDIUM"))
        assertTrue(document.reportMetadata.lines.contains("strategyConfidenceScore=75"))
        assertTrue(document.reportMetadata.lines.contains("strategyMatrixCoverage=77"))
        assertTrue(document.reportMetadata.lines.contains("strategyWinnerCoverage=100"))
    }
}
