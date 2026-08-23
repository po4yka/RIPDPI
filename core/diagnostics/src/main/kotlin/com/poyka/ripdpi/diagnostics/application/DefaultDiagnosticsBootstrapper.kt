package com.poyka.ripdpi.diagnostics.application

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.AutomaticProbeScheduler
import com.poyka.ripdpi.diagnostics.BundledDiagnosticsProfileImporter
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.RuntimeHistoryStartup
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveExporter
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import kotlinx.coroutines.CoroutineScope
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
            runCatching { rawPathSettlementBarrier.recoverPending() }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w(error) { "Pending raw-path settlement recovery skipped" }
                }
            if (!initialized.compareAndSet(false, true)) {
                return
            }
            runCatching {
                runtimeHistoryStartup.start()
            }.onFailure { error ->
                logRuntimeHistoryBootstrapFailure(error)
            }
            recoverInterruptedScanSessions()
            archiveExporter.cleanupCache()
            if (importBundledProfilesOnInitialize) {
                // A corrupted override or unreadable bundled catalog must degrade to
                // missing persisted profiles instead of crashing every startup caller.
                runCatching { profileImporter.importProfiles() }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Logger.w(error) { "Bundled diagnostics profile import skipped" }
                    }
            }
            scope.launch {
                policyHandoverEventStore.events.collect { event ->
                    automaticProbeScheduler.schedule(event)
                }
            }
        }

        /**
         * Process death mid-scan leaves sessions stuck in the running state; the
         * raw-path settlement barrier only covers raw-path scans, so sweep the
         * remaining stale rows once per process at startup.
         */
        private suspend fun recoverInterruptedScanSessions() {
            val interrupted =
                runCatching {
                    scanRecordStore
                        .observeRecentScanSessions()
                        .first()
                        .filter { it.status == "running" }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w(error) { "Interrupted scan session recovery skipped" }
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
                    Logger.w(error) { "Interrupted scan session ${session.id} left in running state" }
                }
            }
        }

        private fun logRuntimeHistoryBootstrapFailure(error: Throwable) {
            Logger.w(error) { "Runtime history bootstrap skipped" }
        }

        private companion object {
            const val InterruptedScanSummary = "Diagnostics scan interrupted by process death"
        }
    }
