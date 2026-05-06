package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class VpnTunnelRefreshCoordinator(
    private val dependencies: VpnTunnelRefreshDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTunnelRefreshCallbacks,
) {
    suspend fun refreshIfNeeded(session: VpnRuntimeSession) {
        val refreshPlan =
            dependencies.dnsPolicyCoordinator.planRefresh(
                currentSignature = dependencies.vpnTunnelRuntime.currentDnsSignature,
                tunnelRunning = dependencies.vpnTunnelRuntime.isRunning,
            )
        if (!refreshPlan.requiresTunnelRebuild) return
        dependencies.mutex.withLock {
            val activeSession = state.runtimeSession()
            val canRefresh =
                state.status() == ServiceStatus.Connected &&
                    dependencies.vpnTunnelRuntime.isRunning &&
                    activeSession?.runtimeId == session.runtimeId
            if (!canRefresh) return@withLock
            val refreshSession = checkNotNull(activeSession)
            val latestRefreshPlan =
                dependencies.dnsPolicyCoordinator.planRefresh(
                    currentSignature = dependencies.vpnTunnelRuntime.currentDnsSignature,
                    tunnelRunning = dependencies.vpnTunnelRuntime.isRunning,
                )
            if (!latestRefreshPlan.requiresTunnelRebuild) return@withLock
            val latestResolution =
                checkNotNull(latestRefreshPlan.connectionPolicy) {
                    "VPN resolver refresh plan missing connection policy"
                }
            dependencies.vpnTunnelRuntime.stop()
            dependencies.vpnTunnelRuntime.start(
                activeDns = latestResolution.activeDns,
                overrideReason = latestResolution.resolverFallbackReason,
                logContext = refreshSession.buildLogContext(refreshSession.currentActiveConnectionPolicy),
                localProxyEndpoint =
                    checkNotNull(state.currentLocalProxyEndpoint()) {
                        "VPN tunnel refresh requires an active local proxy endpoint"
                    },
            )
            callbacks.updateRuntimeDnsState(refreshSession, latestResolution)
        }
    }

    suspend fun recoverIfNeeded(
        session: VpnRuntimeSession,
        telemetry: VpnTelemetrySnapshot,
    ): Boolean =
        dependencies.dnsPolicyCoordinator.maybeRecoverEncryptedDns(
            session = session,
            currentDnsSignature = dependencies.vpnTunnelRuntime.currentDnsSignature ?: session.currentDnsSignature,
            telemetry = telemetry.tunnelTelemetry,
        )
}

internal interface VpnTunnelRefreshDependencies {
    val mutex: Mutex
    val vpnTunnelRuntime: VpnTunnelRuntime
    val dnsPolicyCoordinator: VpnDnsPolicyCoordinator
}

internal interface VpnTunnelRefreshCallbacks {
    fun updateRuntimeDnsState(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    )
}
