package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.rttBand
import com.poyka.ripdpi.diagnostics.winningStrategyFamily

internal fun DiagnosticsUiFactorySupport.buildOverviewMetrics(
    health: DiagnosticsHealth,
    sessions: List<DiagnosticScanSession>,
    nativeEvents: List<DiagnosticEvent>,
    currentTelemetry: DiagnosticTelemetrySample?,
): List<DiagnosticsMetricUiModel> =
    buildList {
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_sessions),
                value = sessions.size.toString(),
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                label = context.getString(R.string.diagnostics_metric_events),
                value = nativeEvents.size.toString(),
                tone =
                    when (health) {
                        DiagnosticsHealth.Degraded -> DiagnosticsTone.Negative
                        DiagnosticsHealth.Attention -> DiagnosticsTone.Warning
                        DiagnosticsHealth.Healthy -> DiagnosticsTone.Positive
                        DiagnosticsHealth.Idle -> DiagnosticsTone.Neutral
                    },
            ),
        )
        currentTelemetry?.let { sample ->
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_tx),
                    value = formatBytes(sample.txBytes),
                    tone = DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_metric_rx),
                    value = formatBytes(sample.rxBytes),
                    tone = DiagnosticsTone.Info,
                ),
            )
        }
    }

internal fun DiagnosticsUiFactorySupport.buildLiveMetrics(
    telemetry: DiagnosticTelemetrySample?,
): List<DiagnosticsMetricUiModel> = telemetry?.let { buildTelemetryLiveMetrics(it) }.orEmpty()

internal fun DiagnosticsUiFactorySupport.buildTelemetryLiveMetrics(
    telemetry: DiagnosticTelemetrySample,
): List<DiagnosticsMetricUiModel> {
    val retryCount = telemetry.retryCount()
    return buildList {
        fun addWarningMetric(
            labelRes: Int,
            value: String,
        ) {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(labelRes),
                    value = value,
                    tone = DiagnosticsTone.Warning,
                ),
            )
        }

        fun addInfoMetric(
            labelRes: Int,
            value: String,
        ) {
            add(
                DiagnosticsMetricUiModel(
                    label = context.getString(labelRes),
                    value = value,
                    tone = DiagnosticsTone.Info,
                ),
            )
        }

        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_network),
                telemetry.networkType,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_mode),
                telemetry.activeMode ?: context.getString(R.string.diagnostics_metric_idle),
            ),
        )
        telemetry.telemetryErrorSummary()?.let {
            addWarningMetric(R.string.diagnostics_metric_telemetry_error, it)
        }
        telemetry.lastFailureClass?.let { addWarningMetric(R.string.diagnostics_metric_latest_native_failure, it) }
        telemetry.lastFallbackAction?.let { addInfoMetric(R.string.diagnostics_metric_fallback_action, it) }
        telemetry.failureClass?.let { addWarningMetric(R.string.diagnostics_metric_failure_class, it) }
        telemetry.networkHandoverState?.let { addInfoMetric(R.string.diagnostics_metric_handover_state, it) }
        telemetry.winningStrategyFamily()?.let {
            add(
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_metric_winning_strategy),
                    it,
                    DiagnosticsTone.Positive,
                ),
            )
        }
        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_rtt_band),
                telemetry.rttBand(),
                DiagnosticsTone.Info,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                context.getString(R.string.diagnostics_metric_retries),
                retryCount.toString(),
                if (retryCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
            ),
        )
        telemetry.resolverId?.let { resolverId ->
            add(
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_metric_resolver),
                    listOfNotNull(resolverId, telemetry.resolverProtocol).joinToString(" · "),
                    DiagnosticsTone.Info,
                ),
            )
        }
        telemetry.resolverLatencyMs?.let { latency ->
            addInfoMetric(
                R.string.diagnostics_metric_dns_latency,
                context.getString(R.string.diagnostics_metric_dns_latency_format, latency),
            )
        }
        if (telemetry.dnsFailuresTotal > 0) {
            addWarningMetric(R.string.diagnostics_metric_dns_failures, telemetry.dnsFailuresTotal.toString())
        }
        addInfoMetric(R.string.diagnostics_metric_tx_packets, telemetry.txPackets.toString())
        addInfoMetric(R.string.diagnostics_metric_rx_packets, telemetry.rxPackets.toString())
        // Process memory footprint -- the signal Android 17's per-app cap watches.
        telemetry.processRssBytes?.takeIf { it > 0 }?.let { rss ->
            addInfoMetric(R.string.diagnostics_metric_rss, formatBytes(rss))
        }
        telemetry.nativeHeapBytes?.takeIf { it > 0 }?.let { heap ->
            addInfoMetric(R.string.diagnostics_metric_native_heap, formatBytes(heap))
        }
    }
}

internal fun DiagnosticTelemetrySample.telemetryErrorSummary(): String? =
    listOfNotNull(
        telemetryErrorEntry("proxy", proxyTelemetryState, proxyTelemetryMessage),
        telemetryErrorEntry("relay", relayTelemetryState, relayTelemetryMessage),
        telemetryErrorEntry("warp", warpTelemetryState, warpTelemetryMessage),
        telemetryErrorEntry("tunnel", tunnelTelemetryState, tunnelTelemetryMessage),
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun telemetryErrorEntry(
    runtime: String,
    state: String,
    message: String?,
): String? =
    if (state == RuntimeTelemetryState.EngineError.wireValue) {
        if (message.isNullOrBlank()) {
            "$runtime telemetry failed"
        } else {
            "$runtime: $message"
        }
    } else {
        null
    }
