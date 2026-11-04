package com.poyka.ripdpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.SystemClock
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats

/**
 * One owner for the foreground notification of a VPN or proxy service.
 * Service commands and the telemetry loop run on different threads, so every transition holds [lock].
 */
internal class ForegroundNotificationController(
    private val service: Service,
    private val target: ConnectionNotificationTarget,
    private val telemetry: () -> ServiceTelemetrySnapshot?,
    private val pauseIntent: () -> PauseIntent?,
    private val allowsStop: (action: String) -> Boolean = { true },
) {
    private val lock = Any()
    private var phase: ConnectionNotificationPhase = ConnectionNotificationPhase.Connecting
    private var stoppingSinceMillis = 0L

    fun registerChannel() = registerConnectionChannel(service, target.channelId, target.channelName)

    /** Publishes the state a service command leads to; a live connection keeps its state across other commands. */
    fun startForeground(action: String?): Unit =
        synchronized(lock) {
            if (pauseIntent() == null) applyCommand(action)
            refresh()
        }

    fun refresh(): Unit =
        synchronized(lock) {
            val paused = pauseIntent()
            if (paused != null) {
                showPaused(paused)
            } else {
                service.startConnectionForeground(target.notificationId, notification())
            }
        }

    fun showPaused(intent: PauseIntent): Unit =
        synchronized(lock) {
            phase = ConnectionNotificationPhase.Connecting
            startPausedForeground(service, intent, target.channelId, target.notificationId)
        }

    /** Covers a failure that keeps the service alive, such as retained fail-closed or unfinished cleanup. */
    fun onConnectionFailed(): Unit =
        synchronized(lock) {
            if (pauseIntent() == null) {
                phase = ConnectionNotificationPhase.Failed
                post()
            }
        }

    /** Traffic refreshes only for a visible screen; a state change is published at any time. */
    fun update(
        tunnelStats: TunnelStats,
        interactive: Boolean,
    ): Unit =
        synchronized(lock) {
            val next =
                telemetry()
                    ?.takeUnless { pauseIntent() != null || isStopInProgress() }
                    ?.let { connectionNotificationPhase(it, tunnelStats) }
            if (next != null) {
                val stateChanged = !next.hasSameStateAs(phase)
                if (next is ConnectionNotificationPhase.Connected && stateChanged) {
                    cancelConnectionFailureAlert(service)
                }
                phase = next
                if (interactive || stateChanged) post()
            }
        }

    private fun applyCommand(action: String?) {
        if (action != null && isUserServiceStopAction(action)) {
            if (allowsStop(action)) {
                phase = ConnectionNotificationPhase.Stopping
                stoppingSinceMillis = SystemClock.elapsedRealtime()
            }
        } else if (!isLiveConnection()) {
            phase =
                if (action == null || isServiceRecoveryStartAction(action)) {
                    ConnectionNotificationPhase.Reconnecting
                } else {
                    ConnectionNotificationPhase.Connecting
                }
        }
    }

    private fun isLiveConnection(): Boolean =
        phase is ConnectionNotificationPhase.Connected && telemetry()?.serviceStartedAt != null

    // A rejected stop never ends this phase, so the live state returns after the hold.
    private fun isStopInProgress(): Boolean =
        phase == ConnectionNotificationPhase.Stopping &&
            SystemClock.elapsedRealtime() - stoppingSinceMillis < StoppingHoldMillis

    private fun post() {
        @Suppress("SwallowedException")
        try {
            service
                .getSystemService(NotificationManager::class.java)
                ?.notify(target.notificationId, notification())
        } catch (e: SecurityException) {
            Logger.w { "Cannot update notification: permission revoked" }
        }
    }

    private fun notification(): Notification {
        val content = connectionNotificationContent(service, target, phase)
        return createConnectionNotification(
            context = service,
            channelId = target.channelId,
            title = content.title,
            content = content.text,
            service = service.javaClass,
            startedAt = content.startedAt,
            showStopAction = phase != ConnectionNotificationPhase.Stopping && allowsStop(notificationStopAction),
        )
    }

    private companion object {
        const val StoppingHoldMillis = 30_000L
    }
}
