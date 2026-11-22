package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsFieldUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsHttp3TransferPresentationTest {
    private val stalledBody =
        DiagnosticsProbeResultUiModel(
            id = "comparison",
            probeType = "throughput_window",
            target = "HTTPS/TCP and HTTP/3 comparison",
            outcome = "http3_transfer_comparison_recorded",
            tone = DiagnosticsTone.Warning,
            details =
                persistentListOf(
                    DiagnosticsFieldUiModel("tcpOutcome", "verified"),
                    DiagnosticsFieldUiModel("tcpStage", "complete"),
                    DiagnosticsFieldUiModel("tcpBytes", "128"),
                    DiagnosticsFieldUiModel("http3Outcome", "body_timeout"),
                    DiagnosticsFieldUiModel("http3Stage", "body"),
                    DiagnosticsFieldUiModel("http3Bytes", "41"),
                    DiagnosticsFieldUiModel("differenceStage", "body"),
                ),
        )

    @Test
    fun showsBothStageAndByteCountAtBodyDivergence() {
        val comparison = requireNotNull(stalledBody.http3TransferComparison())
        assertEquals(
            listOf(
                Http3TransferLegUiModel(
                    R.string.diagnostics_http3_transfer_tcp,
                    R.string.diagnostics_transfer_complete,
                    R.string.diagnostics_verified_download_complete,
                    128,
                ),
                Http3TransferLegUiModel(
                    R.string.diagnostics_http3_title,
                    R.string.diagnostics_stages_body,
                    R.string.diagnostics_ech_transfer_body_timeout,
                    41,
                ),
            ),
            comparison.legs,
        )
        assertEquals(R.string.diagnostics_stages_body, comparison.difference)
        assertEquals(R.string.diagnostics_probe_result_recorded, diagnosticProbeOutcomeResource(stalledBody))
    }

    @Test
    fun inconclusiveScopeKeepsTheLegsAndWithdrawsTheDivergence() {
        val stale = stalledBody.copy(outcome = "http3_transfer_comparison_inconclusive")
        assertEquals(R.string.diagnostics_matrix_inconclusive, diagnosticProbeOutcomeResource(stale))
        assertEquals(
            stalledBody.http3TransferComparison()?.copy(difference = R.string.diagnostics_matrix_inconclusive),
            stale.http3TransferComparison(),
        )
    }

    @Test
    fun equivalentResultHasNoDifferenceAndOrdinaryThroughputHasNoComparisonRows() {
        val equivalent =
            stalledBody.copy(
                details =
                    persistentListOf(
                        DiagnosticsFieldUiModel("tcpOutcome", "verified"),
                        DiagnosticsFieldUiModel("tcpBytes", "128"),
                        DiagnosticsFieldUiModel("http3Outcome", "verified"),
                        DiagnosticsFieldUiModel("http3Bytes", "128"),
                        DiagnosticsFieldUiModel("differenceStage", "none"),
                    ),
            )
        assertEquals(
            R.string.diagnostics_http3_transfer_no_difference,
            equivalent.http3TransferComparison()?.difference,
        )
        assertNull(stalledBody.copy(outcome = "normal").http3TransferComparison())
    }
}
