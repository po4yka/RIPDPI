package com.poyka.ripdpi.core.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetectionCheckScheduler
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
    ) {
        private var observeJob: Job? = null

        fun startObserving(
            context: Context,
            scope: CoroutineScope,
        ) {
            if (observeJob != null) return
            observeJob =
                scope.launch {
                    serviceStateStore.status
                        .distinctUntilChangedBy { it.first }
                        .filter { it.first == AppStatus.Running }
                        .collect {
                            runQuickCheck(context)
                        }
                }
        }

        fun stopObserving() {
            observeJob?.cancel()
            observeJob = null
        }

        private suspend fun runQuickCheck(context: Context) {
            try {
                val config =
                    DetectionRunnerConfig(
                        ownPackageName = context.packageName,
                        includeBypassCheck = false,
                        includeLocationCheck = false,
                    )
                val result = DetectionRunner.run(context = context, config = config)
                val score = StealthScore.compute(result)

                if (result.verdict == Verdict.DETECTED || score < 50) {
                    postNotification(context, score, result.verdict)
                }

                val historyStore = DetectionHistoryStore(context)
                historyStore.save(
                    DetectionHistoryEntry(
                        networkFingerprint = "auto_vpn_start",
                        networkSummary = "VPN start check",
                        timestamp = System.currentTimeMillis(),
                        verdict = result.verdict.name,
                        stealthScore = score,
                        evidenceCount =
                            result.geoIp.evidence.size +
                                result.directSigns.evidence.size +
                                result.indirectSigns.evidence.size,
                    ),
                )
            } catch (_: Exception) {
                // Silent failure for background check
            }
        }

        private fun postNotification(
            context: Context,
            score: Int,
            verdict: Verdict,
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        "Detection Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "Alerts when VPN bypass is detectable"
                    }
                nm.createNotificationChannel(channel)
            }

            val title =
                when (verdict) {
                    Verdict.DETECTED -> "VPN detected - Score: $score/100"
                    Verdict.NEEDS_REVIEW -> "VPN may be visible - Score: $score/100"
                    Verdict.NOT_DETECTED -> return
                }

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText("Open Detection Check for details and fixes")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

            nm.notify(NOTIFICATION_ID, notification)
        }

        companion object {
            private const val CHANNEL_ID = "detection_alerts"
            private const val NOTIFICATION_ID = 9001
        }
    }
