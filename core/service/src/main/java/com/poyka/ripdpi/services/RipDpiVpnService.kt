package com.poyka.ripdpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.TProxyService
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.settingsStore
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File

class RipDpiVpnService : LifecycleVpnService() {
    private val ripDpiProxy = RipDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var tun2SocksConfigFile: File? = null
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

        try {
            mutex.withLock {
                startProxy()
                startTun2Socks()
            }
            updateStatus(ServiceStatus.Connected)
            startForeground()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to start VPN\n${e.asLog()}" }
            updateStatus(ServiceStatus.Failed)
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
                stopTun2Socks()
                stopProxy()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to stop VPN\n${e.asLog()}" }
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private suspend fun startProxy() {
        logcat(LogPriority.INFO) { "Starting proxy" }

        if (proxyJob != null) {
            logcat(LogPriority.WARN) { "Proxy fields not null" }
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = getRipDpiPreferences()

        proxyJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val code = ripDpiProxy.startProxy(preferences)

                withContext(Dispatchers.Main) {
                    if (code != 0) {
                        logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                        updateStatus(ServiceStatus.Failed)
                    } else {
                        if (!stopping) {
                            stop()
                            updateStatus(ServiceStatus.Disconnected)
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

        ripDpiProxy.stopProxy()
        proxyJob?.join() ?: throw IllegalStateException("ProxyJob field null")
        proxyJob = null

        logcat(LogPriority.INFO) { "Proxy stopped" }
    }

    private suspend fun startTun2Socks() {
        logcat(LogPriority.INFO) { "Starting tun2socks" }

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val settings = withContext(Dispatchers.IO) { settingsStore.data.first() }
        val port = if (settings.proxyPort > 0) settings.proxyPort else 1080
        val dns = settings.dnsIp.ifEmpty { "1.1.1.1" }
        val ipv6 = settings.ipv6Enable

        val tun2socksConfig =
            """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   mtu: 8500
        |   address: 127.0.0.1
        |   port: $port
        |   udp: udp
        """.trimMargin("| ")

        val configPath =
            try {
                File.createTempFile("config", "tmp", cacheDir).apply {
                    writeText(tun2socksConfig)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to create config file\n${e.asLog()}" }
                throw e
            }
        tun2SocksConfigFile = configPath

        val fd =
            createBuilder(dns, ipv6).establish()
                ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        logcat(LogPriority.INFO) { "Tun2Socks started" }
    }

    private fun stopTun2Socks() {
        logcat(LogPriority.INFO) { "Stopping tun2socks" }

        TProxyService.TProxyStopService()

        try {
            val configFile = tun2SocksConfigFile
            if (configFile != null && !configFile.delete() && configFile.exists()) {
                logcat(LogPriority.WARN) { "Failed to delete config file: ${configFile.absolutePath}" }
            }
        } catch (e: SecurityException) {
            logcat(LogPriority.ERROR) { "Failed to delete config file\n${e.asLog()}" }
        } finally {
            tun2SocksConfigFile = null
        }

        tunFd?.close() ?: logcat(LogPriority.WARN) { "VPN not running" }
        tunFd = null

        logcat(LogPriority.INFO) { "Tun2socks stopped" }
    }

    private suspend fun getRipDpiPreferences(): RipDpiProxyPreferences = RipDpiProxyPreferences.fromSettingsStore(this)

    private fun updateStatus(newStatus: ServiceStatus) {
        logcat { "VPN status changed from $status to $newStatus" }

        status = newStatus

        val appStatus =
            when (newStatus) {
                ServiceStatus.Connected -> {
                    AppStatus.Running
                }

                ServiceStatus.Disconnected,
                ServiceStatus.Failed,
                -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            }
        AppStateManager.setStatus(appStatus, Mode.VPN)

        if (newStatus == ServiceStatus.Failed) {
            AppStateManager.emitFailed(Sender.VPN)
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

    private fun createBuilder(
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

        builder.addDisallowedApplication(applicationContext.packageName)

        return builder
    }
}
