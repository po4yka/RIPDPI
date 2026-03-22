package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

internal interface ServiceCoordinatorHost {
    val serviceScope: CoroutineScope

    fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    )

    fun requestStopSelf(stopSelfStartId: Int?)
}

internal interface VpnCoordinatorHost :
    ServiceCoordinatorHost,
    VpnTunnelBuilderHost {
    fun syncUnderlyingNetworksFromActiveNetwork()
}

internal interface HandoverAwareSession {
    var pendingNetworkHandoverClass: String?
    var lastSuccessfulHandoverFingerprintHash: String?
    var lastSuccessfulHandoverAt: Long
}

internal abstract class BaseServiceRuntimeCoordinator<TSession>(
    private val mode: Mode,
    protected val host: ServiceCoordinatorHost,
    protected val connectionPolicyResolver: ConnectionPolicyResolver,
    protected val serviceRuntimeRegistry: ServiceRuntimeRegistry,
    private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    private val networkHandoverMonitor: NetworkHandoverMonitor,
    private val policyHandoverEventStore: PolicyHandoverEventStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    protected val clock: ServiceClock = SystemServiceClock,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private companion object {
        private const val HandoverCooldownMs = 10_000L
    }

    protected val mutex = Mutex()
    protected val lifecycleState = ServiceLifecycleStateMachine()

    @Volatile
    protected var stopping: Boolean = false

    protected var status: ServiceStatus = ServiceStatus.Disconnected
    protected var telemetryJob: Job? = null
    protected var handoverMonitorJob: Job? = null
    protected var runtimeSession: TSession? = null
    protected val consumePendingNetworkHandoverClass: () -> String? = {
        runtimeSession?.pendingNetworkHandoverClass?.also {
            runtimeSession?.pendingNetworkHandoverClass = null
        }
    }

    protected abstract val serviceLabel: String

    suspend fun start() {
        logcat(LogPriority.INFO) { "Starting $serviceLabel" }

        var matchedRememberedPolicy: com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity? = null
        val session = createRuntimeSession()
        val failure =
            runCatching {
                mutex.withLock {
                    if (!lifecycleState.tryBeginStart()) {
                        logcat(LogPriority.WARN) {
                            "Ignoring $serviceLabel start while lifecycle state is ${lifecycleState.state}"
                        }
                        return@withLock false
                    }

                    runCatching {
                        val resolution = resolveInitialConnectionPolicy()
                        matchedRememberedPolicy = resolution.matchedNetworkPolicy
                        applyActiveConnectionPolicy(
                            session = session,
                            resolution = resolution,
                            restartReason = "initial_start",
                            appliedAt = clock.nowMillis(),
                        )
                        startResolvedRuntime(
                            session = session,
                            resolution = resolution,
                        )
                        runtimeSession = session
                        serviceRuntimeRegistry.register(session)
                        updateStatus(ServiceStatus.Connected)
                        startNetworkHandoverMonitoring()
                        startModeTelemetryUpdates()
                        lifecycleState.markStarted()
                        true
                    }.onFailure {
                        lifecycleState.markStartFailed()
                    }.getOrThrow()
                }
            }.exceptionOrNull()

        if (failure != null) {
            val error = failure as? Exception ?: IllegalStateException("Failed to start $serviceLabel", failure)
            logcat(LogPriority.ERROR) { "Failed to start $serviceLabel\n${error.asLog()}" }
            matchedRememberedPolicy?.let { policy ->
                rememberedNetworkPolicyStore.recordFailure(policy)
            }
            val failureReason = classifyStartupFailure(error)
            updateStatus(ServiceStatus.Failed, failureReason)
            stop()
        }
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) {
        logcat(LogPriority.INFO) { "Stopping $serviceLabel" }

        if (stopping) {
            logcat(LogPriority.WARN) { "$serviceLabel stop already in progress" }
            return
        }

        val stopped =
            mutex.withLock {
                if (stopping) {
                    logcat(LogPriority.WARN) { "$serviceLabel stop already in progress" }
                    return@withLock false
                }

                if (lifecycleState.state != ServiceLifecycleStateMachine.State.STOPPING) {
                    lifecycleState.beginStop()
                }
                stopping = true
                runCatching {
                    handoverMonitorJob?.cancel()
                    handoverMonitorJob = null
                    stopModeRuntime(skipRuntimeShutdown)
                }.onFailure { failure ->
                    val error = failure as? Exception ?: IllegalStateException("Failed to stop $serviceLabel", failure)
                    logcat(LogPriority.ERROR) { "Failed to stop $serviceLabel\n${error.asLog()}" }
                }
                try {
                    val session = runtimeSession
                    updateStatus(ServiceStatus.Disconnected)
                    telemetryJob?.cancel()
                    telemetryJob = null
                    onAfterStopCleanup(session)
                    session?.clearActiveConnectionPolicy()
                    session?.let {
                        serviceRuntimeRegistry.unregister(
                            mode = mode,
                            runtimeId = it.runtimeId,
                        )
                    }
                    runtimeSession = null
                    host.requestStopSelf(stopSelfStartId)
                } finally {
                    stopping = false
                    lifecycleState.markStopped()
                }
                true
            }

        if (!stopped) {
            return
        }
    }

    fun onDestroy() {
        telemetryJob?.cancel()
        handoverMonitorJob?.cancel()
    }

    protected fun replaceTelemetryJob(block: suspend CoroutineScope.() -> Unit) {
        telemetryJob?.cancel()
        telemetryJob = host.serviceScope.launch(ioDispatcher, block = block)
    }

    protected fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val classification = consumePendingNetworkHandoverClass() ?: return snapshot
        return snapshot.copy(networkHandoverClass = classification)
    }

    private fun startNetworkHandoverMonitoring() {
        handoverMonitorJob?.cancel()
        handoverMonitorJob =
            host.serviceScope.launch(ioDispatcher) {
                networkHandoverMonitor.events.collect { event ->
                    runtimeSession?.pendingNetworkHandoverClass = event.classification
                    if (!event.isActionable) {
                        return@collect
                    }
                    handleNetworkHandover(event)
                }
            }
    }

    private suspend fun handleNetworkHandover(event: NetworkHandoverEvent) {
        val session = runtimeSession
        val currentFingerprint = event.currentFingerprint
        val canHandle = session != null && currentFingerprint != null && status == ServiceStatus.Connected && !stopping
        if (!canHandle) {
            return
        }
        session as TSession
        currentFingerprint as NetworkFingerprint
        val fingerprintHash = currentFingerprint.scopeKey()
        val now = clock.nowMillis()
        val isCoolingDown =
            session.lastSuccessfulHandoverFingerprintHash == fingerprintHash &&
                now - session.lastSuccessfulHandoverAt < HandoverCooldownMs
        if (isCoolingDown) return

        val previousFingerprintHash = session.currentActiveConnectionPolicy?.fingerprintHash
        val failure =
            runCatching {
                val resolution =
                    resolveHandoverConnectionPolicy(
                        fingerprint = currentFingerprint,
                        handoverClassification = event.classification,
                    )
                mutex.withLock {
                    val activeSession = runtimeSession
                    if (
                        status != ServiceStatus.Connected ||
                        stopping ||
                        activeSession?.runtimeId != session.runtimeId
                    ) {
                        return@withLock
                    }
                    stopping = true
                    try {
                        restartAfterHandover(
                            session = activeSession,
                            resolution = resolution,
                            appliedAt = now,
                        )
                    } finally {
                        stopping = false
                    }
                }

                session.lastSuccessfulHandoverFingerprintHash = fingerprintHash
                session.lastSuccessfulHandoverAt = now
                policyHandoverEventStore.publish(
                    PolicyHandoverEvent(
                        mode = mode,
                        previousFingerprintHash = previousFingerprintHash,
                        currentFingerprintHash = fingerprintHash,
                        classification = event.classification,
                        usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                        policySignature = resolution.policySignature,
                        occurredAt = now,
                    ),
                )
            }.exceptionOrNull()

        if (failure != null) {
            val error =
                failure as? Exception ?: IllegalStateException(
                    "Failed to restart $serviceLabel after handover",
                    failure,
                )
            logcat(LogPriority.ERROR) {
                "Failed to restart $serviceLabel after handover\n${error.asLog()}"
            }
            val reason = classifyHandoverFailure(error)
            updateStatus(ServiceStatus.Failed, reason)
            stop()
        }
    }

    protected abstract fun createRuntimeSession(): TSession

    protected abstract suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution

    protected abstract suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution

    protected abstract fun applyActiveConnectionPolicy(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    )

    protected abstract suspend fun startResolvedRuntime(
        session: TSession,
        resolution: ConnectionPolicyResolution,
    )

    protected abstract suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean)

    protected abstract fun startModeTelemetryUpdates()

    protected abstract suspend fun restartAfterHandover(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    )

    protected abstract fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason? = null,
    )

    protected abstract fun classifyStartupFailure(error: Exception): FailureReason

    protected abstract fun classifyHandoverFailure(error: Exception): FailureReason

    protected open fun onAfterStopCleanup(session: TSession?) = Unit
}
