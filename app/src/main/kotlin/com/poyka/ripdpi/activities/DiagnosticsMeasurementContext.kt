package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample

private data class MeasurementMeaning(
    val explanation: Int? = null,
    val scope: Int? = null,
)

private val MeasurementMeanings =
    mapOf(
        R.string.diagnostics_metric_rtt to MeasurementMeaning(explanation = R.string.measurement_rtt_band),
        R.string.diagnostics_metric_rtt_band to MeasurementMeaning(explanation = R.string.measurement_rtt_band),
        R.string.diagnostics_metric_dns_latency to MeasurementMeaning(explanation = R.string.measurement_dns_last),
        R.string.diagnostics_metric_tx to MeasurementMeaning(explanation = R.string.measurement_byte_total),
        R.string.diagnostics_metric_rx to MeasurementMeaning(explanation = R.string.measurement_byte_total),
        R.string.diagnostics_metric_sessions to MeasurementMeaning(scope = R.string.measurement_loaded_view),
        R.string.diagnostics_metric_events to MeasurementMeaning(scope = R.string.measurement_loaded_view),
        R.string.diagnostics_metric_warnings to MeasurementMeaning(scope = R.string.measurement_loaded_view),
        R.string.diagnostics_metric_errors to MeasurementMeaning(scope = R.string.measurement_loaded_view),
        R.string.diagnostics_metric_retries to MeasurementMeaning(scope = R.string.measurement_counter_total),
        R.string.diagnostics_metric_packets to MeasurementMeaning(scope = R.string.measurement_counter_total),
        R.string.diagnostics_metric_dns_failures to MeasurementMeaning(scope = R.string.measurement_counter_total),
        R.string.diagnostics_metric_tx_packets to MeasurementMeaning(scope = R.string.measurement_counter_total),
        R.string.diagnostics_metric_rx_packets to MeasurementMeaning(scope = R.string.measurement_counter_total),
    )

internal fun DiagnosticsUiFactorySupport.describeMeasurementMetric(
    metric: DiagnosticsMetricUiModel,
    sample: DiagnosticTelemetrySample?,
): DiagnosticsMetricUiModel {
    val meaning =
        MeasurementMeanings.entries.firstOrNull { metric.label == context.getString(it.key) }?.value
            ?: return metric
    val snapshot =
        sample?.createdAt?.takeIf { it > 0L }?.let {
            context.getString(R.string.measurement_snapshot, formatTimestamp(it))
        }
    return metric.copy(
        explanation = meaning.explanation?.let { context.getString(it) } ?: metric.explanation,
        scopeLabel = meaning.scope?.let { context.getString(it) } ?: snapshot ?: metric.scopeLabel,
    )
}
