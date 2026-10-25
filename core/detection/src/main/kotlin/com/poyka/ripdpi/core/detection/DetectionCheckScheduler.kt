@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface DetectionObservationStarter {
    fun startObserving(
        context: Context,
        scope: CoroutineScope,
    )
}

@Singleton
class DetectionCheckScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val appContext: Context,
        private val serviceStateStore: ServiceStateStore,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val detectionCheckRunner: DetectionCheckRunner,
        private val detectionHistoryStore: DetectionHistoryRepository,
    ) : DetectionObservationStarter {
        private var observeJob: Job? = null
        private var handoverJob: Job? = null

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        appContext.getString(R.string.detection_alert_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = appContext.getString(R.string.detection_alert_channel_description)
                    }
                nm.createNotificationChannel(channel)
            }
        }

        override fun startObserving(
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

        fun observeNetworkChanges(
            context: Context,
            scope: CoroutineScope,
            handoverEvents: SharedFlow<*>,
        ) {
            if (handoverJob != null) return
            handoverJob =
                scope.launch {
                    handoverEvents.collect {
                        if (serviceStateStore.status.value.first == AppStatus.Running) {
                            runQuickCheck(context)
                        }
                    }
                }
        }

        fun stopObserving() {
            observeJob?.cancel()
            observeJob = null
            handoverJob?.cancel()
            handoverJob = null
        }

        private suspend fun runQuickCheck(context: Context) {
            try {
                val network = networkFingerprintProvider.capture()
                val networkKey = network?.scopeKey() ?: "unknown"
                // Local checks only: an automatic check must not send the public IP to third-party
                // GeoIP or IP-echo services. The user runs those lookups from the Detection screen.
                val config =
                    DetectionRunnerConfig(
                        ownPackageName = context.packageName,
                        includeGeoIpCheck = false,
                        includeIpComparisonCheck = false,
                        includeBypassCheck = false,
                        includeLocationCheck = false,
                        includeDnsLeakCheck = false,
                        includeWebRtcCheck = false,
                        includeTlsFingerprintCheck = false,
                        includeTimingAnalysis = false,
                    )
                val result = detectionCheckRunner.run(context = context, config = config)
                if (
                    networkFingerprintProvider.capture() != network ||
                    serviceStateStore.status.value.first != AppStatus.Running
                ) {
                    return
                }
                val score = StealthScore.compute(result)

                if (result.verdict == Verdict.DETECTED || score < 50) {
                    postNotification(context, score, result.verdict)
                }

                detectionHistoryStore.save(
                    DetectionHistoryEntry(
                        networkFingerprint = networkKey,
                        networkSummary =
                            network?.summary()?.let { "${it.transport}/${it.identityKind}" } ?: "Unknown network",
                        timestamp = System.currentTimeMillis(),
                        verdict = result.verdict.name,
                        stealthScore = score,
                        evidenceCount =
                            result.geoIp.evidence.size +
                                result.directSigns.evidence.size +
                                result.indirectSigns.evidence.size,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
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

            val title =
                when (verdict) {
                    Verdict.DETECTED -> context.getString(R.string.detection_alert_title_detected, score)
                    Verdict.NEEDS_REVIEW -> context.getString(R.string.detection_alert_title_needs_review, score)
                    Verdict.NOT_DETECTED -> return
                }
            // The launcher activity reads this link; core:detection cannot reference the app's activity class.
            val openDetectionCheck =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(DETECTION_CHECK_DEEP_LINK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.detection_alert_body))
                    .setContentIntent(
                        openDetectionCheck?.let {
                            PendingIntent.getActivity(
                                context,
                                NOTIFICATION_ID,
                                it,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                        },
                    ).setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

            nm.notify(NOTIFICATION_ID, notification)
        }

        companion object {
            private const val CHANNEL_ID = "detection_alerts"
            private const val NOTIFICATION_ID = 9001
            const val DETECTION_CHECK_DEEP_LINK = "ripdpi://detection-check"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DetectionCheckSchedulerBindingsModule {
    @Binds
    @Singleton
    abstract fun bindDetectionObservationStarter(scheduler: DetectionCheckScheduler): DetectionObservationStarter
}
