package com.poyka.ripdpi.services

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LogTags

private const val AlertChannelId = "RIPDPIConnectionAlerts"
private const val AlertNotificationId = 3

/** Unfinished cleanup follows a user stop, and VPN consent loss follows the user or another VPN taking over. */
internal fun FailureReason?.warrantsConnectionAlert(): Boolean =
    this != RuntimeCleanupIncompleteFailure && this != FailureReason.PermissionLost("VPN")

/**
 * The foreground notification usually leaves with its service, so a failure needs its own notification.
 * A visible app already shows the failure on Home.
 */
internal fun showConnectionFailureAlert(
    context: Context,
    appVisible: Boolean = isAppVisible(),
) {
    if (appVisible) return
    @Suppress("TooGenericExceptionCaught")
    try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val localized = ContextCompat.getContextForLanguage(context)
        manager.createNotificationChannel(
            NotificationChannel(
                AlertChannelId,
                localized.getString(R.string.notification_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = localized.getString(R.string.notification_alert_channel_description) },
        )
        manager.notify(
            AlertNotificationId,
            NotificationCompat
                .Builder(context, AlertChannelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.notification_alert_title))
                .setContentText(localized.getString(R.string.notification_alert_text))
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent(context))
                .build(),
        )
    } catch (e: RuntimeException) {
        // Failure reporting must continue if the alert cannot be posted.
        Logger.withTag(LogTags.SERVICE).w { "Failed to post connection alert: ${e.message}" }
    }
}

// A foreground service alone reports IMPORTANCE_FOREGROUND_SERVICE, so this means a visible activity.
private fun isAppVisible(): Boolean {
    val memoryState = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(memoryState)
    return memoryState.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

internal fun cancelConnectionFailureAlert(context: Context) {
    context.getSystemService(NotificationManager::class.java)?.cancel(AlertNotificationId)
}
