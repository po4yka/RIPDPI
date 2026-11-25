package com.poyka.ripdpi.diagnostics.application

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.AutomaticProbeScheduler
import com.poyka.ripdpi.diagnostics.BundledDiagnosticsProfileImporter
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.RuntimeHistoryStartup
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveExporter
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import com.poyka.ripdpi.diagnostics.traceStartupPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsBootstrapper
    @Inject
    constructor(
        private val archiveExporter: DiagnosticsArchiveExporter,
        private val profileImporter: BundledDiagnosticsProfileImporter,
        private val runtimeHistoryStartup: RuntimeHistoryStartup,
        private val policyHandoverEventStore: PolicyHandoverEventStore,
        private val automaticProbeScheduler: AutomaticProbeScheduler,
        private val rawPathSettlementBarrier: RawPathSettlementBarrier,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        @param:Named("importBundledProfilesOnInitialize")
        private val importBundledProfilesOnInitialize: Boolean,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsBootstrapper {
        private val initialized = AtomicBoolean(false)

        override suspend fun initialize() {
            runCatching { traceStartupPhase("raw_path_recovery") { rawPathSettlementBarrier.recoverPending() } }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w(error, tag = LogTags.DIAGNOSTICS) { "Pending raw-path settlement recovery skipped" }
                }
            if (!initialized.compareAndSet(false, true)) {
                return
            }
            var completed = false
            try {
                runCatching {
                    runtimeHistoryStartup.start()
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    logRuntimeHistoryBootstrapFailure(error)
                }
                traceStartupPhase("interrupted_scan_recovery") { recoverInterruptedScanSessions() }
                if (importBundledProfilesOnInitialize) {
                    runCatching { traceStartupPhase("bundled_profile_import") { profileImporter.importProfiles() } }
                        .onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Logger.w(error, tag = LogTags.DIAGNOSTICS) { "Bundled diagnostics profile import skipped" }
                        }
                }
                scope.launch { collectHandoverEvents() }
                scope.launch {
                    runCatching { traceStartupPhase("archive_cache_cleanup") { archiveExporter.cleanupCache() } }
                        .onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Logger.w(error, tag = LogTags.DIAGNOSTICS) { "Diagnostics archive cache cleanup skipped" }
                        }
                }
                completed = true
            } finally {
                if (!completed) initialized.set(false)
            }
        }

        private suspend fun collectHandoverEvents() {
            var attempt = 0
            while (true) {
                val collected =
                    runCatching {
                        policyHandoverEventStore.events.collect { event ->
                            attempt = 0
                            automaticProbeScheduler.schedule(event)
                        }
                    }
                val failure = collected.exceptionOrNull() ?: return
                if (failure is kotlinx.coroutines.CancellationException || failure !is Exception) throw failure
                Logger.w(
                    failure,
                    tag = LogTags.DIAGNOSTICS,
                ) { "Policy handover event collection failed; resubscribing" }
                delay(HandoverRetryBaseDelayMillis shl attempt.coerceAtMost(HandoverRetryMaxShift))
                attempt += 1
            }
        }

        private suspend fun recoverInterruptedScanSessions() {
            val interrupted =
                runCatching {
                    scanRecordStore
                        .observeRecentScanSessions()
                        .first()
                        .filter { it.status == "running" }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w(error, tag = LogTags.DIAGNOSTICS) { "Interrupted scan session recovery skipped" }
                }.getOrNull() ?: return
            interrupted.forEach { session ->
                runCatching {
                    scanRecordStore.upsertScanSession(
                        session.copy(
                            status = "failed",
                            summary = InterruptedScanSummary,
                            finishedAt = session.finishedAt ?: System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w(
                        error,
                        tag = LogTags.DIAGNOSTICS,
                    ) { "Interrupted scan session ${session.id} left in running state" }
                }
            }
        }

        private fun logRuntimeHistoryBootstrapFailure(error: Throwable) {
            Logger.w(error, tag = LogTags.DIAGNOSTICS) { "Runtime history bootstrap skipped" }
        }

        private companion object {
            const val InterruptedScanSummary = "Diagnostics scan interrupted by process death"
            const val HandoverRetryBaseDelayMillis = 1_000L
            const val HandoverRetryMaxShift = 6
        }
    }
