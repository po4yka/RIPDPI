package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxySettingsSection
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val DefaultSocksListenerPort = 1080
private const val DefaultMixedInboundListenerPort = 2080

internal class VpnTunnelRuntime(
    private val vpnHost: VpnCoordinatorHost,
    private val appSettingsRepository: AppSettingsRepository,
    private val proxyGroupRepository: ProxyGroupRepository,
    private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
    private val protectPath: String? = null,
    /**
     * Absolute `<filesDir>/lua` directory the native TUN egress strategy loader
     * jails `lua`-step `script_paths` to. `null` falls back to the process CWD
     * (`"."`); production supplies the app's absolute lua directory.
     */
    private val luaScriptBaseDir: String? = null,
    private val rootHelperSocketPathProvider: () -> String? = { null },
    /**
     * Bridge the native tun2socks worker calls to report flow 5-tuples for per-app
     * attribution. `null` disables attribution (the tunnel still runs); passed
     * straight through to [Tun2SocksBridge.start].
     */
    private val flowAttributionBridge: FlowAttributionBridge? = null,
    private val nativeUidPolicyProvider: ((VpnAppRoutingPlan) -> NativeUidPolicy)? = null,
) {
    @Volatile
    private var tun2SocksBridge: Tun2SocksBridge? = null

    @Volatile
    private var retiringBridge: Tun2SocksBridge? = null

    @Volatile
    private var tunSession: VpnTunnelSession? = null
    private var tunnelStartCount: Int = 0

    @Volatile
    var currentDnsSignature: String? = null
        private set

    @Volatile
    var currentInterfacePolicySignature: String? = null
        private set

    var tunnelRecoveryRetryCount: Long = 0
        private set

    val isRunning: Boolean
        get() = tunSession != null

    val isForwarding: Boolean
        get() = tun2SocksBridge != null

    fun desiredInterfacePolicySignatures(): Flow<String> =
        combine(
            appSettingsRepository.settings,
            proxyGroupRepository.groups(),
            vpnHost.observeInstalledPackages(),
        ) { settings, groups, installedPackages ->
            InterfacePolicyInput(settings, groups, installedPackages)
        }.map { input ->
            resolveInterfacePolicy(input.settings, input.groups, input.installedPackages).signature
        }.distinctUntilChanged()

    suspend fun requiresInterfacePolicyRebuild(): Boolean {
        val appliedSignature = currentInterfacePolicySignature ?: return false
        return isRunning && desiredInterfacePolicySignature() != appliedSignature
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun start(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
    ) {
        check(tunSession == null) { "VPN field not null" }

        val pendingTunnel =
            prepareTunnel(
                activeDns = activeDns,
                overrideReason = overrideReason,
                logContext = logContext,
                localProxyEndpoint = localProxyEndpoint,
                forceTunnelDns = forceTunnelDns,
            )
        try {
            startBridge(pendingTunnel, retainFailedBridge = false)
        } catch (error: Exception) {
            pendingTunnel.session.close()
            throw error
        }

        vpnHost.syncUnderlyingNetworksFromActiveNetwork()
    }

    /**
     * Replaces an active Android VPN interface without exposing a direct-path gap.
     *
     * Android keeps the old interface active when establishing the replacement fails,
     * and switches routes to the replacement only after establishment succeeds. From
     * that point this runtime deliberately retains the replacement session even if the
     * native bridge cannot start, so the TUN remains a fail-closed traffic barrier.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun rebuild(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
    ) {
        val previousSession = checkNotNull(tunSession) { "VPN tunnel is not running" }
        val previousBridge = checkNotNull(tun2SocksBridge) { "VPN tunnel is not forwarding" }
        val pendingTunnel =
            prepareTunnel(
                activeDns = activeDns,
                overrideReason = overrideReason,
                logContext = logContext,
                localProxyEndpoint = localProxyEndpoint,
                forceTunnelDns = forceTunnelDns,
            )

        // Establishment has already moved Android routing to this replacement TUN.
        // Publish it before retiring the old bridge so every subsequent failure keeps
        // a live interface that captures traffic instead of falling back to direct.
        tunSession = pendingTunnel.session
        tun2SocksBridge = null
        try {
            try {
                previousBridge.stop()
            } catch (error: Exception) {
                retiringBridge = previousBridge
                throw error
            }
        } finally {
            previousSession.close()
        }
        startBridge(pendingTunnel, retainFailedBridge = true)

        vpnHost.syncUnderlyingNetworksFromActiveNetwork()
    }

    private suspend fun prepareTunnel(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean,
    ): PendingTunnel {
        val settings = appSettingsRepository.snapshot()
        val dnsPlan = vpnTunnelDnsPlan(activeDns, forceTunnelDns)
        val ipv6 = settings.ipv6Enable
        val tunnelNetworkParameters = vpnHost.currentTunnelNetworkParameters()
        val interfacePolicy =
            resolveInterfacePolicy(
                settings = settings,
                groups = proxyGroupRepository.list(),
                installedPackages = vpnHost.currentInstalledPackages(),
            )
        val appRoutingPlan = interfacePolicy.appRoutingPlan
        val uidPolicy =
            nativeUidPolicyProvider?.invoke(appRoutingPlan)
                ?: flowAttributionBridge?.nativeUidPolicy(appRoutingPlan)
                ?: NativeUidPolicy.Disarmed
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = dnsPlan,
                overrideReason = overrideReason,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = ipv6,
                webrtcProtectionEnabled = settings.webrtcProtectionEnabled,
                tunnelMtu = tunnelNetworkParameters.tunnelMtu,
                logContext = logContext,
                encryptedDnsTlsRootsPem = settings.encryptedDnsTlsRootsPem.takeIf { it.isNotBlank() },
                strategyChainYaml = settings.strategyChainYaml.takeIf { it.isNotBlank() },
                protectPath = protectPath,
                rootHelperSocketPath = rootHelperSocketPathProvider().takeIf { settings.rootModeEnabled },
                luaScriptBaseDir = luaScriptBaseDir,
                uidPolicy = uidPolicy,
            )
        val tunnelSession =
            vpnTunnelSessionProvider.establish(
                host = vpnHost,
                dns = dnsPlan.builderDnsAddress,
                ipv6 = ipv6,
                appRoutingPlan = appRoutingPlan,
                httpProxyPort = interfacePolicy.httpProxyPort,
                interfaceSettings = settings,
            )
        return PendingTunnel(
            session = tunnelSession,
            config = config,
            dnsSignature = dnsSignature(activeDns, overrideReason),
            interfacePolicySignature = interfacePolicy.signature,
        )
    }

    private suspend fun desiredInterfacePolicySignature(): String {
        val settings = appSettingsRepository.snapshot()
        return resolveInterfacePolicy(
            settings = settings,
            groups = proxyGroupRepository.list(),
            installedPackages = vpnHost.currentInstalledPackages(),
        ).signature
    }

    private suspend fun resolveInterfacePolicy(
        settings: AppSettings,
        groups: List<ProxyGroup>,
        installedPackages: Set<String>,
    ): ResolvedVpnInterfacePolicy {
        val packageRoutingRules = groups.flatMap { it.packageRoutingRules }
        val appRoutingPlan = vpnHost.resolveAppRoutingPlan(settings, packageRoutingRules, installedPackages)
        val proxy = settings.toSettingsSections().proxy
        val httpProxyPort =
            if (proxy.appendHttpProxy) effectiveListenerPort(proxy) else null
        return ResolvedVpnInterfacePolicy(
            appRoutingPlan = appRoutingPlan,
            httpProxyPort = httpProxyPort,
            signature = vpnTunnelInterfacePolicySignature(settings, appRoutingPlan, httpProxyPort),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startBridge(
        pendingTunnel: PendingTunnel,
        retainFailedBridge: Boolean,
    ) {
        val tunnelBridge = tun2SocksBridgeFactory.create()
        try {
            tunnelBridge.start(pendingTunnel.config, pendingTunnel.session.tunFd, flowAttributionBridge)
        } catch (error: Exception) {
            if (retainFailedBridge) retiringBridge = tunnelBridge
            throw error
        }
        tun2SocksBridge = tunnelBridge
        tunSession = pendingTunnel.session
        currentDnsSignature = pendingTunnel.dnsSignature
        currentInterfacePolicySignature = pendingTunnel.interfacePolicySignature
        if (tunnelStartCount > 0) {
            tunnelRecoveryRetryCount += 1
        }
        tunnelStartCount += 1
    }

    suspend fun stop() {
        val session = tunSession ?: return
        val activeBridge = tun2SocksBridge
        val inactiveBridge = retiringBridge

        try {
            activeBridge?.stop()
        } finally {
            try {
                if (inactiveBridge !== activeBridge) inactiveBridge?.stop()
            } finally {
                tun2SocksBridge = null
                retiringBridge = null
                session.close()
                tunSession = null
            }
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

    private data class PendingTunnel(
        val session: VpnTunnelSession,
        val config: Tun2SocksConfig,
        val dnsSignature: String,
        val interfacePolicySignature: String,
    )

    private data class InterfacePolicyInput(
        val settings: AppSettings,
        val groups: List<ProxyGroup>,
        val installedPackages: Set<String>,
    )

    private data class ResolvedVpnInterfacePolicy(
        val appRoutingPlan: VpnAppRoutingPlan,
        val httpProxyPort: Int?,
        val signature: String,
    )
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
    proxy.proxyPort.takeIf { it > 0 }
        ?: if (proxy.mixedInboundEnabled) DefaultMixedInboundListenerPort else DefaultSocksListenerPort
