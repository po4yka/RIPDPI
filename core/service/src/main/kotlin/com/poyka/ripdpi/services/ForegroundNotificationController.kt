package com.poyka.ripdpi.services

import android.app.NotificationManager
import android.app.Service
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats

/** One owner for the foreground notification of a VPN or proxy service. */
internal class ForegroundNotificationController(
    private val service: Service,
    private val target: ConnectionNotificationTarget,
    private val telemetry: () -> ServiceTelemetrySnapshot?,
    private val showStopAction: () -> Boolean = { true },
) {
    @Volatile private var phase: ConnectionNotificationPhase = ConnectionNotificationPhase.Connecting

    fun registerChannel() = registerConnectionChannel(service, target.channelId, target.channelName)

    /** Publishes the current state; a live connection keeps its state across repeated service commands. */
    fun startForeground(action: String?) {
        if (phase !is ConnectionNotificationPhase.Connected) {
            phase =
                if (action == null || isServiceRecoveryStartAction(action)) {
                    ConnectionNotificationPhase.Reconnecting
                } else {
                    ConnectionNotificationPhase.Connecting
                }
        }
        service.startConnectionForeground(target.notificationId, notification())
    }

    fun refresh() = service.startConnectionForeground(target.notificationId, notification())

    /** Traffic refreshes only for a visible screen; a state change is published at any time. */
    fun update(
        tunnelStats: TunnelStats,
        interactive: Boolean,
    ) {
        val next = telemetry()?.let { connectionNotificationPhase(it, tunnelStats) } ?: return
        val stateChanged = !next.hasSameStateAs(phase)
        phase = next
        if (!interactive && !stateChanged) return
        @Suppress("SwallowedException")
        try {
            service
                .getSystemService(NotificationManager::class.java)
                ?.notify(target.notificationId, notification())
        } catch (e: SecurityException) {
            Logger.w { "Cannot update notification: permission revoked" }
        }
    }

    private fun notification() =
        connectionNotificationContent(service, target, phase).let { content ->
            createConnectionNotification(
                context = service,
                channelId = target.channelId,
                title = content.title,
                content = content.text,
                service = service.javaClass,
                startedAt = content.startedAt,
                showStopAction = showStopAction(),
            )
        }
}
