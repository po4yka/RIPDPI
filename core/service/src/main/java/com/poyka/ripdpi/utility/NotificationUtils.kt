package com.poyka.ripdpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.STOP_ACTION

fun registerNotificationChannel(
    context: Context,
    id: String,
    @StringRes name: Int,
) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return

    val channel =
        NotificationChannel(
            id,
            context.getString(name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    channel.enableLights(false)
    channel.enableVibration(false)
    channel.setShowBadge(false)

    manager.createNotificationChannel(channel)
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
            0,
            context.getString(R.string.notification_stop),
            PendingIntent.getService(
                context,
                0,
                Intent(context, service).setAction(STOP_ACTION),
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
