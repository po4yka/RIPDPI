package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.connection.ActiveExit

/** [latencyMillis] is the last round trip the egress runtime measured to the server, or null before one. */
@Immutable
data class StatusConnectedServer(
    val name: String,
    val latencyMillis: Long?,
)

/** The saved server that carries traffic now; null while not connected or on a direct path. */
internal fun connectedServerOf(
    exit: ActiveExit?,
    connectionState: ConnectionState,
    telemetry: ServiceTelemetrySnapshot,
): StatusConnectedServer? =
    exit?.takeIf { connectionState == ConnectionState.Connected }?.let {
        // Relay, AmneziaWG and WARP egress are mutually exclusive, so at most one reports a round trip.
        val egress = listOf(telemetry.relayTelemetry, telemetry.awgTelemetry, telemetry.warpTelemetry)
        StatusConnectedServer(it.name, egress.firstNotNullOfOrNull { snapshot -> snapshot.upstreamRttMs })
    }
