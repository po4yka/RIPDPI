package com.poyka.ripdpi.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats

/** Identity of the one foreground notification that each service owns. */
internal enum class ConnectionNotificationTarget(
    val channelId: String,
    @param:StringRes val channelName: Int,
    val notificationId: Int,
) {
    Vpn("RIPDPIVpn", R.string.vpn_channel_name, 1),
    Proxy("RIPDPI Proxy", R.string.proxy_channel_name, 2),
}

/** What the connection is doing, independent of how it is rendered. */
internal sealed interface ConnectionNotificationPhase {
    data object Connecting : ConnectionNotificationPhase

    data object Reconnecting : ConnectionNotificationPhase

    data object Stopping : ConnectionNotificationPhase

    data class Connected(
        val warning: Boolean,
        val traffic: TunnelStats,
        val startedAt: Long,
    ) : ConnectionNotificationPhase
}

internal fun ConnectionNotificationPhase.hasSameStateAs(other: ConnectionNotificationPhase): Boolean =
    if (this is ConnectionNotificationPhase.Connected && other is ConnectionNotificationPhase.Connected) {
        warning == other.warning
    } else {
        this == other
    }

internal fun connectionNotificationPhase(
    telemetry: ServiceTelemetrySnapshot,
    traffic: TunnelStats,
): ConnectionNotificationPhase? {
    val startedAt = telemetry.serviceStartedAt ?: return null
    return if (telemetry.networkHandoverState in HandoverInProgressStates) {
        ConnectionNotificationPhase.Reconnecting
    } else {
        ConnectionNotificationPhase.Connected(telemetry.hasActiveImpairment(), traffic, startedAt)
    }
}

// A persistent title reports what is wrong now. Home keeps the session's cumulative warnings.
private fun ServiceTelemetrySnapshot.hasActiveImpairment(): Boolean =
    relayFailed ||
        tunnelTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
        proxyTelemetryStatus.state == RuntimeTelemetryState.EngineError

private val HandoverInProgressStates =
    setOf(
        NetworkHandoverStates.Observed,
        NetworkHandoverStates.WaitingForNetwork,
        NetworkHandoverStates.DeferredCaptivePortal,
        NetworkHandoverStates.Restarting,
        NetworkHandoverStates.RetryScheduled,
    )

internal data class ConnectionNotificationContent(
    val title: String,
    val text: String? = null,
    val startedAt: Long? = null,
)

internal fun connectionNotificationContent(
    context: Context,
    target: ConnectionNotificationTarget,
    phase: ConnectionNotificationPhase,
): ConnectionNotificationContent {
    val localized = ContextCompat.getContextForLanguage(context)
    return when (phase) {
        ConnectionNotificationPhase.Connecting -> {
            ConnectionNotificationContent(localized.getString(R.string.notification_status_connecting))
        }

        ConnectionNotificationPhase.Reconnecting -> {
            ConnectionNotificationContent(localized.getString(R.string.notification_status_reconnecting))
        }

        ConnectionNotificationPhase.Stopping -> {
            ConnectionNotificationContent(localized.getString(R.string.notification_status_stopping))
        }

        is ConnectionNotificationPhase.Connected -> {
            ConnectionNotificationContent(
                title =
                    localized.getString(
                        if (phase.warning) {
                            R.string.notification_status_warning
                        } else {
                            R.string.notification_status_connected
                        },
                        localized.getString(target.channelName),
                    ),
                text = connectionTrafficText(context, phase.traffic.txBytes, phase.traffic.rxBytes),
                startedAt = phase.startedAt,
            )
        }
    }
}

/** Words instead of arrows so a screen reader announces the meaning; the formatter localizes and isolates sizes. */
internal fun connectionTrafficText(
    context: Context,
    txBytes: Long,
    rxBytes: Long,
): String {
    val localized = ContextCompat.getContextForLanguage(context)
    return localized.getString(
        R.string.notification_traffic,
        Formatter.formatShortFileSize(localized, rxBytes),
        Formatter.formatShortFileSize(localized, txBytes),
    )
}

internal fun Service.startConnectionForeground(
    notificationId: Int,
    notification: Notification,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(notificationId, notification)
    }
}

internal fun registerConnectionChannel(
    context: Context,
    id: String,
    @StringRes name: Int,
) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager == null) {
        Logger.withTag(LogTags.SERVICE).w { "NotificationManager unavailable, skipping channel registration" }
        return
    }

    val channel =
        NotificationChannel(
            id,
            ContextCompat.getString(context, name),
            NotificationManager.IMPORTANCE_LOW,
        )
    channel.description = ContextCompat.getString(context, R.string.notification_channel_description)
    channel.enableLights(false)
    channel.enableVibration(false)
    channel.setShowBadge(false)

    @Suppress("TooGenericExceptionCaught")
    try {
        manager.createNotificationChannel(channel)
    } catch (e: Exception) {
        Logger.withTag(LogTags.SERVICE).w { "Failed to create notification channel '$id': ${e.message}" }
    }
}

/** Defaults shared by every state of the connection notification, including pause. */
internal fun connectionNotificationBuilder(
    context: Context,
    channelId: String,
): NotificationCompat.Builder =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openAppPendingIntent(context))

internal fun openAppPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        context.packageManager.getLaunchIntentForPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    )

fun createConnectionNotification(
    context: Context,
    channelId: String,
    title: String,
    content: String?,
    service: Class<*>,
    startedAt: Long? = null,
    showStopAction: Boolean = true,
): Notification =
    connectionNotificationBuilder(context, channelId)
        .setContentTitle(title)
        .setContentText(content)
        .apply {
            if (startedAt != null) setWhen(startedAt).setShowWhen(true).setUsesChronometer(true)
            if (showStopAction) {
                addAction(
                    R.drawable.ic_notification,
                    ContextCompat.getString(context, R.string.notification_stop),
                    PendingIntent.getService(
                        context,
                        0,
                        Intent(context, service).setAction(notificationStopAction),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }.build()
