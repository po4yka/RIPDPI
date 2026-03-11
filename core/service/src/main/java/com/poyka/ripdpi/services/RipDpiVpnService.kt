package com.poyka.ripdpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class RipDpiVpnService : LifecycleVpnService() {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var proxyPreferencesResolver: ProxyPreferencesResolver

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

    private var ripDpiProxy: RipDpiProxyRuntime? = null
    private var tun2SocksBridge: Tun2SocksBridge? = null
    private var proxyJob: Job? = null
    private var telemetryJob: Job? = null
    private var tunSession: VpnTunnelSession? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    private var status: ServiceStatus = ServiceStatus.Disconnected

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPIVpn"
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
        try {
            mutex.withLock {
                startProxy()
                startTun2Socks()
            }
            updateStatus(ServiceStatus.Connected)
            startTelemetryUpdates()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to start VPN\n${e.asLog()}" }
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

    private suspend fun stop() {
        logcat(LogPriority.INFO) { "Stopping" }

        mutex.withLock {
            stopping = true
            try {
                var shutdownError: Exception? = null
                try {
                    stopTun2Socks()
                } catch (e: Exception) {
                    shutdownError = e
                    logcat(LogPriority.ERROR) { "Failed to stop tunnel\n${e.asLog()}" }
                }
                try {
                    stopProxy()
                } catch (e: Exception) {
                    if (shutdownError == null) {
                        shutdownError = e
                    } else {
                        shutdownError.addSuppressed(e)
                    }
                    logcat(LogPriority.ERROR) { "Failed to stop proxy\n${e.asLog()}" }
                }
                shutdownError?.let { throw it }
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        telemetryJob?.cancel()
        telemetryJob = null
        stopSelf()
    }

    private suspend fun startProxy() {
        logcat(LogPriority.INFO) { "Starting proxy" }

        if (proxyJob != null) {
            logcat(LogPriority.WARN) { "Proxy fields not null" }
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = proxyPreferencesResolver.resolve()
        val proxyInstance = ripDpiProxyFactory.create()
        ripDpiProxy = proxyInstance

        proxyJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val code = proxyInstance.startProxy(preferences)

                withContext(Dispatchers.Main) {
                    if (code != 0) {
                        logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                        updateStatus(ServiceStatus.Failed, FailureReason.NativeError("Proxy exited with code $code"))
                        if (!stopping) {
                            stop()
                        }
                    } else {
                        if (!stopping) {
                            stop()
                        }
                    }
                }
            }

        logcat(LogPriority.INFO) { "Proxy started" }
    }

    private suspend fun stopProxy() {
        logcat(LogPriority.INFO) { "Stopping proxy" }

        if (status == ServiceStatus.Disconnected) {
            logcat(LogPriority.WARN) { "Proxy already disconnected" }
            return
        }

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

    private suspend fun startTun2Socks() {
        logcat(LogPriority.INFO) { "Starting tun2socks" }

        if (tunSession != null) {
            throw IllegalStateException("VPN field not null")
        }

        val settings = appSettingsRepository.snapshot()
        val port = if (settings.proxyPort > 0) settings.proxyPort else 1080
        val dns = settings.dnsIp.ifEmpty { "1.1.1.1" }
        val ipv6 = settings.ipv6Enable
        val config =
            Tun2SocksConfig(
                socks5Port = port,
            )

        val session = vpnTunnelSessionProvider.establish(this, dns, ipv6)

        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            tunnelBridge.start(config, session.tunFd)
            tun2SocksBridge = tunnelBridge
            tunSession = session
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
                    val proxyTelemetry =
                        runCatching { ripDpiProxy?.pollTelemetry() }.getOrNull()
                            ?: NativeRuntimeSnapshot.idle(source = "proxy")
                    val tunnelTelemetry =
                        runCatching { tun2SocksBridge?.telemetry() }.getOrNull()
                            ?: NativeRuntimeSnapshot.idle(source = "tunnel")
                    serviceStateStore.updateTelemetry(
                        ServiceTelemetrySnapshot(
                            mode = Mode.VPN,
                            status = AppStatus.Running,
                            tunnelStats = tunnelTelemetry.tunnelStats,
                            proxyTelemetry = proxyTelemetry,
                            tunnelTelemetry = tunnelTelemetry,
                            updatedAt =
                                maxOf(
                                    System.currentTimeMillis(),
                                    proxyTelemetry.capturedAt,
                                    tunnelTelemetry.capturedAt,
                                ),
                        ),
                    )
                    delay(1_000)
                }
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
