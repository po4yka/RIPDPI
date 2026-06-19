package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.core.withAwgEgressPort
import com.poyka.ripdpi.service.awg.VpnModeAmneziaWgLocalSocksPort

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
) {
    suspend fun start(
        proxyPreferences: RipDpiProxyPreferences,
        onRelayExit: suspend (SupervisorExitCause) -> Unit,
        onWarpExit: suspend (SupervisorExitCause) -> Unit,
        onAwgExit: suspend (SupervisorExitCause) -> Unit,
        onProxyExit: suspend (SupervisorExitCause) -> Unit,
    ): LocalProxyEndpoint {
        val relayQuicMigrationConfig = proxyPreferences.ownedRelayQuicMigrationConfig()
        proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
            upstreamRelaySupervisor.start(relayConfig, relayQuicMigrationConfig, onRelayExit)
        }

        val awgRequest = proxyPreferences.awgConfigOrNull()
        val effectivePreferences: RipDpiProxyPreferences
        if (awgRequest != null) {
            // AWG is the egress: start the AWG supervisor and point the proxy
            // upstream at the AWG loopback port. WARP is not started — AWG wins.
            amneziaWgRuntimeSupervisor.start(awgRequest, onAwgExit)
            effectivePreferences = proxyPreferences.withAwgEgressPort(VpnModeAmneziaWgLocalSocksPort)
        } else {
            proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
                warpRuntimeSupervisor.start(warpConfig, onWarpExit)
            }
            effectivePreferences = proxyPreferences
        }

        return proxyRuntimeSupervisor.start(effectivePreferences, onProxyExit)
    }

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        if (skipRuntimeShutdown) {
            detachAll()
            return
        }

        proxyRuntimeSupervisor.stop()
        warpRuntimeSupervisor.stop()
        amneziaWgRuntimeSupervisor.stop()
        upstreamRelaySupervisor.stop()
    }

    fun detachAll() {
        upstreamRelaySupervisor.detach()
        warpRuntimeSupervisor.detach()
        amneziaWgRuntimeSupervisor.detach()
        proxyRuntimeSupervisor.detach()
    }
}
