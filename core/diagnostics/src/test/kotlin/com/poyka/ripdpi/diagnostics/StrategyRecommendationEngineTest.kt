package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyRecommendationEngineTest {
    @Test
    fun `raw path tcp only failures do not recommend strategy changes`() {
        val report =
            strategyReport(
                listOf(
                    ProbeResult("tcp_fat_header", "203.0.113.10:443", "tcp_reset"),
                    ProbeResult("tcp_fat_header", "203.0.113.11:443", "tcp_reset"),
                    ProbeResult("tcp_fat_header", "203.0.113.12:443", "tcp_16kb_blocked"),
                ),
            )

        val recommendation = StrategyRecommendationEngine.compute(report, currentTcpFamily = "split")

        assertNull(recommendation)
    }

    @Test
    fun `raw path real reachability failure can recommend strategy changes`() {
        val report =
            strategyReport(
                listOf(
                    ProbeResult("tcp_fat_header", "203.0.113.10:443", "tcp_reset"),
                    ProbeResult("tcp_fat_header", "203.0.113.11:443", "tcp_reset"),
                    ProbeResult("domain_reachability", "blocked.example", "unreachable"),
                ),
            )

        val recommendation = StrategyRecommendationEngine.compute(report, currentTcpFamily = "split")

        assertNotNull(recommendation)
        assertEquals("fake", recommendation!!.recommendedFamily)
        assertEquals(StrategyRecommendationConfidence.MEDIUM, recommendation.confidence)
        assertEquals(4, recommendation.evidenceScore)
    }

    @Test
    fun `confirm good verdict recommends transport pivot instead of fingerprint tuning`() {
        val report =
            strategyReport(emptyList()).copy(
                confirmGoodDpiVerdict =
                    ConfirmGoodDpiVerdict(
                        status = ConfirmGoodDpiVerdictStatus.SUSPECTED,
                        evidence =
                            ConfirmGoodDpiEvidence(
                                source = ConfirmGoodDpiEvidenceSource.MIXED,
                                stalledFlowCount = 2,
                                distinctTargetCount = 2,
                                catalogProfileValidated = true,
                                realityHandshakeConfirmed = true,
                                applicationResponseBytes = 0,
                                quicControlSucceeded = true,
                            ),
                    ),
            )

        val recommendation = StrategyRecommendationEngine.compute(report, currentTcpFamily = "split")

        assertNotNull(recommendation)
        assertEquals("udp_quic", recommendation?.recommendedFamily)
        assertEquals(StrategyRecommendationConfidence.HIGH, recommendation?.confidence)
    }

    private fun strategyReport(results: List<ProbeResult>): ScanReport =
        ScanReport(
            sessionId = "strategy-slice",
            profileId = "automatic-probing",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "strategy slice",
            results = results,
        )
}
