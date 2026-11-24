package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.isQuicInitialProbeType
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDiagnosisUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@StringRes
internal fun diagnosticProbeTitleResource(type: String): Int =
    when {
        type.startsWith("selective_availability") -> R.string.diagnostics_matrix_title

        type == "http3" -> R.string.diagnostics_http3_title

        type.startsWith("dns") || type == "strategy_dns" -> R.string.diagnostics_stages_dns

        type == "tcp_fat_header" -> R.string.diagnostics_dpi_probe_kind_tcp16

        type.startsWith("tcp") || type == "strategy_tcp" -> R.string.diagnostics_stages_tcp

        type.startsWith(
            "tls",
        ) || type == "strategy_https" || type == "domain_reachability" -> R.string.diagnostics_stages_tls

        type.startsWith("http") || type == "strategy_http" -> R.string.diagnostics_stages_headers

        type.startsWith("quic") -> R.string.diagnostics_stages_quic_response

        type.contains("throughput") || type.contains("transfer") -> R.string.diagnostics_stages_lane_transfer

        type.startsWith("ip_family") || type.startsWith("nat64") -> R.string.diagnostics_ip_title

        else -> R.string.diagnostics_probe_check_title
    }

@StringRes
internal fun diagnosticProbeOutcomeResource(probe: DiagnosticsProbeResultUiModel): Int =
    fatHeaderOutcomeResource(probe) ?: verifiedTransferOutcomeResource(probe.outcome) ?: when (probe.outcome) {
        "matrix_available" -> {
            R.string.diagnostics_matrix_available
        }

        "matrix_selective" -> {
            R.string.diagnostics_matrix_selective
        }

        "matrix_unavailable" -> {
            R.string.diagnostics_matrix_unavailable
        }

        "matrix_mixed" -> {
            R.string.diagnostics_matrix_mixed
        }

        "matrix_inconclusive" -> {
            R.string.diagnostics_matrix_inconclusive
        }

        "tls_ok", "tcp_ok", "http_ok", "matrix_target_available" -> {
            R.string.diagnostics_stages_succeeded
        }

        "quic_initial_response",
        "quic_initial_ack",
        "quic_initial_other",
        "quic_initial_close",
        "quic_retry",
        "quic_version_negotiation",
        -> {
            R.string.diagnostics_stages_observed
        }

        "cancelled", "matrix_target_cancelled", "quic_cancelled" -> {
            R.string.diagnostics_stages_cancelled
        }

        "timeout", "tcp_timeout", "quic_timeout" -> {
            R.string.diagnostics_stages_timed_out
        }

        "quic_setup_error" -> {
            R.string.diagnostics_stages_not_reached
        }

        "quic_deadline_exceeded" -> {
            R.string.diagnostics_scan_termination_deadline_exceeded
        }

        else -> {
            when (probe.tone) {
                DiagnosticsTone.Negative -> R.string.diagnostics_stages_failed
                DiagnosticsTone.Warning -> R.string.diagnostics_probe_result_review
                else -> R.string.diagnostics_probe_result_recorded
            }
        }
    }

@StringRes
private fun fatHeaderOutcomeResource(probe: DiagnosticsProbeResultUiModel): Int? {
    if (probe.fatHeaderEvidence.isEmpty()) return null
    return when (probe.outcome) {
        "tcp_fat_header_ok", "fat_ok", "whitelist_sni_ok" -> R.string.diagnostics_stages_succeeded
        "inconclusive", "tcp_connect_failed" -> R.string.diagnostics_matrix_inconclusive
        else -> null
    }
}

@StringRes
private fun verifiedTransferOutcomeResource(outcome: String): Int? =
    when (outcome) {
        "verified_download_complete" -> {
            R.string.diagnostics_verified_download_complete
        }

        "verified_download_timeout" -> {
            R.string.diagnostics_verified_download_timeout
        }

        "verified_download_early_eof" -> {
            R.string.diagnostics_verified_download_early_eof
        }

        "verified_upload_complete" -> {
            R.string.diagnostics_verified_upload_complete
        }

        "verified_upload_timeout" -> {
            R.string.diagnostics_verified_upload_timeout
        }

        "verified_upload_early_eof" -> {
            R.string.diagnostics_verified_upload_early_eof
        }

        "verified_download_hash_mismatch", "verified_upload_hash_mismatch" -> {
            R.string.diagnostics_verified_download_hash_mismatch
        }

        "verified_download_size_mismatch", "verified_upload_size_mismatch" -> {
            R.string.diagnostics_verified_download_size_mismatch
        }

        "verified_download_failed", "verified_upload_failed" -> {
            R.string.diagnostics_stages_failed
        }

        "verified_download_inconclusive",
        "verified_upload_inconclusive",
        "ech_transfer_comparison_inconclusive",
        "http3_transfer_comparison_inconclusive",
        -> {
            R.string.diagnostics_matrix_inconclusive
        }

        "ech_transfer_comparison_recorded", "http3_transfer_comparison_recorded" -> {
            R.string.diagnostics_probe_result_recorded
        }

        else -> {
            null
        }
    }

@Composable
internal fun diagnosticProbeTitle(
    probe: DiagnosticsProbeResultUiModel,
    expertMode: Boolean,
): String =
    probe.volumeTransferLegTitleResource()?.let { stringResource(it) }
        ?: diagnosticProbeTitleFallback(probe, expertMode)

@Composable
private fun diagnosticProbeTitleFallback(
    probe: DiagnosticsProbeResultUiModel,
    expertMode: Boolean,
): String =
    if (probe.pmtu != null) {
        probe.pmtu.title
    } else if (probe.http3 != null) {
        probe.http3.title
    } else if (probe.ipFamily != null) {
        stringResource(R.string.diagnostics_ip_title)
    } else if (probe.isEchTransferComparison) {
        stringResource(R.string.diagnostics_ech_transfer_title)
    } else if (probe.isHttp3TransferComparison) {
        stringResource(R.string.diagnostics_http3_transfer_title)
    } else if (probe.outcome.startsWith("verified_upload_")) {
        stringResource(R.string.diagnostics_verified_upload_title)
    } else if (probe.outcome.startsWith("verified_download_")) {
        stringResource(R.string.diagnostics_verified_download_title)
    } else if (expertMode && probe.fatHeaderEvidence.isEmpty()) {
        probe.probeType
    } else {
        stringResource(diagnosticProbeTitleResource(probe.probeType))
    }

@StringRes
internal fun DiagnosticsProbeResultUiModel.volumeTransferLegTitleResource(): Int? {
    if (probeType != "throughput_window" || !outcome.startsWith("verified_download_")) return null
    return when (details.firstOrNull { it.label == "transferVolumeLeg" }?.value) {
        "short" -> R.string.diagnostics_volume_transfer_short
        "long" -> R.string.diagnostics_volume_transfer_long
        else -> null
    }
}

@Composable
internal fun diagnosticProbeOutcome(
    probe: DiagnosticsProbeResultUiModel,
    expertMode: Boolean,
): String =
    (probe.pmtu ?: probe.http3 ?: probe.ipFamily)
        ?.fields
        ?.firstOrNull()
        ?.value
        ?: verifiedTransferOutcomeResource(probe.outcome)?.let { stringResource(it) }
        ?: if (expertMode && probe.fatHeaderEvidence.isEmpty()) {
            probe.outcome
        } else {
            stringResource(diagnosticProbeOutcomeResource(probe))
        }

internal fun formatDiagnosticsProbeEvidence(probe: DiagnosticsProbeResultUiModel): String =
    buildString {
        appendLine("${probe.probeType} -> ${probe.target}")
        appendLine("Outcome: ${probe.outcome}")
        probe.probeRetryCount?.let { appendLine("Retries: $it") }
        (probe.dnsResponses + listOfNotNull(probe.ipFamily, probe.http3, probe.pmtu)).forEach { group ->
            appendLine(group.title)
            group.fields.forEach { appendLine("${it.label}: ${it.value}") }
        }
        (probe.connectionStages ?: probe.transferEvidence?.connectionStages)?.lanes?.forEach { lane ->
            appendLine(
                "Path: ${lane.kind} attempt=${lane.attempt} networkScopeUnverified=${lane.networkScopeUnverified}",
            )
            lane.stages.forEach { stage ->
                appendLine(
                    "${stage.stage}: ${stage.state} durationMs=${stage.durationMs} " +
                        "elapsedMs=${stage.elapsedMs} bytes=${stage.byteCount} httpStatus=${stage.httpStatusCode}",
                )
            }
        }
        probe.transferEvidence?.let { transfer ->
            appendLine("Transfer: ${transfer.target} networkScopeUnverified=${transfer.networkScopeUnverified}")
            transfer.runs.forEach { run ->
                appendLine(
                    "Run ${run.runIndex}/${run.runCount}: receivedBytes=${run.receivedBodyByteCount} " +
                        "expectedBytes=${run.expectedBodyByteCount} elapsedMs=${run.elapsedMs}",
                )
                appendLine(
                    "firstBodyByteMs=${run.firstBodyByteMs} lastBodyProgressMs=${run.lastBodyProgressMs} " +
                        "terminationReason=${run.terminationReason}",
                )
                appendLine("responseComplete=${run.responseComplete} windowComplete=${run.windowComplete}")
                run.samples.forEach { appendLine("Sample: elapsedMs=${it.elapsedMs} bodyBytes=${it.bodyByteCount}") }
            }
        }
        probe.details.forEach { appendLine("${it.label}: ${it.value}") }
    }.trimEnd()

@Composable
internal fun DiagnosisMeasurementNotes(diagnosis: DiagnosticsDiagnosisUiModel) {
    MeasurementNote(diagnosis.controlStatus)
    diagnosis.measurementLimit?.let { MeasurementNote(it) }
}

@Composable
internal fun ProbeOutcome(
    probe: DiagnosticsProbeResultUiModel,
    expertMode: Boolean,
) {
    StatusIndicator(label = diagnosticProbeOutcome(probe, expertMode), tone = statusTone(probe.tone))
    QuicInitialMeasurementNote(probe.probeType)
}

@Composable
internal fun FatHeaderEvidenceRows(probe: DiagnosticsProbeResultUiModel) {
    probe.fatHeaderEvidence.forEach { MeasurementNote(it) }
}

@Composable
internal fun QuicInitialMeasurementNote(probeType: String) {
    if (isQuicInitialProbeType(probeType)) {
        MeasurementNote(stringResource(R.string.diagnostics_quic_initial_limit))
    }
}

@Composable
private fun MeasurementNote(text: String) {
    Text(
        text = text,
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}
