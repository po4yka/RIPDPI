package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import kotlinx.collections.immutable.toImmutableList

private const val MaxPassiveEvents = 8

internal fun DiagnosticsUiFactorySupport.buildLiveUiModel(
    activeConnectionSession: DiagnosticConnectionSession?,
    telemetry: List<DiagnosticTelemetrySample>,
    currentTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
): DiagnosticsLiveUiModel {
    val health = deriveLiveHealth(activeConnectionSession, currentTelemetry, nativeEvents)
    return DiagnosticsLiveUiModel(
        health = health,
        statusLabel =
            currentTelemetry?.connectionState
                ?: activeConnectionSession?.connectionState
                ?: context.getString(R.string.diagnostics_metric_idle),
        statusTone =
            liveStatusTone(
                currentTelemetry?.connectionState ?: activeConnectionSession?.connectionState,
            ),
        freshnessLabel =
            currentTelemetry?.createdAt?.let {
                context.getString(
                    R.string.diagnostics_live_updated_format,
                    formatTimestamp(it),
                )
            }
                ?: context.getString(R.string.diagnostics_live_no_telemetry),
        headline = buildLiveHeadline(health, currentTelemetry, nativeEvents),
        body = buildLiveBody(currentTelemetry, nativeEvents),
        networkLabel = currentTelemetry?.networkType ?: activeConnectionSession?.networkType,
        modeLabel = currentTelemetry?.activeMode ?: activeConnectionSession?.serviceMode,
        signalLabel = buildLiveSignalLabel(currentTelemetry),
        eventSummaryLabel = buildLiveEventSummaryLabel(nativeEvents),
        highlights = buildLiveHighlights(currentTelemetry, nativeEvents).toImmutableList(),
        metrics = buildLiveMetrics(currentTelemetry).toImmutableList(),
        trends = buildLiveTrends(telemetry).toImmutableList(),
        snapshot = latestSnapshot,
        contextGroups = latestContext?.let(::toLiveContextGroups).orEmpty().toImmutableList(),
        passiveEvents = nativeEvents.take(MaxPassiveEvents).map(::toEventUiModel).toImmutableList(),
    )
}
