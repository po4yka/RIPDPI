package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class VpnTunnelRefreshCoordinator(
    private val dependencies: VpnTunnelRefreshDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTunnelRefreshCallbacks,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun refreshIfNeeded(
        session: VpnRuntimeSession,
        interfacePolicyChangeObserved: Boolean = false,
    ) {
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
            if (!latestRefreshPlan.requiresTunnelRebuild &&
                (!interfacePolicyChangeObserved || !dependencies.vpnTunnelRuntime.requiresInterfacePolicyRebuild())
            ) {
                return@withLock
            }
            val latestConnectionPolicy = checkNotNull(latestRefreshPlan.connectionPolicy)
            try {
                dependencies.vpnTunnelRuntime.rebuild(
                    activeDns = latestConnectionPolicy.activeDns,
                    overrideReason = latestConnectionPolicy.resolverFallbackReason,
                    logContext = refreshSession.buildLogContext(refreshSession.currentActiveConnectionPolicy),
                    localProxyEndpoint =
                        checkNotNull(state.currentLocalProxyEndpoint()) {
                            "VPN tunnel refresh requires an active local proxy endpoint"
                        },
                )
                callbacks.updateRuntimeDnsState(refreshSession, latestConnectionPolicy)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val isCurrentRuntimeGeneration =
                    state.runtimeSession()?.runtimeId == refreshSession.runtimeId &&
                        state.status() == ServiceStatus.Connected
                if (isCurrentRuntimeGeneration) {
                    callbacks.failTunnelRefresh(refreshSession, error)
                }
            }
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

    fun failTunnelRefresh(
        session: VpnRuntimeSession,
        error: Exception,
    )
}
