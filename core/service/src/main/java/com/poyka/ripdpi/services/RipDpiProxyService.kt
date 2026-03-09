package com.poyka.ripdpi.services

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

class RipDpiProxyService : LifecycleService() {
    private var proxy = RipDpiProxy()
    private var proxyJob: Job? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    private var status: ServiceStatus = ServiceStatus.Disconnected

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPI Proxy"
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
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

    private suspend fun start() {
        logcat(LogPriority.INFO) { "Starting" }

        if (status == ServiceStatus.Connected) {
            logcat(LogPriority.WARN) { "Proxy already connected" }
            return
        }

        try {
            mutex.withLock {
                startProxy()
            }
            updateStatus(ServiceStatus.Connected)
            startForeground()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to start proxy\n${e.asLog()}" }
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
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        logcat(LogPriority.INFO) { "Stopping proxy" }

        mutex.withLock {
            stopping = true
            try {
                stopProxy()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to stop proxy\n${e.asLog()}" }
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

        proxy = RipDpiProxy()
        val preferences = getRipDpiPreferences()

        proxyJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val code = proxy.startProxy(preferences)

                withContext(Dispatchers.Main) {
                    if (code != 0) {
                        logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                        updateStatus(ServiceStatus.Failed)
                    } else if (!stopping) {
                        updateStatus(ServiceStatus.Disconnected)
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

        proxy.stopProxy()
        proxyJob?.join()
        proxyJob = null

        logcat(LogPriority.INFO) { "Proxy stopped" }
    }

    private suspend fun getRipDpiPreferences(): RipDpiProxyPreferences = RipDpiProxyPreferences.fromSettingsStore(this)

    private fun updateStatus(newStatus: ServiceStatus) {
        logcat { "Proxy status changed from $status to $newStatus" }

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
        AppStateManager.setStatus(appStatus, Mode.Proxy)

        if (newStatus == ServiceStatus.Failed) {
            AppStateManager.emitFailed(Sender.Proxy)
        }
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            RipDpiProxyService::class.java,
        )
}
