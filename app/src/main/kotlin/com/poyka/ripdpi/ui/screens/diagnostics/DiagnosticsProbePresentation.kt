package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone

@StringRes
internal fun diagnosticProbeTitleResource(type: String): Int =
    when {
        type.startsWith("selective_availability") -> R.string.diagnostics_matrix_title

        type == "http3" -> R.string.diagnostics_http3_title

        type.startsWith("dns") || type == "strategy_dns" -> R.string.diagnostics_stages_dns

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
    when (probe.outcome) {
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

        "cancelled", "matrix_target_cancelled" -> {
            R.string.diagnostics_stages_cancelled
        }

        "timeout", "tcp_timeout" -> {
            R.string.diagnostics_stages_timed_out
        }

        else -> {
            when (probe.tone) {
                DiagnosticsTone.Negative -> R.string.diagnostics_stages_failed
                DiagnosticsTone.Warning -> R.string.diagnostics_probe_result_review
                else -> R.string.diagnostics_probe_result_recorded
            }
        }
    }

@Composable
internal fun diagnosticProbeTitle(
    probe: DiagnosticsProbeResultUiModel,
    expertMode: Boolean,
): String =
    if (probe.pmtu != null) {
        probe.pmtu.title
    } else if (probe.http3 != null) {
        probe.http3.title
    } else if (probe.ipFamily != null) {
        stringResource(R.string.diagnostics_ip_title)
    } else if (expertMode) {
        probe.probeType
    } else {
        stringResource(diagnosticProbeTitleResource(probe.probeType))
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
        ?: if (expertMode) probe.outcome else stringResource(diagnosticProbeOutcomeResource(probe))

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
