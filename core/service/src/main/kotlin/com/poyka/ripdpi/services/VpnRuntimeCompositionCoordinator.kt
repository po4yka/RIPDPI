package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.relayConfigOrNull
import java.util.UUID

/**
 * Composes a VPN runtime — starts and stops the shared proxy runtime stack and
 * the VPN tunnel together as one unit, and applies the active connection
 * policy. Driven by `VpnServiceRuntimeCoordinator`.
 *
 * ### Provider delegation
 * When [providerDelegate] is non-null it is consulted FIRST at start / stop /
 * handover. If it reports it handled the call, the coordinator returns; only
 * when it declines does the EXISTING native composition below run
 * BYTE-IDENTICAL. A default install (no provider selected) always declines, so
 * the native path is unchanged. All provider-specific logic lives behind the
 * delegate — this coordinator only owns the native composition.
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
    /**
     * Optional embedded provider seam. Null in builds / sessions where no
     * alternate provider is wired; when present it is consulted at start / stop /
     * handover and only takes over when it reports it handled the call.
     */
    providerController: XrayProviderSessionController? = null,
    private val initialRelayRacePolicy: InitialRelayRacePolicy? = null,
) {
    var currentLocalProxyEndpoint: LocalProxyEndpoint? = null
        private set

    private val providerDelegate: XrayConnectFlowDelegate? =
        providerController?.let { controller ->
            XrayConnectFlowDelegate(
                controller = controller,
                publishActiveDnsState = ::updateRuntimeDnsState,
                applyActiveConnectionPolicy = applyActiveConnectionPolicy,
            )
        }

    suspend fun start(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        startComposedRuntime(session, resolution)
    }

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        if (providerDelegate?.tryStop() == true) {
            currentLocalProxyEndpoint = null
            return
        }
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
        if (providerDelegate?.tryRestart(session, resolution, appliedAt) == true) {
            return
        }
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
        providerDelegate?.reset()
        session?.encryptedDnsFailoverState?.resetAll()
    }

    private suspend fun startComposedRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        // PROVIDER SEAM: let the delegate take over only when it reports it
        // handled the start. Everything below this guard is the native
        // composition, unchanged.
        if (providerDelegate?.tryStart(session, resolution) == true) {
            return
        }

        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        val authToken =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
        val configuredRelay = resolution.proxyPreferences.relayConfigOrNull()
        val racePlan =
            initialRelayRacePolicy?.plan(
                configuredRelayProfileId = configuredRelay?.profileId,
                configuredRelayKind = configuredRelay?.kind,
                networkScopeKey = resolution.networkScopeKey,
            )
        val localProxyEndpoint =
            proxyRuntimeStack.start(
                proxyPreferences =
                    resolution
                        .proxyPreferences
                        .withLogContext(logContext)
                        .withSessionLocalProxyOverrides(listenPortOverride = 0, authToken = authToken),
                onRelayExit = supervisorExitHandler::handleRelayExit,
                onWarpExit = supervisorExitHandler::handleWarpExit,
                onAwgExit = supervisorExitHandler::handleAwgExit,
                onProxyExit = supervisorExitHandler::handleProxyExit,
                initialRelayRacePlan = racePlan,
                onInitialRelayRaceState = { state -> initialRelayRacePolicy?.onStateChanged(state) },
                onInitialRelaySelected = { result -> initialRelayRacePolicy?.onSelected(result) },
            )
        currentLocalProxyEndpoint = localProxyEndpoint
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
            localProxyEndpoint = localProxyEndpoint,
            forceTunnelDns =
                forceTunnelDnsForRelay(resolution.settings) && resolution.proxyPreferences.relayConfigOrNull() != null,
        )
        updateRuntimeDnsState(session, resolution)
    }
}
