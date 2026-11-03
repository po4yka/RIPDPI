package com.poyka.ripdpi.data.fakes

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [ServiceStateStore] that derives telemetry from status changes, like the production store. */
class FakeServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
        val now = System.currentTimeMillis()
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            currentTelemetry.copy(
                mode = mode,
                status = status,
                serviceStartedAt =
                    when {
                        status == AppStatus.Running && currentTelemetry.status != AppStatus.Running -> now
                        status == AppStatus.Running -> currentTelemetry.serviceStartedAt
                        else -> null
                    },
                restartCount =
                    if (status == AppStatus.Running && currentTelemetry.status != AppStatus.Running) {
                        currentTelemetry.restartCount + 1
                    } else {
                        currentTelemetry.restartCount
                    },
                updatedAt = now,
            )
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val now = System.currentTimeMillis()
        telemetryState.value =
            telemetryState.value.copy(
                lastFailureSender = sender,
                lastFailureAt = now,
                updatedAt = now,
            )
        eventFlow.tryEmit(ServiceEvent.Failed(sender, reason))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            snapshot.copy(
                serviceStartedAt = snapshot.serviceStartedAt ?: currentTelemetry.serviceStartedAt,
                restartCount = maxOf(snapshot.restartCount, currentTelemetry.restartCount),
                lastFailureSender = snapshot.lastFailureSender ?: currentTelemetry.lastFailureSender,
                lastFailureAt = snapshot.lastFailureAt ?: currentTelemetry.lastFailureAt,
            )
    }
}
