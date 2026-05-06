package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex

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
    private val loopOwner =
        ServiceRuntimeLoopOwner(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
            permissionWatchdog = permissionWatchdog,
            onPermissionRevoked = ::onPermissionRevoked,
        )
    private val handoverRestarter =
        ServiceRuntimeHandoverRestarter(
            mode = mode,
            mutex = mutex,
            policyHandoverEventStore = policyHandoverEventStore,
            currentSession = { runtimeSession },
            currentStatus = { status },
            isStopping = { stopping },
            setStopping = { stopping = it },
            resolveConnectionPolicy = ::resolveHandoverConnectionPolicy,
            restartAfterHandover = ::restartAfterHandover,
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
            performRestart = handoverRestarter::restart,
            onExhaustedFailure = ::handleExhaustedHandoverFailure,
            handoverCooldownMillis = HandoverCooldownMs,
        )
    private val startStopOrchestrator =
        ServiceRuntimeStartStopOrchestrator(
            dependencies =
                ServiceRuntimeStartStopDependencies(
                    mode = mode,
                    serviceLabel = { serviceLabel },
                    lifecycleRunner = lifecycleRunner,
                    serviceRuntimeRegistry = serviceRuntimeRegistry,
                    rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                    loopOwner = loopOwner,
                    handoverProcessor = handoverProcessor,
                    clock = clock,
                    host = host,
                ),
            callbacks =
                ServiceRuntimeStartStopCallbacks(
                    currentSession = { runtimeSession },
                    setRuntimeSession = { runtimeSession = it },
                    createRuntimeSession = ::createRuntimeSession,
                    resolveInitialConnectionPolicy = ::resolveInitialConnectionPolicy,
                    applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
                    startResolvedRuntime = ::startResolvedRuntime,
                    stopModeRuntime = ::stopModeRuntime,
                    startModeTelemetryUpdates = ::startModeTelemetryUpdates,
                    onAfterStopCleanup = ::onAfterStopCleanup,
                    updateStatus = ::updateStatus,
                    classifyStartupFailure = ::classifyStartupFailure,
                ),
        )

    protected abstract val serviceLabel: String

    suspend fun start() = startStopOrchestrator.start()

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) = startStopOrchestrator.stop(
        stopSelfStartId = stopSelfStartId,
        skipRuntimeShutdown = skipRuntimeShutdown,
    )

    fun onDestroy() {
        loopOwner.cancelTelemetry()
        handoverProcessor.cancel()
        loopOwner.cancelPermissionWatchdog()
    }

    protected fun replaceTelemetryJob(block: suspend CoroutineScope.() -> Unit) {
        loopOwner.replaceTelemetryJob(block)
    }

    protected fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val classification = consumePendingNetworkHandoverClass() ?: return snapshot
        return snapshot.copy(networkHandoverClass = classification)
    }

    protected open fun onPermissionRevoked(event: PermissionChangeEvent) = Unit

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
