package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.RuntimeTelemetryStatus

/**
 * Groups the per-runtime telemetry status values that are reported together
 * on every status transition. Using a holder keeps [RuntimeTelemetryProjection]
 * and [com.poyka.ripdpi.services.ServiceStatusReporter] below the detekt
 * LongParameterList threshold.
 */
internal data class RuntimeTelemetryStatuses(
    val proxy: RuntimeTelemetryStatus? = null,
    val relay: RuntimeTelemetryStatus? = null,
    val warp: RuntimeTelemetryStatus? = null,
    val awg: RuntimeTelemetryStatus? = null,
    val tunnel: RuntimeTelemetryStatus? = null,
)
