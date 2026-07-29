package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.ScanTerminationReason
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsSessionDetailUiFactoryTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
    private val factory = DiagnosticsSessionDetailUiFactory(support)

    @Test
    fun `session detail factory groups probes and preserves visibility flags`() {
        val detail =
            factory.toSessionDetailUiModel(
                detail =
                    historyDiagnosticsDetail("scan-1").copy(
                        results =
                            listOf(
                                historyProbeResult(),
                                historyProbeResult().copy(probeType = "http"),
                            ),
                    ),
                showSensitiveDetails = true,
            )

        assertEquals("scan-1", detail.session.id)
        assertEquals(2, detail.probeGroups.size)
        assertEquals(1, detail.snapshots.size)
        assertEquals(1, detail.events.size)
        assertTrue(detail.contextGroups.isNotEmpty())
        assertTrue(detail.hasSensitiveDetails)
        assertTrue(detail.sensitiveDetailsVisible)
    }

    @Test
    fun `confirm good diagnosis uses suspected behavioral wording and transport pivot action`() {
        val diagnosis =
            support.toDiagnosisUiModel(
                Diagnosis(
                    code = "confirm_good_dpi_suspected",
                    summary = "native fallback",
                    evidence = listOf("native evidence"),
                    recommendation = "native action",
                ),
            )

        assertEquals(support.context.getString(R.string.diagnostics_confirm_good_dpi_summary), diagnosis.summary)
        assertEquals(
            support.context.getString(R.string.diagnostics_confirm_good_dpi_recommendation),
            diagnosis.recommendation,
        )
        assertEquals(DiagnosticsTone.Warning, diagnosis.tone)
    }

    @Test
    fun `session detail surfaces top level completion and termination reason`() {
        val input = historyDiagnosticsDetail("scan-terminated")
        val detail =
            factory.toSessionDetailUiModel(
                detail =
                    input.copy(
                        session =
                            input.session.copy(
                                report =
                                    DiagnosticsSessionProjection(
                                        completionKind = ScanCompletionKind.TERMINATED,
                                        terminationReason = ScanTerminationReason.NETWORK_UNAVAILABLE,
                                    ),
                            ),
                    ),
                showSensitiveDetails = false,
            )

        assertTrue(
            detail.reportMetadata.contains(
                DiagnosticsFieldUiModel(
                    support.context.getString(R.string.diagnostics_scan_metadata_completion),
                    support.context.getString(R.string.diagnostics_scan_completion_terminated),
                ),
            ),
        )
        assertTrue(
            detail.reportMetadata.contains(
                DiagnosticsFieldUiModel(
                    support.context.getString(R.string.diagnostics_scan_metadata_termination_reason),
                    support.context.getString(R.string.diagnostics_scan_termination_network_unavailable),
                ),
            ),
        )
    }
}
