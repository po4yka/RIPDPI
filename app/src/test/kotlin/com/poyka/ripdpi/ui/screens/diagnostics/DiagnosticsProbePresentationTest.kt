package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsConnectionLaneUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsConnectionMeasurementUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsConnectionStageUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsFieldUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTransferRunUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTransferSampleUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTransferUiModel
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
    fun quicInitialProbeShowsObservationAndDistinguishesUnrunChecks() {
        val statuses =
            mapOf(
                "quic_initial_close" to R.string.diagnostics_stages_observed,
                "quic_retry" to R.string.diagnostics_stages_observed,
                "quic_version_negotiation" to R.string.diagnostics_stages_observed,
                "quic_timeout" to R.string.diagnostics_stages_timed_out,
                "quic_setup_error" to R.string.diagnostics_stages_not_reached,
                "quic_cancelled" to R.string.diagnostics_stages_cancelled,
                "quic_deadline_exceeded" to R.string.diagnostics_scan_termination_deadline_exceeded,
            )
        statuses.forEach { (outcome, label) ->
            assertEquals(
                outcome,
                label,
                diagnosticProbeOutcomeResource(probe("quic_reachability", outcome, DiagnosticsTone.Warning)),
            )
        }
    }

    @Test
    fun verifiedTransfersDistinguishCompletionAndFailureReasons() {
        val outcomes =
            mapOf(
                "verified_download_complete" to R.string.diagnostics_verified_download_complete,
                "verified_download_timeout" to R.string.diagnostics_verified_download_timeout,
                "verified_download_early_eof" to R.string.diagnostics_verified_download_early_eof,
                "verified_download_hash_mismatch" to R.string.diagnostics_verified_download_hash_mismatch,
                "verified_download_size_mismatch" to R.string.diagnostics_verified_download_size_mismatch,
                "verified_download_failed" to R.string.diagnostics_stages_failed,
                "verified_download_inconclusive" to R.string.diagnostics_matrix_inconclusive,
                "verified_upload_complete" to R.string.diagnostics_verified_upload_complete,
                "verified_upload_timeout" to R.string.diagnostics_verified_upload_timeout,
                "verified_upload_early_eof" to R.string.diagnostics_verified_upload_early_eof,
                "verified_upload_hash_mismatch" to R.string.diagnostics_verified_download_hash_mismatch,
                "verified_upload_size_mismatch" to R.string.diagnostics_verified_download_size_mismatch,
                "verified_upload_failed" to R.string.diagnostics_stages_failed,
                "verified_upload_inconclusive" to R.string.diagnostics_matrix_inconclusive,
            )
        outcomes.forEach { (outcome, label) ->
            assertEquals(
                outcome,
                label,
                diagnosticProbeOutcomeResource(probe("throughput_window", outcome, DiagnosticsTone.Warning)),
            )
        }
    }

    @Test
    fun echComparisonKeepsModeAndStatusSeparateAcrossScopeLoss() {
        val result =
            probe("throughput_window", "ech_transfer_comparison_recorded", DiagnosticsTone.Warning).copy(
                details =
                    persistentListOf(
                        DiagnosticsFieldUiModel("tls13Outcome", "verified"),
                        DiagnosticsFieldUiModel("tls13Bytes", "31"),
                        DiagnosticsFieldUiModel("echGreaseOutcome", "body_timeout"),
                        DiagnosticsFieldUiModel("echGreaseBytes", "7"),
                        DiagnosticsFieldUiModel("echOutcome", "ech_config_missing"),
                    ),
            )
        val stalled =
            EchTransferRow(
                R.string.diagnostics_ech_transfer_grease,
                R.string.diagnostics_ech_transfer_body_timeout,
                7L,
            )
        assertEquals(
            listOf(
                EchTransferRow(
                    R.string.diagnostics_stages_lane_tls13,
                    R.string.diagnostics_verified_download_complete,
                    31L,
                ),
                stalled,
                EchTransferRow(
                    R.string.diagnostics_stages_lane_ech,
                    R.string.diagnostics_ech_transfer_config_missing,
                    null,
                ),
            ),
            result.echTransferRows(),
        )
        val stale = result.copy(outcome = "ech_transfer_comparison_inconclusive")
        assertEquals(R.string.diagnostics_matrix_inconclusive, diagnosticProbeOutcomeResource(stale))
        assertEquals(result.echTransferRows(), stale.echTransferRows())
        assertTrue(result.copy(outcome = "verified_download_complete").echTransferRows().isEmpty())
    }

    @Test
    fun echComparisonDistinguishesHandshakeFailureFromHeaderTimeoutAndUnrunAttempt() {
        val result =
            probe("throughput_window", "ech_transfer_comparison_recorded", DiagnosticsTone.Warning).copy(
                details =
                    persistentListOf(
                        DiagnosticsFieldUiModel("tls13Outcome", "response_timeout"),
                        DiagnosticsFieldUiModel("echGreaseOutcome", "tls_failed"),
                        DiagnosticsFieldUiModel("echOutcome", "not_run"),
                    ),
            )
        assertEquals(
            listOf(
                R.string.diagnostics_ech_transfer_response_timeout,
                R.string.diagnostics_replay_error_tls_handshake_failed,
                R.string.diagnostics_stages_not_reached,
            ),
            result.echTransferRows().map(EchTransferRow::status),
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
