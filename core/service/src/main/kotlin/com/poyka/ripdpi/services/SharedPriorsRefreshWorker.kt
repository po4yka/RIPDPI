package com.poyka.ripdpi.services

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
import com.poyka.ripdpi.core.RipDpiSharedPriorsNativeBindings
import com.poyka.ripdpi.core.service.BuildConfig
import com.poyka.ripdpi.data.SharedPriorsRefreshCache
import com.poyka.ripdpi.data.SharedPriorsRefreshState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// 24-hour periodic worker that fetches the signed shared-priors bundle
// from the GitHub release channel, hands it to the native verifier, and
// records the apply outcome.
//
// The worker is fail-secure end-to-end:
//   1. HEAD probe on the manifest URL; skip the body fetch when
//      Last-Modified matches the previously-applied value.
//   2. GET manifest + GET priors. Either failure short-circuits with a
//      Result.retry() so WorkManager backs off and tries later.
//   3. Hand both blobs to RipDpiSharedPriorsNativeBindings.applySharedPriors;
//      the native side validates the ed25519 signature against the
//      embedded release public key. Until the embedded key is replaced
//      with a real one, the native side returns
//      `{"ok": false, "error": "no production..."}` and the worker
//      logs the reason without retrying.
//
private const val SharedPriorsRefreshIntervalHours = 24L
private const val MillisPerSecond = 1_000L
private const val minRefreshIntervalMs = SharedPriorsRefreshIntervalHours * 60L * 60L * MillisPerSecond

internal data class SharedPriorsReleaseConfig(
    val manifestUrl: String,
    val priorsUrl: String,
) {
    val isConfigured: Boolean
        get() = manifestUrl.isNotBlank() && priorsUrl.isNotBlank()
}

private fun sharedPriorsReleaseConfigFromBuildConfig(): SharedPriorsReleaseConfig =
    SharedPriorsReleaseConfig(
        manifestUrl = BuildConfig.SHARED_PRIORS_MANIFEST_URL.trim(),
        priorsUrl = BuildConfig.SHARED_PRIORS_PRIORS_URL.trim(),
    )

@HiltWorker
class SharedPriorsRefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val downloadService: SharedPriorsCatalogDownloadService,
        private val refreshCache: SharedPriorsRefreshCache,
    ) : CoroutineWorker(appContext, workerParams) {
        private val log = Logger.withTag("shared-priors")

        override suspend fun doWork(): Result =
            runCatching {
                refresh()
            }.getOrElse { error ->
                log.w(error) { "shared-priors refresh worker threw" }
                Result.retry()
            }

        @Suppress("ReturnCount")
        private suspend fun refresh(): Result {
            val releaseConfig = sharedPriorsReleaseConfigFromBuildConfig()
            if (!releaseConfig.isConfigured) {
                log.w { "shared-priors refresh ran without configured release URLs" }
                return Result.failure()
            }
            val manifestUrl = releaseConfig.manifestUrl
            val priorsUrl = releaseConfig.priorsUrl

            val previous = refreshCache.load()
            val now = System.currentTimeMillis()
            if (previous != null && now - previous.lastRefreshUnixMs < minRefreshIntervalMs) {
                log.d {
                    "shared-priors cooldown active; " +
                        "${(now - previous.lastRefreshUnixMs) / MillisPerSecond} s since last refresh"
                }
                return Result.success()
            }

            val lastModified =
                runCatching { downloadService.headManifestLastModified(manifestUrl) }.getOrNull()
            if (lastModified != null && lastModified == previous?.lastModifiedHeader) {
                refreshCache.save(SharedPriorsRefreshState(lastRefreshUnixMs = now, lastModifiedHeader = lastModified))
                log.d { "shared-priors manifest unchanged since last refresh; skipping body fetch" }
                return Result.success()
            }

            val manifestJson =
                runCatching { downloadService.downloadManifest(manifestUrl) }.getOrElse { error ->
                    log.w(error) { "shared-priors manifest fetch failed" }
                    return Result.retry()
                }
            val priorsBytes =
                runCatching { downloadService.downloadPriors(priorsUrl) }.getOrElse { error ->
                    log.w(error) { "shared-priors payload fetch failed" }
                    return Result.retry()
                }

            val nativeStatus = RipDpiSharedPriorsNativeBindings.applySharedPriors(manifestJson, priorsBytes)
            log.i { "shared-priors apply result: $nativeStatus" }

            refreshCache.save(SharedPriorsRefreshState(lastRefreshUnixMs = now, lastModifiedHeader = lastModified))
            return Result.success()
        }

        companion object {
            const val UNIQUE_WORK_NAME = "ripdpi.shared-priors.refresh"

            fun enqueuePeriodic(context: Context): Boolean {
                val releaseConfig = sharedPriorsReleaseConfigFromBuildConfig()
                val workManager = WorkManager.getInstance(context)
                if (!releaseConfig.isConfigured) {
                    Logger.withTag("shared-priors").i {
                        "shared-priors release URLs not configured; cancelling periodic refresh"
                    }
                    workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                    return false
                }
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                val request =
                    PeriodicWorkRequestBuilder<SharedPriorsRefreshWorker>(
                        SharedPriorsRefreshIntervalHours,
                        TimeUnit.HOURS,
                    ).setConstraints(constraints).build()
                workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                return true
            }
        }
    }
