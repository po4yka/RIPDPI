package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSummaryProjectorTest {
    @Test
    @Suppress("detekt.LongMethod")
    fun `strategy probe audit assessment is included in report metadata`() {
        val document =
            DiagnosticsSummaryProjector().project(
                session = null,
                report =
                    DiagnosticsSessionProjection(
                        strategyProbeReport =
                            StrategyProbeReport(
                                suiteId = "full_matrix_v1",
                                tcpCandidates =
                                    listOf(
                                        StrategyProbeCandidateSummary(
                                            id = "tcp-1",
                                            label = "TCP candidate",
                                            family = "tlsrec_seqovl",
                                            emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                                            exactEmitterRequiresRoot = true,
                                            emitterDowngraded = true,
                                            outcome = "success",
                                            rationale = "winner",
                                            succeededTargets = 3,
                                            totalTargets = 3,
                                            weightedSuccessScore = 9,
                                            totalWeight = 9,
                                            qualityScore = 9,
                                        ),
                                    ),
                                quicCandidates =
                                    listOf(
                                        StrategyProbeCandidateSummary(
                                            id = "quic-1",
                                            label = "QUIC candidate",
                                            family = "quic_multi_initial_realistic",
                                            emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
                                            outcome = "success",
                                            rationale = "winner",
                                            succeededTargets = 1,
                                            totalTargets = 1,
                                            weightedSuccessScore = 2,
                                            totalWeight = 2,
                                            qualityScore = 2,
                                        ),
                                    ),
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
                                pilotBucketLabels = listOf("foreign:cloudflare:ech=yes", "domestic:domesticcdn:ech=no"),
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
        assertTrue(
            document.reportMetadata.lines.contains(
                "strategyPilotBuckets=foreign:cloudflare:ech=yes|domestic:domesticcdn:ech=no",
            ),
        )
        assertTrue(document.reportMetadata.lines.contains("strategyConfidence=MEDIUM"))
        assertTrue(document.reportMetadata.lines.contains("strategyConfidenceScore=75"))
        assertTrue(document.reportMetadata.lines.contains("strategyMatrixCoverage=77"))
        assertTrue(document.reportMetadata.lines.contains("strategyWinnerCoverage=100"))
        assertTrue(document.reportMetadata.lines.contains("strategyRecommendedTcpEmitterTier=ROOTED_PRODUCTION"))
        assertTrue(document.reportMetadata.lines.contains("strategyRecommendedTcpRequiresRoot=true"))
        assertTrue(document.reportMetadata.lines.contains("strategyRecommendedTcpDowngraded=true"))
        assertTrue(document.reportMetadata.lines.contains("strategyRecommendedQuicEmitterTier=NON_ROOT_PRODUCTION"))
    }
}
