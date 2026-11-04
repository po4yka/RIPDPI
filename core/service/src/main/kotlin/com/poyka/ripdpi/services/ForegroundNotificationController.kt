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

    // Partial cleanup shows pause text after the pause authority has already cleared.
    private var shownPause: PauseIntent? = null
    private var closed = false

    fun registerChannel() = registerConnectionChannel(service, target.channelId, target.channelName)

    /** Satisfies the foreground contract on service entry, before anyone decides whether the command runs. */
    fun refresh(): Unit =
        synchronized(lock) {
            val pause = pauseIntent() ?: shownPause
            if (pause != null) {
                showPaused(pause)
            } else {
                service.startConnectionForeground(target.notificationId, notification())
            }
        }

    /** Publishes the state an accepted service command leads to; a live connection keeps its state otherwise. */
    fun onCommandAccepted(action: String?): Unit =
        synchronized(lock) {
            if (pauseIntent() == null) {
                shownPause = null
                applyCommand(action)
            }
            refresh()
        }

    fun showPaused(intent: PauseIntent): Unit =
        synchronized(lock) {
            phase = ConnectionNotificationPhase.Connecting
            shownPause = intent
            startPausedForeground(service, intent, target.channelId, target.notificationId)
        }

    /** Removes the notification for good: a late event must not leave an ongoing one that no service owns. */
    fun close(): Unit =
        synchronized(lock) {
            closed = true
            service.getSystemService(NotificationManager::class.java)?.cancel(target.notificationId)
        }

    /** Covers a failure that keeps the service alive, such as retained fail-closed or unfinished cleanup. */
    fun onConnectionFailed(): Unit =
        synchronized(lock) {
            if (pauseIntent() == null) {
                phase = ConnectionNotificationPhase.Failed
                shownPause = null
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
                shownPause = null
                val stateChanged = !next.hasSameStateAs(phase)
                if (next is ConnectionNotificationPhase.Connected && stateChanged) {
                    cancelConnectionFailureAlert(service)
                }
                phase = next.keepingTrafficFrom(phase)
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
        if (closed) return
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
