package com.poyka.ripdpi.services.fieldreport

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
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.InPathRouteLeaseAcquisition
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.localSocksProxy
import com.poyka.ripdpi.services.withLogin
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val HttpAccepted = 202
private const val RequestTimeoutSeconds = 30L
private val JsonMediaType = "application/json".toMediaType()

internal data class FieldReportTarget(
    val config: RipdpiFieldReport,
    private val members: Map<FieldReportMember, String>,
) {
    fun isServedBy(upstream: FieldReportUpstream): Boolean =
        members.keys.any { member ->
            member.memberId == upstream.memberId && (upstream.groupId == null || member.groupId == upstream.groupId)
        }

    fun owns(bucket: FieldReportBucket): Boolean = members[bucket.member] == bucket.serverRef
}

internal data class FieldReportTunnel(
    val route: LocalSocksRoute,
    val upstream: FieldReportUpstream,
    val isCurrent: () -> Boolean,
)

/** The selector group and member (or relay profile id) the active VPN tunnel exits through. */
internal data class FieldReportUpstream(
    val groupId: String?,
    val memberId: String?,
)

/** The upstream of the applied VPN configuration of [runtimeId], or null when none is known for that runtime. */
internal fun fieldReportUpstream(
    application: RuntimeConfigurationApplication?,
    runtimeId: String?,
): FieldReportUpstream? {
    val applied = (application as? RuntimeConfigurationApplication.Applied)?.configuration
    val selection = applied?.takeIf { runtimeId != null && it.runtimeId == runtimeId }?.effectiveSelection
    return selection?.let { FieldReportUpstream(it.selectorGroupId, it.selectorMemberId ?: it.profileId) }
}

internal enum class FieldReportPostResult {
    Accepted,
    Failed,
    RouteChanged,
}

/** RouteChanged means the request was stopped before any report bytes reached the network. */
internal fun interface FieldReportPoster {
    suspend fun post(
        route: LocalSocksRoute,
        target: RipdpiFieldReport,
        body: String,
        isCurrent: () -> Boolean,
    ): FieldReportPostResult
}

/**
 * Uploads each completed hour once. Nothing is sent without consent, without the active VPN tunnel, without a
 * bundle `field_report` target, or past the target's `max_reports_per_day` in the last 24 hours. A report that
 * fails is dropped: the run makes one request per report and never retries it.
 */
@Singleton
class FieldReportUploader internal constructor(
    private val ledger: FieldReportLedger,
    private val consent: suspend () -> Boolean,
    private val targets: suspend () -> List<FieldReportTarget>,
    private val appVersion: String?,
    private val tunnel: suspend () -> FieldReportTunnel?,
    private val poster: FieldReportPoster,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        ledger: FieldReportLedger,
        settings: AppSettingsRepository,
        groups: ProxyGroupRepository,
        diagnostics: DiagnosticsRuntimeCoordinator,
        configurations: AppliedRuntimeConfigurationSource,
        tlsClientFactory: OwnedTlsClientFactory,
    ) : this(
        ledger = ledger,
        consent = { settings.snapshot().fieldReportEnabled },
        targets = {
            groups
                .list()
                .filter { it.fieldReport != null }
                .groupBy { checkNotNull(it.fieldReport) }
                .map { (config, owners) ->
                    val members =
                        owners
                            .flatMap { group ->
                                group.members.map { FieldReportMember(group.id, it.id) to it.displayName }
                            }.toMap()
                    FieldReportTarget(config, members)
                }
        },
        appVersion =
            fieldReportAppVersion(
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull(),
            ),
        tunnel = {
            currentFieldReportTunnel(diagnostics, configurations, settings.snapshot().noDomain)
        },
        poster = OkHttpFieldReportPoster(tlsClientFactory),
        clock = System::currentTimeMillis,
    )

    private val log = Logger.withTag("field-report")

    suspend fun uploadCompletedHours() {
        if (!consent()) {
            ledger.clear()
            return
        }
        // Flush pending counts and age out data even when there is no usable tunnel.
        ledger.update { it }
        val due = ledger.snapshot().hours.filter { it.startEpochMillis < hourStart(clock()) }
        if (due.isEmpty()) return
        upload(due, targets().filter { it.config.endpoint.startsWith("https://") })
    }

    private suspend fun upload(
        due: List<FieldReportHour>,
        reportTargets: List<FieldReportTarget>,
    ) {
        for (hour in due) {
            for (target in reportTargets) {
                val live = tunnel() ?: return
                val claim =
                    live.takeIf { target.isServedBy(it.upstream) && it.isCurrent() }?.let {
                        ledger.claim { it.claimReport(hour, target, appVersion, clock()) }
                    } ?: continue
                if (!consent()) return
                val (epoch, reservation) = claim
                postClaim(target, live, epoch, reservation)
            }
        }
    }

    private suspend fun postClaim(
        target: FieldReportTarget,
        live: FieldReportTunnel,
        epoch: Long,
        reservation: FieldReportReservation,
    ) {
        val result =
            poster.post(live.route, target.config, reservation.body) {
                ledger.isClaimCurrent(epoch) && live.isCurrent()
            }
        when (result) {
            FieldReportPostResult.Accepted -> Unit
            FieldReportPostResult.Failed -> log.i { "field report dropped after one failed request" }
            FieldReportPostResult.RouteChanged -> ledger.restore(epoch, reservation::restore)
        }
    }
}

private data class FieldReportReservation(
    val body: String,
    val hour: FieldReportHour,
    val endpoint: String,
    val instant: Long,
) {
    fun restore(state: FieldReportState): FieldReportState {
        val history = state.uploads[endpoint].orEmpty().toMutableList()
        val index = history.lastIndexOf(instant)
        if (index < 0) return state
        history.removeAt(index)
        val pending =
            state.hours.firstOrNull {
                it.startEpochMillis == hour.startEpochMillis &&
                    it.network == hour.network
            }
        return state.copy(
            hours =
                if (pending ==
                    null
                ) {
                    state.hours + hour
                } else {
                    state.hours.map { if (it === pending) it.restore(hour) else it }
                },
            uploads = state.uploads + (endpoint to history),
        )
    }
}

private fun FieldReportState.claimReport(
    due: FieldReportHour,
    target: FieldReportTarget,
    version: String?,
    now: Long,
): Pair<FieldReportState, FieldReportReservation?> {
    val hour = hours.firstOrNull { it.startEpochMillis == due.startEpochMillis && it.network == due.network }
    val (owned, remaining) = hour?.buckets.orEmpty().partition(target::owns)
    if (hour == null || owned.isEmpty()) return this to null
    val endpoint = target.config.endpoint
    val history = uploads[endpoint].orEmpty()
    val report =
        version?.takeIf { history.size < target.config.maxReportsPerDay }?.let {
            val serverRefs = owned.mapTo(mutableSetOf()) { bucket -> bucket.serverRef }
            val claimedHour = hour.copy(buckets = owned)
            buildFieldReport(claimedHour, it, serverRefs)?.let { body ->
                FieldReportReservation(body.toString(), claimedHour, endpoint, now)
            }
        }
    val remainingHour = hour.copy(buckets = remaining).takeIf { remaining.isNotEmpty() }
    val next =
        copy(
            hours = hours.mapNotNull { if (it === hour) remainingHour else it },
            uploads = if (report == null) uploads else uploads + (endpoint to (history + now)),
        )
    return next to report
}

private suspend fun currentFieldReportTunnel(
    diagnostics: DiagnosticsRuntimeCoordinator,
    configurations: AppliedRuntimeConfigurationSource,
    noDomain: Boolean,
): FieldReportTunnel? {
    val acquisition = if (noDomain) null else diagnostics.acquireInPathRouteLease()
    val lease = (acquisition as? InPathRouteLeaseAcquisition.Acquired)?.lease ?: return null
    val applied = configurations.applications.value[Mode.VPN]
    return fieldReportUpstream(applied, lease.runtimeId)?.let { upstream ->
        FieldReportTunnel(
            LocalSocksRoute(lease.host, lease.port).withLogin(lease.credentials.username, lease.credentials.password),
            upstream,
        ) {
            configurations.applications.value[Mode.VPN] == applied &&
                diagnostics.validateInPathRouteLease(lease) == null
        }
    }
}

internal class OkHttpFieldReportPoster(
    private val tlsClientFactory: OwnedTlsClientFactory,
) : FieldReportPoster {
    override suspend fun post(
        route: LocalSocksRoute,
        target: RipdpiFieldReport,
        body: String,
        isCurrent: () -> Boolean,
    ): FieldReportPostResult =
        withContext(Dispatchers.IO) {
            val client =
                tlsClientFactory.create {
                    callTimeout(RequestTimeoutSeconds, TimeUnit.SECONDS)
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    addNetworkInterceptor { chain ->
                        if (!isCurrent()) throw FieldReportRouteChanged()
                        chain.proceed(chain.request())
                    }
                    // The listener, not the system, looks up the report host, inside the tunnel.
                    localSocksProxy(route.copy(resolveNamesLocally = false))
                }
            val request =
                Request
                    .Builder()
                    .url(target.endpoint)
                    .header("Authorization", "Bearer ${target.cohortToken}")
                    .post(body.toRequestBody(JsonMediaType))
                    .build()
            try {
                if (!isCurrent()) return@withContext FieldReportPostResult.RouteChanged
                client.newCall(request).executeAsync().use { response ->
                    if (response.code == HttpAccepted) FieldReportPostResult.Accepted else FieldReportPostResult.Failed
                }
            } catch (_: FieldReportRouteChanged) {
                FieldReportPostResult.RouteChanged
            } catch (_: IOException) {
                FieldReportPostResult.Failed
            }
        }
}

private class FieldReportRouteChanged : IOException()

@HiltWorker
class FieldReportWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val uploader: FieldReportUploader,
    ) : CoroutineWorker(appContext, workerParams) {
        // Always success: WorkManager backoff would turn a failed report into a retry.
        override suspend fun doWork(): Result {
            try {
                uploader.uploadCompletedHours()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                Logger.withTag("field-report").w { "field report run failed: ${ignored.javaClass.simpleName}" }
            }
            return Result.success()
        }

        companion object {
            const val UNIQUE_WORK_NAME = "ripdpi.field-report.upload"
        }
    }

/**
 * Keeps the hourly upload scheduled exactly while consent is on; revoking consent deletes unsent reports and the hint.
 */
@Singleton
class FieldReportConsentFollower
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: AppSettingsRepository,
        private val ledger: FieldReportLedger,
        private val networkContext: FieldReportNetworkContext,
    ) {
        suspend fun follow() {
            // It runs in the handler-less application scope, so no failure may escape: neither the settings flow
            // nor a step for one value. A failed step leaves the flow running for the next value.
            try {
                settings.settings
                    .map { it.fieldReportEnabled }
                    .distinctUntilChanged()
                    .collect { enabled -> contained("consent change") { apply(enabled) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                log.w { "field report consent follower stopped: ${ignored.javaClass.simpleName}" }
            }
        }

        private suspend fun apply(enabled: Boolean) {
            contained("scheduling") { schedule(enabled) }
            if (!enabled) {
                networkContext.clearHint()
                ledger.clear()
            }
        }

        private suspend fun contained(
            step: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                log.w { "field report $step failed: ${ignored.javaClass.simpleName}" }
            }
        }

        private val log = Logger.withTag("field-report")

        private fun schedule(enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (enabled) {
                val request =
                    PeriodicWorkRequestBuilder<FieldReportWorker>(1, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                workManager.enqueueUniquePeriodicWork(
                    FieldReportWorker.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } else {
                workManager.cancelUniqueWork(FieldReportWorker.UNIQUE_WORK_NAME)
            }
        }
    }
