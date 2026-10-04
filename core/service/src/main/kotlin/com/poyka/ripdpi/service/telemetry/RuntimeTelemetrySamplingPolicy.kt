package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot

/** Source-owned cadence shared by producers and freshness presentation. */
object RuntimeTelemetrySamplingPolicy {
    const val InteractiveIntervalMillis = 1_000L
    const val BackgroundIntervalMillis = 5_000L

    fun intervalMillis(interactive: Boolean): Long =
        if (interactive) InteractiveIntervalMillis else BackgroundIntervalMillis
}

data class RuntimeMeasurementSource(
    val source: String,
    val capturedAt: Long,
    val serviceStatus: AppStatus,
    val telemetryStatus: RuntimeTelemetryStatus,
)

enum class RuntimeMeasurementFreshness { Unknown, Stopped, Error, Current, Stale }

fun RuntimeMeasurementSource.freshness(now: Long): RuntimeMeasurementFreshness =
    when {
        telemetryStatus.state == RuntimeTelemetryState.EngineError -> {
            RuntimeMeasurementFreshness.Error
        }

        serviceStatus == AppStatus.Halted -> {
            RuntimeMeasurementFreshness.Stopped
        }

        serviceStatus != AppStatus.Running || capturedAt <= 0L ||
            telemetryStatus.state != RuntimeTelemetryState.Snapshot -> {
            RuntimeMeasurementFreshness.Unknown
        }

        (now - capturedAt).coerceAtLeast(0L) >= RuntimeTelemetrySamplingPolicy.BackgroundIntervalMillis * 2 -> {
            RuntimeMeasurementFreshness.Stale
        }

        else -> {
            RuntimeMeasurementFreshness.Current
        }
    }

fun NativeRuntimeSnapshot.measurementSource(telemetry: ServiceTelemetrySnapshot): RuntimeMeasurementSource =
    RuntimeMeasurementSource(
        source = source,
        capturedAt = capturedAt,
        serviceStatus = telemetry.status,
        telemetryStatus =
            if (this ===
                telemetry.proxyTelemetry
            ) {
                telemetry.proxyTelemetryStatus
            } else {
                telemetry.tunnelTelemetryStatus
            },
    )
