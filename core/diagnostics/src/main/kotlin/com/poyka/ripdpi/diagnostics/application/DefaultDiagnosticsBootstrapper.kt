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
            runCatching { rawPathSettlementBarrier.recoverPending() }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.w(error) { "Pending raw-path settlement recovery skipped" }
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
                recoverInterruptedScanSessions()
                // A failed cache sweep must not skip the profile import and the handover collector.
                runCatching { archiveExporter.cleanupCache() }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Logger.w(error) { "Diagnostics archive cache cleanup skipped" }
                    }
                if (importBundledProfilesOnInitialize) {
                    // A corrupted override or unreadable bundled catalog must degrade to
                    // missing persisted profiles instead of crashing every startup caller.
                    runCatching { profileImporter.importProfiles() }
                        .onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Logger.w(error) { "Bundled diagnostics profile import skipped" }
                        }
                }
                scope.launch { collectHandoverEvents() }
                completed = true
            } finally {
                // A caller cancelled mid-way must leave the remaining steps to the next initialize().
                if (!completed) initialized.set(false)
            }
        }

        /** Resubscribes with capped backoff, so one store failure does not stop handover probes for the process. */
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
                Logger.w(failure) { "Policy handover event collection failed; resubscribing" }
                delay(HandoverRetryBaseDelayMillis shl attempt.coerceAtMost(HandoverRetryMaxShift))
                attempt += 1
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
            const val HandoverRetryBaseDelayMillis = 1_000L
            const val HandoverRetryMaxShift = 6
        }
    }
