package com.poyka.ripdpi.service.runtime.proxy

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
import com.poyka.ripdpi.services.BaseServiceRuntimeCoordinator
import com.poyka.ripdpi.services.ConnectionPolicyResolution
import com.poyka.ripdpi.services.ConnectionPolicyResolver
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.NoOpDirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.PermissionChangeEvent
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.ProxyRuntimeSession
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.ProxySupervisorExitHandler
import com.poyka.ripdpi.services.ProxyTelemetryCoordinator
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.ScreenStateObserver
import com.poyka.ripdpi.services.ServiceClock
import com.poyka.ripdpi.services.ServiceCoordinatorHost
import com.poyka.ripdpi.services.ServiceRuntimeHandoverHooks
import com.poyka.ripdpi.services.ServiceRuntimeModeHooks
import com.poyka.ripdpi.services.ServiceRuntimePermissionHooks
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceRuntimeStartHooks
import com.poyka.ripdpi.services.ServiceRuntimeStatusHooks
import com.poyka.ripdpi.services.ServiceRuntimeStopHooks
import com.poyka.ripdpi.services.ServiceStatusReporter
import com.poyka.ripdpi.services.SharedProxyRuntimeStack
import com.poyka.ripdpi.services.SystemServiceClock
import com.poyka.ripdpi.services.TelemetryJobReplacer
import com.poyka.ripdpi.services.UpstreamRelaySupervisor
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.buildLogContext
import com.poyka.ripdpi.services.withLogContext
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
    private val rootHelperManager: RootHelperManager = RootHelperManager(),
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

    override val runtimeHooks =
        ServiceRuntimeModeHooks(
            serviceLabel = "proxy",
            startHooks =
                ServiceRuntimeStartHooks(
                    createRuntimeSession = ::createRuntimeSession,
                    resolveInitialConnectionPolicy = ::resolveInitialConnectionPolicy,
                    applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
                    startResolvedRuntime = ::startResolvedRuntime,
                    startModeTelemetryUpdates = ::startModeTelemetryUpdates,
                ),
            stopHooks =
                ServiceRuntimeStopHooks(
                    stopModeRuntime = ::stopModeRuntime,
                ),
            handoverHooks =
                ServiceRuntimeHandoverHooks(
                    resolveConnectionPolicy = ::resolveHandoverConnectionPolicy,
                    restartAfterHandover = ::restartAfterHandover,
                    classifyFailure = ::classifyHandoverFailure,
                ),
            statusHooks =
                ServiceRuntimeStatusHooks(
                    updateStatus = ::updateStatus,
                    classifyStartupFailure = ::classifyStartupFailure,
                ),
            permissionHooks = ServiceRuntimePermissionHooks(::onPermissionRevoked),
        )

    private fun createRuntimeSession(): ProxyRuntimeSession = ProxyRuntimeSession()

    private suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(mode = Mode.Proxy)

    private suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.Proxy,
            fingerprint = fingerprint,
            handoverClassification = handoverClassification,
        )

    private fun applyActiveConnectionPolicy(
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

    private suspend fun startResolvedRuntime(
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

    private suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        try {
            proxyRuntimeStack.stop(skipRuntimeShutdown)
        } finally {
            rootHelperManager.stop()
        }
    }

    private fun startModeTelemetryUpdates(replaceTelemetryJob: TelemetryJobReplacer) {
        telemetryCoordinator.start(replaceTelemetryJob)
    }

    private suspend fun restartAfterHandover(
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

    private fun updateStatus(
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

    private fun onPermissionRevoked(event: PermissionChangeEvent) {
        if (event.kind == PermissionChangeEvent.KIND_NOTIFICATIONS) {
            Logger.i { "Notification permission revoked while proxy running" }
        }
    }

    private fun classifyStartupFailure(error: Exception): FailureReason = classifyFailureReason(error)

    private fun classifyHandoverFailure(error: Exception): FailureReason = classifyFailureReason(error)
}
