package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull

internal class SharedProxyRuntimeStack(
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
) {
    suspend fun start(
        proxyPreferences: RipDpiProxyPreferences,
        onRelayExit: suspend (SupervisorExitCause) -> Unit,
        onWarpExit: suspend (SupervisorExitCause) -> Unit,
        onProxyExit: suspend (SupervisorExitCause) -> Unit,
    ): LocalProxyEndpoint {
        val relayQuicMigrationConfig = proxyPreferences.ownedRelayQuicMigrationConfig()
        proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
            upstreamRelaySupervisor.start(relayConfig, relayQuicMigrationConfig, onRelayExit)
        }
        proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
            warpRuntimeSupervisor.start(warpConfig, onWarpExit)
        }
        return proxyRuntimeSupervisor.start(proxyPreferences, onProxyExit)
    }

    suspend fun stop(skipRuntimeShutdown: Boolean) {
        if (skipRuntimeShutdown) {
            detachAll()
            return
        }

        proxyRuntimeSupervisor.stop()
        warpRuntimeSupervisor.stop()
        upstreamRelaySupervisor.stop()
    }

    fun detachAll() {
        upstreamRelaySupervisor.detach()
        warpRuntimeSupervisor.detach()
        proxyRuntimeSupervisor.detach()
    }
}
