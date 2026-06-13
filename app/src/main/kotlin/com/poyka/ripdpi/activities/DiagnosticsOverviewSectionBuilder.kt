package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.ScanProgress
import kotlinx.collections.immutable.toImmutableList

private const val MaxOverviewRememberedNetworks = 6

internal fun DiagnosticsUiFactorySupport.buildOverviewUiModel(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: DiagnosticScanSession?,
    recentAutomaticProbe: DiagnosticsAutomaticProbeCalloutUiModel?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    currentTelemetry: DiagnosticTelemetrySample?,
    sessions: List<DiagnosticScanSession>,
    nativeEvents: List<DiagnosticEvent>,
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    sessionRows: List<DiagnosticsSessionRowUiModel>,
    rememberedNetworkRows: List<DiagnosticsRememberedNetworkUiModel>,
    warnings: List<DiagnosticsEventUiModel>,
): DiagnosticsOverviewUiModel =
    DiagnosticsOverviewUiModel(
        health = health,
        headline = overviewHeadline(health, progress, latestSession, selectedProfile),
        body = overviewBody(health, latestSession, latestSnapshot, currentTelemetry),
        activeProfile = selectedProfile,
        recentAutomaticProbe = recentAutomaticProbe,
        latestSnapshot = latestSnapshot,
        latestSession = sessionRows.firstOrNull(),
        contextSummary = latestContext?.let(::toOverviewContextGroup),
        metrics = buildOverviewMetrics(health, sessions, nativeEvents, currentTelemetry).toImmutableList(),
        warnings = warnings.toImmutableList(),
        rememberedNetworks = rememberedNetworkRows.take(MaxOverviewRememberedNetworks).toImmutableList(),
    )

private fun DiagnosticsUiFactorySupport.overviewHeadline(
    health: DiagnosticsHealth,
    progress: ScanProgress?,
    latestSession: DiagnosticScanSession?,
    selectedProfile: DiagnosticsProfileOptionUiModel? = null,
): String =
    when {
        progress != null && selectedProfile?.isStrategyProbe == true -> {
            context.getString(R.string.diagnostics_headline_probe_active)
        }

        progress != null -> {
            context.getString(R.string.diagnostics_headline_scan_active)
        }

        latestSession == null -> {
            context.getString(R.string.diagnostics_headline_no_data)
        }

        health == DiagnosticsHealth.Degraded -> {
            context.getString(R.string.diagnostics_headline_degraded)
        }

        health == DiagnosticsHealth.Attention -> {
            context.getString(R.string.diagnostics_headline_attention)
        }

        health == DiagnosticsHealth.Healthy -> {
            context.getString(R.string.diagnostics_headline_healthy)
        }

        else -> {
            context.getString(R.string.diagnostics_headline_waiting)
        }
    }

private fun DiagnosticsUiFactorySupport.overviewBody(
    health: DiagnosticsHealth,
    latestSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    telemetry: DiagnosticTelemetrySample?,
): String {
    // No scan session yet: keep the body consistent with the "no diagnostics
    // captured yet" headline instead of surfacing a health-derived warning that
    // contradicts it (passive events alone can flip health to Attention).
    if (latestSession == null) {
        return context.getString(R.string.diagnostics_body_idle)
    }
    return when (health) {
        DiagnosticsHealth.Healthy -> {
            context.getString(R.string.diagnostics_body_healthy)
        }

        DiagnosticsHealth.Attention -> {
            context.getString(R.string.diagnostics_body_attention)
        }

        DiagnosticsHealth.Degraded -> {
            context.getString(R.string.diagnostics_body_degraded)
        }

        DiagnosticsHealth.Idle -> {
            latestSnapshot?.subtitle ?: telemetry?.connectionState ?: context.getString(R.string.diagnostics_body_idle)
        }
    }
}
