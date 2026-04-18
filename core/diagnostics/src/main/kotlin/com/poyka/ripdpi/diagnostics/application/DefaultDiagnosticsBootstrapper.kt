package com.poyka.ripdpi.diagnostics.application

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.diagnostics.AutomaticProbeScheduler
import com.poyka.ripdpi.diagnostics.BundledDiagnosticsProfileImporter
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.RuntimeHistoryStartup
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveExporter
import kotlinx.coroutines.CoroutineScope
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
        @param:Named("importBundledProfilesOnInitialize")
        private val importBundledProfilesOnInitialize: Boolean,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsBootstrapper {
        private val initialized = AtomicBoolean(false)

        override suspend fun initialize() {
            if (!initialized.compareAndSet(false, true)) {
                return
            }
            runCatching {
                runtimeHistoryStartup.start()
            }.onFailure { error ->
                logRuntimeHistoryBootstrapFailure(error)
            }
            archiveExporter.cleanupCache()
            if (importBundledProfilesOnInitialize) {
                profileImporter.importProfiles()
            }
            scope.launch {
                policyHandoverEventStore.events.collect { event ->
                    automaticProbeScheduler.schedule(event)
                }
            }
        }

        private fun logRuntimeHistoryBootstrapFailure(error: Throwable) {
            Logger.w(error) { "Runtime history bootstrap skipped" }
        }
    }
