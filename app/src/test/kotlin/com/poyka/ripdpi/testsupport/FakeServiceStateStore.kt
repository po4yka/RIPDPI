package com.poyka.ripdpi.testsupport

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

internal class FakeServiceStateStore : ServiceStateStore {
    private val _status = MutableStateFlow(AppStatus.Halted to Mode.VPN)
    override val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 4)
    override val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

    private val _telemetry = MutableStateFlow(ServiceTelemetrySnapshot())
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = _telemetry.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        _status.value = status to mode
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        _events.tryEmit(ServiceEvent.Failed(sender, reason))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        _telemetry.value = snapshot
    }
}
