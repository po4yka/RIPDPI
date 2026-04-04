package com.poyka.ripdpi.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.DEFAULT_TUN2SOCKS_TUNNEL_MTU
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.utility.NotificationContentBuilder
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.createDynamicConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RipDpiVpnService :
    LifecycleVpnService(),
    VpnCoordinatorHost {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var vpnAppExclusionPolicy: VpnAppExclusionPolicy

    @Inject
    internal lateinit var coordinatorFactory: VpnServiceRuntimeCoordinatorFactory

    private lateinit var coordinator: VpnServiceRuntimeCoordinator
    private lateinit var shellDelegate: ServiceShellDelegate
    private lateinit var protectSocketServer: VpnProtectSocketServer
    private var revoked = false

    override val serviceScope = lifecycleScope

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
        coordinator = coordinatorFactory.create(host = this)
        protectSocketServer =
            VpnProtectSocketServer(
                vpnService = this,
                socketPath = java.io.File(filesDir, "protect_path").absolutePath,
            )
        protectSocketServer.start()
        com.poyka.ripdpi.core.RipDpiProxyNativeBindings
            .jniRegisterVpnProtect(this)
        shellDelegate =
            ServiceShellDelegate(
                serviceScope = lifecycleScope,
                serviceLabel = "vpn",
                onStart = coordinator::start,
                onStop = coordinator::stop,
                onRevoke = {
                    serviceStateStore.emitFailed(
                        sender = Sender.VPN,
                        reason = FailureReason.NativeError("VPN revoked by system or another app"),
                    )
                    coordinator.stop()
                },
            )
    }

    override fun onDestroy() {
        com.poyka.ripdpi.core.RipDpiProxyNativeBindings
            .jniUnregisterVpnProtect()
        protectSocketServer.stop()
        if (!revoked) {
            coordinator.onDestroy()
        }
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
        startForegroundService()
        return shellDelegate.onStartCommand(intent?.action, startId)
    }

    override fun onRevoke() {
        revoked = true
        shellDelegate.onRevoke()
    }

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        val startedAt = serviceStateStore.telemetry.value.serviceStartedAt ?: return
        val elapsedMs = System.currentTimeMillis() - startedAt
        val content =
            NotificationContentBuilder.buildContentText(
                txBytes = tunnelStats.txBytes,
                rxBytes = tunnelStats.rxBytes,
                elapsedMs = elapsedMs,
            )
        val subText =
            NotificationContentBuilder.buildSubText(
                activeSessions = proxyTelemetry.activeSessions,
                rttMs = proxyTelemetry.upstreamRttMs,
            )
        val notification =
            createDynamicConnectionNotification(
                context = this,
                channelId = NOTIFICATION_CHANNEL_ID,
                title = getString(R.string.notification_title),
                content = content,
                subText = subText,
                service = RipDpiVpnService::class.java,
                whenTimestamp = startedAt,
            )
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(FOREGROUND_SERVICE_ID, notification)
        } catch (e: SecurityException) {
            Logger.w { "Cannot update notification: permission revoked" }
        }
    }

    override fun requestStopSelf(stopSelfStartId: Int?) {
        val stoppedSelf = stopSelfStartId?.let(::stopSelfResult)
        if (stoppedSelf == null) {
            stopSelf()
        }
    }

    override fun createTunnelBuilder(
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelBuilder =
        AndroidVpnTunnelBuilder(
            builder = createBuilder(dns, ipv6),
        )

    @android.annotation.SuppressLint("MissingPermission")
    override fun syncUnderlyingNetworksFromActiveNetwork() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork =
            if (hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
                connectivityManager.activeNetwork
            } else {
                null
            }
        setUnderlyingNetworks(activeNetwork?.let { arrayOf(it) })
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundService() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            RipDpiVpnService::class.java,
        )

    internal fun createBuilder(
        dns: String,
        ipv6: Boolean,
    ): Builder {
        Logger.v { "DNS: $dns" }
        val builder = Builder()
        builder.setSession("RIPDPI")
        builder.setMtu(DEFAULT_TUN2SOCKS_TUNNEL_MTU)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        builder
            .addAddress(TUNNEL_IPV4_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUNNEL_IPV6_ADDRESS, 128)
            .addRoute("::", 0)

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (vpnAppExclusionPolicy.shouldExcludeOwnPackage()) {
            builder.addDisallowedApplication(applicationContext.packageName)
        }

        return builder
    }

    private class AndroidVpnTunnelBuilder(
        private val builder: Builder,
    ) : VpnTunnelBuilder {
        override fun establish(): VpnTunnelSession? = builder.establish()?.let(::ParcelFileDescriptorVpnTunnelSession)
    }

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPIVpn"
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
            socks5Port: Int,
            ipv6Enabled: Boolean,
            logContext: RipDpiLogContext? = null,
        ): Tun2SocksConfig =
            Tun2SocksConfig(
                tunnelMtu = DEFAULT_TUN2SOCKS_TUNNEL_MTU,
                tunnelIpv4 = TUNNEL_IPV4_CIDR,
                tunnelIpv6 = if (ipv6Enabled) TUNNEL_IPV6_CIDR else null,
                socks5Port = socks5Port,
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
                logContext = logContext,
            )
    }
}
