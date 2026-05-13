package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.winningStrategyFamily

internal fun DiagnosticsUiFactorySupport.buildLiveHeadline(
    health: DiagnosticsHealth,
    telemetry: DiagnosticTelemetrySample?,
    events: List<DiagnosticEvent>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    return when {
        surfacedEvent?.level?.equals(
            "error",
            ignoreCase = true,
        ) == true -> context.getString(R.string.diagnostics_live_headline_error)

        health == DiagnosticsHealth.Degraded -> context.getString(R.string.diagnostics_live_headline_error)

        telemetry == null -> context.getString(R.string.diagnostics_live_headline_standby)

        health == DiagnosticsHealth.Attention -> context.getString(R.string.diagnostics_live_headline_attention)

        telemetry.connectionState.equals(
            "running",
            ignoreCase = true,
        ) -> context.getString(R.string.diagnostics_live_headline_traffic, telemetry.networkType)

        else -> telemetry.connectionState.replaceFirstChar { it.uppercase() }
    }
}

internal fun DiagnosticsUiFactorySupport.liveStatusTone(connectionState: String?): DiagnosticsTone =
    when {
        connectionState.equals("running", ignoreCase = true) -> DiagnosticsTone.Positive
        connectionState.equals("failed", ignoreCase = true) -> DiagnosticsTone.Negative
        connectionState.isNullOrBlank() -> DiagnosticsTone.Neutral
        else -> DiagnosticsTone.Neutral
    }

internal fun DiagnosticsUiFactorySupport.buildLiveBody(
    telemetry: DiagnosticTelemetrySample?,
    events: List<DiagnosticEvent>,
): String {
    val surfacedEvent =
        events.firstOrNull { it.level.equals("error", ignoreCase = true) }
            ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
    return when {
        surfacedEvent != null -> surfacedEvent.message
        telemetry == null -> context.getString(R.string.diagnostics_live_body_waiting)
        else -> buildTelemetryBody(telemetry)
    }
}

private fun DiagnosticsUiFactorySupport.buildTelemetryBody(telemetry: DiagnosticTelemetrySample): String {
    val telemetryErrorSummary = telemetry.telemetryErrorSummary()
    val failureClass = telemetry.failureClass
    val resolverFallbackReason = telemetry.resolverFallbackReason
    val networkHandoverState = telemetry.networkHandoverState
    val networkHandoverClass = telemetry.networkHandoverClass
    val winningStrategyFamily = telemetry.winningStrategyFamily()
    return when {
        telemetryErrorSummary != null -> {
            context.getString(
                R.string.diagnostics_live_telemetry_error_format,
                telemetryErrorSummary,
            )
        }

        telemetry.lastFailureClass != null || telemetry.lastFallbackAction != null -> {
            listOfNotNull(telemetry.lastFailureClass, telemetry.lastFallbackAction).joinToString(" · ")
        }

        failureClass != null -> {
            context.getString(R.string.diagnostics_live_failure_class_format, failureClass)
        }

        resolverFallbackReason != null -> {
            context.getString(R.string.diagnostics_live_dns_override_format, resolverFallbackReason)
        }

        networkHandoverState != null -> {
            context.getString(R.string.diagnostics_live_handover_state_format, networkHandoverState)
        }

        networkHandoverClass != null -> {
            context.getString(R.string.diagnostics_live_handover_format, networkHandoverClass)
        }

        winningStrategyFamily != null -> {
            context.getString(
                R.string.diagnostics_live_winning_strategy_format,
                winningStrategyFamily,
            )
        }

        else -> {
            val totalBytes = formatBytes(telemetry.txBytes + telemetry.rxBytes)
            val packetCount = telemetry.txPackets + telemetry.rxPackets
            val modeLabel = telemetry.activeMode ?: context.getString(R.string.diagnostics_metric_idle)
            context.getString(R.string.diagnostics_live_mode_summary_format, modeLabel, totalBytes, packetCount)
        }
    }
}

internal fun DiagnosticsUiFactorySupport.buildLiveSignalLabel(telemetry: DiagnosticTelemetrySample?): String =
    telemetry?.let {
        context.getString(
            R.string.diagnostics_live_signal_format,
            formatBytes(it.txBytes),
            formatBytes(it.rxBytes),
        )
    }
        ?: context.getString(R.string.diagnostics_live_no_transfer)

internal fun DiagnosticsUiFactorySupport.buildLiveEventSummaryLabel(events: List<DiagnosticEvent>): String {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return when {
        errorCount > 0 && warningCount > 0 -> {
            context.getString(
                R.string.diagnostics_live_errors_and_warnings_format,
                errorCount,
                pluralSuffix(errorCount),
                warningCount,
                pluralSuffix(warningCount),
            )
        }

        errorCount > 0 -> {
            context.getString(R.string.diagnostics_live_errors_format, errorCount, pluralSuffix(errorCount))
        }

        warningCount > 0 -> {
            context.getString(R.string.diagnostics_live_warnings_format, warningCount, pluralSuffix(warningCount))
        }

        events.isNotEmpty() -> {
            context.getString(R.string.diagnostics_live_info_events_format, events.size, pluralSuffix(events.size))
        }

        else -> {
            context.getString(R.string.diagnostics_live_feed_quiet)
        }
    }
}

private fun pluralSuffix(count: Int): String = if (count == 1) "" else "s"
