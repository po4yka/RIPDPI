package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.connection.ActiveExit

@Immutable
data class StatusConnectedServer(
    val name: String,
    val latencyMillis: Long?,
)

internal fun connectedServerOf(
    exit: ActiveExit?,
    connectionState: ConnectionState,
    telemetry: ServiceTelemetrySnapshot,
): StatusConnectedServer? =
    exit?.takeIf { connectionState == ConnectionState.Connected }?.let {
        val egress = listOf(telemetry.relayTelemetry, telemetry.awgTelemetry, telemetry.warpTelemetry)
        StatusConnectedServer(it.name, egress.firstNotNullOfOrNull { snapshot -> snapshot.upstreamRttMs })
    }
