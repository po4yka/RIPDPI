package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ProbeResult
import java.util.Locale

/** Known Telegram captions. Unknown evidence values keep their original text. */
@StringRes
internal fun telegramStatusLabelRes(status: String): Int? =
    when (status) {
        "ok" -> R.string.diagnostics_telegram_status_ok

        "slow" -> R.string.diagnostics_telegram_status_slow

        "stalled" -> R.string.diagnostics_telegram_status_stalled

        "blocked" -> R.string.diagnostics_telegram_status_blocked

        "partial" -> R.string.diagnostics_telegram_status_partial

        "error" -> R.string.diagnostics_telegram_status_error

        "cancelled" -> R.string.diagnostics_telegram_status_cancelled

        "deadline_exceeded" -> R.string.diagnostics_telegram_status_deadline_exceeded

        "unknown" -> R.string.diagnostics_telegram_status_unknown

        "not_run", "capability_skipped" -> R.string.diagnostics_matrix_stage_not_run

        "unreachable", "fail" -> R.string.diagnostics_telegram_status_unreachable

        "invalid_target",
        "subprotocol_not_negotiated",
        "upgrade_rejected",
        "upgrade_failed",
        -> R.string.diagnostics_telegram_status_error

        else -> null
    }

internal data class TelegramDcPresentation(
    val label: String,
    val status: String,
    val connectMs: Long?,
    val endpointOrPorts: String?,
)

internal fun parseTelegramDcResult(value: String): TelegramDcPresentation? {
    val parts = value.split(':', limit = 4)
    val label = parts.first()
    if (label.isEmpty() || label == "dc_probe_aborted") return null
    val status =
        when (val token = parts.getOrNull(1)) {
            "reachable=true" -> "ok"
            "reachable=false" -> "unreachable"
            null -> "unknown"
            else -> token
        }
    val connectMs =
        parts
            .getOrNull(2)
            ?.removePrefix("medianRttMs=")
            ?.removeSuffix("ms")
            ?.toLongOrNull()
    val endpointOrPorts =
        parts.getOrNull(DcDetailsIndex)?.let { details ->
            if (details.startsWith("address=")) {
                details.removePrefix("address=").substringBefore(';')
            } else {
                details.removePrefix("ports=").takeIf(String::isNotEmpty)
            }
        }
    return TelegramDcPresentation(label, status, connectMs, endpointOrPorts)
}

private const val DcDetailsIndex = 3
private const val BitsPerByte = 8.0
private const val DecimalKilo = 1_000.0
private const val DecimalMega = 1_000_000.0

/** Native Telegram avgBps and peakBps contain bytes per second, despite their wire names. */
internal fun formatTelegramThroughput(
    bytesPerSecond: Long,
    locale: Locale,
): String {
    val bitsPerSecond = bytesPerSecond.toDouble() * BitsPerByte
    return when {
        bitsPerSecond >= DecimalMega -> String.format(locale, "%.1f Mbps", bitsPerSecond / DecimalMega)
        bitsPerSecond >= DecimalKilo -> String.format(locale, "%.1f Kbps", bitsPerSecond / DecimalKilo)
        else -> String.format(locale, "%.0f bps", bitsPerSecond)
    }
}

internal fun DiagnosticsUiFactorySupport.telegramShareLines(result: ProbeResult): List<String> =
    buildList {
        val details = result.details.associate { it.key to it.value }
        val verdict = details["verdict"] ?: result.outcome
        val label = telegramStatusLabelRes(verdict)?.let { context.getString(it) } ?: verdict
        add(context.getString(R.string.diagnostics_share_telegram_format, label))
        val downloadStatus = details["downloadStatus"]
        val uploadStatus = details["uploadStatus"]
        if (downloadStatus != null) {
            val name = context.getString(R.string.diagnostics_telegram_download)
            val status = telegramStatusLabelRes(downloadStatus)?.let { context.getString(it) } ?: downloadStatus
            add("$name · $status")
        }
        details["downloadAvgBps"]?.toLongOrNull()?.let { bps ->
            add(
                context.getString(
                    R.string.diagnostics_share_download_format,
                    formatTelegramThroughput(bps, core.formatter.locale),
                    formatBytes(details["downloadBytes"]?.toLongOrNull() ?: 0),
                ),
            )
        }
        if (uploadStatus != null) {
            val name = context.getString(R.string.diagnostics_telegram_upload)
            val status = telegramStatusLabelRes(uploadStatus)?.let { context.getString(it) } ?: uploadStatus
            add("$name · $status")
        }
        details["uploadAvgBps"]?.toLongOrNull()?.let { bps ->
            add(
                context.getString(
                    R.string.diagnostics_share_upload_format,
                    formatTelegramThroughput(bps, core.formatter.locale),
                    formatBytes(details["uploadBytes"]?.toLongOrNull() ?: 0),
                ),
            )
        }
        addAll(telegramConnectivityShareLines(details))
        details["dcResults"]?.split('|')?.mapNotNull(::parseTelegramDcResult)?.forEach { dc ->
            val status = telegramStatusLabelRes(dc.status)?.let { context.getString(it) } ?: dc.status
            val elapsed = dc.connectMs?.let { context.getString(R.string.diagnostics_metric_dns_latency_format, it) }
            add(listOfNotNull(dc.label, dc.endpointOrPorts, status, elapsed).joinToString(" · "))
        }
        add(telegramWebSocketShareLine(details))
    }

private fun DiagnosticsUiFactorySupport.telegramConnectivityShareLines(details: Map<String, String>): List<String> =
    buildList {
        val dcReachable = details["dcReachable"]?.toIntOrNull()
        val dcAttempted = details["dcTotal"]?.toIntOrNull()
        val dcPlanned = details["dcPlanned"]?.toIntOrNull()
        val connectivityLabel =
            context.getString(
                if (dcPlanned !=
                    null
                ) {
                    R.string.diagnostics_telegram_tcp_endpoints
                } else {
                    R.string.diagnostics_telegram_data_centers
                },
            )
        if (dcReachable != null && dcAttempted != null && dcAttempted > 0) {
            if (dcPlanned == null) {
                add(
                    context.getString(
                        R.string.diagnostics_share_data_centers_format,
                        dcReachable.toString(),
                        dcAttempted.toString(),
                    ),
                )
            } else {
                val counts =
                    context.getString(
                        R.string.diagnostics_telegram_reachable_format,
                        dcReachable.toString(),
                        dcAttempted.toString(),
                    )
                add("$connectivityLabel · $counts")
                if (dcPlanned > dcAttempted) {
                    add(context.getString(R.string.diagnostics_telegram_unmeasured_format, dcPlanned - dcAttempted))
                }
            }
        } else {
            add(connectivityLabel + " · " + context.getString(R.string.diagnostics_matrix_stage_not_run))
        }
    }

private fun DiagnosticsUiFactorySupport.telegramWebSocketShareLine(details: Map<String, String>): String {
    val wsStatus = details["wsTunnelStatus"] ?: "not_run"
    val wsLabel = telegramStatusLabelRes(wsStatus)?.let { context.getString(it) } ?: wsStatus
    val wsTime =
        details["wsTunnelOpenMs"]?.toLongOrNull()?.let {
            context.getString(R.string.diagnostics_metric_dns_latency_format, it)
        }
    return listOfNotNull(context.getString(R.string.diagnostics_telegram_ws_handshake), wsLabel, wsTime)
        .joinToString(" · ")
}
