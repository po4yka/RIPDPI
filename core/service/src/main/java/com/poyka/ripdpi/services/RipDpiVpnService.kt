package com.poyka.ripdpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.TProxyService
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.settingsStore
import com.poyka.ripdpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class RipDpiVpnService : LifecycleVpnService() {
    private val ripDpiProxy = RipDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    private var status: ServiceStatus = ServiceStatus.Disconnected

    companion object {
        private val TAG: String = RipDpiVpnService::class.java.simpleName
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

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
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
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
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
            Log.e(TAG, "Failed to start VPN", e)
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
        Log.i(TAG, "Stopping")

        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
                stopProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private suspend fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = getRipDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = ripDpiProxy.startProxy(preferences)

            withContext(Dispatchers.Main) {
                if (code != 0) {
                    Log.e(TAG, "Proxy stopped with code $code")
                    updateStatus(ServiceStatus.Failed)
                } else {
                    if (!stopping) {
                        stop()
                        updateStatus(ServiceStatus.Disconnected)
                    }
                }
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        if (status == ServiceStatus.Disconnected) {
            Log.w(TAG, "Proxy already disconnected")
            return
        }

        ripDpiProxy.stopProxy()
        proxyJob?.join() ?: throw IllegalStateException("ProxyJob field null")
        proxyJob = null

        Log.i(TAG, "Proxy stopped")
    }

    private suspend fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val settings = withContext(Dispatchers.IO) { settingsStore.data.first() }
        val port = if (settings.proxyPort > 0) settings.proxyPort else 1080
        val dns = settings.dnsIp.ifEmpty { "1.1.1.1" }
        val ipv6 = settings.ipv6Enable

        val tun2socksConfig = """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   mtu: 8500
        |   address: 127.0.0.1
        |   port: $port
        |   udp: udp
        """.trimMargin("| ")

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        Log.i(TAG, "Tun2Socks started")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        TProxyService.TProxyStopService()

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        tunFd?.close() ?: Log.w(TAG, "VPN not running")
        tunFd = null

        Log.i(TAG, "Tun2socks stopped")
    }

    private suspend fun getRipDpiPreferences(): RipDpiProxyPreferences =
        RipDpiProxyPreferences.fromSettingsStore(this)

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        val appStatus = when (newStatus) {
            ServiceStatus.Connected -> AppStatus.Running
            ServiceStatus.Disconnected,
            ServiceStatus.Failed -> {
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

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession("RIPDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
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
