package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxySettingsSection
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.toSettingsSections

internal class VpnTunnelRuntime(
    private val vpnHost: VpnCoordinatorHost,
    private val appSettingsRepository: AppSettingsRepository,
    private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
    private val protectPath: String? = null,
    private val rootHelperSocketPathProvider: () -> String? = { null },
    /**
     * Bridge the native tun2socks worker calls to report flow 5-tuples for per-app
     * attribution. `null` disables attribution (the tunnel still runs); passed
     * straight through to [Tun2SocksBridge.start].
     */
    private val flowAttributionBridge: Any? = null,
) {
    private var tun2SocksBridge: Tun2SocksBridge? = null
    private var tunSession: VpnTunnelSession? = null
    private var tunnelStartCount: Int = 0

    var currentDnsSignature: String? = null
        private set

    var currentInterfacePolicySignature: String? = null
        private set

    var tunnelRecoveryRetryCount: Long = 0
        private set

    val isRunning: Boolean
        get() = tunSession != null

    @Suppress("TooGenericExceptionCaught")
    suspend fun start(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
    ) {
        check(tunSession == null) { "VPN field not null" }

        val settings = appSettingsRepository.snapshot()
        val dnsPlan = vpnTunnelDnsPlan(activeDns, forceTunnelDns)
        val ipv6 = settings.ipv6Enable
        val config =
            RipDpiVpnService.buildTun2SocksConfig(
                dnsPlan = dnsPlan,
                overrideReason = overrideReason,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = ipv6,
                logContext = logContext,
                encryptedDnsTlsRootsPem = settings.encryptedDnsTlsRootsPem.takeIf { it.isNotBlank() },
                strategyChainYaml = settings.strategyChainYaml.takeIf { it.isNotBlank() },
                protectPath = protectPath,
                rootHelperSocketPath = rootHelperSocketPathProvider().takeIf { settings.rootModeEnabled },
            )

        val proxy = settings.toSettingsSections().proxy
        val httpProxyPort =
            if (proxy.appendHttpProxy) effectiveListenerPort(proxy) else null
        val tunnelSession =
            vpnTunnelSessionProvider.establish(vpnHost, dnsPlan.builderDnsAddress, ipv6, httpProxyPort)
        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            tunnelBridge.start(config, tunnelSession.tunFd, flowAttributionBridge)
            tun2SocksBridge = tunnelBridge
            tunSession = tunnelSession
            currentDnsSignature = dnsSignature(activeDns, overrideReason)
            currentInterfacePolicySignature = vpnTunnelInterfacePolicySignature(settings)
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

    suspend fun pollTelemetry(): RuntimeTelemetryOutcome {
        val bridge = tun2SocksBridge ?: return RuntimeTelemetryOutcome.NoData
        return runCatching { bridge.telemetry() }
            .fold(
                onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
                onFailure = { error ->
                    RuntimeTelemetryOutcome.EngineError(
                        message = error.message ?: "Tunnel telemetry polling failed",
                        causeClass = error.javaClass.name,
                    )
                },
            )
    }

    fun resetRuntimeState() {
        currentDnsSignature = null
        currentInterfacePolicySignature = null
        tunnelStartCount = 0
        tunnelRecoveryRetryCount = 0L
    }
}

/**
 * Returns the effective local listener port using the same resolution logic as
 * `buildListenConfig` in core/engine — the single source of truth for which port the
 * native core binds to. A positive [ProxySettingsSection.proxyPort] always wins; otherwise
 * the port defaults based on whether the mixed inbound listener is enabled.
 *
 * This mirrors the logic in `NativeProxyRuntimePreferencesMapper.buildListenConfig` without
 * importing that internal helper across module boundaries.
 */
internal fun effectiveListenerPort(proxy: ProxySettingsSection): Int =
    proxy.proxyPort.takeIf { it > 0 } ?: if (proxy.mixedInboundEnabled) 2080 else 1080
