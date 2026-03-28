package com.poyka.ripdpi.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.utility.NotificationContentBuilder
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.createDynamicConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import logcat.LogPriority
import logcat.logcat
import javax.inject.Inject

@AndroidEntryPoint
class RipDpiProxyService :
    LifecycleService(),
    ServiceCoordinatorHost {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    internal lateinit var coordinatorFactory: ProxyServiceRuntimeCoordinatorFactory

    private lateinit var coordinator: ProxyServiceRuntimeCoordinator
    private lateinit var shellDelegate: ServiceShellDelegate

    override val serviceScope = lifecycleScope

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
        coordinator = coordinatorFactory.create(host = this)
        shellDelegate =
            ServiceShellDelegate(
                serviceScope = lifecycleScope,
                serviceLabel = "proxy",
                onStart = coordinator::start,
                onStop = coordinator::stop,
            )
    }

    override fun onDestroy() {
        coordinator.onDestroy()
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
            logcat(LogPriority.WARN) { "Sticky restart aborted: notification permission revoked" }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForegroundService()
        return shellDelegate.onStartCommand(intent?.action, startId)
    }

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        val startedAt = serviceStateStore.telemetry.value.serviceStartedAt ?: return
        val elapsedMs = System.currentTimeMillis() - startedAt
        val content =
            NotificationContentBuilder.buildContentText(
                txBytes = proxyTelemetry.tunnelStats.txBytes,
                rxBytes = proxyTelemetry.tunnelStats.rxBytes,
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
                service = RipDpiProxyService::class.java,
                whenTimestamp = startedAt,
            )
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(FOREGROUND_SERVICE_ID, notification)
        } catch (e: SecurityException) {
            logcat(LogPriority.WARN) { "Cannot update notification: permission revoked" }
        }
    }

    override fun requestStopSelf(stopSelfStartId: Int?) {
        val stoppedSelf = stopSelfStartId?.let(::stopSelfResult)
        if (stoppedSelf == null) {
            stopSelf()
        }
    }

    private fun startForegroundService() {
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

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            RipDpiProxyService::class.java,
        )

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPI Proxy"
    }
}
