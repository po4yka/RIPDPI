package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionConcurrencyContractTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `assessment and observation round trip with schema defaults`() {
        val assessment =
            ConnectionConcurrencyAssessment(
                verdict = ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED,
                selectedProfileId = "firefox_stable",
                safeCap = 4,
                plannedCells = 36,
                cleanCells = 34,
                affectedTargets = 2,
                healthyCapsByProfile = mapOf("firefox_stable" to 4),
            )
        val report =
            StrategyProbeReport(
                suiteId = "quick_v1",
                recommendation =
                    StrategyProbeRecommendation(
                        tcpCandidateId = "baseline",
                        tcpCandidateLabel = "Baseline",
                        quicCandidateId = "quic_disabled",
                        quicCandidateLabel = "QUIC disabled",
                        rationale = "fixture",
                        recommendedProxyConfigJson = "{}",
                    ),
                connectionConcurrencyAssessment = assessment,
            )

        val decoded = json.decodeFromString<StrategyProbeReport>(json.encodeToString(report))

        assertEquals(
            ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED,
            decoded.connectionConcurrencyAssessment?.verdict,
        )
        assertEquals(4, decoded.connectionConcurrencyAssessment?.healthyCapsByProfile?.get("firefox_stable"))
    }

    @Test
    fun `older observations default the concurrency fact to absent`() {
        val decoded = json.decodeFromString<ObservationFact>("""{"kind":"DOMAIN","target":"example.test"}""")

        assertNull(decoded.connectionConcurrency)
    }
}
