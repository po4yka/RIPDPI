package com.poyka.ripdpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.utility.NotificationContentBuilder

/** One owner for the foreground notification of a VPN or proxy service. */
internal class ForegroundNotificationController(
    private val service: Service,
    private val target: ConnectionNotificationTarget,
    @param:StringRes private val startingContent: Int,
    private val serviceStartedAt: () -> Long?,
    private val showStopAction: () -> Boolean = { true },
) {
    fun registerChannel() = registerConnectionChannel(service, target.channelId, target.channelName)

    fun startForeground() {
        service.startConnectionForeground(
            target.notificationId,
            notification(ContextCompat.getString(service, startingContent), startedAt = null, subText = null),
        )
    }

    fun update(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        val startedAt = serviceStartedAt() ?: return
        val content =
            NotificationContentBuilder.buildContentText(
                context = service,
                txBytes = tunnelStats.txBytes,
                rxBytes = tunnelStats.rxBytes,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        val subText =
            NotificationContentBuilder.buildSubText(
                context = service,
                activeSessions = proxyTelemetry.activeSessions,
                rttMs = proxyTelemetry.upstreamRttMs,
            )
        val notification = notification(content, startedAt, subText)
        @Suppress("SwallowedException")
        try {
            service
                .getSystemService(NotificationManager::class.java)
                ?.notify(target.notificationId, notification)
        } catch (e: SecurityException) {
            Logger.w { "Cannot update notification: permission revoked" }
        }
    }

    private fun notification(
        content: String,
        startedAt: Long?,
        subText: String?,
    ): Notification =
        createConnectionNotification(
            context = service,
            channelId = target.channelId,
            title = ContextCompat.getString(service, R.string.notification_title),
            content = content,
            service = service.javaClass,
            startedAt = startedAt,
            showStopAction = showStopAction(),
        ).also { built ->
            subText?.let { built.extras.putCharSequence(Notification.EXTRA_SUB_TEXT, it) }
        }
}
