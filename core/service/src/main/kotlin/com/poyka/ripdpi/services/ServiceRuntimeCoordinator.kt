package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    fun prepareDirectDnsUnderlay(
        candidates: List<String>,
        leaseGeneration: Long?,
    ): Long = 0L

    fun finishDirectDnsUnderlay(
        token: Long,
        action: DirectDnsUnderlayAction,
    ): Boolean = false
}

enum class DirectDnsUnderlayAction {
    Commit,
    Abort,
    FailClosed,
}

internal interface HandoverAwareSession {
    var pendingNetworkHandoverClass: String?
    var networkHandoverState: String?
    var lastSuccessfulHandoverFingerprintHash: String?
    var lastSuccessfulHandoverAt: Long
}

/**
 * Abstract per-mode runtime orchestrator — owns runtime state, connection-
 * policy resolution, network-handover handling, and permission gating shared
 * by VPN and proxy modes. Concrete subclasses are `VpnServiceRuntimeCoordinator`
 * and `ProxyServiceRuntimeCoordinator`. See this module's `README.md`,
 * "Runtime / proxy / VPN orchestration".
 */
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
    private val selectorListeners: Set<SelectorRuntimeLifecycleListener> = emptySet(),
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    protected val mutex = Mutex()
    protected val lifecycleState = ServiceLifecycleStateMachine()

    @Volatile
    protected var stopping: Boolean = false

    @Volatile
    protected var handoverRestarting: Boolean = false

    protected var status: ServiceStatus = ServiceStatus.Disconnected
    protected var runtimeSession: TSession? = null

    protected abstract suspend fun reloadResolvedRuntime(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    )

    protected abstract val runtimeHooks: ServiceRuntimeModeHooks<TSession>

    protected val consumePendingNetworkHandoverClass: () -> String? = {
        sessionLifecycle.consumePendingNetworkHandoverClass()
    }
    protected val currentNetworkHandoverState: () -> String? = {
        sessionLifecycle.currentNetworkHandoverState()
    }

    private val sharedState =
        ServiceRuntimeSharedState<TSession>(
            currentSession = { runtimeSession },
            setRuntimeSession = { runtimeSession = it },
            currentStatus = { status },
            isStopping = { stopping },
            setStopping = { stopping = it },
            setHandoverRestarting = { handoverRestarting = it },
        )
    private val sessionLifecycle: ServiceRuntimeSessionLifecycle<TSession> by lazy {
        ServiceRuntimeSessionLifecycle(
            dependencies =
                ServiceRuntimeSessionLifecycleDependencies(
                    mode = mode,
                    host = host,
                    serviceRuntimeRegistry = serviceRuntimeRegistry,
                    rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                    networkHandoverMonitor = networkHandoverMonitor,
                    policyHandoverEventStore = policyHandoverEventStore,
                    permissionWatchdog = permissionWatchdog,
                    ioDispatcher = ioDispatcher,
                    clock = clock,
                    mutex = mutex,
                    lifecycleState = lifecycleState,
                    state = sharedState,
                ),
            hooks =
                ServiceRuntimeModeHooks(
                    serviceLabel = runtimeHooks.serviceLabel,
                    startHooks =
                        ServiceRuntimeStartHooks(
                            createRuntimeSession = {
                                runtimeHooks.startHooks.createRuntimeSession().also { session ->
                                    session.reloadPolicy = { isCurrent -> reloadConnectionPolicy(session, isCurrent) }
                                }
                            },
                            resolveInitialConnectionPolicy = {
                                selectorListeners.forEach { it.prepare() }
                                runtimeHooks.startHooks.resolveInitialConnectionPolicy()
                            },
                            applyActiveConnectionPolicy = runtimeHooks.startHooks.applyActiveConnectionPolicy,
                            startResolvedRuntime = runtimeHooks.startHooks.startResolvedRuntime,
                            evidencePublication = runtimeHooks.startHooks.evidencePublication,
                            startModeTelemetryUpdates = runtimeHooks.startHooks.startModeTelemetryUpdates,
                        ),
                    stopHooks = runtimeHooks.stopHooks,
                    handoverHooks = runtimeHooks.handoverHooks,
                    statusHooks = runtimeHooks.statusHooks,
                    permissionHooks = runtimeHooks.permissionHooks,
                ),
        )
    }

    suspend fun start(stopSelfStartId: Int? = null) {
        sessionLifecycle.start(stopSelfStartId = stopSelfStartId)
        selectorListeners.forEach { it.afterStart() }
    }

    protected suspend fun startTransaction(transaction: RuntimeStartTransaction) {
        sessionLifecycle.start(transaction = transaction)
        selectorListeners.forEach { it.afterStart() }
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
        guard: RuntimeStopGuard? = null,
    ) = sessionLifecycle.stop(
        stopSelfStartId = stopSelfStartId,
        skipRuntimeShutdown = skipRuntimeShutdown,
        guard = guard,
    )

    protected suspend fun failAndStopRuntime(
        failureReason: FailureReason,
        guard: RuntimeStopGuard? = null,
        beforeStopFinalization: suspend () -> Unit,
    ) = sessionLifecycle.failAndStop(
        failureReason = failureReason,
        guard = guard,
        beforeStopFinalization = beforeStopFinalization,
    )

    protected fun monitorNfqws(
        manager: RootHelperManager,
        beforeStopFinalization: suspend () -> Unit = {},
    ) {
        val observedSession = runtimeSession ?: return
        manager.monitorNfqws(host.serviceScope) { error ->
            failAndStopRuntime(
                failureReason = FailureReason.NativeError(error.message ?: "nfqws2 failed"),
                guard = RuntimeStopGuard(isCurrent = { runtimeSession?.runtimeId == observedSession.runtimeId }),
                beforeStopFinalization = beforeStopFinalization,
            )
        }
    }

    /**
     * Replaces a running stack inside its owning session. Stop and handover share this mutex.
     * Cancellation before replacement has no runtime effect; once replacement begins, cleanup
     * completes before cancellation propagates. The VPN hook retains the installed TUN barrier.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reloadConnectionPolicy(
        observedSession: TSession,
        isCurrent: suspend () -> Boolean,
    ): Boolean {
        var cleanupRequired = false
        var failureReason: FailureReason? = null
        try {
            return mutex.withLock {
                val lifecycleReady = status == ServiceStatus.Connected && !stopping && !handoverRestarting
                if (runtimeSession?.runtimeId != observedSession.runtimeId || !lifecycleReady) return@withLock false
                if (!isCurrent()) return@withLock false
                val resolution = runtimeHooks.startHooks.resolveInitialConnectionPolicy()
                if (!isCurrent()) return@withLock false
                handoverRestarting = true
                try {
                    withContext(NonCancellable) {
                        try {
                            withTimeout(SelectorRuntimeReloadTimeoutMillis) {
                                reloadResolvedRuntime(observedSession, resolution, clock.nowMillis())
                            }
                            true
                        } catch (failure: Exception) {
                            failureReason = runtimeHooks.handoverHooks.classifyFailure(failure)
                            cleanupRequired = true
                            cleanupRequired = !runtimeHooks.handoverHooks.retainFailClosedAfterExhaustion()
                            runtimeHooks.statusHooks.updateStatus(ServiceStatus.Failed, failureReason)
                            throw failure
                        }
                    }
                } finally {
                    handoverRestarting = false
                }
            }
        } catch (failure: Exception) {
            if (cleanupRequired) {
                withContext(NonCancellable) {
                    stop(
                        guard =
                            RuntimeStopGuard(
                                isCurrent = { runtimeSession?.runtimeId == observedSession.runtimeId },
                                failureReason = failureReason,
                            ),
                    )
                }
            }
            throw failure
        }
    }

    open fun onDestroy() {
        sessionLifecycle.onDestroy()
    }

    protected fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot =
        sessionLifecycle.applyPendingNetworkHandoverClass(snapshot)
}

private const val SelectorRuntimeReloadTimeoutMillis = 45_000L
