package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniFailure
import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniResult
import com.poyka.ripdpi.diagnostics.dpi.DcReachability
import com.poyka.ripdpi.diagnostics.dpi.ProbeStats
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.dpi.TelegramDcFailure
import com.poyka.ripdpi.diagnostics.dpi.TelegramProbeStatus
import com.poyka.ripdpi.diagnostics.dpi.TelegramTransferFailure
import com.poyka.ripdpi.diagnostics.dpi.TelegramTransferKind
import com.poyka.ripdpi.platform.StringResolver

internal fun diagnosticPeerLabel(
    host: String,
    port: Int,
): String = if (':' in host) "[$host]:$port" else "$host:$port"

internal fun Tcp16ProbeResult.toSuiteDetail(strings: StringResolver): DiagnosticsDpiSuiteProbeDetailUiModel =
    DiagnosticsDpiSuiteProbeDetailUiModel(
        label = diagnosticPeerLabel(ip, port),
        detail =
            buildList {
                add(strings.getString(verdict.labelRes()))
                httpStatus?.let { add(strings.getString(R.string.diagnostics_stages_http_status, it)) }
                if (completedPaddingBytes > 0) {
                    add(strings.getString(R.string.diagnostics_dpi_suite_completed_padding, completedPaddingBytes))
                }
                meanResponseMs?.let { add(strings.getString(R.string.diagnostics_transfer_ms, it)) }
            }.joinToString(" | "),
        tone = if (verdict == Tcp16Verdict.UNSUPPORTED) DiagnosticsTone.Neutral else DiagnosticsTone.Warning,
    )

@StringRes
private fun Tcp16Verdict.labelRes(): Int =
    when (this) {
        Tcp16Verdict.OK -> R.string.diagnostics_cidr_whitelist_status_ok
        Tcp16Verdict.UNSUPPORTED -> R.string.diagnostics_ip_unsupported
        Tcp16Verdict.INVALID_TARGET -> R.string.diagnostics_ip_invalid_config
        Tcp16Verdict.HTTP_REJECTED -> R.string.diagnostics_dpi_suite_http_rejected
        Tcp16Verdict.PADDED_TRAIN_INTERRUPTED -> R.string.diagnostics_dpi_suite_train_interrupted
        Tcp16Verdict.SERVER_CLOSED -> R.string.diagnostics_dpi_suite_peer_closed
        Tcp16Verdict.TIMED_OUT -> R.string.diagnostics_ip_timeout
        Tcp16Verdict.INVALID_RECONNECTED -> R.string.diagnostics_tcp16_metric_single_socket_invalid
        Tcp16Verdict.DEAD -> R.string.diagnostics_telegram_status_unreachable
        Tcp16Verdict.ERROR -> R.string.diagnostics_dpi_suite_error
    }

@StringRes
internal fun AllowlistSniFailure.labelRes(): Int =
    when (this) {
        AllowlistSniFailure.NO_TLS_BASELINE -> R.string.diagnostics_dpi_suite_sni_no_tls_baseline
        AllowlistSniFailure.BASELINE_NOT_REPRODUCED -> R.string.diagnostics_dpi_suite_sni_baseline_changed
        AllowlistSniFailure.SEARCH_TIMED_OUT -> R.string.diagnostics_ip_timeout
    }

internal fun AllowlistSniResult.toSuiteDetail(strings: StringResolver): DiagnosticsDpiSuiteProbeDetailUiModel =
    DiagnosticsDpiSuiteProbeDetailUiModel(
        label = diagnosticPeerLabel(ip, port),
        detail =
            buildList {
                if (compatibleSnis.isNotEmpty()) add(compatibleSnis.joinToString { it.sni })
                failure?.let { add(strings.getString(it.labelRes())) }
                if (isEmpty()) add(strings.getString(R.string.asset_provider_version_none))
            }.joinToString(" | "),
        tone =
            when {
                aborted -> DiagnosticsTone.Warning
                compatibleSnis.isEmpty() -> DiagnosticsTone.Neutral
                else -> DiagnosticsTone.Positive
            },
    )

internal fun ProbeStats.toSuiteDetail(
    strings: StringResolver,
    direction: TelegramTransferKind,
): DiagnosticsDpiSuiteProbeDetailUiModel {
    val upload = direction == TelegramTransferKind.UPLOAD
    val uploadBytes = R.string.diagnostics_dpi_suite_upload_bytes_written
    val payloadLabel = if (upload) uploadBytes else R.string.diagnostics_stages_bytes
    return DiagnosticsDpiSuiteProbeDetailUiModel(
        label =
            strings.getString(
                if (upload) R.string.diagnostics_telegram_upload else R.string.diagnostics_telegram_download,
            ),
        detail =
            buildList {
                add(strings.getString(status.labelRes()))
                if (status == TelegramProbeStatus.ERROR) {
                    failure?.let { add(strings.getString(it.labelRes())) }
                }
                add(strings.getString(payloadLabel, bytesTotal))
                add(strings.getString(R.string.diagnostics_transfer_ms, durationMs))
                if (avgBps > 0) add("${strings.getString(R.string.diagnostics_telegram_avg_speed)} $avgBps B/s")
                if (peakBps > 0) add("${strings.getString(R.string.diagnostics_telegram_peak_speed)} $peakBps B/s")
                httpStatus?.let { add(strings.getString(R.string.diagnostics_stages_http_status, it)) }
                responseDelayMs?.let { add(strings.getString(R.string.diagnostics_dpi_suite_http_response_wait, it)) }
            }.joinToString(" | "),
        tone = if (status == TelegramProbeStatus.OK) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
    )
}

@StringRes
internal fun TelegramProbeStatus.labelRes(): Int =
    when (this) {
        TelegramProbeStatus.OK -> R.string.diagnostics_cidr_whitelist_status_ok
        TelegramProbeStatus.INCOMPLETE -> R.string.diagnostics_dpi_suite_partial
        TelegramProbeStatus.STALLED -> R.string.diagnostics_dpi_suite_stalled
        TelegramProbeStatus.ERROR -> R.string.diagnostics_dpi_suite_error
    }

@StringRes
private fun TelegramTransferFailure.labelRes(): Int =
    when (this) {
        TelegramTransferFailure.HTTP_REJECTED -> R.string.diagnostics_dpi_suite_http_rejected
        TelegramTransferFailure.TIMEOUT -> R.string.diagnostics_ip_timeout
        TelegramTransferFailure.DNS, TelegramTransferFailure.IO -> R.string.diagnostics_dns_transport_error
        TelegramTransferFailure.TLS -> R.string.diagnostics_replay_error_tls_handshake_failed
        TelegramTransferFailure.PROTOCOL -> R.string.diagnostics_pt_status_invalid_response
        TelegramTransferFailure.LOCAL_POLICY -> R.string.diagnostics_ip_unsupported
    }

internal fun DcReachability.toSuiteDetail(strings: StringResolver): DiagnosticsDpiSuiteProbeDetailUiModel {
    val status =
        when (failure) {
            TelegramDcFailure.TIMEOUT -> {
                R.string.diagnostics_ip_timeout
            }

            TelegramDcFailure.CONNECT -> {
                R.string.diagnostics_telegram_status_unreachable
            }

            TelegramDcFailure.LOCAL_POLICY -> {
                R.string.diagnostics_ip_unsupported
            }

            null -> {
                if (reachable) {
                    R.string.diagnostics_cidr_whitelist_status_ok
                } else {
                    R.string.diagnostics_telegram_status_unknown
                }
            }
        }
    val detail =
        listOfNotNull(
            ip,
            strings.getString(status),
            rttMs?.let { strings.getString(R.string.diagnostics_transfer_ms, it) },
        ).joinToString(" | ")
    return DiagnosticsDpiSuiteProbeDetailUiModel(
        label = label,
        detail = detail,
        tone = if (reachable) DiagnosticsTone.Positive else DiagnosticsTone.Neutral,
    )
}
