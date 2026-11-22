package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsArchivePathComparisonRedactionTest {
    private val json = diagnosticsTestJson()
    private val redactor = DiagnosticsArchiveRedactor(json)

    @Test
    fun `path comparison export keeps outcomes and counts and redacts target identities`() {
        val target = "private-recovery.example"
        val stage =
            ConnectivityComparedStage(
                target = target,
                stage = "http",
                control = false,
                raw = ConnectivityStageMeasurement(ConnectivityStageOutcome.FAILED, 2),
                inPath = ConnectivityStageMeasurement(ConnectivityStageOutcome.SUCCEEDED, 1),
            )
        val assessment =
            ConnectivityAssessment(
                assessmentCode = ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING,
                assessmentSummary = "Paired observations",
                confidence = "medium",
                pathComparison =
                    ConnectivityPathComparison(
                        completeRawReports = 2,
                        incompleteRawReports = 0,
                        activePathCoverage = ConnectivityActivePathCoverage.COMPLETE,
                        stages = listOf(stage),
                        unmeasuredTargets = listOf("missing-private.example"),
                        httpRecoveredTargets = listOf(target),
                    ),
            )

        val sanitized = requireNotNull(redactor.redact(assessment))
        val comparison = requireNotNull(sanitized.pathComparison)

        assertEquals(stage.copy(target = "redacted"), comparison.stages.single())
        assertEquals(1, comparison.httpRecoveredTargets.size)
        val encoded = json.encodeToString(sanitized)
        assertFalse(encoded.contains(target))
        assertFalse(encoded.contains("missing-private.example"))
    }
}
