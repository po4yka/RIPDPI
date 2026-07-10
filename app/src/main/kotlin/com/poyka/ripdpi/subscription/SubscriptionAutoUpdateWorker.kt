package com.poyka.ripdpi.subscription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.SubscriptionKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** WorkManager's hard floor for a periodic refresh interval. */
const val WorkManagerMinIntervalMinutes: Long = 15L

/** Long-lived subscription groups eligible for an automatic refresh pass. */
fun subscriptionsDueForAutoUpdate(groups: List<ProxyGroup>): List<ProxyGroup> =
    groups.filter { group ->
        val subscription = group.subscription
        group.type == ProxyGroupType.SUBSCRIPTION &&
            subscription != null &&
            subscription.autoUpdate &&
            subscription.kind != SubscriptionKind.BOOTSTRAP
    }

/** Shortest configured refresh delay, clamped to WorkManager's 15-minute floor. */
fun autoUpdateIntervalMinutes(groups: List<ProxyGroup>): Long {
    val shortest =
        subscriptionsDueForAutoUpdate(groups)
            .mapNotNull { it.subscription?.autoUpdateDelay?.takeIf { delay -> delay > 0L } }
            .minOrNull()
            ?: WorkManagerMinIntervalMinutes
    return maxOf(shortest, WorkManagerMinIntervalMinutes)
}

/** Periodic adapter around the testable [SubscriptionRefreshCoordinator]. */
@HiltWorker
class SubscriptionAutoUpdateWorker
    @AssistedInject
    internal constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val coordinator: SubscriptionRefreshCoordinator,
    ) : CoroutineWorker(appContext, workerParams) {
        private val log = Logger.withTag("subscription-auto-update")

        override suspend fun doWork(): Result =
            runCatching { coordinator.refreshAll() }
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            SubscriptionRefreshRunResult.SUCCESS -> Result.success()
                            SubscriptionRefreshRunResult.RETRY -> Result.retry()
                        }
                    },
                    onFailure = { error ->
                        log.w(error) { "subscription auto-update worker threw" }
                        Result.retry()
                    },
                )

        companion object {
            const val UNIQUE_WORK_NAME = "ripdpi.subscription.auto-update"

            fun enqueuePeriodic(
                context: Context,
                groups: List<ProxyGroup>,
            ) {
                val workManager = WorkManager.getInstance(context)
                val eligible = subscriptionsDueForAutoUpdate(groups)
                if (eligible.isEmpty()) {
                    Logger.withTag("subscription-auto-update").d {
                        "no auto-updating subscriptions; cancelling periodic work"
                    }
                    workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                    return
                }
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                val request =
                    PeriodicWorkRequestBuilder<SubscriptionAutoUpdateWorker>(
                        autoUpdateIntervalMinutes(groups),
                        TimeUnit.MINUTES,
                    ).setConstraints(constraints).build()
                workManager.enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }
    }
