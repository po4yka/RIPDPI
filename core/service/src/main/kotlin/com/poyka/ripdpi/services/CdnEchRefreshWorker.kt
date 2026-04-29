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
import com.poyka.ripdpi.core.RipDpiCdnEchNativeBindings
import com.poyka.ripdpi.data.CdnEchPersistedCache
import com.poyka.ripdpi.data.PersistedEchEntry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// 24-hour periodic worker that refreshes the singleton CdnEchUpdater
// from its DoH primary (or the bundled fallback) and persists the
// resulting cache to EncryptedSharedPreferences.
//
// Without this worker, CdnEchUpdater::refresh is never called in
// production: the cache stays cold across boot and the in-process
// fallback is exercised only on lazy demand. The persistence pairing
// means the TTL window survives a process restart -- a 6 h-old
// persisted entry against a 24 h TTL is still considered fresh after
// the restart.
@HiltWorker
class CdnEchRefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val persistedCache: CdnEchPersistedCache,
    ) : CoroutineWorker(appContext, workerParams) {
        private val log = Logger.withTag("cdn-ech")

        override suspend fun doWork(): Result =
            runCatching {
                refresh()
            }.getOrElse { error ->
                log.w(error) { "cdn-ech refresh worker threw" }
                Result.retry()
            }

        @Suppress("ReturnCount")
        private suspend fun refresh(): Result {
            val refreshStatus = RipDpiCdnEchNativeBindings.refresh()
            log.i { "cdn-ech native refresh result: $refreshStatus" }
            // Whether the refresh succeeded or fell back to bundled, we
            // still snapshot the resulting cache so persisted state
            // matches in-memory state. A missing snapshot (cold cache,
            // both sources failed and there was no prior entry) leaves
            // the persisted cache untouched.
            val snapshot = RipDpiCdnEchNativeBindings.snapshot()
            if (snapshot == null) {
                log.d { "cdn-ech cache is cold; skipping persistence write" }
                return Result.success()
            }
            persistedCache.save(
                PersistedEchEntry(configBytes = snapshot.configBytes, fetchedAtUnixMs = snapshot.fetchedAtUnixMs),
            )
            return Result.success()
        }

        companion object {
            const val UNIQUE_WORK_NAME = "ripdpi.cdn-ech.refresh"

            fun enqueuePeriodic(context: Context) {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                val request =
                    PeriodicWorkRequestBuilder<CdnEchRefreshWorker>(24L, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
