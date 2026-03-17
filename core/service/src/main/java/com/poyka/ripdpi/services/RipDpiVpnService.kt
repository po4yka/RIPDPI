package com.poyka.ripdpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.lifecycleScope
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
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.deriveRuntimeFieldTelemetry
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
    lateinit var networkFingerprintProvider: NetworkFingerprintProvider

    @Inject
    lateinit var telemetryFingerprintHasher: TelemetryFingerprintHasher

    @Inject
    lateinit var vpnTunnelSessionProvider: VpnTunnelSessionProvider

    @Inject
    lateinit var vpnAppExclusionPolicy: VpnAppExclusionPolicy

    @Inject
    lateinit var resolverOverrideStore: ResolverOverrideStore

    @Inject
    lateinit var serviceRuntimeRegistry: ServiceRuntimeRegistry

    @Inject
    lateinit var rememberedNetworkPolicyStore: RememberedNetworkPolicyStore

    @Inject
    lateinit var networkHandoverMonitor: NetworkHandoverMonitor

    @Inject
    lateinit var policyHandoverEventStore: PolicyHandoverEventStore

    private var ripDpiProxy: RipDpiProxyRuntime? = null
    private var tun2SocksBridge: Tun2SocksBridge? = null
    private var proxyJob: Job? = null
    private var telemetryJob: Job? = null
    private var handoverMonitorJob: Job? = null
    private var tunSession: VpnTunnelSession? = null
    private var runtimeSession: VpnRuntimeSession? = null
    private val mutex = Mutex()
    @Volatile
    private var stopping: Boolean = false

    private var status: ServiceStatus = ServiceStatus.Disconnected
    private val lifecycleState = ServiceLifecycleStateMachine()

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
        private const val HandoverCooldownMs: Long = 10_000L

        internal fun buildTun2SocksConfig(
            activeDns: ActiveDnsSettings,
            overrideReason: String?,
            socks5Port: Int,
            ipv6Enabled: Boolean,
        ): Tun2SocksConfig =
            Tun2SocksConfig(
                tunnelIpv4 = TUNNEL_IPV4_CIDR,
                tunnelIpv6 = if (ipv6Enabled) TUNNEL_IPV6_CIDR else null,
                socks5Port = socks5Port,
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
        startForeground()
        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop(stopSelfStartId = startId) }
                START_NOT_STICKY
            }

            else -> {
                logcat(LogPriority.WARN) { "Unknown action: $action" }
                lifecycleScope.launch { stop(stopSelfStartId = startId) }
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

        var matchedRememberedPolicy: com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity? = null
        val session = VpnRuntimeSession()
        try {
            val started =
                mutex.withLock {
                    if (!lifecycleState.tryBeginStart()) {
                        logcat(LogPriority.WARN) {
                            "Ignoring VPN start while lifecycle state is ${lifecycleState.state}"
                        }
                        return@withLock false
                    }

                    try {
                        val resolution =
                            connectionPolicyResolver.resolve(
                                mode = Mode.VPN,
                                resolverOverride = resolverOverrideStore.override.value,
                            )
                        matchedRememberedPolicy = resolution.matchedNetworkPolicy
                        applyActiveConnectionPolicy(
                            session = session,
                            resolution = resolution,
                            restartReason = "initial_start",
                            appliedAt = System.currentTimeMillis(),
                        )
                        startProxy(resolution.proxyPreferences)
                        startTun2Socks(
                            session = session,
                            activeDns = resolution.activeDns,
                            overrideReason = resolution.resolverFallbackReason,
                        )
                        runtimeSession = session
                        serviceRuntimeRegistry.register(session)
                        updateStatus(ServiceStatus.Connected)
                        startNetworkHandoverMonitoring()
                        startTelemetryUpdates()
                        lifecycleState.markStarted()
                        true
                    } catch (e: Exception) {
                        lifecycleState.beginStop()
                        throw e
                    }
                }

            if (!started) {
                return
            }
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

    private suspend fun stop(
        skipProxyShutdown: Boolean = false,
        stopSelfStartId: Int? = null,
    ) {
        logcat(LogPriority.INFO) { "Stopping" }

        val stopped =
            mutex.withLock {
                if (stopping) {
                    logcat(LogPriority.WARN) { "VPN stop already in progress" }
                    return@withLock false
                }

                if (lifecycleState.state != ServiceLifecycleStateMachine.State.STOPPING) {
                    lifecycleState.beginStop()
                }
                stopping = true
                try {
                    handoverMonitorJob?.cancel()
                    handoverMonitorJob = null
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
                    try {
                        val session = runtimeSession
                        updateStatus(ServiceStatus.Disconnected)
                        telemetryJob?.cancel()
                        telemetryJob = null
                        resolverOverrideStore.clear()
                        session?.clearActiveConnectionPolicy()
                        session?.let {
                            serviceRuntimeRegistry.unregister(
                                mode = Mode.VPN,
                                runtimeId = it.runtimeId,
                            )
                        }
                        runtimeSession = null
                        val stoppedSelf = stopSelfStartId?.let(::stopSelfResult)
                        if (stoppedSelf == null) {
                            stopSelf()
                        }
                    } finally {
                        stopping = false
                        lifecycleState.markStopped()
                    }
                }
                true
            }

        if (!stopped) {
            return
        }
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
            lifecycleScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
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
            val proxyStartWasActive = job.isActive
            runCatching {
                if (proxyStartWasActive) {
                    proxyInstance.stopProxy()
                }
            }
            job.join()
            proxyJob = null
            ripDpiProxy = null
            throw resolveProxyStartupFailure(
                readinessError = e,
                proxyStartWasActive = proxyStartWasActive,
                proxyStartResult = exitResult.await(),
            )
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
        session: VpnRuntimeSession,
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
        val config = buildTun2SocksConfig(activeDns, overrideReason, port, ipv6)

        val tunnelSession = vpnTunnelSessionProvider.establish(this, dns, ipv6)

        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            tunnelBridge.start(config, tunnelSession.tunFd)
            tun2SocksBridge = tunnelBridge
            tunSession = tunnelSession
            session.currentDnsSignature = dnsSignature(activeDns, overrideReason)
            if (session.tunnelStartCount > 0) {
                session.tunnelRecoveryRetryCount += 1
            }
            session.tunnelStartCount += 1
        } catch (e: Exception) {
            tunnelSession.close()
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
        val session = runtimeSession
        val currentTelemetry = serviceStateStore.telemetry.value
        val proxyTelemetry =
            if (newStatus == ServiceStatus.Connected) {
                NativeRuntimeSnapshot.idle(source = "proxy")
            } else {
                currentTelemetry.proxyTelemetry
            }
        val tunnelTelemetry =
            applyPendingNetworkHandoverClass(
                if (newStatus == ServiceStatus.Connected) {
                    NativeRuntimeSnapshot.idle(source = "tunnel")
                } else {
                    currentTelemetry.tunnelTelemetry
                },
            )
        val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
            currentWinningFamilies(currentTelemetry.runtimeFieldTelemetry)

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
                tunnelStats = tunnelTelemetry.tunnelStats,
                proxyTelemetry = proxyTelemetry,
                tunnelTelemetry = tunnelTelemetry,
                runtimeFieldTelemetry =
                    deriveRuntimeFieldTelemetry(
                        telemetryNetworkFingerprintHash =
                            currentTelemetryFingerprintHash(currentTelemetry.runtimeFieldTelemetry),
                        winningTcpStrategyFamily = winningTcpStrategyFamily,
                        winningQuicStrategyFamily = winningQuicStrategyFamily,
                        winningDnsStrategyFamily = winningDnsStrategyFamily,
                        proxyTelemetry = proxyTelemetry,
                        tunnelTelemetry = tunnelTelemetry,
                        tunnelRecoveryRetryCount = session?.tunnelRecoveryRetryCount ?: 0L,
                        failureReason = failureReason,
                    ),
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
                    val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
                        currentWinningFamilies(serviceStateStore.telemetry.value.runtimeFieldTelemetry)
                    val tunnelTelemetryResult = runCatching { tun2SocksBridge?.telemetry() }
                    val tunnelTelemetry =
                        tunnelTelemetryResult.getOrNull()
                            ?: NativeRuntimeSnapshot.idle(source = "tunnel")
                    val enrichedTunnelTelemetry =
                        applyPendingNetworkHandoverClass(tunnelTelemetry)

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
                            serviceStateStore.updateTelemetry(
                                ServiceTelemetrySnapshot(
                                    mode = Mode.VPN,
                                    status = AppStatus.Running,
                                    tunnelStats = enrichedTunnelTelemetry.tunnelStats,
                                    proxyTelemetry = proxyTelemetry,
                                    tunnelTelemetry = enrichedTunnelTelemetry,
                                    runtimeFieldTelemetry =
                                        deriveRuntimeFieldTelemetry(
                                            telemetryNetworkFingerprintHash =
                                                currentTelemetryFingerprintHash(
                                                    serviceStateStore.telemetry.value.runtimeFieldTelemetry,
                                                ),
                                            winningTcpStrategyFamily = winningTcpStrategyFamily,
                                            winningQuicStrategyFamily = winningQuicStrategyFamily,
                                            winningDnsStrategyFamily = winningDnsStrategyFamily,
                                            proxyTelemetry = proxyTelemetry,
                                            tunnelTelemetry = enrichedTunnelTelemetry,
                                            tunnelRecoveryRetryCount = runtimeSession?.tunnelRecoveryRetryCount ?: 0L,
                                            failureReason = failureReason,
                                        ),
                                    updatedAt =
                                        maxOf(
                                            System.currentTimeMillis(),
                                            proxyTelemetry.capturedAt,
                                            enrichedTunnelTelemetry.capturedAt,
                                        ),
                                ),
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
                            runtimeFieldTelemetry =
                                deriveRuntimeFieldTelemetry(
                                    telemetryNetworkFingerprintHash =
                                        currentTelemetryFingerprintHash(
                                            serviceStateStore.telemetry.value.runtimeFieldTelemetry,
                                        ),
                                    winningTcpStrategyFamily = winningTcpStrategyFamily,
                                    winningQuicStrategyFamily = winningQuicStrategyFamily,
                                    winningDnsStrategyFamily = winningDnsStrategyFamily,
                                    proxyTelemetry = proxyTelemetry,
                                    tunnelTelemetry = enrichedTunnelTelemetry,
                                    tunnelRecoveryRetryCount = runtimeSession?.tunnelRecoveryRetryCount ?: 0L,
                                ),
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
        val session = runtimeSession ?: return
        val resolution =
            connectionPolicyResolver.resolve(
                mode = Mode.VPN,
                resolverOverride = resolverOverrideStore.override.value,
            )
        if (resolution.settings.activeDnsSettings() == resolution.activeDns && resolverOverrideStore.override.value != null) {
            resolverOverrideStore.clear()
        }
        val signature = dnsSignature(resolution.activeDns, resolution.resolverFallbackReason)
        if (tunSession == null || session.currentDnsSignature == signature) {
            return
        }

        mutex.withLock {
            val activeSession = runtimeSession
            if (status != ServiceStatus.Connected || tunSession == null || activeSession?.runtimeId != session.runtimeId) {
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
            if (activeSession.currentDnsSignature == latestSignature) {
                return
            }
            stopTun2Socks()
            startTun2Socks(
                session = activeSession,
                activeDns = latestResolution.activeDns,
                overrideReason = latestResolution.resolverFallbackReason,
            )
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

    private fun currentWinningFamilies(fallback: RuntimeFieldTelemetry): Triple<String?, String?, String?> {
        val activePolicy = runtimeSession?.currentActiveConnectionPolicy?.policy
        return if (activePolicy != null) {
            Triple(
                activePolicy.winningTcpStrategyFamily,
                activePolicy.winningQuicStrategyFamily,
                activePolicy.winningDnsStrategyFamily,
            )
        } else {
            Triple(
                fallback.winningTcpStrategyFamily,
                fallback.winningQuicStrategyFamily,
                fallback.winningDnsStrategyFamily,
            )
        }
    }

    private fun currentTelemetryFingerprintHash(fallback: RuntimeFieldTelemetry): String? =
        telemetryFingerprintHasher.hash(networkFingerprintProvider.capture())
            ?: fallback.telemetryNetworkFingerprintHash

    private fun startNetworkHandoverMonitoring() {
        handoverMonitorJob?.cancel()
        handoverMonitorJob =
            lifecycleScope.launch {
                networkHandoverMonitor.events.collect { event ->
                    runtimeSession?.pendingNetworkHandoverClass = event.classification
                    if (!event.isActionable) {
                        return@collect
                    }
                    handleNetworkHandover(event)
                }
            }
    }

    private suspend fun handleNetworkHandover(event: NetworkHandoverEvent) {
        if (status != ServiceStatus.Connected || stopping) {
            return
        }
        val session = runtimeSession ?: return
        val currentFingerprint = event.currentFingerprint ?: return
        val fingerprintHash = currentFingerprint.scopeKey()
        val now = System.currentTimeMillis()
        if (
            session.lastSuccessfulHandoverFingerprintHash == fingerprintHash &&
            now - session.lastSuccessfulHandoverAt < HandoverCooldownMs
        ) {
            return
        }

        val previousFingerprintHash = session.currentActiveConnectionPolicy?.fingerprintHash
        try {
            val resolution =
                connectionPolicyResolver.resolve(
                    mode = Mode.VPN,
                    resolverOverride = resolverOverrideStore.override.value,
                    fingerprint = currentFingerprint,
                    handoverClassification = event.classification,
                )
            mutex.withLock {
                val activeSession = runtimeSession
                if (status != ServiceStatus.Connected || stopping || activeSession?.runtimeId != session.runtimeId) {
                    return
                }
                stopping = true
                try {
                    stopTun2Socks()
                    stopProxy()
                    applyActiveConnectionPolicy(
                        session = activeSession,
                        resolution = resolution,
                        restartReason = "network_handover",
                        appliedAt = now,
                    )
                    startProxy(resolution.proxyPreferences)
                    startTun2Socks(
                        session = activeSession,
                        activeDns = resolution.activeDns,
                        overrideReason = resolution.resolverFallbackReason,
                    )
                } finally {
                    stopping = false
                }
            }

            session.lastSuccessfulHandoverFingerprintHash = fingerprintHash
            session.lastSuccessfulHandoverAt = now
            policyHandoverEventStore.publish(
                PolicyHandoverEvent(
                    mode = Mode.VPN,
                    previousFingerprintHash = previousFingerprintHash,
                    currentFingerprintHash = fingerprintHash,
                    classification = event.classification,
                    usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                    policySignature = resolution.policySignature,
                    occurredAt = now,
                ),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to restart VPN after handover\n${e.asLog()}" }
            val reason = classifyFailureReason(e, isTunnelContext = true)
            updateStatus(ServiceStatus.Failed, reason)
            stop()
        }
    }

    private fun applyActiveConnectionPolicy(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        val policy = resolution.appliedPolicy ?: run {
            session.clearActiveConnectionPolicy()
            return
        }
        session.updateActiveConnectionPolicy(
            ActiveConnectionPolicy(
                mode = Mode.VPN,
                policy = policy,
                matchedPolicy = resolution.matchedNetworkPolicy,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                fingerprintHash = resolution.fingerprintHash,
                policySignature = resolution.policySignature,
                appliedAt = appliedAt,
                restartReason = restartReason,
                handoverClassification = resolution.handoverClassification,
            ),
        )
    }

    private fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val session = runtimeSession ?: return snapshot
        val classification = session.pendingNetworkHandoverClass ?: return snapshot
        session.pendingNetworkHandoverClass = null
        return snapshot.copy(networkHandoverClass = classification)
    }

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
            .addAddress(TUNNEL_IPV4_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder
                .addAddress(TUNNEL_IPV6_ADDRESS, 128)
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
