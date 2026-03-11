package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.NativeRuntimeSnapshot
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

sealed interface FailureReason {
    data class NativeError(val message: String) : FailureReason
    data object TunnelEstablishmentFailed : FailureReason
    data class Unexpected(val cause: Throwable) : FailureReason
}

sealed class ServiceEvent {
    data class Failed(
        val sender: Sender,
        val reason: FailureReason,
    ) : ServiceEvent()
}

data class ServiceTelemetrySnapshot(
    val mode: Mode? = null,
    val status: AppStatus = AppStatus.Halted,
    val tunnelStats: TunnelStats = TunnelStats(),
    val proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
    val tunnelTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "tunnel"),
    val serviceStartedAt: Long? = null,
    val restartCount: Int = 0,
    val lastFailureSender: Sender? = null,
    val lastFailureAt: Long? = null,
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

    fun emitFailed(sender: Sender, reason: FailureReason)

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
            val previousStatus = _status.value.first
            _status.value = status to mode
            val currentTelemetry = _telemetry.value
            _telemetry.value =
                currentTelemetry.copy(
                    mode = mode,
                    status = status,
                    serviceStartedAt =
                        when {
                            status == AppStatus.Running && previousStatus != AppStatus.Running -> System.currentTimeMillis()
                            status == AppStatus.Running -> currentTelemetry.serviceStartedAt
                            else -> null
                        },
                    restartCount =
                        when {
                            status == AppStatus.Running && previousStatus != AppStatus.Running -> currentTelemetry.restartCount + 1
                            else -> currentTelemetry.restartCount
                        },
                )
        }

        override fun emitFailed(sender: Sender, reason: FailureReason) {
            _events.tryEmit(ServiceEvent.Failed(sender, reason))
            _telemetry.value =
                _telemetry.value.copy(
                    lastFailureSender = sender,
                    lastFailureAt = System.currentTimeMillis(),
                )
        }

        override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
            val currentTelemetry = _telemetry.value
            _telemetry.value =
                snapshot.copy(
                    serviceStartedAt = snapshot.serviceStartedAt ?: currentTelemetry.serviceStartedAt,
                    restartCount = maxOf(snapshot.restartCount, currentTelemetry.restartCount),
                    lastFailureSender = snapshot.lastFailureSender ?: currentTelemetry.lastFailureSender,
                    lastFailureAt = snapshot.lastFailureAt ?: currentTelemetry.lastFailureAt,
                )
        }
    }
