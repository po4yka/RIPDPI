package com.poyka.ripdpi.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.defaultTun2SocksTunnelMtu
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider

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
    internal lateinit var sessionComponentBuilderProvider: Provider<VpnServiceSessionComponentBuilder>

    private lateinit var sessionLifecycle: VpnServiceSessionLifecycle
    private lateinit var shellDelegate: ServiceShellDelegate
    private lateinit var notificationController: VpnForegroundNotificationController
    private lateinit var underlyingNetworkBinder: VpnUnderlyingNetworkBinder
    private var revoked = false

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
    }

    override fun onDestroy() {
        sessionLifecycle.destroy(revoked)
        rootHelperManager.stop()
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Logger.w { "Sticky restart aborted: notification permission revoked" }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notificationController.startForeground(this)
        return shellDelegate.onStartCommand(intent?.action, startId)
    }

    override fun onRevoke() {
        revoked = true
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
    ): VpnTunnelBuilder =
        AndroidVpnTunnelBuilder(
            builder = createBuilder(dns, ipv6),
        )

    @android.annotation.SuppressLint("MissingPermission")
    override fun syncUnderlyingNetworksFromActiveNetwork() = underlyingNetworkBinder.syncFromActiveNetwork()

    internal suspend fun createBuilder(
        dns: String,
        ipv6: Boolean,
    ): Builder {
        Logger.v { "DNS configured" }
        val builder = Builder()
        builder.setSession("RIPDPI")
        builder.setMtu(defaultTun2SocksTunnelMtu)
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
            builder.setMetered(false)
        }

        if (vpnAppExclusionPolicy.shouldExcludeOwnPackage()) {
            builder.addDisallowedApplication(applicationContext.packageName)
        }

        for (pkg in vpnAppExclusionPolicy.russianAppsToExclude()) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                // App not installed, skip silently
            }
        }

        applyDhtMitigation(builder)
        return builder
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
            activeDns: ActiveDnsSettings,
            overrideReason: String?,
            localProxyEndpoint: LocalProxyEndpoint,
            ipv6Enabled: Boolean,
            logContext: RipDpiLogContext? = null,
            strategyChainYaml: String? = null,
            protectPath: String? = null,
            rootHelperSocketPath: String? = null,
        ): Tun2SocksConfig =
            Tun2SocksConfig(
                tunnelMtu = defaultTun2SocksTunnelMtu,
                tunnelIpv4 = TUNNEL_IPV4_CIDR,
                tunnelIpv6 = if (ipv6Enabled) TUNNEL_IPV6_CIDR else null,
                socks5Address = localProxyEndpoint.host,
                socks5Port = localProxyEndpoint.port,
                socks5Udp = "udp",
                mapdnsAddress = if (activeDns.mode == DnsModeEncrypted) MAPDNS_ADDRESS else null,
                mapdnsPort = if (activeDns.mode == DnsModeEncrypted) MAPDNS_PORT else null,
                mapdnsNetwork = if (activeDns.mode == DnsModeEncrypted) MAPDNS_NETWORK else null,
                mapdnsNetmask = if (activeDns.mode == DnsModeEncrypted) MAPDNS_NETMASK else null,
                mapdnsCacheSize = if (activeDns.mode == DnsModeEncrypted) MAPDNS_CACHE_SIZE else null,
                encryptedDnsResolverId = if (activeDns.mode == DnsModeEncrypted) activeDns.providerId else null,
                encryptedDnsProtocol = if (activeDns.mode == DnsModeEncrypted) activeDns.encryptedDnsProtocol else null,
                encryptedDnsHost = if (activeDns.mode == DnsModeEncrypted) activeDns.encryptedDnsHost else null,
                encryptedDnsPort = if (activeDns.mode == DnsModeEncrypted) activeDns.encryptedDnsPort else null,
                encryptedDnsTlsServerName =
                    if (activeDns.mode == DnsModeEncrypted) {
                        activeDns.encryptedDnsTlsServerName
                    } else {
                        null
                    },
                encryptedDnsBootstrapIps =
                    if (activeDns.mode == DnsModeEncrypted) {
                        activeDns.encryptedDnsBootstrapIps
                    } else {
                        emptyList()
                    },
                encryptedDnsDohUrl =
                    if (activeDns.mode == DnsModeEncrypted) {
                        activeDns.encryptedDnsDohUrl
                    } else {
                        null
                    },
                encryptedDnsDnscryptProviderName =
                    if (activeDns.mode == DnsModeEncrypted) {
                        activeDns.encryptedDnsDnscryptProviderName
                    } else {
                        null
                    },
                encryptedDnsDnscryptPublicKey =
                    if (activeDns.mode == DnsModeEncrypted) {
                        activeDns.encryptedDnsDnscryptPublicKey
                    } else {
                        null
                    },
                dnsQueryTimeoutMs = if (activeDns.mode == DnsModeEncrypted) DNS_QUERY_TIMEOUT_MS else null,
                resolverFallbackActive = overrideReason != null,
                resolverFallbackReason = overrideReason,
                strategyChainYaml = strategyChainYaml,
                protectPath = protectPath,
                rootHelperSocketPath = rootHelperSocketPath,
                logContext = logContext,
                username = localProxyEndpoint.username,
                password = localProxyEndpoint.password,
            )

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
