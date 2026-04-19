package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    var networkHandoverState: String?
    var lastSuccessfulHandoverFingerprintHash: String?
    var lastSuccessfulHandoverAt: Long
    var handoverRetryCount: Int
}

private class RuntimeLifecycleRunner(
    private val mutex: Mutex,
    private val lifecycleState: ServiceLifecycleStateMachine,
    private val serviceLabel: () -> String,
    private val isStopping: () -> Boolean,
    private val setStopping: (Boolean) -> Unit,
) {
    @Suppress("detekt.TooGenericExceptionCaught")
    suspend fun start(startBlock: suspend () -> Unit): Throwable? =
        mutex.withLock {
            if (!lifecycleState.tryBeginStart()) {
                Logger.d {
                    "Ignoring ${serviceLabel()} start while lifecycle state is ${lifecycleState.state}"
                }
                return@withLock null
            }

            try {
                startBlock()
                lifecycleState.markStarted()
                null
            } catch (failure: Exception) {
                lifecycleState.markStartFailed()
                failure
            }
        }

    suspend fun stop(stopBlock: suspend () -> Unit): Boolean {
        if (isStopping()) {
            Logger.d { "${serviceLabel()} stop already in progress" }
            return false
        }

        return mutex.withLock {
            if (isStopping()) {
                Logger.d { "${serviceLabel()} stop already in progress" }
                return@withLock false
            }

            if (lifecycleState.state != ServiceLifecycleStateMachine.State.STOPPING) {
                lifecycleState.beginStop()
            }
            setStopping(true)
            try {
                stopBlock()
                true
            } finally {
                setStopping(false)
                lifecycleState.markStopped()
            }
        }
    }
}

private interface HandoverRetryPolicy {
    fun nextRetryDelayMillis(currentRetryCount: Int): Long?
}

private class ExponentialHandoverRetryPolicy(
    private val maxRetries: Int,
    private val baseDelayMillis: Long,
    private val maxDelayMillis: Long,
) : HandoverRetryPolicy {
    override fun nextRetryDelayMillis(currentRetryCount: Int): Long? {
        if (currentRetryCount >= maxRetries) {
            return null
        }
        return (baseDelayMillis * (1L shl currentRetryCount.coerceAtMost(maxRetries)))
            .coerceAtMost(maxDelayMillis)
    }
}

private class PermissionWatchdogCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val permissionWatchdog: PermissionWatchdog,
    private val onPermissionRevoked: suspend (PermissionChangeEvent) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        cancel()
        job =
            scope.launch(dispatcher) {
                permissionWatchdog.changes.collect { event ->
                    Logger.i { "Permission change detected: ${event.kind}" }
                    onPermissionRevoked(event)
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

private class ServiceTelemetryLoopCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    fun replace(block: suspend CoroutineScope.() -> Unit) {
        cancel()
        job = scope.launch(dispatcher, block = block)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

private class NetworkHandoverProcessor<TSession>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val networkHandoverMonitor: NetworkHandoverMonitor,
    private val retryPolicy: HandoverRetryPolicy,
    private val clock: ServiceClock,
    private val serviceLabel: () -> String,
    private val currentSession: () -> TSession?,
    private val currentStatus: () -> ServiceStatus,
    private val isStopping: () -> Boolean,
    private val recordPendingClassification: (String) -> Unit,
    private val updateHandoverState: (String?) -> Unit,
    private val performRestart: suspend (TSession, NetworkHandoverEvent, Long) -> String?,
    private val onExhaustedFailure: suspend (Exception) -> Unit,
    private val handoverCooldownMillis: Long,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private var monitorJob: Job? = null

    fun startMonitoring() {
        cancel()
        monitorJob =
            scope.launch(dispatcher) {
                networkHandoverMonitor.events.collect { event ->
                    recordPendingClassification(event.classification)
                    updateHandoverState(
                        if (event.isActionable) {
                            NetworkHandoverStates.Observed
                        } else {
                            NetworkHandoverStates.WaitingForNetwork
                        },
                    )
                    if (!event.isActionable) {
                        return@collect
                    }
                    handle(event)
                }
            }
    }

    fun cancel() {
        monitorJob?.cancel()
        monitorJob = null
    }

    @Suppress("ReturnCount")
    private suspend fun handle(event: NetworkHandoverEvent) {
        val session = currentSession()
        val currentFingerprint = event.currentFingerprint
        val canHandle =
            session != null &&
                currentFingerprint != null &&
                currentStatus() == ServiceStatus.Connected &&
                !isStopping()
        if (!canHandle) {
            return
        }

        if (currentFingerprint.captivePortalDetected) {
            updateHandoverState(NetworkHandoverStates.DeferredCaptivePortal)
            Logger.i {
                "${serviceLabel()}: captive portal detected, deferring handover until network is validated"
            }
            return
        }

        val fingerprintHash = currentFingerprint.scopeKey()
        val now = clock.nowMillis()
        val isCoolingDown =
            session.lastSuccessfulHandoverFingerprintHash == fingerprintHash &&
                now - session.lastSuccessfulHandoverAt < handoverCooldownMillis
        if (isCoolingDown) {
            updateHandoverState(NetworkHandoverStates.Revalidated)
            return
        }

        updateHandoverState(NetworkHandoverStates.Restarting)
        val restartAttempt = runCatching { performRestart(session, event, now) }
        val failure = restartAttempt.exceptionOrNull()
        if (failure == null) {
            val appliedFingerprintHash = restartAttempt.getOrNull() ?: return
            session.lastSuccessfulHandoverFingerprintHash = appliedFingerprintHash
            session.lastSuccessfulHandoverAt = now
            session.handoverRetryCount = 0
            updateHandoverState(NetworkHandoverStates.Revalidated)
            return
        }

        val retryCount = session.handoverRetryCount
        val retryDelayMillis = retryPolicy.nextRetryDelayMillis(retryCount)
        if (retryDelayMillis != null) {
            session.handoverRetryCount = retryCount + 1
            updateHandoverState(NetworkHandoverStates.RetryScheduled)
            Logger.w {
                "${serviceLabel()} handover failed (attempt ${retryCount + 1}), " +
                    "retrying in ${retryDelayMillis}ms"
            }
            scope.launch(dispatcher) {
                delay(retryDelayMillis)
                if (currentSession()?.runtimeId == session.runtimeId && !isStopping()) {
                    handle(event)
                }
            }
            return
        }

        val error =
            failure as? Exception ?: IllegalStateException(
                "Failed to restart ${serviceLabel()} after handover",
                failure,
            )
        Logger.e(error) {
            "Failed to restart ${serviceLabel()} after handover (exhausted retries)"
        }
        updateHandoverState(NetworkHandoverStates.Failed)
        onExhaustedFailure(error)
    }
}

internal class SharedProxyRuntimeStack(
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
) {
    suspend fun start(
        proxyPreferences: RipDpiProxyPreferences,
        onRelayExit: suspend (Result<Int>) -> Unit,
        onWarpExit: suspend (Result<Int>) -> Unit,
        onProxyExit: suspend (Result<Int>) -> Unit,
    ): LocalProxyEndpoint {
        val relayQuicMigrationConfig = proxyPreferences.ownedRelayQuicMigrationConfig()
        proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
            upstreamRelaySupervisor.start(relayConfig, relayQuicMigrationConfig, onRelayExit)
        }
        proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
            warpRuntimeSupervisor.start(warpConfig, onWarpExit)
        }
        return proxyRuntimeSupervisor.start(proxyPreferences, onProxyExit)
    }

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        if (skipRuntimeShutdown) {
            detachAll()
            return
        }

        proxyRuntimeSupervisor.stop()
        warpRuntimeSupervisor.stop()
        upstreamRelaySupervisor.stop()
    }

    fun detachAll() {
        upstreamRelaySupervisor.detach()
        warpRuntimeSupervisor.detach()
        proxyRuntimeSupervisor.detach()
    }
}

@Suppress("TooManyFunctions")
internal abstract class BaseServiceRuntimeCoordinator<TSession>(
    private val mode: Mode,
    protected val host: ServiceCoordinatorHost,
    protected val connectionPolicyResolver: ConnectionPolicyResolver,
    protected val serviceRuntimeRegistry: ServiceRuntimeRegistry,
    private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    private val policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    protected val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    protected val clock: ServiceClock = SystemServiceClock,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private companion object {
        private const val HandoverCooldownMs = 10_000L
        private const val MaxHandoverRetries = 4
        private const val HandoverRetryBaseMs = 2_000L
        private const val HandoverRetryMaxMs = 30_000L
    }

    protected val mutex = Mutex()
    protected val lifecycleState = ServiceLifecycleStateMachine()

    @Volatile
    protected var stopping: Boolean = false

    protected var status: ServiceStatus = ServiceStatus.Disconnected
    protected var runtimeSession: TSession? = null
    protected val consumePendingNetworkHandoverClass: () -> String? = {
        runtimeSession?.pendingNetworkHandoverClass?.also {
            runtimeSession?.pendingNetworkHandoverClass = null
        }
    }
    protected val currentNetworkHandoverState: () -> String? = {
        runtimeSession?.networkHandoverState
    }

    private val lifecycleRunner =
        RuntimeLifecycleRunner(
            mutex = mutex,
            lifecycleState = lifecycleState,
            serviceLabel = { serviceLabel },
            isStopping = { stopping },
            setStopping = { stopping = it },
        )
    private val telemetryLoopCoordinator =
        ServiceTelemetryLoopCoordinator(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
        )
    private val permissionWatchdogCoordinator =
        PermissionWatchdogCoordinator(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
            permissionWatchdog = permissionWatchdog,
            onPermissionRevoked = ::onPermissionRevoked,
        )
    private val handoverProcessor =
        NetworkHandoverProcessor(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
            networkHandoverMonitor = networkHandoverMonitor,
            retryPolicy =
                ExponentialHandoverRetryPolicy(
                    maxRetries = MaxHandoverRetries,
                    baseDelayMillis = HandoverRetryBaseMs,
                    maxDelayMillis = HandoverRetryMaxMs,
                ),
            clock = clock,
            serviceLabel = { serviceLabel },
            currentSession = { runtimeSession },
            currentStatus = { status },
            isStopping = { stopping },
            recordPendingClassification = { classification ->
                runtimeSession?.pendingNetworkHandoverClass = classification
            },
            updateHandoverState = { state ->
                runtimeSession?.networkHandoverState = state
            },
            performRestart = ::performHandoverRestart,
            onExhaustedFailure = ::handleExhaustedHandoverFailure,
            handoverCooldownMillis = HandoverCooldownMs,
        )

    protected abstract val serviceLabel: String

    suspend fun start() {
        Logger.i { "Starting $serviceLabel" }

        var matchedRememberedPolicy: RememberedNetworkPolicyEntity? = null
        val session = createRuntimeSession()
        val failure =
            lifecycleRunner.start {
                session.networkHandoverState = null
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
                handoverProcessor.startMonitoring()
                startModeTelemetryUpdates()
                permissionWatchdogCoordinator.start()
            }
                ?: return
        val error = failure as? Exception ?: IllegalStateException("Failed to start $serviceLabel", failure)
        Logger.e(error) { "Failed to start $serviceLabel" }
        matchedRememberedPolicy?.let { policy ->
            rememberedNetworkPolicyStore.recordFailure(policy)
        }
        val failureReason = classifyStartupFailure(error)
        updateStatus(ServiceStatus.Failed, failureReason)
        stop()
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) {
        Logger.i { "Stopping $serviceLabel" }

        val stopped =
            lifecycleRunner.stop {
                handoverProcessor.cancel()
                permissionWatchdogCoordinator.cancel()
                runCatching {
                    stopModeRuntime(skipRuntimeShutdown)
                }.onFailure { failure ->
                    val error =
                        failure as? Exception ?: IllegalStateException(
                            "Failed to stop $serviceLabel",
                            failure,
                        )
                    Logger.e(error) { "Failed to stop $serviceLabel" }
                }

                val session = runtimeSession
                updateStatus(ServiceStatus.Disconnected)
                telemetryLoopCoordinator.cancel()
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
            }

        if (!stopped) {
            return
        }
    }

    fun onDestroy() {
        telemetryLoopCoordinator.cancel()
        handoverProcessor.cancel()
        permissionWatchdogCoordinator.cancel()
    }

    protected fun replaceTelemetryJob(block: suspend CoroutineScope.() -> Unit) {
        telemetryLoopCoordinator.replace(block)
    }

    protected fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val classification = consumePendingNetworkHandoverClass() ?: return snapshot
        return snapshot.copy(networkHandoverClass = classification)
    }

    protected open fun onPermissionRevoked(event: PermissionChangeEvent) = Unit

    private suspend fun performHandoverRestart(
        session: TSession,
        event: NetworkHandoverEvent,
        appliedAt: Long,
    ): String? {
        val currentFingerprint = checkNotNull(event.currentFingerprint)
        val currentFingerprintHash = currentFingerprint.scopeKey()
        val resolution =
            resolveHandoverConnectionPolicy(
                fingerprint = currentFingerprint,
                handoverClassification = event.classification,
            )

        val restartResult =
            mutex.withLock {
                val activeSession = runtimeSession
                if (
                    status != ServiceStatus.Connected ||
                    stopping ||
                    activeSession?.runtimeId != session.runtimeId
                ) {
                    return@withLock null
                }

                val previousFingerprintHash = activeSession.currentActiveConnectionPolicy?.fingerprintHash
                stopping = true
                try {
                    restartAfterHandover(
                        session = activeSession,
                        resolution = resolution,
                        appliedAt = appliedAt,
                    )
                    HandoverRestartResult(
                        previousFingerprintHash = previousFingerprintHash,
                        currentFingerprintHash = currentFingerprintHash,
                    )
                } finally {
                    stopping = false
                }
            } ?: return null

        policyHandoverEventStore.publish(
            PolicyHandoverEvent(
                mode = mode,
                previousFingerprintHash = restartResult.previousFingerprintHash,
                currentFingerprintHash = restartResult.currentFingerprintHash,
                classification = event.classification,
                currentNetworkValidated = currentFingerprint.networkValidated,
                currentCaptivePortalDetected = currentFingerprint.captivePortalDetected,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                policySignature = resolution.policySignature,
                occurredAt = appliedAt,
            ),
        )

        return currentFingerprintHash
    }

    private suspend fun handleExhaustedHandoverFailure(error: Exception) {
        val reason = classifyHandoverFailure(error)
        updateStatus(ServiceStatus.Failed, reason)
        stop()
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

private data class HandoverRestartResult(
    val previousFingerprintHash: String?,
    val currentFingerprintHash: String,
)
