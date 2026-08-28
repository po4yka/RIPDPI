package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

internal class ServiceRuntimeSessionLifecycle<TSession>(
    private val dependencies: ServiceRuntimeSessionLifecycleDependencies<TSession>,
    private val hooks: ServiceRuntimeModeHooks<TSession>,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private var stopAfterHandoverFailure: (suspend () -> Unit)? = null

    private val lifecycleRunner =
        RuntimeLifecycleRunner(
            mutex = dependencies.mutex,
            lifecycleState = dependencies.lifecycleState,
            serviceLabel = { hooks.serviceLabel },
            isStopping = dependencies.state.isStopping,
            setStopping = dependencies.state.setStopping,
        )
    private val loopOwner =
        ServiceRuntimeLoopOwner(
            scope = dependencies.host.serviceScope,
            dispatcher = dependencies.ioDispatcher,
            permissionWatchdog = dependencies.permissionWatchdog,
            onPermissionRevoked = hooks.permissionHooks.onPermissionRevoked,
        )
    private val handoverCoordinator =
        ServiceRuntimeHandoverCoordinator(
            dependencies =
                ServiceRuntimeHandoverDependencies(
                    mode = dependencies.mode,
                    scope = dependencies.host.serviceScope,
                    dispatcher = dependencies.ioDispatcher,
                    mutex = dependencies.mutex,
                    networkHandoverMonitor = dependencies.networkHandoverMonitor,
                    policyHandoverEventStore = dependencies.policyHandoverEventStore,
                    clock = dependencies.clock,
                    state = dependencies.state,
                ),
            hooks = hooks,
            stopService = { stopAfterHandoverFailure?.invoke() },
        )
    private val startStopOrchestrator =
        ServiceRuntimeStartStopOrchestrator(
            dependencies =
                ServiceRuntimeStartStopDependencies(
                    mode = dependencies.mode,
                    serviceLabel = { hooks.serviceLabel },
                    lifecycleRunner = lifecycleRunner,
                    serviceRuntimeRegistry = dependencies.serviceRuntimeRegistry,
                    rememberedNetworkPolicyStore = dependencies.rememberedNetworkPolicyStore,
                    loopOwner = loopOwner,
                    handoverProcessor = handoverCoordinator.processor,
                    clock = dependencies.clock,
                    host = dependencies.host,
                ),
            callbacks =
                ServiceRuntimeStartStopCallbacks(
                    currentStatus = dependencies.state.currentStatus,
                    currentSession = dependencies.state.currentSession,
                    setRuntimeSession = dependencies.state.setRuntimeSession,
                    createRuntimeSession = hooks.startHooks.createRuntimeSession,
                    resolveInitialConnectionPolicy = hooks.startHooks.resolveInitialConnectionPolicy,
                    applyActiveConnectionPolicy = hooks.startHooks.applyActiveConnectionPolicy,
                    startResolvedRuntime = hooks.startHooks.startResolvedRuntime,
                    publishRuntimeStartEvidence = hooks.startHooks.publishRuntimeStartEvidence,
                    stopModeRuntime = hooks.stopHooks.stopModeRuntime,
                    captureFinalTelemetry = hooks.stopHooks.captureFinalTelemetry,
                    startModeTelemetryUpdates = {
                        hooks.startHooks.startModeTelemetryUpdates(::replaceTelemetryJob)
                    },
                    onAfterStopCleanup = hooks.stopHooks.onAfterStopCleanup,
                    updateStatus = hooks.statusHooks.updateStatus,
                    classifyStartupFailure = hooks.statusHooks.classifyStartupFailure,
                ),
        )

    init {
        stopAfterHandoverFailure = {
            startStopOrchestrator.stop()
        }
    }

    suspend fun start(
        stopSelfStartId: Int? = null,
        transaction: RuntimeStartTransaction? = null,
    ) = startStopOrchestrator.start(stopSelfStartId = stopSelfStartId, transaction = transaction)

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
        guard: RuntimeStopGuard? = null,
    ) = startStopOrchestrator.stop(
        stopSelfStartId = stopSelfStartId,
        skipRuntimeShutdown = skipRuntimeShutdown,
        guard = guard,
    )

    fun onDestroy() {
        loopOwner.cancelTelemetry()
        handoverCoordinator.cancel()
        loopOwner.cancelPermissionWatchdog()
    }

    fun replaceTelemetryJob(block: suspend CoroutineScope.() -> Unit) {
        loopOwner.replaceTelemetryJob(block)
    }

    fun consumePendingNetworkHandoverClass(): String? = handoverCoordinator.consumePendingNetworkHandoverClass()

    fun currentNetworkHandoverState(): String? = handoverCoordinator.currentNetworkHandoverState()

    fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot =
        handoverCoordinator.applyPendingNetworkHandoverClass(snapshot)
}

@Suppress("LongParameterList")
internal class ServiceRuntimeSessionLifecycleDependencies<TSession>(
    val mode: Mode,
    val host: ServiceCoordinatorHost,
    val serviceRuntimeRegistry: ServiceRuntimeRegistry,
    val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    val networkHandoverMonitor: NetworkHandoverMonitor,
    val policyHandoverEventStore: PolicyHandoverEventStore,
    val permissionWatchdog: PermissionWatchdog,
    val ioDispatcher: CoroutineDispatcher,
    val clock: ServiceClock,
    val mutex: Mutex,
    val lifecycleState: ServiceLifecycleStateMachine,
    val state: ServiceRuntimeSharedState<TSession>,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession
