package com.poyka.ripdpi.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.os.Build
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.defaultTun2SocksTunnelMtu
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider

/**
 * VPN-mode foreground `VpnService` — the Android entry point for VPN mode.
 * Hosts the VPN session lifecycle, the foreground notification, underlying-
 * network binding, and `onRevoke`; runtime orchestration is delegated to
 * `VpnServiceRuntimeCoordinator`. Lifecycle-callback behavior is frozen — see
 * this module's `README.md`.
 */
@AndroidEntryPoint
class RipDpiVpnService :
    LifecycleVpnService(),
    VpnCoordinatorHost {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var vpnAppExclusionPolicy: VpnAppExclusionPolicy

    @Inject
    lateinit var vpnDhtMitigationPolicy: VpnDhtMitigationPolicy

    @Inject
    lateinit var rootHelperManager: RootHelperManager

    @Inject
    lateinit var hardKillSwitchStateStore: AndroidHardKillSwitchStateStore

    @Inject
    internal lateinit var sessionComponentBuilderProvider: Provider<VpnServiceSessionComponentBuilder>

    private lateinit var sessionLifecycle: VpnServiceSessionLifecycle
    private lateinit var shellDelegate: ServiceShellDelegate
    private lateinit var notificationController: VpnForegroundNotificationController
    private lateinit var underlyingNetworkBinder: VpnUnderlyingNetworkBinder
    private val connectivityManager: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val serviceScope = lifecycleScope

    override fun onCreate() {
        super.onCreate()
        notificationController = VpnForegroundNotificationController(serviceStateStore)
        notificationController.registerChannel(this)
        underlyingNetworkBinder = VpnUnderlyingNetworkBinder(this)
        sessionLifecycle =
            VpnServiceSessionLifecycle(
                service = this,
                serviceStateStore = serviceStateStore,
                sessionComponentBuilderProvider = sessionComponentBuilderProvider,
            )
        shellDelegate = sessionLifecycle.createShellDelegate()
        refreshHardKillSwitchState()
    }

    override fun onDestroy() {
        sessionLifecycle.destroy()
        rootHelperManager.stop()
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        notificationController.startForeground(this)
        refreshHardKillSwitchState()
        // A null action is Android re-delivering a START_STICKY intent after the
        // process was killed (LMK / memory limiter). Publish Reconnecting ONLY from
        // a Halted baseline — i.e. a genuinely fresh process whose store re-init'd to
        // Halted. Guarding on Halted is load-bearing: a null re-delivery to a process
        // whose service is still Running must not demote it to Reconnecting (which
        // would also wipe serviceStartedAt), because the runtime start that follows
        // is then rejected as already-running and would never restore Running —
        // leaving the status stuck. Reconnecting is overwritten by Running on connect
        // or Halted if the resume fails.
        if (intent?.action == null && serviceStateStore.status.value.first == AppStatus.Halted) {
            serviceStateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
        }
        return shellDelegate.onStartCommand(intent?.action, startId)
    }

    override fun onRevoke() {
        refreshHardKillSwitchState()
        sessionLifecycle.revoke()
        shellDelegate.onRevoke()
    }

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) = notificationController.update(this, tunnelStats, proxyTelemetry)

    override fun requestStopSelf(stopSelfStartId: Int?) {
        requestStopSelfWithFallback(
            stopSelfStartId = stopSelfStartId,
            stopSelfResult = ::stopSelfResult,
            stopSelf = ::stopSelf,
        )
    }

    override suspend fun createTunnelBuilder(
        dns: String,
        ipv6: Boolean,
        httpProxyPort: Int?,
    ): VpnTunnelBuilder =
        AndroidVpnTunnelBuilder(
            builder = createBuilder(dns, ipv6, httpProxyPort),
        )

    @android.annotation.SuppressLint("MissingPermission")
    override fun syncUnderlyingNetworksFromActiveNetwork() {
        refreshHardKillSwitchState()
        underlyingNetworkBinder.syncFromActiveNetwork()
    }

    internal suspend fun createBuilder(
        dns: String,
        ipv6: Boolean,
        httpProxyPort: Int? = null,
    ): Builder {
        Logger.v { "DNS configured" }
        val tunnelNetworkParameters = currentTunnelNetworkParameters()
        val builder = Builder()
        builder.setSession("RIPDPI")
        builder.setMtu(tunnelNetworkParameters.tunnelMtu)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        builder.applyTunnelRoutePlan(vpnTunnelRoutePlan(ipv6))

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(tunnelNetworkParameters.metered)
            if (httpProxyPort != null) {
                builder.setHttpProxy(buildHttpProxyInfo(httpProxyPort))
            }
        }

        // Android forbids mixing addAllowedApplication and addDisallowedApplication on the same
        // Builder, so the policy returns exactly one shape. The plan is derived from the settings
        // store (NOT the routing_rules Room table), so it never reorders the user's routing rules.
        when (val plan = vpnAppExclusionPolicy.appRoutingPlan(applicationContext.packageName)) {
            is VpnAppRoutingPlan.Disallow -> {
                plan.packages.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // App not installed, skip silently
                    }
                }
            }

            is VpnAppRoutingPlan.AllowOnly -> {
                plan.packages.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // App not installed, skip silently
                    }
                }
            }
        }

        applyDhtMitigation(builder)
        refreshHardKillSwitchState()
        return builder
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun currentTunnelNetworkParameters(): VpnTunnelNetworkParameters {
        val network = connectivityManager.activeNetwork
        val linkProperties = network?.let(connectivityManager::getLinkProperties)
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        return VpnTunnelNetworkPolicy.parameters(linkProperties, capabilities)
    }

    private fun refreshHardKillSwitchState() {
        hardKillSwitchStateStore.update(AndroidHardKillSwitchStateReader.read(this))
    }

    private suspend fun applyDhtMitigation(builder: Builder) {
        val supportsRouteExclusion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val plan = vpnDhtMitigationPolicy.buildPlan(supportsRouteExclusion = supportsRouteExclusion)

        if (supportsRouteExclusion) {
            plan.excludedRoutes.forEach { route ->
                runCatching {
                    builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(route.address), route.prefixLength))
                }.onFailure { error ->
                    Logger.w(error) {
                        "Failed to exclude DHT trigger route ${route.address}/${route.prefixLength}"
                    }
                }
            }
        }

        plan.warningMessage?.let { warning ->
            Logger.w { warning }
        }
    }

    private class AndroidVpnTunnelBuilder(
        private val builder: Builder,
    ) : VpnTunnelBuilder {
        override fun establish(): VpnTunnelSession? = builder.establish()?.let(::ParcelFileDescriptorVpnTunnelSession)
    }

    companion object {
        private const val TunnelIpv4PrefixLen = 32
        private const val TunnelIpv6PrefixLen = 128
        private const val TUNNEL_IPV4_ADDRESS = "10.10.10.10"
        private const val TUNNEL_IPV4_CIDR = "10.10.10.10/32"
        private const val TUNNEL_IPV6_ADDRESS = "fd00::1"
        private const val TUNNEL_IPV6_CIDR = "fd00::1/128"
        private const val MAPDNS_ADDRESS = "198.18.0.53"
        private const val MAPDNS_NETWORK = "198.18.0.0"
        private const val MAPDNS_NETMASK = "255.254.0.0"
        private const val MAPDNS_PORT = 53
        private const val MAPDNS_CACHE_SIZE = 10_000
        private const val DNS_QUERY_TIMEOUT_MS = 4_000

        internal fun buildTun2SocksConfig(
            dnsPlan: VpnTunnelDnsPlan,
            overrideReason: String?,
            localProxyEndpoint: LocalProxyEndpoint,
            ipv6Enabled: Boolean,
            tunnelMtu: Int = defaultTun2SocksTunnelMtu,
            logContext: RipDpiLogContext? = null,
            encryptedDnsTlsRootsPem: String? = null,
            strategyChainYaml: String? = null,
            protectPath: String? = null,
            rootHelperSocketPath: String? = null,
            luaScriptBaseDir: String? = null,
        ): Tun2SocksConfig {
            val tunnelDns = dnsPlan.resolverDns
            val mapDnsEnabled = dnsPlan.mapDnsEnabled
            return Tun2SocksConfig(
                tunnelMtu = tunnelMtu,
                tunnelIpv4 = TUNNEL_IPV4_CIDR,
                tunnelIpv6 = if (ipv6Enabled) TUNNEL_IPV6_CIDR else null,
                socks5Address = localProxyEndpoint.host,
                socks5Port = localProxyEndpoint.port,
                socks5Udp = "udp",
                mapdnsAddress = if (mapDnsEnabled) MAPDNS_ADDRESS else null,
                mapdnsPort = if (mapDnsEnabled) MAPDNS_PORT else null,
                mapdnsNetwork = if (mapDnsEnabled) MAPDNS_NETWORK else null,
                mapdnsNetmask = if (mapDnsEnabled) MAPDNS_NETMASK else null,
                mapdnsCacheSize = if (mapDnsEnabled) MAPDNS_CACHE_SIZE else null,
                encryptedDnsResolverId = mapDnsValue(mapDnsEnabled, tunnelDns.providerId),
                encryptedDnsProtocol = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsProtocol),
                encryptedDnsHost = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsHost),
                encryptedDnsPort = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsPort),
                encryptedDnsTlsServerName = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsTlsServerName),
                encryptedDnsBootstrapIps = mapDnsList(mapDnsEnabled, tunnelDns.encryptedDnsBootstrapIps),
                encryptedDnsDohUrl = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsDohUrl),
                encryptedDnsDnscryptProviderName =
                    mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsDnscryptProviderName),
                encryptedDnsDnscryptPublicKey = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsDnscryptPublicKey),
                encryptedDnsOdohProxyUrl = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohProxyUrl),
                encryptedDnsOdohProxyOperatorId = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohProxyOperatorId),
                encryptedDnsOdohTargetHost = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohTargetHost),
                encryptedDnsOdohTargetPath = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohTargetPath),
                encryptedDnsOdohTargetOperatorId =
                    mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohTargetOperatorId),
                encryptedDnsOdohConfigSource = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigSource),
                encryptedDnsOdohConfigsHex = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigsHex),
                encryptedDnsOdohConfigsRetrievedAtSecs =
                    mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigsRetrievedAtSecs),
                encryptedDnsOdohConfigsTtlSecs = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigsTtlSecs),
                encryptedDnsTlsRootsPem =
                    mapDnsValue(mapDnsEnabled, encryptedDnsTlsRootsPem?.takeIf { it.isNotBlank() }),
                dnsQueryTimeoutMs = if (mapDnsEnabled) DNS_QUERY_TIMEOUT_MS else null,
                resolverFallbackActive = overrideReason != null,
                resolverFallbackReason = overrideReason,
                routeDnsThroughSocks5 = dnsPlan.routeDnsThroughSocks5,
                strategyChainYaml = strategyChainYaml,
                protectPath = protectPath,
                rootHelperSocketPath = rootHelperSocketPath,
                luaScriptBaseDir = luaScriptBaseDir,
                logContext = logContext,
                username = localProxyEndpoint.username,
                password = localProxyEndpoint.password,
            )
        }

        private fun <T> mapDnsValue(
            mapDnsEnabled: Boolean,
            value: T,
        ): T? = if (mapDnsEnabled) value else null

        private fun mapDnsList(
            mapDnsEnabled: Boolean,
            values: List<String>,
        ): List<String> = if (mapDnsEnabled) values else emptyList()

        /** Loopback hosts/addresses excluded from the advertised HTTP proxy. */
        internal val httpProxyExclusionList: List<String> = listOf("localhost", "127.0.0.1", "::1")

        /**
         * Builds the [android.net.ProxyInfo] to advertise via
         * [android.net.VpnService.Builder.setHttpProxy] on Android Q+.
         * Extracted for unit-testability (no [android.net.VpnService] context needed).
         */
        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
        internal fun buildHttpProxyInfo(port: Int): android.net.ProxyInfo =
            android.net.ProxyInfo.buildDirectProxy("127.0.0.1", port, httpProxyExclusionList)

        internal fun vpnTunnelRoutePlan(ipv6Enabled: Boolean): VpnTunnelRoutePlan =
            VpnTunnelRoutePlan(
                addresses =
                    buildList {
                        add(VpnTunnelRouteEntry(TUNNEL_IPV4_ADDRESS, TunnelIpv4PrefixLen))
                        if (ipv6Enabled) {
                            add(VpnTunnelRouteEntry(TUNNEL_IPV6_ADDRESS, TunnelIpv6PrefixLen))
                        }
                    },
                routes =
                    buildList {
                        add(VpnTunnelRouteEntry("0.0.0.0", 0))
                        if (ipv6Enabled) {
                            add(VpnTunnelRouteEntry("::", 0))
                        }
                    },
            )
    }
}

internal data class VpnTunnelRouteEntry(
    val address: String,
    val prefixLength: Int,
)

internal data class VpnTunnelRoutePlan(
    val addresses: List<VpnTunnelRouteEntry>,
    val routes: List<VpnTunnelRouteEntry>,
)

private fun android.net.VpnService.Builder.applyTunnelRoutePlan(plan: VpnTunnelRoutePlan) {
    plan.addresses.forEach { address ->
        addAddress(address.address, address.prefixLength)
    }
    plan.routes.forEach { route ->
        addRoute(route.address, route.prefixLength)
    }
}
