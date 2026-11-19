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
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.localSocksProxy
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val HttpAccepted = 202
private const val RequestTimeoutSeconds = 30L
private val JsonMediaType = "application/json".toMediaType()

/** One `field_report` upload target, the server_refs (member tags) its groups own, and those groups and members. */
internal data class FieldReportTarget(
    val config: RipdpiFieldReport,
    val serverRefs: Set<String>,
    val groupIds: Set<String>,
    val memberIds: Set<String>,
) {
    /** True when the tunnel exits through one of this target's own members, so the report never leaves elsewhere. */
    fun isServedBy(upstream: FieldReportUpstream): Boolean =
        upstream.groupId in groupIds || upstream.memberId in memberIds
}

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

/** Sends one report through the tunnel listener [route]; true only when the server accepted it. */
internal fun interface FieldReportPoster {
    suspend fun post(
        route: LocalSocksRoute,
        target: RipdpiFieldReport,
        body: String,
    ): Boolean
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
    private val tunnelRoute: suspend () -> LocalSocksRoute?,
    private val activeUpstream: suspend () -> FieldReportUpstream?,
    private val poster: FieldReportPoster,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        ledger: FieldReportLedger,
        settings: AppSettingsRepository,
        groups: ProxyGroupRepository,
        runtimes: ServiceRuntimeRegistry,
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
                    val members = owners.flatMap { it.members }
                    FieldReportTarget(
                        config,
                        members.mapTo(mutableSetOf()) { it.displayName },
                        owners.mapTo(mutableSetOf()) { it.id },
                        members.mapTo(mutableSetOf()) { it.id },
                    )
                }
        },
        appVersion =
            fieldReportAppVersion(
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull(),
            ),
        // The app's own UID bypasses the VPN, so the report enters the listener the tunnel forwards into.
        tunnelRoute = {
            fieldReportTunnelRoute(runtimes.current(Mode.VPN)?.tunnelSocksRoute, settings.snapshot().noDomain)
        },
        activeUpstream = {
            fieldReportUpstream(configurations.applications.value[Mode.VPN], runtimes.current(Mode.VPN)?.runtimeId)
        },
        poster = OkHttpFieldReportPoster(tlsClientFactory),
        clock = System::currentTimeMillis,
    )

    private val log = Logger.withTag("field-report")

    suspend fun uploadCompletedHours() {
        // A write prunes hours older than 24 hours, so an hour that never saw the tunnel ages out of the file.
        ledger.update { it }
        val now = clock()
        val state = ledger.snapshot()
        val due = state.hours.filter { it.startEpochMillis < hourStart(now) }
        // Without the tunnel nothing is sent; the hours wait for a later run and age out after 24 hours.
        val route = if (due.isEmpty()) null else tunnelRoute()
        // The tunnel may exit from the real address (no relay) or through another operator's relay, so a
        // report goes only to a target whose own member carries the tunnel; otherwise the hours wait as above.
        val upstream = route?.let { activeUpstream() }
        val reportTargets =
            if (upstream == null) {
                emptyList()
            } else {
                targets().filter { it.config.endpoint.startsWith("https://") && it.isServedBy(upstream) }
            }
        if (!consent()) {
            ledger.clear()
        } else if (route != null && reportTargets.isNotEmpty()) {
            upload(due, state.uploads, route, reportTargets, now)
        }
    }

    private suspend fun upload(
        due: List<FieldReportHour>,
        history: Map<String, List<Long>>,
        route: LocalSocksRoute,
        reportTargets: List<FieldReportTarget>,
        now: Long,
    ) {
        val version = appVersion
        val reports =
            if (version == null) {
                emptyList()
            } else {
                due.flatMap { hour ->
                    reportTargets.mapNotNull { target ->
                        buildFieldReport(hour, version, target.serverRefs)?.let { target to it }
                    }
                }
            }
        val requests = mutableMapOf<String, MutableList<Long>>()
        for ((target, report) in reports) {
            val endpoint = target.config.endpoint
            val used = history[endpoint].orEmpty().count { now - it < MillisPerDay } + requests[endpoint].orEmpty().size
            // Consent is re-read before every request, so a revocation stops the run.
            if (used < target.config.maxReportsPerDay && consent()) {
                requests.getOrPut(endpoint) { mutableListOf() } += now
                if (!poster.post(route, target.config, report.toString())) {
                    log.i { "field report dropped after one failed request" }
                }
            }
        }
        val handled = due.mapTo(mutableSetOf()) { it.startEpochMillis to it.network }
        ledger.update { current ->
            current.copy(
                hours = current.hours.filterNot { (it.startEpochMillis to it.network) in handled },
                uploads =
                    (current.uploads.keys + requests.keys).associateWith {
                        current.uploads[it].orEmpty() + requests[it].orEmpty()
                    },
            )
        }
    }
}

/**
 * The listener route for an upload, or null when none may be used. The app's own UID is outside the VPN, so a
 * name looked up on the device would leave outside the tunnel; with noDomain the listener cannot look up names,
 * so the upload is skipped instead.
 */
internal fun fieldReportTunnelRoute(
    route: LocalSocksRoute?,
    noDomain: Boolean,
): LocalSocksRoute? = route?.takeUnless { noDomain }

internal class OkHttpFieldReportPoster(
    private val tlsClientFactory: OwnedTlsClientFactory,
) : FieldReportPoster {
    override suspend fun post(
        route: LocalSocksRoute,
        target: RipdpiFieldReport,
        body: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val client =
                tlsClientFactory.create {
                    callTimeout(RequestTimeoutSeconds, TimeUnit.SECONDS)
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
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
                client.newCall(request).execute().use { response -> response.code == HttpAccepted }
            } catch (_: IOException) {
                false
            }
        }
}

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

/** Keeps the hourly upload scheduled exactly while consent is on; revoking consent deletes unsent reports. */
@Singleton
class FieldReportConsentFollower
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: AppSettingsRepository,
        private val ledger: FieldReportLedger,
    ) {
        suspend fun follow() {
            settings.settings
                .map { it.fieldReportEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    // It runs in the handler-less application scope, so a scheduling failure must not escape.
                    try {
                        schedule(enabled)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        Logger
                            .withTag(
                                "field-report",
                            ).w { "field report scheduling failed: ${ignored.javaClass.simpleName}" }
                    }
                    if (!enabled) ledger.clear()
                }
        }

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
