package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class ServiceEvent {
    data class Failed(
        val sender: Sender,
        val reason: FailureReason,
    ) : ServiceEvent()

    data class PermissionRevoked(
        val kind: String,
    ) : ServiceEvent()
}

object NetworkHandoverStates {
    const val Observed = "observed"
    const val WaitingForNetwork = "waiting_for_network"
    const val DeferredCaptivePortal = "deferred_captive_portal"
    const val Restarting = "restarting"
    const val RetryScheduled = "retry_scheduled"
    const val Revalidated = "revalidated"
    const val Failed = "failed"
}

data class ServiceTelemetrySnapshot(
    val mode: Mode? = null,
    val status: AppStatus = AppStatus.Halted,
    val tunnelStats: TunnelStats = TunnelStats(),
    val proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
    val proxyTelemetryStatus: RuntimeTelemetryStatus = RuntimeTelemetryStatus.NoData,
    val relayTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "relay"),
    val relayTelemetryStatus: RuntimeTelemetryStatus = RuntimeTelemetryStatus.NoData,
    val warpTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "warp"),
    val warpTelemetryStatus: RuntimeTelemetryStatus = RuntimeTelemetryStatus.NoData,
    val tunnelTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "tunnel"),
    val tunnelTelemetryStatus: RuntimeTelemetryStatus = RuntimeTelemetryStatus.NoData,
    val networkHandoverState: String? = null,
    val runtimeFieldTelemetry: RuntimeFieldTelemetry = RuntimeFieldTelemetry(),
    val serviceStartedAt: Long? = null,
    val restartCount: Int = 0,
    val lastFailureSender: Sender? = null,
    val lastFailureAt: Long? = null,
    val updatedAt: Long = 0L,
)

interface ServiceStateStore {
    /**
     * The canonical runtime-state observable — the current [AppStatus] paired
     * with the active [Mode]. RIPDPI has no single unified `RuntimeMode` type;
     * runtime state is this pair plus the relay / root / diagnostics layers
     * inferred from settings and native handles. See
     * `docs/architecture/RUNTIME_MODES.md`, "The runtime mode state model".
     */
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
    constructor(
        private val widgetStateRepository: WidgetStateRepository,
        private val widgetNotifier: WidgetNotifier,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : ServiceStateStore {
        constructor() : this(
            NoopWidgetStateRepository,
            NoopWidgetNotifier,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        private val _status = MutableStateFlow(AppStatus.Halted to Mode.VPN)
        override val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()

        private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)
        override val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

        private val _telemetry = MutableStateFlow(ServiceTelemetrySnapshot())
        override val telemetry: StateFlow<ServiceTelemetrySnapshot> = _telemetry.asStateFlow()

        init {
            applicationScope.launch {
                combine(_status, _telemetry) { statusPair, telemetry ->
                    val startedAt = telemetry.serviceStartedAt
                    val uptimeMs =
                        if (statusPair.first == AppStatus.Running && startedAt != null) {
                            System.currentTimeMillis() - startedAt
                        } else {
                            0L
                        }
                    WidgetSnapshot(
                        status = statusPair.first,
                        mode = statusPair.second,
                        uptimeMs = uptimeMs,
                        bytesUp = telemetry.tunnelStats.txBytes,
                        bytesDown = telemetry.tunnelStats.rxBytes,
                        restartCount = telemetry.restartCount,
                    )
                }.conflate().collect { widgetSnapshot ->
                    widgetStateRepository.write(widgetSnapshot)
                    widgetNotifier.pushUpdate()
                }
            }
        }

        override fun setStatus(
            status: AppStatus,
            mode: Mode,
        ) {
            val previousStatus = _status.value.first
            val now = System.currentTimeMillis()
            _status.value = status to mode
            val currentTelemetry = _telemetry.value
            _telemetry.value =
                currentTelemetry.copy(
                    mode = mode,
                    status = status,
                    serviceStartedAt =
                        when {
                            status == AppStatus.Running && previousStatus != AppStatus.Running -> {
                                now
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
                    updatedAt = now,
                )
        }

        override fun emitFailed(
            sender: Sender,
            reason: FailureReason,
        ) {
            val now = System.currentTimeMillis()
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
                            relayTelemetry = currentTelemetry.relayTelemetry,
                            warpTelemetry = currentTelemetry.warpTelemetry,
                            tunnelTelemetry = currentTelemetry.tunnelTelemetry,
                            tunnelRecoveryRetryCount =
                                currentTelemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount,
                            failureReason = reason,
                        ),
                    lastFailureSender = sender,
                    lastFailureAt = now,
                    updatedAt = now,
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

private object NoopWidgetStateRepository : WidgetStateRepository {
    private val snapshot = MutableStateFlow(WidgetSnapshot())

    override suspend fun write(snapshot: WidgetSnapshot) {
        this.snapshot.value = snapshot
    }

    override fun observe(): StateFlow<WidgetSnapshot> = snapshot.asStateFlow()

    override suspend fun snapshot(): WidgetSnapshot = snapshot.value
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceStateStoreModule {
    @Binds
    @Singleton
    abstract fun bindServiceStateStore(serviceStateStore: DefaultServiceStateStore): ServiceStateStore
}
