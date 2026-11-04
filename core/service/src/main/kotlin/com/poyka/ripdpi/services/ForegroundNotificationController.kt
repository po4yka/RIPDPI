package com.poyka.ripdpi.services

import android.app.NotificationManager
import android.app.Service
import android.os.SystemClock
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats

/** One owner for the foreground notification of a VPN or proxy service. */
internal class ForegroundNotificationController(
    private val service: Service,
    private val target: ConnectionNotificationTarget,
    private val telemetry: () -> ServiceTelemetrySnapshot?,
    private val pauseIntent: () -> PauseIntent?,
    private val showStopAction: () -> Boolean = { true },
) {
    @Volatile private var phase: ConnectionNotificationPhase = ConnectionNotificationPhase.Connecting

    @Volatile private var stoppingSinceMillis = 0L

    fun registerChannel() = registerConnectionChannel(service, target.channelId, target.channelName)

    /** Publishes the state a service command leads to; a live connection keeps its state across other commands. */
    fun startForeground(action: String?) {
        val paused = pauseIntent()
        when {
            isUserServiceStopAction(action) && showStopAction() -> {
                phase = ConnectionNotificationPhase.Stopping
                stoppingSinceMillis = SystemClock.elapsedRealtime()
            }

            paused != null -> {
                return showPaused(paused)
            }

            phase !is ConnectionNotificationPhase.Connected -> {
                phase =
                    if (action == null || isServiceRecoveryStartAction(action)) {
                        ConnectionNotificationPhase.Reconnecting
                    } else {
                        ConnectionNotificationPhase.Connecting
                    }
            }
        }
        refresh()
    }

    fun showPaused(intent: PauseIntent) {
        phase = ConnectionNotificationPhase.Connecting
        startPausedForeground(service, intent, target.channelId, target.notificationId)
    }

    fun refresh() = service.startConnectionForeground(target.notificationId, notification())

    /** Traffic refreshes only for a visible screen; a state change is published at any time. */
    fun update(
        tunnelStats: TunnelStats,
        interactive: Boolean,
    ) {
        if (pauseIntent() != null || isStopInProgress()) return
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

    // A rejected stop never ends this phase, so the live state returns after the hold.
    private fun isStopInProgress(): Boolean =
        phase == ConnectionNotificationPhase.Stopping &&
            SystemClock.elapsedRealtime() - stoppingSinceMillis < StoppingHoldMillis

    private fun notification() =
        connectionNotificationContent(service, target, phase).let { content ->
            createConnectionNotification(
                context = service,
                channelId = target.channelId,
                title = content.title,
                content = content.text,
                service = service.javaClass,
                startedAt = content.startedAt,
                showStopAction = phase != ConnectionNotificationPhase.Stopping && showStopAction(),
            )
        }

    private companion object {
        const val StoppingHoldMillis = 10_000L
    }
}
