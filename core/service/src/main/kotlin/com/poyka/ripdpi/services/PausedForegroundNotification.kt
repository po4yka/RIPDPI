package com.poyka.ripdpi.services

import android.app.Service
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.PausePhase
import java.util.Date

internal fun startPausedForeground(
    service: Service,
    intent: PauseIntent,
    channel: String,
    notificationId: Int,
) {
    val localizedContext = ContextCompat.getContextForLanguage(service)
    val deadline =
        android.text.format.DateFormat
            .getTimeFormat(localizedContext)
            .format(Date(intent.deadlineWallMillis))
    val content =
        localizedContext.getString(
            when (intent.phase) {
                PausePhase.Paused -> R.string.pause_notification_until
                PausePhase.CleanupPending, PausePhase.Releasing -> R.string.pause_notification_cleanup
                PausePhase.Resuming -> R.string.pause_notification_resuming
                PausePhase.Deferred -> R.string.pause_notification_deferred
            },
            deadline,
        )
    val notification =
        connectionNotificationBuilder(service, channel)
            .setContentTitle(localizedContext.getString(R.string.pause_notification_title))
            .setContentText(content)
            .apply {
                if (intent.phase == PausePhase.Paused || intent.phase == PausePhase.Deferred) {
                    addAction(
                        R.drawable.ic_notification,
                        localizedContext.getString(R.string.pause_notification_resume),
                        TimedPauseController.pendingIntent(service, intent, TimedPauseController.PauseResumeAction),
                    )
                }
            }.addAction(
                R.drawable.ic_notification,
                localizedContext.getString(R.string.notification_stop),
                TimedPauseController.pendingIntent(service, intent, TimedPauseController.PauseStopAction),
            ).build()
    service.startConnectionForeground(notificationId, notification)
}
