package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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
    val runtimeFieldTelemetry: RuntimeFieldTelemetry = RuntimeFieldTelemetry(),
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

    fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    )

    fun updateTelemetry(snapshot: ServiceTelemetrySnapshot)
}

@Singleton
class DefaultServiceStateStore
    @Inject
    constructor() : ServiceStateStore {
        private val _status = MutableStateFlow(AppStatus.Halted to Mode.VPN)
        override val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()

        private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)
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
                            status == AppStatus.Running && previousStatus != AppStatus.Running -> {
                                System
                                    .currentTimeMillis()
                            }

                            status == AppStatus.Running -> {
                                currentTelemetry.serviceStartedAt
                            }

                            else -> {
                                null
                            }
                        },
                    restartCount =
                        when {
                            status == AppStatus.Running && previousStatus != AppStatus.Running -> {
                                currentTelemetry.restartCount +
                                    1
                            }

                            else -> {
                                currentTelemetry.restartCount
                            }
                        },
                )
        }

        override fun emitFailed(
            sender: Sender,
            reason: FailureReason,
        ) {
            val currentTelemetry = _telemetry.value
            _telemetry.value =
                currentTelemetry.copy(
                    runtimeFieldTelemetry =
                        deriveRuntimeFieldTelemetry(
                            telemetryNetworkFingerprintHash =
                                currentTelemetry.runtimeFieldTelemetry.telemetryNetworkFingerprintHash,
                            winningTcpStrategyFamily =
                                currentTelemetry.runtimeFieldTelemetry.winningTcpStrategyFamily,
                            winningQuicStrategyFamily =
                                currentTelemetry.runtimeFieldTelemetry.winningQuicStrategyFamily,
                            winningDnsStrategyFamily =
                                currentTelemetry.runtimeFieldTelemetry.winningDnsStrategyFamily,
                            proxyTelemetry = currentTelemetry.proxyTelemetry,
                            tunnelTelemetry = currentTelemetry.tunnelTelemetry,
                            tunnelRecoveryRetryCount =
                                currentTelemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount,
                            failureReason = reason,
                        ),
                    lastFailureSender = sender,
                    lastFailureAt = System.currentTimeMillis(),
                )
            _events.tryEmit(ServiceEvent.Failed(sender, reason))
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

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceStateStoreModule {
    @Binds
    @Singleton
    abstract fun bindServiceStateStore(serviceStateStore: DefaultServiceStateStore): ServiceStateStore
}
