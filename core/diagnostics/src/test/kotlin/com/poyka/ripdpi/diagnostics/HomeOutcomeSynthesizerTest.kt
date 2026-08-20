package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOutcomeSynthesizerTest {
    @Test
    fun `summary labels resolver only applications as dns settings`() {
        val outcome =
            DiagnosticsHomeCompositeOutcome(
                runId = "resolver-only-run",
                actionable = true,
                headline = "DNS settings applied",
                summary = "Resolver fallback applied",
                appliedSettings =
                    listOf(
                        DiagnosticsAppliedSetting(label = "Resolver", value = "adguard"),
                        DiagnosticsAppliedSetting(label = "Protocol", value = "DOH"),
                    ),
            )

        val (headline) = synthesizeActionableSummary(outcome)

        assertEquals("Applied 2 DNS settings.", headline)
    }

    @Test
    fun `summary keeps bypass wording for mixed resolver and strategy applications`() {
        val outcome =
            DiagnosticsHomeCompositeOutcome(
                runId = "mixed-run",
                actionable = true,
                headline = "Settings applied",
                summary = "Resolver fallback and strategy applied",
                appliedSettings =
                    listOf(
                        DiagnosticsAppliedSetting(label = "Resolver", value = "adguard"),
                        DiagnosticsAppliedSetting(label = "TCP/TLS lane", value = "split"),
                    ),
            )

        val (headline) = synthesizeActionableSummary(outcome)

        assertEquals("Applied 2 bypass settings.", headline)
    }
}
