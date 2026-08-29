package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.awgConfigOrNull
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
            val latestRefreshPlan = planRefreshOrFail(refreshSession) ?: return@withLock
            val latestConnectionPolicy = checkNotNull(latestRefreshPlan.connectionPolicy)
            if (latestRefreshPlan.requiresRuntimeRecompose) {
                try {
                    callbacks.recomposeRuntimeForPolicyChange(refreshSession, latestConnectionPolicy)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failCurrentGeneration(refreshSession, error)
                }
                return@withLock
            }
            if (!latestRefreshPlan.requiresTunnelRebuild &&
                (!interfacePolicyChangeObserved || !dependencies.vpnTunnelRuntime.requiresInterfacePolicyRebuild())
            ) {
                return@withLock
            }
            try {
                refreshSession.revokeInPathLease()
                val endpoint =
                    checkNotNull(state.currentLocalProxyEndpoint()) {
                        "VPN tunnel refresh requires an active local proxy endpoint"
                    }
                dependencies.vpnTunnelRuntime.rebuild(
                    activeDns = latestConnectionPolicy.activeDns,
                    overrideReason = latestConnectionPolicy.resolverFallbackReason,
                    logContext = refreshSession.buildLogContext(refreshSession.currentActiveConnectionPolicy),
                    localProxyEndpoint = endpoint,
                    splitStrictDnsPolicy = latestConnectionPolicy.splitStrictDnsPolicy,
                    profileInterface = latestConnectionPolicy.proxyPreferences.awgConfigOrNull()?.vpnProfileInterface(),
                    forceTunnelDns =
                        latestConnectionPolicy.proxyPreferences
                            .awgConfigOrNull()
                            ?.dnsServers
                            ?.isEmpty() == true,
                )
                dependencies.vpnTunnelRuntime.publishInPathLease(refreshSession, endpoint)
                callbacks.updateRuntimeDnsState(refreshSession, latestConnectionPolicy)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failCurrentGeneration(refreshSession, error)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun planRefreshOrFail(session: VpnRuntimeSession): ResolverRefreshPlan? =
        try {
            dependencies.dnsPolicyCoordinator
                .planRefresh(
                    currentSignature = dependencies.vpnTunnelRuntime.currentDnsSignature,
                    currentDestinationRoutingDigest = session.currentDestinationRoutingDigest,
                    tunnelRunning = dependencies.vpnTunnelRuntime.isRunning,
                ).also { checkNotNull(it.connectionPolicy) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failCurrentGeneration(session, error)
            null
        }

    private suspend fun failCurrentGeneration(
        session: VpnRuntimeSession,
        error: Exception,
    ) {
        val isCurrentRuntimeGeneration =
            state.runtimeSession()?.runtimeId == session.runtimeId &&
                state.status() == ServiceStatus.Connected
        if (isCurrentRuntimeGeneration) callbacks.failTunnelRefresh(session, error)
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
    suspend fun recomposeRuntimeForPolicyChange(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    )

    fun updateRuntimeDnsState(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    )

    suspend fun failTunnelRefresh(
        session: VpnRuntimeSession,
        error: Exception,
    )
}
