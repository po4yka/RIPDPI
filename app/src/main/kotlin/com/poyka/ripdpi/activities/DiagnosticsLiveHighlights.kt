package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.rttBand
import com.poyka.ripdpi.diagnostics.winningStrategyFamily

internal fun DiagnosticsUiFactorySupport.buildLiveHighlights(
    telemetry: DiagnosticTelemetrySample?,
    events: List<DiagnosticEvent>,
): List<DiagnosticsMetricUiModel> {
    val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
    val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
    return buildList {
        telemetry?.let {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_tx),
                    value = formatBytes(it.txBytes),
                    tone = DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx),
                    value = formatBytes(it.rxBytes),
                    tone = DiagnosticsTone.Positive,
                ),
            )
            it.winningStrategyFamily()?.let { winningStrategy ->
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_strategy),
                        value = winningStrategy,
                        tone = DiagnosticsTone.Positive,
                    ),
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rtt),
                    value = it.rttBand(),
                    tone = DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_retries),
                    value = it.retryCount().toString(),
                    tone = if (it.retryCount() > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                ),
            )
            if (it.resolverFallbackActive) {
                add(
                    DiagnosticsMetricUiModel(
                        label = context.getString(R.string.diagnostics_metric_resolver_fallback),
                        value =
                            it.resolverFallbackReason
                                ?: context.getString(R.string.diagnostics_metric_resolver_fallback_active),
                        tone = DiagnosticsTone.Warning,
                    ),
                )
            }
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_packets),
                    value = (it.txPackets + it.rxPackets).toString(),
                    tone = DiagnosticsTone.Neutral,
                ),
            )
        }
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_warnings),
                value = warningCount.toString(),
                tone = if (warningCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_errors),
                value = errorCount.toString(),
                tone = if (errorCount > 0) DiagnosticsTone.Negative else DiagnosticsTone.Neutral,
            ),
        )
    }
}
