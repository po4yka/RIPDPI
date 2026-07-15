package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.awgConfigOrNull
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
    // Clears the sticky "foreign relay failed" signal. Invoked on every relay (re)start
    // and on stop so a clean session never inherits a previous session's Degraded state.
    private val clearForeignRelayFailed: () -> Unit = {},
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
        var effectivePreferences: RipDpiProxyPreferences = proxyPreferences
        if (awgRequest != null) {
            // AWG is the egress: start the AWG supervisor and point the proxy
            // upstream at the AWG loopback port. WARP is not started — AWG wins.
            amneziaWgRuntimeSupervisor.start(awgRequest, onAwgExit)
            effectivePreferences = proxyPreferences.withAwgEgressPort(AmneziaWgLocalSocksPort)
        } else {
            val relayQuicMigrationConfig = proxyPreferences.ownedRelayQuicMigrationConfig()
            proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
                // A fresh relay start clears any stale foreign-relay-failed signal from a
                // previous session so this session does not begin in a Degraded state.
                clearForeignRelayFailed()
                if (initialRelayRacePlan == null) {
                    upstreamRelaySupervisor.start(relayConfig, relayQuicMigrationConfig, onRelayExit)
                } else {
                    val promoted =
                        upstreamRelaySupervisor.startRace(
                            plan = initialRelayRacePlan,
                            quicMigrationConfig = relayQuicMigrationConfig,
                            onUnexpectedExit = onRelayExit,
                            onState = onInitialRelayRaceState,
                        )
                    onInitialRelaySelected(promoted.result)
                    effectivePreferences =
                        proxyPreferences.withRelayRuntimeSelection(
                            selectedConfig =
                                RipDpiRelayConfig(
                                    enabled = true,
                                    kind = promoted.result.selectedCandidate.relayKind,
                                    profileId = promoted.result.selectedCandidate.profileId,
                                ),
                            localSocksHost = promoted.endpoint.host,
                            localSocksPort = promoted.endpoint.port,
                        )
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
        proxyRuntimeSupervisor.stop()
        warpRuntimeSupervisor.stop()
        amneziaWgRuntimeSupervisor.stop()
        upstreamRelaySupervisor.stop()
    }

    fun detachAll() {
        clearForeignRelayFailed()
        upstreamRelaySupervisor.detach()
        warpRuntimeSupervisor.detach()
        amneziaWgRuntimeSupervisor.detach()
        proxyRuntimeSupervisor.detach()
    }
}
