package com.poyka.ripdpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class RipDpiVpnService : LifecycleVpnService() {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var connectionPolicyResolver: ConnectionPolicyResolver

    @Inject
    lateinit var ripDpiProxyFactory: RipDpiProxyFactory

    @Inject
    lateinit var tun2SocksBridgeFactory: Tun2SocksBridgeFactory

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var vpnTunnelSessionProvider: VpnTunnelSessionProvider

    @Inject
    lateinit var vpnAppExclusionPolicy: VpnAppExclusionPolicy

    @Inject
    lateinit var resolverOverrideStore: ResolverOverrideStore

    @Inject
    lateinit var activeConnectionPolicyStore: ActiveConnectionPolicyStore

    @Inject
    lateinit var rememberedNetworkPolicyStore: RememberedNetworkPolicyStore

    private var ripDpiProxy: RipDpiProxyRuntime? = null
    private var tun2SocksBridge: Tun2SocksBridge? = null
    private var proxyJob: Job? = null
    private var telemetryJob: Job? = null
    private var tunSession: VpnTunnelSession? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false
    private var currentDnsSignature: String? = null
    private var lastNetworkFingerprint: NetworkFingerprint? = null

    private var status: ServiceStatus = ServiceStatus.Disconnected

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPIVpn"
        private const val MAPDNS_ADDRESS = "198.18.0.53"
        private const val MAPDNS_NETWORK = "198.18.0.0"
        private const val MAPDNS_NETMASK = "255.254.0.0"
        private const val MAPDNS_PORT = 53
        private const val MAPDNS_CACHE_SIZE = 10_000
        private const val DNS_QUERY_TIMEOUT_MS = 4_000
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onStartCommand(
        intent: android.content.Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            else -> {
                logcat(LogPriority.WARN) { "Unknown action: $action" }
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        logcat(LogPriority.INFO) { "VPN revoked" }
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        logcat(LogPriority.INFO) { "Starting" }

        if (status == ServiceStatus.Connected) {
            logcat(LogPriority.WARN) { "VPN already connected" }
            return
        }

        startForeground()
        var matchedRememberedPolicy: com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity? = null
        try {
            val resolution =
                connectionPolicyResolver.resolve(
                    mode = Mode.VPN,
                    resolverOverride = resolverOverrideStore.override.value,
                )
            matchedRememberedPolicy = resolution.matchedNetworkPolicy
            resolution.appliedPolicy?.let { policy ->
                activeConnectionPolicyStore.set(
                    ActiveConnectionPolicy(
                        mode = Mode.VPN,
                        policy = policy,
                        matchedPolicy = resolution.matchedNetworkPolicy,
                        usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                    ),
                )
            }
            mutex.withLock {
                startProxy(resolution.proxyPreferences)
                startTun2Socks(
                    activeDns = resolution.activeDns,
                    overrideReason = resolution.resolverFallbackReason,
                )
            }
            updateStatus(ServiceStatus.Connected)
            startTelemetryUpdates()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to start VPN\n${e.asLog()}" }
            matchedRememberedPolicy?.let { policy ->
                rememberedNetworkPolicyStore.recordFailure(policy)
            }
            val reason = classifyFailureReason(e, isTunnelContext = true)
            updateStatus(ServiceStatus.Failed, reason)
            stop()
        }
    }

    private fun startForeground() {
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

    private suspend fun stop(skipProxyShutdown: Boolean = false) {
        logcat(LogPriority.INFO) { "Stopping" }

        mutex.withLock {
            stopping = true
            try {
                try {
                    stopTun2Socks()
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to stop tunnel\n${e.asLog()}" }
                }
                if (!skipProxyShutdown) {
                    try {
                        stopProxy()
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "Failed to stop proxy\n${e.asLog()}" }
                    }
                } else {
                    proxyJob = null
                    ripDpiProxy = null
                }
            } finally {
                stopping = false
                currentDnsSignature = null
                lastNetworkFingerprint = null
                resolverOverrideStore.clear()
                activeConnectionPolicyStore.clear()
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        telemetryJob?.cancel()
        telemetryJob = null
        stopSelf()
    }

    private suspend fun startProxy(preferences: RipDpiProxyPreferences) {
        logcat(LogPriority.INFO) { "Starting proxy" }

        if (proxyJob != null) {
            logcat(LogPriority.WARN) { "Proxy fields not null" }
            throw IllegalStateException("Proxy fields not null")
        }

        val proxyInstance = ripDpiProxyFactory.create()
        ripDpiProxy = proxyInstance

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    exitResult.complete(runCatching { proxyInstance.startProxy(preferences) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("Proxy job cancelled")))
                    }
                }
            }
        proxyJob = job

        try {
            proxyInstance.awaitReady()
        } catch (e: Exception) {
            runCatching {
                if (job.isActive) {
                    proxyInstance.stopProxy()
                }
            }
            job.join()
            proxyJob = null
            ripDpiProxy = null
            throw e
        }

        job.invokeOnCompletion {
            if (stopping) {
                return@invokeOnCompletion
            }
            lifecycleScope.launch {
                handleProxyExit(exitResult.await())
            }
        }

        logcat(LogPriority.INFO) { "Proxy started" }
    }

    private suspend fun stopProxy() {
        logcat(LogPriority.INFO) { "Stopping proxy" }

        val proxyInstance = ripDpiProxy
        if (proxyInstance == null) {
            logcat(LogPriority.WARN) { "Proxy instance missing during stop" }
            proxyJob = null
            return
        }

        proxyInstance.stopProxy()
        proxyJob?.join()
        proxyJob = null
        ripDpiProxy = null

        logcat(LogPriority.INFO) { "Proxy stopped" }
    }

    private suspend fun handleProxyExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("Proxy runtime failed", throwable)
                logcat(LogPriority.ERROR) { "Proxy failed\n${error.asLog()}" }
                classifyFailureReason(error)
            } ?: result.getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                    FailureReason.NativeError("Proxy exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        stop(skipProxyShutdown = true)
    }

    private suspend fun startTun2Socks(
        activeDns: ActiveDnsSettings,
        overrideReason: String? = null,
    ) {
        logcat(LogPriority.INFO) { "Starting tun2socks" }

        if (tunSession != null) {
            throw IllegalStateException("VPN field not null")
        }

        val settings = appSettingsRepository.snapshot()
        val port = if (settings.proxyPort > 0) settings.proxyPort else 1080
        val dns = if (activeDns.mode == DnsModeEncrypted) MAPDNS_ADDRESS else activeDns.dnsIp
        val ipv6 = settings.ipv6Enable
        val config =
            Tun2SocksConfig(
                socks5Port = port,
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
            )

        val session = vpnTunnelSessionProvider.establish(this, dns, ipv6)

        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            tunnelBridge.start(config, session.tunFd)
            tun2SocksBridge = tunnelBridge
            tunSession = session
            currentDnsSignature = dnsSignature(activeDns, overrideReason)
        } catch (e: Exception) {
            session.close()
            throw e
        }

        logcat(LogPriority.INFO) { "Tun2Socks started" }
    }

    private suspend fun stopTun2Socks() {
        logcat(LogPriority.INFO) { "Stopping tun2socks" }

        val session = tunSession
        if (session == null) {
            logcat(LogPriority.WARN) { "VPN not running" }
            return
        }

        try {
            tun2SocksBridge?.stop()
        } finally {
            tun2SocksBridge = null
            session.close()
            tunSession = null
        }

        logcat(LogPriority.INFO) { "Tun2socks stopped" }
    }

    private fun updateStatus(newStatus: ServiceStatus, failureReason: FailureReason? = null) {
        logcat { "VPN status changed from $status to $newStatus" }

        status = newStatus

        val appStatus =
            when (newStatus) {
                ServiceStatus.Connected -> {
                    AppStatus.Running
                }

                ServiceStatus.Failed,
                -> {
                    AppStatus.Halted
                }

                ServiceStatus.Disconnected,
                -> {
                    proxyJob = null
                    ripDpiProxy = null
                    tun2SocksBridge = null
                    tunSession = null
                    AppStatus.Halted
                }
            }
        serviceStateStore.setStatus(appStatus, Mode.VPN)
        serviceStateStore.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = Mode.VPN,
                status = appStatus,
                tunnelStats = com.poyka.ripdpi.core.TunnelStats(),
                proxyTelemetry = NativeRuntimeSnapshot.idle(source = "proxy"),
                tunnelTelemetry = NativeRuntimeSnapshot.idle(source = "tunnel"),
                updatedAt = System.currentTimeMillis(),
            ),
        )

        if (newStatus == ServiceStatus.Failed) {
            serviceStateStore.emitFailed(
                Sender.VPN,
                failureReason ?: FailureReason.Unexpected(IllegalStateException("Unknown failure")),
            )
        }
    }

    private fun startTelemetryUpdates() {
        telemetryJob?.cancel()
        telemetryJob =
            lifecycleScope.launch {
                while (status == ServiceStatus.Connected) {
                    refreshEffectiveResolverIfNeeded()
                    val proxyTelemetry =
                        runCatching { ripDpiProxy?.pollTelemetry() }.getOrNull()
                            ?: NativeRuntimeSnapshot.idle(source = "proxy")
                    val tunnelTelemetryResult = runCatching { tun2SocksBridge?.telemetry() }
                    val tunnelTelemetry =
                        tunnelTelemetryResult.getOrNull()
                            ?: NativeRuntimeSnapshot.idle(source = "tunnel")
                    val enrichedTunnelTelemetry =
                        tunnelTelemetry.copy(
                            networkHandoverClass = deriveNetworkHandoverClass(),
                        )

                    if (!stopping) {
                        val telemetryFailure =
                            tunnelTelemetryResult.exceptionOrNull()?.let { throwable ->
                                val error =
                                    throwable as? Exception
                                        ?: IllegalStateException("Tunnel telemetry failed", throwable)
                                classifyFailureReason(error, isTunnelContext = true)
                            }
                        val tunnelStoppedUnexpectedly =
                            tunSession != null && enrichedTunnelTelemetry.state != "running"
                        if (telemetryFailure != null || tunnelStoppedUnexpectedly) {
                            val failureReason =
                                telemetryFailure
                                    ?: FailureReason.NativeError(
                                        enrichedTunnelTelemetry.lastError ?: "Tunnel exited unexpectedly",
                                    )
                            updateStatus(ServiceStatus.Failed, failureReason)
                            stop()
                            return@launch
                        }
                    }

                    serviceStateStore.updateTelemetry(
                        ServiceTelemetrySnapshot(
                            mode = Mode.VPN,
                            status = AppStatus.Running,
                            tunnelStats = enrichedTunnelTelemetry.tunnelStats,
                            proxyTelemetry = proxyTelemetry,
                            tunnelTelemetry = enrichedTunnelTelemetry,
                            updatedAt =
                                maxOf(
                                    System.currentTimeMillis(),
                                    proxyTelemetry.capturedAt,
                                    enrichedTunnelTelemetry.capturedAt,
                                ),
                        ),
                    )
                    delay(1_000)
                }
            }
    }

    private suspend fun refreshEffectiveResolverIfNeeded() {
        val resolution =
            connectionPolicyResolver.resolve(
                mode = Mode.VPN,
                resolverOverride = resolverOverrideStore.override.value,
            )
        if (resolution.settings.activeDnsSettings() == resolution.activeDns && resolverOverrideStore.override.value != null) {
            resolverOverrideStore.clear()
        }
        val signature = dnsSignature(resolution.activeDns, resolution.resolverFallbackReason)
        if (tunSession == null || currentDnsSignature == signature) {
            return
        }

        mutex.withLock {
            if (status != ServiceStatus.Connected || tunSession == null) {
                return
            }
            val latestResolution =
                connectionPolicyResolver.resolve(
                    mode = Mode.VPN,
                    resolverOverride = resolverOverrideStore.override.value,
                )
            if (latestResolution.settings.activeDnsSettings() == latestResolution.activeDns && resolverOverrideStore.override.value != null) {
                resolverOverrideStore.clear()
            }
            val latestSignature = dnsSignature(latestResolution.activeDns, latestResolution.resolverFallbackReason)
            if (currentDnsSignature == latestSignature) {
                return
            }
            stopTun2Socks()
            startTun2Socks(
                activeDns = latestResolution.activeDns,
                overrideReason = latestResolution.resolverFallbackReason,
            )
        }
    }

    private fun deriveNetworkHandoverClass(): String? {
        val current = currentNetworkFingerprint()
        val previous = lastNetworkFingerprint
        lastNetworkFingerprint = current
        return classifyNetworkHandover(previous, current)
    }

    private fun currentNetworkFingerprint(): NetworkFingerprint? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        return NetworkFingerprint(
            transportLabel = capabilities.transportLabel(),
            interfaceName = linkProperties?.interfaceName,
            dnsServers = linkProperties?.dnsServers.orEmpty().map { it.hostAddress.orEmpty() },
        )
    }

    private fun NetworkCapabilities?.transportLabel(): String {
        if (this == null) {
            return "unknown"
        }
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
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
        logcat { "DNS: $dns" }
        val builder = Builder()
        builder.setSession("RIPDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        builder
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder
                .addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

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
}
