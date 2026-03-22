@file:Suppress("TooGenericExceptionCaught")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.NativeRuntimeSnapshot

internal class VpnTunnelRuntime(
    private val vpnHost: VpnCoordinatorHost,
    private val appSettingsRepository: AppSettingsRepository,
    private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
) {
    private companion object {
        private const val MapDnsAddress = "198.18.0.53"
        private const val DefaultProxyPort = 1080
    }

    private var tun2SocksBridge: Tun2SocksBridge? = null
    private var tunSession: VpnTunnelSession? = null
    private var tunnelStartCount: Int = 0

    var currentDnsSignature: String? = null
        private set

    var tunnelRecoveryRetryCount: Long = 0
        private set

    val isRunning: Boolean
        get() = tunSession != null

    suspend fun start(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
    ) {
        check(tunSession == null) { "VPN field not null" }

        val settings = appSettingsRepository.snapshot()
        val port = if (settings.proxyPort > 0) settings.proxyPort else DefaultProxyPort
        val dns = if (activeDns.mode == DnsModeEncrypted) MapDnsAddress else activeDns.dnsIp
        val ipv6 = settings.ipv6Enable
        val config = RipDpiVpnService.buildTun2SocksConfig(activeDns, overrideReason, port, ipv6)

        val tunnelSession = vpnTunnelSessionProvider.establish(vpnHost, dns, ipv6)
        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            tunnelBridge.start(config, tunnelSession.tunFd)
            tun2SocksBridge = tunnelBridge
            tunSession = tunnelSession
            currentDnsSignature = dnsSignature(activeDns, overrideReason)
            if (tunnelStartCount > 0) {
                tunnelRecoveryRetryCount += 1
            }
            tunnelStartCount += 1
        } catch (error: Exception) {
            tunnelSession.close()
            throw error
        }

        vpnHost.syncUnderlyingNetworksFromActiveNetwork()
    }

    suspend fun stop() {
        val session = tunSession ?: return

        try {
            tun2SocksBridge?.stop()
        } finally {
            tun2SocksBridge = null
            session.close()
            tunSession = null
        }
    }

    suspend fun pollTelemetry(): Result<NativeRuntimeSnapshot?> = runCatching { tun2SocksBridge?.telemetry() }

    fun resetRuntimeState() {
        currentDnsSignature = null
        tunnelStartCount = 0
        tunnelRecoveryRetryCount = 0L
    }
}
