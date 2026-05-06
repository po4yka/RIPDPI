package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal data class ProxyRuntimeSupervisorBundle(
    val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
)

internal class ProxyServiceRuntimeCoordinator(
    host: ServiceCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    supervisors: ProxyRuntimeSupervisorBundle,
    private val statusReporter: ServiceStatusReporter,
    private val screenStateObserver: ScreenStateObserver,
    private val directPathPolicyTelemetryConsumer:
        DirectPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: ServiceClock = SystemServiceClock,
) : BaseServiceRuntimeCoordinator<ProxyRuntimeSession>(
        mode = Mode.Proxy,
        host = host,
        connectionPolicyResolver = connectionPolicyResolver,
        serviceRuntimeRegistry = serviceRuntimeRegistry,
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        networkHandoverMonitor = networkHandoverMonitor,
        policyHandoverEventStore = policyHandoverEventStore,
        permissionWatchdog = permissionWatchdog,
        ioDispatcher = ioDispatcher,
        clock = clock,
    ) {
    private val upstreamRelaySupervisor = supervisors.upstreamRelaySupervisor
    private val warpRuntimeSupervisor = supervisors.warpRuntimeSupervisor
    private val proxyRuntimeSupervisor = supervisors.proxyRuntimeSupervisor

    private val proxyRuntimeStack =
        SharedProxyRuntimeStack(
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
        )
    private val supervisorExitHandler =
        ProxySupervisorExitHandler(
            host = host,
            ioDispatcher = ioDispatcher,
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
            updateStatus = ::updateStatus,
            stopService = { skipRuntimeShutdown -> stop(skipRuntimeShutdown = skipRuntimeShutdown) },
        )
    private val telemetryCoordinator =
        ProxyTelemetryCoordinator(
            host = host,
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
            statusReporter = statusReporter,
            screenStateObserver = screenStateObserver,
            directPathPolicyTelemetryConsumer = directPathPolicyTelemetryConsumer,
            currentStatus = { status },
            currentSession = { runtimeSession },
            consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
            currentNetworkHandoverState = currentNetworkHandoverState,
        )

    override val serviceLabel: String = "proxy"

    override fun createRuntimeSession(): ProxyRuntimeSession = ProxyRuntimeSession()

    override suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(mode = Mode.Proxy)

    override suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.Proxy,
            fingerprint = fingerprint,
            handoverClassification = handoverClassification,
        )

    override fun applyActiveConnectionPolicy(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        val policy =
            resolution.appliedPolicy ?: run {
                session.clearActiveConnectionPolicy()
                return
            }
        session.updateActiveConnectionPolicy(
            ActiveConnectionPolicy(
                mode = Mode.Proxy,
                policy = policy,
                matchedPolicy = resolution.matchedNetworkPolicy,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                rememberedPolicyAppliedByExactMatch = resolution.rememberedPolicyAppliedByExactMatch,
                fingerprintHash = resolution.fingerprintHash,
                policySignature = resolution.policySignature,
                appliedAt = appliedAt,
                restartReason = restartReason,
                handoverClassification = resolution.handoverClassification,
            ),
        )
    }

    override suspend fun startResolvedRuntime(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        proxyRuntimeStack.start(
            proxyPreferences =
                resolution.proxyPreferences.withLogContext(
                    session.buildLogContext(session.currentActiveConnectionPolicy),
                ),
            onRelayExit = supervisorExitHandler::handleRelayExit,
            onWarpExit = supervisorExitHandler::handleWarpExit,
            onProxyExit = supervisorExitHandler::handleProxyExit,
        )
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        proxyRuntimeStack.stop(skipRuntimeShutdown)
    }

    override fun startModeTelemetryUpdates() {
        telemetryCoordinator.start(::replaceTelemetryJob)
    }

    override suspend fun restartAfterHandover(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        proxyRuntimeStack.stop(skipRuntimeShutdown = false)
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        proxyRuntimeStack.start(
            proxyPreferences =
                resolution.proxyPreferences.withLogContext(
                    session.buildLogContext(session.currentActiveConnectionPolicy),
                ),
            onRelayExit = supervisorExitHandler::handleRelayExit,
            onWarpExit = supervisorExitHandler::handleWarpExit,
            onProxyExit = supervisorExitHandler::handleProxyExit,
        )
    }

    override fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        Logger.d { "Proxy status: $status -> $newStatus" }
        status = newStatus
        statusReporter.reportStatus(
            newStatus = newStatus,
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
            currentNetworkHandoverState = currentNetworkHandoverState,
            tunnelRecoveryRetryCount = 0,
            failureReason = failureReason,
        )
    }

    override fun onPermissionRevoked(event: PermissionChangeEvent) {
        if (event.kind == PermissionChangeEvent.KIND_NOTIFICATIONS) {
            Logger.i { "Notification permission revoked while proxy running" }
        }
    }

    override fun classifyStartupFailure(error: Exception): FailureReason = classifyFailureReason(error)

    override fun classifyHandoverFailure(error: Exception): FailureReason = classifyFailureReason(error)
}
