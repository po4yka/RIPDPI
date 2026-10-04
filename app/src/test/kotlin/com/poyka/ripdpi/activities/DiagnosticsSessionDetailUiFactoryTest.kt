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
    fun `session paths localize display and scope while preserving machine contracts`() {
        listOf(
            "RAW_PATH" to R.string.diagnostics_scope_direct_label,
            "IN_PATH" to R.string.diagnostics_scope_active_vpn_label,
            "FUTURE_PATH" to R.string.diagnostics_field_unknown,
        ).forEach { (path, labelResource) ->
            val input = historyDiagnosticsDetail("scope-$path")
            val detail =
                factory.toSessionDetailUiModel(
                    detail = input.copy(session = input.session.copy(pathMode = path)),
                    showSensitiveDetails = false,
                )
            val label = support.context.getString(labelResource)
            assertEquals(path, detail.session.pathMode)
            assertTrue(detail.session.subtitle.startsWith(label))
            assertEquals(
                label,
                detail.session.metrics
                    .first {
                        it.label == support.context.getString(R.string.diagnostics_metric_path)
                    }.value,
            )
            assertTrue(
                detail.reportMetadata.contains(
                    DiagnosticsFieldUiModel(
                        support.context.getString(R.string.diagnostics_scope_title),
                        support.context.getString(
                            when (path) {
                                "RAW_PATH" -> R.string.diagnostics_scope_direct_result
                                "IN_PATH" -> R.string.diagnostics_scope_active_description
                                else -> R.string.diagnostics_field_unknown
                            },
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `historical proxy scan identifies proxy scope without asserting successful measurements`() {
        val input = historyDiagnosticsDetail("proxy-scope")
        val detail =
            factory.toSessionDetailUiModel(
                detail =
                    input.copy(
                        session = input.session.copy(pathMode = "IN_PATH", serviceMode = "Proxy", status = "failed"),
                        results = emptyList(),
                    ),
                showSensitiveDetails = false,
            )
        assertEquals("IN_PATH", detail.session.pathMode)
        assertTrue(
            detail.session.subtitle.startsWith(
                support.context.getString(R.string.diagnostics_scope_active_proxy_label),
            ),
        )
        assertTrue(
            detail.reportMetadata.contains(
                DiagnosticsFieldUiModel(
                    support.context.getString(R.string.diagnostics_scope_title),
                    support.context.getString(R.string.diagnostics_scope_active_description),
                ),
            ),
        )
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
