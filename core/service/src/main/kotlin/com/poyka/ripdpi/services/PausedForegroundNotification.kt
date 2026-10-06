package com.poyka.ripdpi.services

import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.PausePhase
import java.text.DateFormat
import java.util.Date

internal fun startPausedForeground(
    service: Service,
    intent: PauseIntent,
    channel: String,
    notificationId: Int,
) {
    val deadline =
        android.text.format.DateFormat
            .getTimeFormat(service)
            .format(Date(intent.deadlineWallMillis))
    val content =
        service.getString(
            when (intent.phase) {
                PausePhase.Paused -> R.string.pause_notification_until
                PausePhase.CleanupPending, PausePhase.Releasing -> R.string.pause_notification_cleanup
                PausePhase.Resuming -> R.string.pause_notification_resuming
                PausePhase.Deferred -> R.string.pause_notification_deferred
            },
            deadline,
        )
    val notification =
        NotificationCompat
            .Builder(service, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentTitle(service.getString(R.string.pause_notification_title))
            .setContentText(content)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    service.packageManager.getLaunchIntentForPackage(service.packageName),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).apply {
                if (intent.phase == PausePhase.Paused || intent.phase == PausePhase.Deferred) {
                    addAction(
                        R.drawable.ic_notification,
                        service.getString(R.string.pause_notification_resume),
                        TimedPauseController.pendingIntent(service, intent, TimedPauseController.PauseResumeAction),
                    )
                }
            }.addAction(
                R.drawable.ic_notification,
                service.getString(R.string.notification_stop),
                TimedPauseController.pendingIntent(service, intent, TimedPauseController.PauseStopAction),
            ).build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        service.startForeground(notificationId, notification)
    }
}
