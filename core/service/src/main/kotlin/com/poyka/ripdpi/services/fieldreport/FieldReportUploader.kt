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
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroupRepository
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

/** One `field_report` upload target and the server_refs (member tags) its groups own. */
internal data class FieldReportTarget(
    val config: RipdpiFieldReport,
    val serverRefs: Set<String>,
)

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
        tlsClientFactory: OwnedTlsClientFactory,
    ) : this(
        ledger = ledger,
        consent = { settings.snapshot().fieldReportEnabled },
        targets = {
            groups
                .list()
                .mapNotNull { group ->
                    group.fieldReport?.let { it to group.members.map { member -> member.displayName } }
                }.groupBy({ it.first }, { it.second })
                .map { (config, tags) -> FieldReportTarget(config, tags.flatten().toSet()) }
        },
        appVersion =
            fieldReportAppVersion(
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull(),
            ),
        // The app's own UID bypasses the VPN, so the report enters the listener the tunnel forwards into.
        tunnelRoute = {
            fieldReportTunnelRoute(runtimes.current(Mode.VPN)?.tunnelSocksRoute, settings.snapshot().noDomain)
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
        if (!consent()) {
            ledger.clear()
        } else if (route != null) {
            upload(due, state.uploads, route, now)
        }
    }

    private suspend fun upload(
        due: List<FieldReportHour>,
        history: Map<String, List<Long>>,
        route: LocalSocksRoute,
        now: Long,
    ) {
        val version = appVersion
        val reportTargets = targets().filter { it.config.endpoint.startsWith("https://") }
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
 * locally resolved name would leak the report host to system DNS; with noDomain the listener cannot resolve names,
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
                    // The listener resolves the report host inside the tunnel, never system DNS.
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
