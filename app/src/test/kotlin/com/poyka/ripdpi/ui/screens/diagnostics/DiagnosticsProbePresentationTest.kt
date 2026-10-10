package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsConnectionLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsConnectionMeasurementUiModel
import com.poyka.ripdpi.activities.DiagnosticsConnectionStageUiModel
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsTransferRunUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferSampleUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferUiModel
import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsProbePresentationTest {
    @Test
    fun copyKeepsPartialTransferAndUnverifiedStageEvidenceWithoutRawJson() {
        val run =
            DiagnosticsTransferRunUiModel(
                runIndex = 1,
                runCount = 2,
                receivedBodyByteCount = 128,
                expectedBodyByteCount = null,
                elapsedMs = 30,
                firstBodyByteMs = 10,
                lastBodyProgressMs = 20,
                terminationReason = "idle_timeout",
                responseComplete = false,
                windowComplete = false,
                samples = persistentListOf(DiagnosticsTransferSampleUiModel(20, 128)),
            )
        val stages =
            DiagnosticsConnectionStageUiModel(
                persistentListOf(
                    DiagnosticsConnectionLaneUiModel(
                        kind = ConnectionLaneKind.TRANSFER,
                        attempt = 1,
                        networkScopeUnverified = true,
                        stages =
                            persistentListOf(
                                DiagnosticsConnectionMeasurementUiModel(
                                    stage = ConnectionStage.BODY,
                                    state = ConnectionStageState.PARTIAL,
                                    durationMs = null,
                                    elapsedMs = 20,
                                    byteCount = 128,
                                    httpStatusCode = null,
                                ),
                            ),
                    ),
                ),
            )
        val probe =
            probe("throughput", "partial", DiagnosticsTone.Warning).copy(
                connectionStages = stages,
                transferEvidence = DiagnosticsTransferUiModel("example.org", persistentListOf(run), true),
                details = persistentListOf(DiagnosticsFieldUiModel("scope", "requested_path")),
            )
        val copy = formatDiagnosticsProbeEvidence(probe)
        listOf(
            "BODY: PARTIAL",
            "networkScopeUnverified=true",
            "receivedBytes=128",
            "expectedBytes=null",
            "firstBodyByteMs=10",
            "lastBodyProgressMs=20",
            "terminationReason=idle_timeout",
            "responseComplete=false",
            "windowComplete=false",
            "Sample: elapsedMs=20 bodyBytes=128",
            "scope: requested_path",
        ).forEach { assertTrue("Missing evidence: $it", copy.contains(it)) }
    }

    @Test
    fun unknownPositiveObservationDoesNotClaimSuccess() {
        assertEquals(
            R.string.diagnostics_probe_result_recorded,
            diagnosticProbeOutcomeResource(probe("quic", "quic_response", DiagnosticsTone.Positive)),
        )
        assertEquals(
            R.string.diagnostics_matrix_inconclusive,
            diagnosticProbeOutcomeResource(
                probe("selective_availability_summary", "matrix_inconclusive", DiagnosticsTone.Warning),
            ),
        )
        assertEquals(
            R.string.diagnostics_stages_cancelled,
            diagnosticProbeOutcomeResource(
                probe("selective_availability", "matrix_target_cancelled", DiagnosticsTone.Neutral),
            ),
        )
    }

    @Test
    fun unknownProbeTypeHasNeutralLocalizedTitle() {
        assertEquals(R.string.diagnostics_probe_check_title, diagnosticProbeTitleResource("future_probe"))
        assertEquals(R.string.diagnostics_matrix_title, diagnosticProbeTitleResource("selective_availability"))
        assertEquals(R.string.diagnostics_http3_title, diagnosticProbeTitleResource("http3"))
    }

    @Test
    fun copyRetainsHttp3ProtocolEvidence() {
        val http3 =
            DiagnosticsContextGroupUiModel(
                title = "HTTP/3",
                fields = persistentListOf(DiagnosticsFieldUiModel("Protocol", "h3")),
            )
        val copy =
            formatDiagnosticsProbeEvidence(probe("http3", "http3_ok", DiagnosticsTone.Positive).copy(http3 = http3))
        assertTrue(copy.contains("HTTP/3"))
        assertTrue(copy.contains("Protocol: h3"))
    }

    @Test
    fun copyRetainsPmtuEvidenceAfterPresentationRebase() {
        val pmtu =
            DiagnosticsContextGroupUiModel(
                title = "Path MTU",
                fields = persistentListOf(DiagnosticsFieldUiModel("Validated payload", "1232 bytes")),
            )
        val copy =
            formatDiagnosticsProbeEvidence(probe("pmtu", "pmtu_validated", DiagnosticsTone.Positive).copy(pmtu = pmtu))
        assertTrue(copy.contains("Path MTU"))
        assertTrue(copy.contains("Validated payload: 1232 bytes"))
    }

    private fun probe(
        type: String,
        outcome: String,
        tone: DiagnosticsTone,
    ) = DiagnosticsProbeResultUiModel(
        id = "probe",
        probeType = type,
        target = "example.org",
        outcome = outcome,
        tone = tone,
        details = persistentListOf(),
    )
}
