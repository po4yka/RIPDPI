package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import java.util.Locale

private const val ShortCodeMaxLength = 4

internal fun DiagnosticsUiFactorySupport.buildHostAutolearnOverviewSummary(service: ServiceContextModel): String {
    val state = formatAutolearnState(service.hostAutolearnEnabled)
    val details =
        buildList {
            if (service.learnedHostCount > 0) {
                add(context.getString(R.string.diagnostics_autolearn_learned_format, service.learnedHostCount))
            }
            if (service.penalizedHostCount > 0) {
                add(context.getString(R.string.diagnostics_autolearn_penalized_format, service.penalizedHostCount))
            }
            if (service.blockedHostCount > 0) {
                add(context.getString(R.string.diagnostics_autolearn_blocked_format, service.blockedHostCount))
            }
        }
    return if (details.isEmpty()) state else "$state · ${details.joinToString(" · ")}"
}

internal fun DiagnosticsUiFactorySupport.formatAutolearnState(value: String): String =
    when (value.lowercase(Locale.US)) {
        "enabled" -> context.getString(R.string.diagnostics_autolearn_active)
        "disabled" -> context.getString(R.string.diagnostics_autolearn_off)
        else -> context.getString(R.string.diagnostics_field_unknown)
    }

internal fun DiagnosticsUiFactorySupport.formatAutolearnHost(value: String): String =
    value.takeUnless { it.isBlank() || it == "none" } ?: context.getString(R.string.diagnostics_autolearn_none_yet)

internal fun DiagnosticsUiFactorySupport.formatAutolearnGroup(value: String): String =
    value
        .takeUnless { it.isBlank() || it == "none" }
        ?.let { context.getString(R.string.diagnostics_autolearn_route_format, it) }
        ?: context.getString(R.string.diagnostics_autolearn_none_yet)

internal fun DiagnosticsUiFactorySupport.formatAutolearnAction(value: String): String =
    when (value.lowercase(Locale.US)) {
        "host_promoted" -> context.getString(R.string.diagnostics_autolearn_promoted)
        "group_penalized" -> context.getString(R.string.diagnostics_autolearn_penalized)
        "host_blocked" -> context.getString(R.string.diagnostics_autolearn_host_blocked)
        "store_reset" -> context.getString(R.string.diagnostics_autolearn_store_reset)
        "none", "" -> context.getString(R.string.diagnostics_autolearn_none_yet)
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }

internal fun DiagnosticsUiFactorySupport.formatBlockSignal(value: String): String =
    when (value.lowercase(Locale.US)) {
        "http_blockpage" -> context.getString(R.string.block_signal_http_blockpage)
        "http_redirect" -> context.getString(R.string.block_signal_http_redirect)
        "tls_alert" -> context.getString(R.string.block_signal_tls_alert)
        "silent_drop" -> context.getString(R.string.block_signal_silent_drop)
        "tcp_reset" -> context.getString(R.string.block_signal_tcp_reset)
        "connection_freeze" -> context.getString(R.string.block_signal_connection_freeze)
        "quic_breakage" -> context.getString(R.string.block_signal_quic_breakage)
        "tcp_retransmissions" -> context.getString(R.string.block_signal_tcp_retransmissions)
        "none", "" -> context.getString(R.string.diagnostics_autolearn_none_yet)
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }

internal fun DiagnosticsUiFactorySupport.formatBlockProvider(value: String): String {
    val isShortCodeLike = value.none { it == '_' || it == '-' || it == ' ' } && value.length <= ShortCodeMaxLength
    return when {
        value.isBlank() || value == "none" -> {
            context.getString(R.string.diagnostics_autolearn_none_yet)
        }

        isShortCodeLike -> {
            value.uppercase(Locale.US)
        }

        else -> {
            value
                .split('_', '-', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.lowercase(Locale.US).replaceFirstChar { char -> char.uppercase(Locale.US) }
                }
        }
    }
}
