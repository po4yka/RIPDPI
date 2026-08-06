package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.core.isUdpAssociateEnabled
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.core.withAwgEgressPort
import com.poyka.ripdpi.core.withRelayRuntimeSelection
import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import com.poyka.ripdpi.service.awg.AmneziaWgLocalSocksPort

/**
 * Composes the upstream relay, WireGuard egress, and proxy into a running stack.
 *
 * The two WireGuard egress transports are mutually exclusive; when an AWG request
 * is present ([awgConfigOrNull]) it takes precedence and its egress port is wired
 * into the proxy upstream via [withAwgEgressPort]. Otherwise the existing path
 * applies unchanged. Precedence is enforced here so the runtime is safe even if
 * the UI layer permits a contradictory config.
 */
internal class SharedProxyRuntimeStack(
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val awgEgressReadinessVerifier: AwgEgressReadinessVerifier =
        AwgEgressReadinessVerifier(amneziaWgRuntimeSupervisor),
    // Clears the sticky "foreign relay failed" signal. Invoked on every relay (re)start
    // and on stop so a clean session never inherits a previous session's Degraded state.
    private val clearForeignRelayFailed: () -> Unit = {},
    private val relayRuntimeSelectionRenderer:
        (RipDpiProxyPreferences, RipDpiRelayConfig, String, Int) -> RipDpiProxyPreferences =
        { preferences, selection, host, port ->
            preferences.withRelayRuntimeSelection(selection, host, port)
        },
) {
    suspend fun start(
        proxyPreferences: RipDpiProxyPreferences,
        onRelayExit: suspend (SupervisorExitCause) -> Unit,
        onWarpExit: suspend (SupervisorExitCause) -> Unit,
        onAwgExit: suspend (SupervisorExitCause) -> Unit,
        onProxyExit: suspend (SupervisorExitCause) -> Unit,
        initialRelayRacePlan: InitialRelayRacePlan? = null,
        onInitialRelayRaceState: (InitialTransportRaceSnapshot) -> Unit = {},
        onInitialRelaySelected: (InitialRelayRaceResult) -> Unit = {},
    ): LocalProxyEndpoint {
        val awgRequest = proxyPreferences.awgConfigOrNull()
        val egressRequirements =
            EgressRequirements(
                tcpConnect = true,
                udpAssociate = proxyPreferences.isUdpAssociateEnabled(),
            )
        check(initialRelayRacePlan == null || initialRelayRacePlan.requirements == egressRequirements) {
            "Initial relay plan requirements do not match effective proxy configuration"
        }
        var effectivePreferences: RipDpiProxyPreferences = proxyPreferences
        if (awgRequest != null) {
            // AWG is the egress: start the AWG supervisor and point the proxy
            // upstream at the AWG loopback port. WARP is not started — AWG wins.
            amneziaWgRuntimeSupervisor.start(awgRequest, onAwgExit)
            initialRelayRacePlan?.let { plan ->
                awgEgressReadinessVerifier.verify(
                    requestProfileId = awgRequest.profileId,
                    plan = plan,
                    onState = onInitialRelayRaceState,
                    onSelected = onInitialRelaySelected,
                )
            }
            effectivePreferences = proxyPreferences.withAwgEgressPort(AmneziaWgLocalSocksPort)
        } else {
            val relayQuicMigrationConfig = proxyPreferences.ownedRelayQuicMigrationConfig()
            proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
                // A fresh relay start clears any stale foreign-relay-failed signal from a
                // previous session so this session does not begin in a Degraded state.
                clearForeignRelayFailed()
                if (initialRelayRacePlan == null) {
                    upstreamRelaySupervisor.start(
                        config = relayConfig,
                        quicMigrationConfig = relayQuicMigrationConfig,
                        requirements = egressRequirements,
                        onUnexpectedExit = onRelayExit,
                    )
                } else {
                    val promoted =
                        upstreamRelaySupervisor.startRace(
                            plan = initialRelayRacePlan,
                            quicMigrationConfig = relayQuicMigrationConfig,
                            onUnexpectedExit = onRelayExit,
                            onState = onInitialRelayRaceState,
                        )
                    effectivePreferences =
                        relayRuntimeSelectionRenderer(
                            proxyPreferences,
                            RipDpiRelayConfig(
                                enabled = true,
                                kind = promoted.result.selectedCandidate.relayKind,
                                profileId = promoted.result.selectedCandidate.profileId,
                                udpEnabled = promoted.udpEnabled,
                            ),
                            promoted.endpoint.host,
                            promoted.endpoint.port,
                        )
                    val renderedRelay = effectivePreferences.relayConfigOrNull()
                    check(
                        renderedRelay != null &&
                            renderedRelay.enabled &&
                            renderedRelay.kind == promoted.result.selectedCandidate.relayKind &&
                            renderedRelay.profileId == promoted.result.selectedCandidate.profileId &&
                            renderedRelay.udpEnabled == promoted.udpEnabled &&
                            renderedRelay.localSocksHost == promoted.endpoint.host &&
                            renderedRelay.localSocksPort == promoted.endpoint.port,
                    ) {
                        "Promoted relay endpoint was not applied to proxy preferences"
                    }
                    onInitialRelaySelected(promoted.result)
                }
            }
            proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
                warpRuntimeSupervisor.start(warpConfig, onWarpExit)
            }
        }

        return proxyRuntimeSupervisor.start(effectivePreferences, onProxyExit)
    }

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        if (skipRuntimeShutdown) {
            detachAll()
            return
        }

        clearForeignRelayFailed()
        var stopFailure: Throwable? = null
        val stopActions =
            listOf<suspend () -> Unit>(
                proxyRuntimeSupervisor::stop,
                warpRuntimeSupervisor::stop,
                amneziaWgRuntimeSupervisor::stop,
                upstreamRelaySupervisor::stop,
            )
        for (stopAction in stopActions) {
            runCatching { stopAction() }
                .onFailure { error ->
                    if (stopFailure == null) {
                        stopFailure = error
                    } else {
                        stopFailure.addSuppressed(error)
                    }
                }
        }
        stopFailure?.let { throw it }
    }

    fun detachAll() {
        clearForeignRelayFailed()
        upstreamRelaySupervisor.detach()
        warpRuntimeSupervisor.detach()
        amneziaWgRuntimeSupervisor.detach()
        proxyRuntimeSupervisor.detach()
    }
}
