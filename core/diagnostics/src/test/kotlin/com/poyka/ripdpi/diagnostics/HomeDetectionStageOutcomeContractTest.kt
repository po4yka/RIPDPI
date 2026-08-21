package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDetectionStageOutcomeContractTest {
    @Test
    fun `explicit network scope from WebRTC TLS and timing checkers is accepted`() {
        val checkerSignals =
            listOf("webrtc", "tls", "timing").map {
                HomeDetectionDecisionSignal(
                    category = HomeDetectionSignalCategory.NETWORK_OBSERVATION,
                    semantics = HomeDetectionSignalSemantics.NETWORK_OBSERVATION_PRESENT,
                    source = "NETWORK_CAPABILITIES",
                    confidence = "MEDIUM",
                    scope = "NETWORK_OBSERVATION",
                )
            }

        val outcome =
            HomeDetectionStageOutcome(
                verdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
                findings = emptyList(),
                ruleApplied = "R6",
                detectedSignalCount = checkerSignals.size,
                evidenceScopes = listOf(DetectionScope.NETWORK_OBSERVATION),
                decisionSignals = checkerSignals,
            )

        assertEquals(3, outcome.detectedSignalCount)
        assertEquals(listOf(DetectionScope.NETWORK_OBSERVATION), outcome.evidenceScopes)
    }
}
