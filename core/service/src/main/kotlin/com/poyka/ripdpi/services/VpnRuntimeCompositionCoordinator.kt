package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.core.isUdpAssociateEnabled
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private val packetBackendRuntime: PacketBackendRuntime,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    private val supervisorExitHandlerFor: (VpnRuntimeSession) -> VpnSupervisorExitHandler,
    private val applyActiveConnectionPolicy: suspend (
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
    private val providerController: XrayProviderSessionController? = null,
    private val initialRelayRacePolicy: InitialRelayRacePolicy? = null,
) {
    private var appliedNativeProxy: AppliedNativeVpnProxy? = null

    var currentLocalProxyEndpoint: LocalProxyEndpoint? = null
        private set

    val isStopped: Boolean
        get() = !proxyRuntimeStack.hasOwnedRuntime && !vpnTunnelRuntime.isRunning && !vpnTunnelRuntime.isForwarding

    suspend fun retainVpnBarrier(): Boolean =
        withContext(NonCancellable) {
            runCatching { vpnTunnelRuntime.retainFailClosedBarrier() }.getOrDefault(false)
        }

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
    ): RuntimeStartEvidence = startComposedRuntime(session, resolution)

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        try {
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
                if (failure is RuntimeCleanupPendingException && previousFailure !is RuntimeCleanupPendingException) {
                    previousFailure?.let(failure::addSuppressed)
                    stopFailure = failure
                } else if (previousFailure == null) {
                    stopFailure = failure
                } else {
                    previousFailure.addSuppressed(failure)
                }
            }
            stopFailure?.let { failure ->
                val error = failure as? Exception ?: IllegalStateException("Failed to stop VPN runtime", failure)
                throw error
            }
        } finally {
            packetBackendRuntime.stopFor(Mode.VPN)
        }
    }

    suspend fun restartAfterHandover(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) = restartAfterPolicyChange(session, resolution, appliedAt, "network_handover")

    /**
     * Leaves the installed native TUN as a blocking barrier after handover
     * retries are exhausted. The runtime remains registered so an explicit
     * user stop can close the barrier deliberately.
     */
    suspend fun retainFailClosedAfterHandoverFailure(): Boolean {
        if (!vpnTunnelRuntime.isRunning) return false
        val retention =
            runCatching { vpnTunnelRuntime.retainFailClosedBarrier() }
                .recoverCatching { error ->
                    Logger.w(error, tag = LogTags.SERVICE) { "Retrying shutdown of VPN forwarding" }
                    vpnTunnelRuntime.retainFailClosedBarrier()
                }
        if (providerDelegate?.ownsActiveProviderPath != true && providerController?.isActive != true) {
            runCatching { proxyRuntimeStack.stop(skipRuntimeShutdown = false) }
        }
        currentLocalProxyEndpoint = null
        return retention.getOrElse { error ->
            Logger.e(error, tag = LogTags.SERVICE) { "VPN forwarding cleanup is incomplete; keeping the installed TUN" }
            vpnTunnelRuntime.isRunning
        }
    }

    suspend fun restartAfterPolicyChange(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
        restartReason: String,
    ): RuntimeStartEvidence {
        providerDelegate?.tryRestart(session, resolution, appliedAt, restartReason)?.let { evidence ->
            appliedNativeProxy = null
            currentLocalProxyEndpoint = providerDelegate.currentLocalProxyEndpoint
            return evidence
        }
        val reusable = reusableNativeProxy(resolution, restartReason)
        // Claim the runtime command before changing the stack or its published policy.
        applyActiveConnectionPolicy(session, resolution, restartReason, appliedAt)
        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        val proxyStartResult =
            reusable ?: run {
                session.currentDns = null
                session.currentDnsSignature = null
                session.currentNetworkScopeKey = null
                session.encryptedDnsFailoverState.resetAll()
                session.revokeInPathLease()
                // The installed TUN remains a barrier while the old proxy stops.
                proxyRuntimeStack.stop(skipRuntimeShutdown = false)
                currentLocalProxyEndpoint = null
                startProxyRuntime(session, resolution, logContext)
            }
        if (reusable == null || vpnTunnelRuntime.requiresHandoverRefresh(session, resolution)) {
            session.suspendInPathLease()
            rebuildNativeTunnel(resolution, logContext, proxyStartResult)
            vpnTunnelRuntime.publishInPathLease(session, proxyStartResult.endpoint)
        }
        return publishNativeEvidence(session, resolution, proxyStartResult)
    }

    private fun reusableNativeProxy(
        resolution: ConnectionPolicyResolution,
        restartReason: String,
    ): ProxyRuntimeStartResult? {
        if (restartReason != "network_handover" || !vpnTunnelRuntime.isForwarding || !proxyRuntimeStack.hasLiveProxy) {
            return null
        }
        return appliedNativeProxy
            ?.takeIf { it.start.endpoint == currentLocalProxyEndpoint && it.matches(resolution) }
            ?.start
    }

    private suspend fun rebuildNativeTunnel(
        resolution: ConnectionPolicyResolution,
        logContext: RipDpiLogContext,
        proxyStartResult: ProxyRuntimeStartResult,
    ) {
        vpnTunnelRuntime.rebuild(
            configurationInput = resolution.requestedConfiguration.tunnelInput,
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
            localProxyEndpoint = proxyStartResult.endpoint,
            sharedProxyPath = proxyStartResult.consumedUpstreams.isNotEmpty(),
            forceTunnelDns =
                resolution.proxyPreferences
                    .awgConfigOrNull()
                    ?.dnsServers
                    ?.isEmpty()
                    ?: (
                        forceTunnelDnsForRelay(resolution.settings) &&
                            resolution.proxyPreferences.relayConfigOrNull() != null
                    ),
            splitStrictDnsPolicy = resolution.splitStrictDnsPolicy,
            profileInterface = resolution.proxyPreferences.awgConfigOrNull()?.vpnProfileInterface(),
        )
    }

    private fun publishNativeEvidence(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        proxyStartResult: ProxyRuntimeStartResult,
    ): RuntimeStartEvidence {
        val evidence = readyNativeVpnEvidence(proxyStartResult, vpnTunnelRuntime.requireReadyEvidence())
        appliedNativeProxy = AppliedNativeVpnProxy(resolution, proxyStartResult)
        updateRuntimeDnsState(session, resolution)
        return evidence
    }

    fun updateRuntimeDnsState(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        session.currentDns = resolution.activeDns
        session.currentDnsSignature = resolution.vpnDnsSignature()
        session.currentNetworkScopeKey = resolution.networkScopeKey
        session.recordDestinationPolicy(resolution)
    }

    fun resetAfterStop(session: VpnRuntimeSession?) {
        appliedNativeProxy = null
        vpnTunnelRuntime.resetRuntimeState()
        currentLocalProxyEndpoint = null
        providerDelegate?.reset()
        session?.encryptedDnsFailoverState?.resetAll()
        session?.revokeInPathLease()
        session?.clearDestinationPolicy()
    }

    private suspend fun startComposedRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ): RuntimeStartEvidence {
        // PROVIDER SEAM: let the delegate take over only when it reports it
        // handled the start. Everything below this guard is the native
        // composition, unchanged.
        providerDelegate?.tryStart(session, resolution)?.let { evidence ->
            appliedNativeProxy = null
            currentLocalProxyEndpoint = providerDelegate.currentLocalProxyEndpoint
            return evidence
        }

        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        val proxyStartResult = startProxyRuntime(session, resolution, logContext, verifyRelayEgress = true)
        vpnTunnelRuntime.start(
            configurationInput = resolution.requestedConfiguration.tunnelInput,
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
            localProxyEndpoint = proxyStartResult.endpoint,
            sharedProxyPath = proxyStartResult.consumedUpstreams.isNotEmpty(),
            forceTunnelDns =
                resolution.proxyPreferences
                    .awgConfigOrNull()
                    ?.dnsServers
                    ?.isEmpty()
                    ?: (
                        forceTunnelDnsForRelay(resolution.settings) &&
                            resolution.proxyPreferences.relayConfigOrNull() != null
                    ),
            splitStrictDnsPolicy = resolution.splitStrictDnsPolicy,
            profileInterface = resolution.proxyPreferences.awgConfigOrNull()?.vpnProfileInterface(),
        )
        vpnTunnelRuntime.publishInPathLease(session, proxyStartResult.endpoint)
        return publishNativeEvidence(session, resolution, proxyStartResult)
    }

    private suspend fun startProxyRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        logContext: RipDpiLogContext,
        verifyRelayEgress: Boolean = false,
    ): ProxyRuntimeStartResult {
        val exits = supervisorExitHandlerFor(session)
        val authToken =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
        val configuredAwg = resolution.proxyPreferences.awgConfigOrNull()
        val configuredRelay = resolution.proxyPreferences.relayConfigOrNull().takeIf { configuredAwg == null }
        val requirements =
            EgressRequirements(
                tcpConnect = true,
                udpAssociate = resolution.proxyPreferences.isUdpAssociateEnabled(),
            )
        val racePlan =
            initialRelayRacePolicy?.plan(
                configuredRelayProfileId = configuredRelay?.profileId ?: configuredAwg?.profileId,
                configuredRelayKind = configuredRelay?.kind ?: configuredAwg?.let { AmneziaWgEgressKind },
                networkScopeKey = resolution.networkScopeKey,
                requirements = requirements,
            )
        return proxyRuntimeStack
            .start(
                requestedWarpReference = resolution.requestedConfiguration.warpReference,
                networkScopeKey = resolution.networkScopeKey,
                relayInputs = resolution.requestedConfiguration.relayInputs,
                requestedSelection = resolution.requestedConfiguration.selection,
                proxyPreferences =
                    resolution
                        .proxyPreferences
                        .withLogContext(logContext)
                        .withSessionLocalProxyOverrides(listenPortOverride = 0, authToken = authToken),
                onRelayExit = exits::handleRelayExit,
                onWarpExit = exits::handleWarpExit,
                onAwgExit = exits::handleAwgExit,
                onProxyExit = exits::handleProxyExit,
                initialRelayRacePlan = racePlan,
                onInitialRelayRaceState = { state -> initialRelayRacePolicy?.onStateChanged(state) },
                onInitialRelaySelected = { result -> initialRelayRacePolicy?.onSelected(result) },
                verifyRelayEgress = verifyRelayEgress,
            ).also { currentLocalProxyEndpoint = it.endpoint }
    }
}
