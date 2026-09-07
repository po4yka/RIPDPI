package com.poyka.ripdpi.diagnostics

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRetentionStore
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFileStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

internal suspend fun trimDiagnosticsHistory(
    retentionDays: Int,
    retentionStore: DiagnosticsHistoryRetentionStore,
) = retentionStore.trimOldData(retentionDays)

internal suspend fun cleanupDiagnosticsPcapFiles(fileStore: DiagnosticsArchiveFileStore) = fileStore.cleanupPcapFiles()

@HiltWorker
class DiagnosticsRetentionWorker
    @AssistedInject
    internal constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val settingsRepository: AppSettingsRepository,
        private val retentionStore: DiagnosticsHistoryRetentionStore,
        private val archiveFileStore: DiagnosticsArchiveFileStore,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result =
            runCatching {
                trimDiagnosticsHistory(settingsRepository.snapshot().diagnosticsHistoryRetentionDays, retentionStore)
                cleanupDiagnosticsPcapFiles(archiveFileStore)
            }.fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )

        companion object {
            const val UniqueWorkName = "ripdpi.diagnostics.retention"

            fun enqueuePeriodic(context: Context) {
                val request = PeriodicWorkRequestBuilder<DiagnosticsRetentionWorker>(1, TimeUnit.DAYS).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UniqueWorkName,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }
    }
