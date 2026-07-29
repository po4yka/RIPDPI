package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.ScanTerminationReason
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsUiShareBuildersTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `share header qualifies completed lifecycle with semantic partial reason`() {
        val report =
            DiagnosticsSessionProjection(
                completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                terminationReason = ScanTerminationReason.DEADLINE_EXCEEDED,
            )
        val session =
            historyScanSession(
                id = "scan-partial",
                status = "completed",
                summary = "Lifecycle completed",
                report = report,
            )

        val body =
            support
                .buildSharePreview(
                    latestSession = session,
                    latestSnapshot = null,
                    latestContext = null,
                    telemetry = null,
                    nativeEvents = emptyList(),
                    latestReport = report,
                ).body

        assertTrue(body.contains("Session scan-par · RAW_PATH · Partial results · Time limit reached"))
        assertFalse(body.contains("Session scan-par · RAW_PATH · completed"))
    }
}
