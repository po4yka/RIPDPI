package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.RunningReconnectFailure
import com.poyka.ripdpi.services.RunningReconnectResult
import com.poyka.ripdpi.services.RunningReconnectState
import com.poyka.ripdpi.services.RunningServiceReconnect
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class FakeAppliedRuntimeConfigurationSource : AppliedRuntimeConfigurationSource {
    override val applications =
        MutableStateFlow(
            Mode.entries.associateWith<Mode, RuntimeConfigurationApplication> {
                RuntimeConfigurationApplication.Unknown
            },
        )
    override val pendingChanges =
        MutableStateFlow(Mode.entries.associateWith { RuntimeConfigurationPendingStatus.Unknown })
}

/** A VPN session that carries traffic through [selection] now. */
internal fun runningOn(selection: RuntimeConfigurationSelection): Map<Mode, RuntimeConfigurationApplication> {
    val configuration =
        AppliedRuntimeConfiguration(
            runtimeId = "runtime",
            revision = 1L,
            appliedAt = 0L,
            mode = Mode.VPN,
            requestedSelection = selection,
            effectiveSelection = selection,
            dns = RuntimeConfigurationDns("plain_udp", "system"),
            strategy = RuntimeConfigurationStrategy(enabled = false),
            reason = RuntimeConfigurationApplyReason.InitialStart,
        )
    return mapOf(Mode.VPN to RuntimeConfigurationApplication.Applied(configuration))
}

/** UI action test double. Positive provider readiness is exercised by core service tests. */
internal class FakeRunningServiceReconnect(
    private val controller: com.poyka.ripdpi.services.TestSynchronousServiceController? = null,
    private val service: ServiceStateStore? = null,
) : RunningServiceReconnect {
    override val reconnectState = MutableStateFlow<RunningReconnectState>(RunningReconnectState.Idle)
    val requestedModes = mutableListOf<Mode>()

    override fun cancelReconnect() = Unit

    override suspend fun reconnect(request: com.poyka.ripdpi.services.RunningReconnectRequest): RunningReconnectResult {
        val mode = request.mode
        requestedModes += mode
        return if (controller == null || service == null) {
            RunningReconnectResult.NotRunning
        } else {
            controller.stop()
            val stopped = withTimeoutOrNull(10_000) { service.status.first { it.first == AppStatus.Halted } }
            if (stopped == null) {
                RunningReconnectResult.Failed(RunningReconnectFailure.StopTimedOut)
            } else {
                when (controller.start(mode)) {
                    is ServiceStartResult.Accepted -> {
                        RunningReconnectResult.NotRunning
                    }

                    is ServiceStartResult.MaintenanceAccepted -> {
                        RunningReconnectResult.Failed(RunningReconnectFailure.StartRejected)
                    }

                    is ServiceStartResult.Rejected -> {
                        RunningReconnectResult.Failed(
                            RunningReconnectFailure.StartRejected,
                        )
                    }
                }
            }
        }
    }
}
