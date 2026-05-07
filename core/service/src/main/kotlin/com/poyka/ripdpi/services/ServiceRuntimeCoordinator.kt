package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
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
    protected val mutex = Mutex()
    protected val lifecycleState = ServiceLifecycleStateMachine()

    @Volatile
    protected var stopping: Boolean = false

    protected var status: ServiceStatus = ServiceStatus.Disconnected
    protected var runtimeSession: TSession? = null

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
            hooks = runtimeHooks,
        )
    }

    suspend fun start() = sessionLifecycle.start()

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) = sessionLifecycle.stop(
        stopSelfStartId = stopSelfStartId,
        skipRuntimeShutdown = skipRuntimeShutdown,
    )

    fun onDestroy() {
        sessionLifecycle.onDestroy()
    }

    protected fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot =
        sessionLifecycle.applyPendingNetworkHandoverClass(snapshot)
}
