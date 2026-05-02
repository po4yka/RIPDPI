package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample

private const val LiveTelemetrySamples = 24

internal fun DiagnosticsUiFactorySupport.buildLiveTrends(
    telemetry: List<DiagnosticTelemetrySample>,
): List<DiagnosticsSparklineUiModel> {
    val samples = telemetry.take(LiveTelemetrySamples).reversed()
    if (samples.isEmpty()) {
        return emptyList()
    }
    return listOf(
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_tx_bytes),
            values = samples.map { it.txBytes.toFloat() },
            tone = DiagnosticsTone.Info,
        ),
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_rx_bytes),
            values = samples.map { it.rxBytes.toFloat() },
            tone = DiagnosticsTone.Positive,
        ),
        DiagnosticsSparklineUiModel(
            label = context.getString(R.string.diagnostics_sparkline_errors),
            values =
                samples.map { sample ->
                    if (sample.connectionState.equals("running", ignoreCase = true)) {
                        0f
                    } else {
                        1f
                    }
                },
            tone = DiagnosticsTone.Warning,
        ),
    )
}
