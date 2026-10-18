package com.poyka.ripdpi.ui.screens.health

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource

/** Aggregate values and their source at the time the user opened the details. */
@Immutable
internal data class LatencyDetailSnapshot(
    val quality: ConnectionQualitySnapshot,
    val source: RuntimeMeasurementSource?,
    val connected: Boolean,
    val selectedAtMs: Long,
)

internal fun selectedLatencyDetailSnapshot(
    quality: ConnectionQualitySnapshot?,
    source: RuntimeMeasurementSource?,
    connected: Boolean,
    selectedAtMs: Long,
): LatencyDetailSnapshot? =
    quality?.takeIf { it.sampleCount > 0L }?.let {
        LatencyDetailSnapshot(
            quality = it.copy(),
            source = source?.copy(telemetryStatus = source.telemetryStatus.copy()),
            connected = connected,
            selectedAtMs = selectedAtMs,
        )
    }
