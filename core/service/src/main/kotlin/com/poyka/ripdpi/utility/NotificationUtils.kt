package com.poyka.ripdpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.stopAction

fun registerNotificationChannel(
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
            context.getString(name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
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

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    service: Class<*>,
): Notification =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setContentTitle(context.getString(title))
        .setContentText(context.getString(content))
        .addAction(
            R.drawable.ic_notification,
            context.getString(R.string.notification_stop),
            PendingIntent.getService(
                context,
                0,
                Intent(context, service).setAction(stopAction),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

fun createDynamicConnectionNotification(
    context: Context,
    channelId: String,
    title: String,
    content: String,
    subText: String?,
    service: Class<*>,
    whenTimestamp: Long,
): Notification =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentTitle(title)
        .setContentText(content)
        .apply { if (subText != null) setSubText(subText) }
        .setWhen(whenTimestamp)
        .setShowWhen(true)
        .setUsesChronometer(true)
        .addAction(
            R.drawable.ic_notification,
            context.getString(R.string.notification_stop),
            PendingIntent.getService(
                context,
                0,
                Intent(context, service).setAction(stopAction),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
