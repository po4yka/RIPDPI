package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiInputs
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.connection.runningSelections

@Immutable
data class StatusConnectedServer(
    val name: String,
    val latencyMillis: Long?,
)

internal fun MainUiInputs.connectedServer(connectionState: ConnectionState): StatusConnectedServer? =
    connectedServerOf(activeExit?.name ?: runningRelayServerName(applications, serverNames), connectionState, telemetry)

internal fun connectedServerOf(
    name: String?,
    connectionState: ConnectionState,
    telemetry: ServiceTelemetrySnapshot,
): StatusConnectedServer? =
    name?.takeIf { connectionState == ConnectionState.Connected }?.let {
        val egress = listOf(telemetry.relayTelemetry, telemetry.awgTelemetry, telemetry.warpTelemetry)
        StatusConnectedServer(it, egress.firstNotNullOfOrNull { snapshot -> snapshot.upstreamRttMs })
    }

internal fun runningRelayServerName(
    applications: Map<Mode, RuntimeConfigurationApplication>,
    serverNames: Map<String, String>,
): String? =
    applications
        .runningSelections()
        .firstOrNull { it.provider == NativeProvider && it.selectorGroupId == null && it.relayKind != WarpRelayKind }
        ?.profileId
        ?.let { serverNames[it] ?: it }

private const val NativeProvider = "native"
private const val WarpRelayKind = "warp"
