package com.poyka.ripdpi.ui.theme

import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.ui.components.feedback.RipDpiMeasurementTone

/**
 * Resolves the MeasurementStrip tone for a quality snapshot.
 *
 * Returns `null` when sample count is below
 * [RipDpiNetworkQualityThresholds.minSampleCountForVerdict] —
 * UI should suppress the strip entirely.
 *
 * Returns [RipDpiMeasurementTone.Critical] when ANY of loss/rtt/jitter
 * meets its critical threshold.
 *
 * Returns [RipDpiMeasurementTone.Warning] when ANY meets warning
 * threshold (and none critical).
 *
 * Returns `null` when all values are below warning.
 *
 * Caller is responsible for hysteresis (state needed to track whether
 * the strip was previously shown) — this helper is stateless.
 */
fun resolveMeasurementTone(
    snapshot: ConnectionQualitySnapshot,
    thresholds: RipDpiNetworkQualityThresholds = DefaultRipDpiNetworkQualityThresholds,
): RipDpiMeasurementTone? {
    if (snapshot.sampleCount < thresholds.minSampleCountForVerdict) {
        return null
    }
    val anyCritical =
        snapshot.lossPct >= thresholds.lossCriticalPct ||
            snapshot.rttP50Ms >= thresholds.rttCriticalMs ||
            snapshot.jitterMs >= thresholds.jitterCriticalMs
    val anyWarn =
        snapshot.lossPct >= thresholds.lossWarnPct ||
            snapshot.rttP50Ms >= thresholds.rttWarnMs ||
            snapshot.jitterMs >= thresholds.jitterWarnMs
    return when {
        anyCritical -> RipDpiMeasurementTone.Critical
        anyWarn -> RipDpiMeasurementTone.Warning
        else -> null
    }
}
