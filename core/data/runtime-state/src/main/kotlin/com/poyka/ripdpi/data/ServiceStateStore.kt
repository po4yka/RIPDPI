package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

internal const val WidgetTelemetryUpdateIntervalMillis = 30_000L

sealed class ServiceEvent {
    data class Failed(
        val sender: Sender,
        val reason: FailureReason,
        /** Coarse runtime state captured synchronously when the failure was published. */
        val statusAtFailure: AppStatus? = null,
        val modeAtFailure: Mode? = null,
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
    /**
     * AmneziaWG egress telemetry, populated when AWG is the active VPN-mode egress.
     * Idle (health = "idle") when the session runs a relay egress instead.
     * Mirrors [warpTelemetry] structurally; the two WireGuard transports are mutually exclusive.
     */
    val awgTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "awg"),
    val awgTelemetryStatus: RuntimeTelemetryStatus = RuntimeTelemetryStatus.NoData,
    val tunnelTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "tunnel"),
    val tunnelTelemetryStatus: RuntimeTelemetryStatus = RuntimeTelemetryStatus.NoData,
    val networkHandoverState: String? = null,
    val runtimeFieldTelemetry: RuntimeFieldTelemetry = RuntimeFieldTelemetry(),
    val initialTransportRaceSnapshot: InitialTransportRaceSnapshot? = null,
    /**
     * Live Xray provider engine snapshot, populated only when the active session
     * runs the Xray provider. Additive runtime DTO field (no proto/schema bump):
     * the typed provider health axis surfaced DISTINCTLY from the tunnel
     * data-plane telemetry above. Every field on it is already privacy-safe —
     * versions, states, error classes, and an ALREADY-REDACTED failure detail
     * (see [com.poyka.ripdpi.data.xray.XrayProviderSnapshot]). Null for the
     * native provider path.
     */
    val xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot? = null,
    val serviceStartedAt: Long? = null,
    val restartCount: Int = 0,
    val lastFailureSender: Sender? = null,
    val lastFailureAt: Long? = null,
    /**
     * Sticky "the foreign upstream relay died after connecting" signal. When true, the
     * base proxy/VPN runtime is intentionally kept alive but traffic egresses DIRECT,
     * so the Home actuator surfaces a Degraded (not Locked) status. Set by the relay
     * exit handlers on an unexpected relay exit; cleared on the next relay start and on
     * service stop. Driven only by an explicit relay-failure event — never inferred from
     * the absence of live relay sessions (subprocess relays never populate those).
     */
    val relayFailed: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
data class InitialTransportRaceCandidateSnapshot(
    val transportClass: String,
    val outcome: String,
    val latencyMs: Long? = null,
)

@Serializable
data class InitialTransportRaceSnapshot(
    val state: String,
    val candidates: List<InitialTransportRaceCandidateSnapshot> = emptyList(),
    val selectedTransportClass: String? = null,
    val usedCachedFallback: Boolean = false,
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

    /**
     * Opens a writer for one service-session generation.
     *
     * The default keeps lightweight test doubles source-compatible. The production store returns a
     * generation-bound facade so callbacks retained by a stopped service cannot mutate a newer session.
     */
    fun beginSession(mode: Mode): ServiceStateStore {
        setStatus(AppStatus.Halted, mode)
        updateTelemetry(ServiceTelemetrySnapshot(mode = mode, status = AppStatus.Halted))
        return this
    }

    /** Revokes this writer and optionally publishes its terminal failure atomically. */
    fun endSession(
        sender: Sender? = null,
        reason: FailureReason? = null,
    ) {
        require((sender == null) == (reason == null)) { "Terminal failure requires both sender and reason" }
        if (sender != null && reason != null) emitFailed(sender, reason)
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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

        private val lock = Any()

        private val projection =
            MutableStateFlow(
                ServiceStateProjection(
                    status = AppStatus.Halted to Mode.VPN,
                    telemetry = ServiceTelemetrySnapshot(),
                ),
            )
        override val status: StateFlow<Pair<AppStatus, Mode>> =
            MappedStateFlow(projection, ServiceStateProjection::status)

        private val eventStream = MutableStateFlow(SessionEventStream(generation = 0L))
        override val events: SharedFlow<ServiceEvent> =
            eventStream
                .flatMapLatest { stream ->
                    stream.events
                        .receiveAsFlow()
                        .filter { stream === eventStream.value }
                }.buffer(capacity = 0)
                .shareIn(
                    scope = applicationScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L),
                    replay = 0,
                )

        override val telemetry: StateFlow<ServiceTelemetrySnapshot> =
            MappedStateFlow(projection, ServiceStateProjection::telemetry)

        init {
            applicationScope.launch {
                val snapshots = projection.map { state -> toWidgetSnapshot(state.status, state.telemetry) }
                merge(
                    snapshots
                        .distinctUntilChangedBy(WidgetSnapshot::controlState)
                        .map { WidgetPublication(it, WidgetUpdateTarget.All) },
                    snapshots
                        .sample(WidgetTelemetryUpdateIntervalMillis)
                        .map { WidgetPublication(it, WidgetUpdateTarget.Telemetry) },
                ).distinctUntilChangedBy(WidgetPublication::snapshot)
                    .collect { publication ->
                        widgetStateRepository.write(publication.snapshot)
                        widgetNotifier.pushUpdate(publication.target)
                    }
            }
        }

        override fun setStatus(
            status: AppStatus,
            mode: Mode,
        ) {
            synchronized(lock) {
                val currentProjection = projection.value
                val previousStatus = currentProjection.status.first
                val now = System.currentTimeMillis()
                val currentTelemetry = currentProjection.telemetry
                projection.value =
                    ServiceStateProjection(
                        status = status to mode,
                        telemetry =
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
                            ),
                    )
            }
        }

        override fun emitFailed(
            sender: Sender,
            reason: FailureReason,
        ) {
            synchronized(lock) {
                publishFailedEvent(eventStream.value, sender, reason)
            }
        }

        override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
            synchronized(lock) {
                val currentProjection = projection.value
                val currentTelemetry = currentProjection.telemetry
                projection.value =
                    currentProjection.copy(
                        telemetry =
                            snapshot.copy(
                                serviceStartedAt = snapshot.serviceStartedAt ?: currentTelemetry.serviceStartedAt,
                                restartCount = maxOf(snapshot.restartCount, currentTelemetry.restartCount),
                                lastFailureSender = snapshot.lastFailureSender ?: currentTelemetry.lastFailureSender,
                                lastFailureAt = snapshot.lastFailureAt ?: currentTelemetry.lastFailureAt,
                            ),
                    )
            }
        }

        override fun beginSession(mode: Mode): ServiceStateStore {
            val generation =
                synchronized(lock) {
                    val previousEventStream = eventStream.value
                    val generation = previousEventStream.generation + 1L
                    val restartCount = projection.value.telemetry.restartCount
                    eventStream.value = SessionEventStream(generation)
                    previousEventStream.events.close()
                    projection.value =
                        ServiceStateProjection(
                            status = AppStatus.Halted to mode,
                            telemetry =
                                ServiceTelemetrySnapshot(
                                    mode = mode,
                                    status = AppStatus.Halted,
                                    restartCount = restartCount,
                                ),
                        )
                    generation
                }
            return SessionBoundServiceStateStore(this, generation)
        }

        private fun setStatusForSession(
            generation: Long,
            status: AppStatus,
            mode: Mode,
        ) {
            synchronized(lock) {
                if (generation != eventStream.value.generation) return
                setStatus(status, mode)
            }
        }

        private fun emitFailedForSession(
            generation: Long,
            sender: Sender,
            reason: FailureReason,
        ) {
            synchronized(lock) {
                val stream = eventStream.value
                if (generation != stream.generation) return
                publishFailedEvent(stream, sender, reason)
            }
        }

        private fun updateTelemetryForSession(
            generation: Long,
            snapshot: ServiceTelemetrySnapshot,
        ) {
            synchronized(lock) {
                if (generation != eventStream.value.generation) return
                updateTelemetry(snapshot)
            }
        }

        private fun endSessionForSession(
            generation: Long,
            sender: Sender?,
            reason: FailureReason?,
        ) {
            synchronized(lock) {
                require((sender == null) == (reason == null)) { "Terminal failure requires both sender and reason" }
                val currentStream = eventStream.value
                if (generation != currentStream.generation) return
                val terminalStream = SessionEventStream(generation + 1L)
                eventStream.value = terminalStream
                currentStream.events.close()
                if (sender != null && reason != null) {
                    publishFailedEvent(terminalStream, sender, reason)
                }
                val terminalProjection = projection.value
                val mode = terminalProjection.status.second
                val terminalTelemetry = terminalProjection.telemetry
                val resetTelemetry =
                    ServiceTelemetrySnapshot(
                        mode = mode,
                        status = AppStatus.Halted,
                        restartCount = terminalTelemetry.restartCount,
                        lastFailureSender = terminalTelemetry.lastFailureSender,
                        lastFailureAt = terminalTelemetry.lastFailureAt,
                        updatedAt = System.currentTimeMillis(),
                    )
                projection.value =
                    ServiceStateProjection(
                        status = AppStatus.Halted to mode,
                        telemetry =
                            resetTelemetry.copy(
                                runtimeFieldTelemetry =
                                    if (reason != null) {
                                        deriveRuntimeFieldTelemetry(
                                            telemetryNetworkFingerprintHash = null,
                                            winningTcpStrategyFamily = null,
                                            winningQuicStrategyFamily = null,
                                            winningDnsStrategyFamily = null,
                                            proxyTelemetry = resetTelemetry.proxyTelemetry,
                                            relayTelemetry = resetTelemetry.relayTelemetry,
                                            warpTelemetry = resetTelemetry.warpTelemetry,
                                            tunnelTelemetry = resetTelemetry.tunnelTelemetry,
                                            tunnelRecoveryRetryCount = 0,
                                            failureReason = reason,
                                        )
                                    } else {
                                        RuntimeFieldTelemetry(
                                            failureClass = terminalTelemetry.runtimeFieldTelemetry.failureClass,
                                        )
                                    },
                            ),
                    )
            }
        }

        private fun publishFailedEvent(
            stream: SessionEventStream,
            sender: Sender,
            reason: FailureReason,
        ) {
            val now = System.currentTimeMillis()
            val currentProjection = projection.value
            val (statusAtFailure, modeAtFailure) = currentProjection.status
            val currentTelemetry = currentProjection.telemetry
            projection.value =
                currentProjection.copy(
                    telemetry =
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
                        ),
                )
            val event =
                ServiceEvent.Failed(
                    sender = sender,
                    reason = reason,
                    statusAtFailure = statusAtFailure,
                    modeAtFailure = modeAtFailure,
                )
            check(stream.events.trySend(event).isSuccess) {
                "Service event stream is unavailable"
            }
        }

        private class SessionBoundServiceStateStore(
            private val delegate: DefaultServiceStateStore,
            private val generation: Long,
        ) : ServiceStateStore {
            override val status: StateFlow<Pair<AppStatus, Mode>> = delegate.status
            override val events: SharedFlow<ServiceEvent> = delegate.events
            override val telemetry: StateFlow<ServiceTelemetrySnapshot> = delegate.telemetry

            override fun setStatus(
                status: AppStatus,
                mode: Mode,
            ) = delegate.setStatusForSession(generation, status, mode)

            override fun emitFailed(
                sender: Sender,
                reason: FailureReason,
            ) = delegate.emitFailedForSession(generation, sender, reason)

            override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) =
                delegate.updateTelemetryForSession(generation, snapshot)

            override fun beginSession(mode: Mode): ServiceStateStore =
                error("A session-bound state writer cannot open another session")

            override fun endSession(
                sender: Sender?,
                reason: FailureReason?,
            ) = delegate.endSessionForSession(generation, sender, reason)
        }
    }

private data class ServiceStateProjection(
    val status: Pair<AppStatus, Mode>,
    val telemetry: ServiceTelemetrySnapshot,
)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(
    private val upstream: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(upstream.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var initialized = false
        var previous: R? = null
        upstream.collect { upstreamValue ->
            val mapped = transform(upstreamValue)
            if (!initialized || mapped != previous) {
                initialized = true
                previous = mapped
                collector.emit(mapped)
            }
        }
    }
}

private data class SessionEventStream(
    val generation: Long,
    val events: Channel<ServiceEvent> = Channel(capacity = Channel.UNLIMITED),
)

private data class WidgetPublication(
    val snapshot: WidgetSnapshot,
    val target: WidgetUpdateTarget,
)

private data class WidgetControlState(
    val status: AppStatus,
    val mode: Mode?,
    val restartCount: Int,
)

private fun WidgetSnapshot.controlState(): WidgetControlState =
    WidgetControlState(
        status = status,
        mode = mode,
        restartCount = restartCount,
    )

private fun toWidgetSnapshot(
    statusPair: Pair<AppStatus, Mode>,
    telemetry: ServiceTelemetrySnapshot,
): WidgetSnapshot {
    val startedAt = telemetry.serviceStartedAt
    val uptimeMs =
        if (statusPair.first == AppStatus.Running && startedAt != null) {
            System.currentTimeMillis() - startedAt
        } else {
            0L
        }
    return WidgetSnapshot(
        status = statusPair.first,
        mode = statusPair.second,
        uptimeMs = uptimeMs,
        bytesUp = telemetry.tunnelStats.txBytes,
        bytesDown = telemetry.tunnelStats.rxBytes,
        restartCount = telemetry.restartCount,
    )
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
