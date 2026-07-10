package com.poyka.ripdpi.subscription

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.SubscriptionClientSignal
import com.poyka.ripdpi.data.actionableSubscriptionSignals
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SubscriptionStatusNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SubscriptionSignalPublisher {
        private val manager = context.getSystemService(NotificationManager::class.java)
        private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

        override fun publish(
            groups: List<ProxyGroup>,
            nowEpochMillis: Long,
        ) {
            val signals = actionableSubscriptionSignals(groups, nowEpochMillis)
            if (signals.isEmpty()) {
                manager?.cancel(NotificationId)
                preferences.edit().remove(FingerprintKey).apply()
                return
            }
            val fingerprint = signals.joinToString("|") { (group, signal) -> "${group.id}:${signal.name}" }
            val notificationManager = manager
            val shouldNotify =
                preferences.getString(FingerprintKey, null) != fingerprint &&
                    canNotify() &&
                    notificationManager != null
            if (shouldNotify) {
                val notification = buildNotification(signals)
                notificationManager.let { activeManager ->
                    try {
                        registerChannel(activeManager)
                        activeManager.notify(NotificationId, notification)
                        preferences.edit().putString(FingerprintKey, fingerprint).apply()
                    } catch (_: SecurityException) {
                        // The in-app banner remains authoritative when permission is revoked.
                    }
                }
            }
        }

        private fun canNotify(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun registerChannel(notificationManager: NotificationManager) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    ChannelId,
                    context.getString(R.string.subscription_signal_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        private fun buildNotification(signals: List<Pair<ProxyGroup, SubscriptionClientSignal>>) =
            NotificationCompat
                .Builder(context, ChannelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.subscription_signal_notification_title))
                .setContentText(notificationText(signals))
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build()

        private fun notificationText(signals: List<Pair<ProxyGroup, SubscriptionClientSignal>>): String {
            if (signals.size > 1) {
                return context.resources.getQuantityString(
                    R.plurals.subscription_signal_notification_multiple,
                    signals.size,
                    signals.size,
                )
            }
            val (group, signal) = signals.single()
            val message =
                when (signal) {
                    SubscriptionClientSignal.EXPIRED -> R.string.subscription_signal_notification_expired
                    SubscriptionClientSignal.REVOKED -> R.string.subscription_signal_notification_revoked
                    SubscriptionClientSignal.UNAVAILABLE -> R.string.subscription_signal_notification_unavailable
                    SubscriptionClientSignal.STALE -> R.string.subscription_signal_notification_stale
                }
            return context.getString(message, group.name)
        }

        private fun contentIntent(): PendingIntent =
            PendingIntent.getActivity(
                context,
                NotificationId,
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(DeepLink)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        internal companion object {
            const val NotificationId = 0x535542
            const val DeepLink = "ripdpi://subscription-failover"
            private const val ChannelId = "RIPDPISubscriptionStatus"
            private const val PreferencesName = "subscription_status_notifications"
            private const val FingerprintKey = "last_fingerprint"
        }
    }
