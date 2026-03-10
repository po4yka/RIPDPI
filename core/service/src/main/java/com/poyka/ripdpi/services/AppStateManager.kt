package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.TunnelStats
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ServiceEvent {
    data class Failed(
        val sender: Sender,
    ) : ServiceEvent()
}

data class ServiceTelemetrySnapshot(
    val mode: Mode? = null,
    val status: AppStatus = AppStatus.Halted,
    val tunnelStats: TunnelStats = TunnelStats(),
    val updatedAt: Long = 0L,
)

interface ServiceStateStore {
    val status: StateFlow<Pair<AppStatus, Mode>>
    val events: SharedFlow<ServiceEvent>
    val telemetry: StateFlow<ServiceTelemetrySnapshot>

    fun setStatus(
        status: AppStatus,
        mode: Mode,
    )

    fun emitFailed(sender: Sender)

    fun updateTelemetry(snapshot: ServiceTelemetrySnapshot)
}

@Singleton
class DefaultServiceStateStore
    @Inject
    constructor() : ServiceStateStore {
        private val _status = MutableStateFlow(AppStatus.Halted to Mode.VPN)
        override val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()

        private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
        override val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

        private val _telemetry = MutableStateFlow(ServiceTelemetrySnapshot())
        override val telemetry: StateFlow<ServiceTelemetrySnapshot> = _telemetry.asStateFlow()

        override fun setStatus(
            status: AppStatus,
            mode: Mode,
        ) {
            _status.value = status to mode
        }

        override fun emitFailed(sender: Sender) {
            _events.tryEmit(ServiceEvent.Failed(sender))
        }

        override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
            _telemetry.value = snapshot
        }
    }
