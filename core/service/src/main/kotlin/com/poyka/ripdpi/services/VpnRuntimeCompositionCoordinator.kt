package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.relayConfigOrNull
import java.util.UUID

/**
 * Composes a VPN runtime — starts and stops the shared proxy runtime stack and
 * the VPN tunnel together as one unit, and applies the active connection
 * policy. Driven by `VpnServiceRuntimeCoordinator`.
 */
internal class VpnRuntimeCompositionCoordinator(
    private val proxyRuntimeStack: SharedProxyRuntimeStack,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    private val supervisorExitHandler: VpnSupervisorExitHandler,
    private val applyActiveConnectionPolicy: (
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) -> Unit,
) {
    var currentLocalProxyEndpoint: LocalProxyEndpoint? = null
        private set

    suspend fun start(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        startComposedRuntime(session, resolution)
    }

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        var stopFailure: Throwable? = null
        runCatching {
            vpnTunnelRuntime.stop()
        }.onFailure { failure ->
            stopFailure = failure
        }
        runCatching {
            proxyRuntimeStack.stop(skipRuntimeShutdown)
        }.onFailure { failure ->
            val previousFailure = stopFailure
            if (previousFailure == null) {
                stopFailure = failure
            } else {
                previousFailure.addSuppressed(failure)
            }
        }
        stopFailure?.let { failure ->
            val error = failure as? Exception ?: IllegalStateException("Failed to stop VPN runtime", failure)
            throw error
        }
    }

    suspend fun restartAfterHandover(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        session.currentDns = null
        session.currentDnsSignature = null
        session.currentNetworkScopeKey = null
        session.encryptedDnsFailoverState.resetAll()
        vpnTunnelRuntime.stop()
        proxyRuntimeStack.stop(skipRuntimeShutdown = false)
        applyActiveConnectionPolicy(
            session,
            resolution,
            "network_handover",
            appliedAt,
        )
        startComposedRuntime(session, resolution)
    }

    fun updateRuntimeDnsState(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        session.currentDns = resolution.activeDns
        session.currentDnsSignature = dnsSignature(resolution.activeDns, resolution.resolverFallbackReason)
        session.currentNetworkScopeKey = resolution.networkScopeKey
    }

    fun resetAfterStop(session: VpnRuntimeSession?) {
        vpnTunnelRuntime.resetRuntimeState()
        currentLocalProxyEndpoint = null
        session?.encryptedDnsFailoverState?.resetAll()
    }

    private suspend fun startComposedRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        val authToken =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
        val localProxyEndpoint =
            proxyRuntimeStack.start(
                proxyPreferences =
                    resolution
                        .proxyPreferences
                        .withLogContext(logContext)
                        .withSessionLocalProxyOverrides(listenPortOverride = 0, authToken = authToken),
                onRelayExit = supervisorExitHandler::handleRelayExit,
                onWarpExit = supervisorExitHandler::handleWarpExit,
                onProxyExit = supervisorExitHandler::handleProxyExit,
            )
        currentLocalProxyEndpoint = localProxyEndpoint
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
            localProxyEndpoint = localProxyEndpoint,
            forceTunnelDns = resolution.proxyPreferences.relayConfigOrNull() != null,
        )
        updateRuntimeDnsState(session, resolution)
    }
}
