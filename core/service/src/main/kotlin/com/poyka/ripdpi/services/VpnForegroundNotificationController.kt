package com.poyka.ripdpi.services

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.utility.NotificationContentBuilder
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.createDynamicConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel

internal class VpnForegroundNotificationController(
    private val serviceStateStore: ServiceStateStore,
) {
    fun registerChannel(service: RipDpiVpnService) {
        registerNotificationChannel(
            service,
            NotificationChannelId,
            R.string.vpn_channel_name,
        )
    }

    fun startForeground(service: RipDpiVpnService) {
        val notification: Notification = createNotification(service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                ForegroundServiceId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(ForegroundServiceId, notification)
        }
    }

    fun update(
        service: RipDpiVpnService,
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
                context = service,
                channelId = NotificationChannelId,
                title = service.getString(R.string.notification_title),
                content = content,
                subText = subText,
                service = RipDpiVpnService::class.java,
                whenTimestamp = startedAt,
            )
        @Suppress("SwallowedException")
        try {
            service
                .getSystemService(NotificationManager::class.java)
                ?.notify(ForegroundServiceId, notification)
        } catch (e: SecurityException) {
            Logger.w { "Cannot update notification: permission revoked" }
        }
    }

    private fun createNotification(service: RipDpiVpnService): Notification =
        createConnectionNotification(
            service,
            NotificationChannelId,
            R.string.notification_title,
            R.string.vpn_notification_content,
            RipDpiVpnService::class.java,
        )

    private companion object {
        private const val ForegroundServiceId: Int = 1
        private const val NotificationChannelId: String = "RIPDPIVpn"
    }
}
