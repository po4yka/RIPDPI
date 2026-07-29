package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot

internal data class FreshTelemetrySample(
    val fresh: Boolean,
)

internal data class ServiceInstance(
    val startedAt: Long?,
    val restartCount: Int,
)

internal data class GuidedObservation(
    val status: AppStatus,
    val mode: Mode,
    val telemetry: ServiceTelemetrySnapshot,
    val interactive: Boolean,
    val underlayTransport: String?,
) {
    val isVpnRunning: Boolean
        get() = status == AppStatus.Running && mode == Mode.VPN
}

internal data class GuidedTriggers(
    val reconnect: Boolean,
    val handover: Boolean,
) {
    val any: Boolean
        get() = reconnect || handover
}
