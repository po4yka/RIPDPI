package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeBucket
import com.poyka.ripdpi.diagnostics.ScanProgress

internal fun DiagnosticsUiFactorySupport.deriveHealth(
    progress: ScanProgress?,
    latestSession: DiagnosticScanSession?,
    latestTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
): DiagnosticsHealth {
    val hasError = nativeEvents.any { it.level.equals("error", ignoreCase = true) }
    val hasWarning = nativeEvents.any { it.level.equals("warn", ignoreCase = true) }
    val latestSessionBucket =
        latestSession
            ?.report
            ?.results
            ?.takeIf { it.isNotEmpty() }
            ?.let { results ->
                core.aggregateBucketForProbeResults(parsePathMode(latestSession.pathMode), results)
            }
    return when {
        progress != null -> {
            DiagnosticsHealth.Attention
        }

        latestSession == null && latestTelemetry == null && nativeEvents.isEmpty() -> {
            DiagnosticsHealth.Idle
        }

        hasError -> {
            DiagnosticsHealth.Degraded
        }

        latestSessionBucket == DiagnosticsOutcomeBucket.Failed -> {
            DiagnosticsHealth.Degraded
        }

        latestSession?.status.equals("failed", ignoreCase = true) -> {
            DiagnosticsHealth.Degraded
        }

        hasWarning -> {
            DiagnosticsHealth.Attention
        }

        latestSessionBucket == DiagnosticsOutcomeBucket.Attention ||
            latestSessionBucket == DiagnosticsOutcomeBucket.Inconclusive -> {
            DiagnosticsHealth.Attention
        }

        else -> {
            DiagnosticsHealth.Healthy
        }
    }
}

internal fun DiagnosticsUiFactorySupport.deriveLiveHealth(
    activeConnectionSession: DiagnosticConnectionSession?,
    latestTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
): DiagnosticsHealth {
    if (activeConnectionSession == null) {
        return DiagnosticsHealth.Idle
    }

    val hasError = nativeEvents.any { it.level.equals("error", ignoreCase = true) }
    val hasWarning = nativeEvents.any { it.level.equals("warn", ignoreCase = true) }
    return when {
        hasError -> DiagnosticsHealth.Degraded
        activeConnectionSession.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsHealth.Degraded
        activeConnectionSession.health.equals("degraded", ignoreCase = true) -> DiagnosticsHealth.Degraded
        latestTelemetry?.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsHealth.Degraded
        hasWarning -> DiagnosticsHealth.Attention
        latestTelemetry == null -> DiagnosticsHealth.Idle
        else -> DiagnosticsHealth.Healthy
    }
}
