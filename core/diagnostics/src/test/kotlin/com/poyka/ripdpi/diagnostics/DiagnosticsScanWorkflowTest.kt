package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
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
    }
}
