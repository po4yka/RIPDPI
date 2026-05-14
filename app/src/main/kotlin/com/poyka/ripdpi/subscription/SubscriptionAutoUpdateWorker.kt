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
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WorkManager's hard floor for a [PeriodicWorkRequest] interval. */
const val WorkManagerMinIntervalMinutes: Long = 15L

/**
 * The subscription groups eligible for an auto-update pass.
 *
 * A group is due when it is a [ProxyGroupType.SUBSCRIPTION] group with
 * [com.poyka.ripdpi.data.Subscription.autoUpdate] enabled, and it is **not** a
 * [SubscriptionKind.BOOTSTRAP] subscription — a bootstrap token is single-use,
 * so polling its URL on a schedule would draw a spurious HTTP 410 "subscription
 * dead" error after the server deletes the spent token.
 *
 * Pure function; the worker calls this against the live repository snapshot.
 */
fun subscriptionsDueForAutoUpdate(groups: List<ProxyGroup>): List<ProxyGroup> =
    groups.filter { group ->
        val subscription = group.subscription
        group.type == ProxyGroupType.SUBSCRIPTION &&
            subscription != null &&
            subscription.autoUpdate &&
            subscription.kind != SubscriptionKind.BOOTSTRAP
    }

/**
 * The periodic interval (minutes) for the auto-update worker: the shortest
 * configured `autoUpdateDelay` across all eligible groups (see
 * [subscriptionsDueForAutoUpdate]), clamped up to the WorkManager
 * [WorkManagerMinIntervalMinutes] floor. With no eligible group the floor
 * is returned. Mirrors NekoBox's shortest-interval clamp.
 */
fun autoUpdateIntervalMinutes(groups: List<ProxyGroup>): Long {
    val shortest =
        subscriptionsDueForAutoUpdate(groups)
            .mapNotNull { it.subscription?.autoUpdateDelay?.takeIf { delay -> delay > 0L } }
            .minOrNull()
            ?: WorkManagerMinIntervalMinutes
    return maxOf(shortest, WorkManagerMinIntervalMinutes)
}

/**
 * Periodic worker that refreshes every auto-updating long-lived subscription at
 * the shortest configured cadence (see [autoUpdateIntervalMinutes]).
 *
 * Bootstrap subscriptions are excluded by [subscriptionsDueForAutoUpdate]: they
 * are single-use and re-fetching a spent bootstrap URL only produces a spurious
 * 410. Each refresh fetches the subscription payload over HTTP, parses it with
 * the shared subscription parsers, and replaces the group's profile set;
 * transient failures yield [Result.retry] so WorkManager backs off.
 */
@HiltWorker
class SubscriptionAutoUpdateWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val repository: ProxyGroupRepository,
    ) : CoroutineWorker(appContext, workerParams) {
        private val log = Logger.withTag("subscription-auto-update")
        private val httpClient = OkHttpClient()

        override suspend fun doWork(): Result =
            runCatching { refresh() }
                .getOrElse { error ->
                    log.w(error) { "subscription auto-update worker threw" }
                    Result.retry()
                }

        private suspend fun refresh(): Result {
            val due = subscriptionsDueForAutoUpdate(repository.list())
            if (due.isEmpty()) {
                log.d { "no auto-updating subscriptions; nothing to refresh" }
                return Result.success()
            }
            var anyTransientFailure = false
            due.forEach { group ->
                val link = group.subscription?.link.orEmpty()
                if (link.isBlank()) return@forEach
                when (val outcome = fetchAndParse(link, group.id)) {
                    is RefreshOutcome.Updated -> {
                        log.i { "refreshed subscription group ${group.id}: ${outcome.profileCount} profiles" }
                    }

                    is RefreshOutcome.TransientFailure -> {
                        anyTransientFailure = true
                        log.w { "transient failure refreshing subscription group ${group.id}: ${outcome.reason}" }
                    }

                    is RefreshOutcome.ParseFailure -> {
                        log.w { "parse failure refreshing subscription group ${group.id}: ${outcome.reason}" }
                    }
                }
            }
            return if (anyTransientFailure) Result.retry() else Result.success()
        }

        private suspend fun fetchAndParse(
            url: String,
            groupId: String,
        ): RefreshOutcome =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .build()
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@use RefreshOutcome.TransientFailure("HTTP ${response.code}")
                        }
                        val body = response.body.string()
                        val singBox = SingBoxSubscriptionParser.parse(body, groupId)
                        if (singBox is SingBoxParseResult.Success && singBox.profiles.isNotEmpty()) {
                            return@use RefreshOutcome.Updated(singBox.profiles.size)
                        }
                        val base64 = Base64SubscriptionParser.parse(body, groupId)
                        if (base64.profiles.isNotEmpty()) {
                            RefreshOutcome.Updated(base64.profiles.size)
                        } else {
                            RefreshOutcome.ParseFailure("no profiles in subscription payload")
                        }
                    }
                } catch (error: IOException) {
                    RefreshOutcome.TransientFailure(error.message ?: "network unreachable")
                }
            }

        private sealed interface RefreshOutcome {
            data class Updated(
                val profileCount: Int,
            ) : RefreshOutcome

            data class TransientFailure(
                val reason: String,
            ) : RefreshOutcome

            data class ParseFailure(
                val reason: String,
            ) : RefreshOutcome
        }

        companion object {
            const val UNIQUE_WORK_NAME = "ripdpi.subscription.auto-update"

            /**
             * (Re-)registers the periodic auto-update work at the shortest
             * applicable interval. When no subscription is eligible the unique
             * work is cancelled instead of scheduled. Safe to call repeatedly
             * (boot, group reconfigure): the request is replaced so the
             * interval always tracks the current group set.
             */
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
