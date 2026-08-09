package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsHomeCompositeHelpersTest {
    @Test
    fun `composite outcome preserves specific actionable audit headline`() {
        val runId = "run-1"
        val auditHeadline = "DNS settings applied, but all bypass strategies failed"
        val auditOutcome =
            DiagnosticsHomeAuditOutcome(
                sessionId = "audit-session",
                actionable = true,
                headline = auditHeadline,
                summary = "DNS settings were applied; no bypass strategy succeeded.",
                appliedSettings = listOf(DiagnosticsAppliedSetting("DNS resolver", "Encrypted DNS")),
                strategyAdequacy = StrategyAdequacy.ALL_CANDIDATES_FAILED,
            )
        val progressState =
            MutableStateFlow(
                mapOf(
                    runId to
                        DiagnosticsHomeCompositeProgress(
                            runId = runId,
                            stages = emptyList(),
                        ),
                ),
            )

        val outcome =
            buildHomeCompositeOutcome(
                runId = runId,
                auditOutcome = auditOutcome,
                coverageNote = null,
                dnsIssuesDetected = true,
                networkChanged = false,
                progressState = progressState,
            )

        assertEquals(auditHeadline, outcome.headline)
    }
}
