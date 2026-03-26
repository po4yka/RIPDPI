package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultDiagnosticsScanController
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
        private val scanRequestFactory: DiagnosticsScanRequestFactory,
        private val scanAdmissionService: ScanAdmissionService,
        private val activeScanRegistry: ActiveScanRegistry,
        private val bridgeExecutionService: BridgeExecutionService,
        private val executionCoordinator: DiagnosticsScanExecutionCoordinator,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsScanController,
        AutomaticProbeLauncher {
        private val startMutex = Mutex()

        override suspend fun startScan(pathMode: ScanPathMode): String =
            startMutex.withLock {
                val (settings, profile) = scanAdmissionService.admitManualStart()
                startPreparedScan(
                    prepared =
                        scanRequestFactory.prepareScan(
                            profile = profile,
                            settings = settings,
                            pathMode = pathMode,
                            exposeProgress = true,
                            registerActiveBridge = true,
                        ),
                    rawPathRunner = { block -> runtimeCoordinator.runRawPathScan(block) },
                )
            }

        override suspend fun cancelActiveScan() {
            val canceledSessionId = activeScanRegistry.cancelActiveScan() ?: return
            val session = scanRecordStore.getScanSession(canceledSessionId) ?: return
            if (session.status == "running") {
                DiagnosticsReportPersister.persistScanFailure(
                    canceledSessionId,
                    "Diagnostics scan canceled",
                    scanRecordStore,
                )
            }
        }

        override fun hasActiveScan(): Boolean = activeScanRegistry.hasActiveScan()

        override suspend fun setActiveProfile(profileId: String) {
            scanAdmissionService.assertProfileExists(profileId)
            appSettingsRepository.update {
                diagnosticsActiveProfileId = profileId
            }
        }

        override suspend fun launchAutomaticProbe(
            settings: com.poyka.ripdpi.proto.AppSettings,
            event: PolicyHandoverEvent,
        ): Boolean =
            startMutex.withLock {
                val profile = scanAdmissionService.admitAutomaticProbe(settings) ?: return@withLock false
                startPreparedScan(
                    prepared =
                        scanRequestFactory.prepareScan(
                            profile = profile,
                            settings = settings,
                            pathMode = ScanPathMode.RAW_PATH,
                            exposeProgress = false,
                            registerActiveBridge = false,
                        ),
                    rawPathRunner = { block -> runtimeCoordinator.runAutomaticRawPathScan(block) },
                )
                true
            }

        private suspend fun startPreparedScan(
            prepared: PreparedDiagnosticsScan,
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
        ): String {
            activeScanRegistry.rememberPreparedScan(prepared)
            scanRecordStore.upsertScanSession(prepared.initialSession)
            artifactWriteStore.upsertSnapshot(prepared.preScanSnapshot)
            artifactWriteStore.upsertContextSnapshot(prepared.preScanContext)

            val handle =
                bridgeExecutionService.createHandle(
                    sessionId = prepared.sessionId,
                    registerActiveBridge = prepared.registerActiveBridge,
                )
            val startFailure =
                runCatching {
                    bridgeExecutionService.start(
                        handle = handle,
                        requestJson = prepared.requestJson,
                    )
                }.exceptionOrNull()
            if (startFailure != null) {
                activeScanRegistry.removePreparedScan(prepared.sessionId)
                runCatching { bridgeExecutionService.destroy(handle) }
                if (prepared.exposeProgress) {
                    activeScanRegistry.updateProgress(null)
                }
                DiagnosticsReportPersister.persistScanFailure(
                    prepared.sessionId,
                    startFailure.message ?: "Diagnostics scan failed to start",
                    scanRecordStore,
                )
                throw startFailure
            }

            if (prepared.exposeProgress) {
                activeScanRegistry.updateProgress(
                    ScanProgress(
                        sessionId = prepared.sessionId,
                        phase = "preparing",
                        completedSteps = 0,
                        totalSteps = 1,
                        message = "Preparing diagnostics session",
                    ),
                )
            }

            val executionJob =
                scope.launch {
                    executionCoordinator.execute(
                        prepared = prepared,
                        handle = handle,
                        rawPathRunner = rawPathRunner,
                    )
                }
            activeScanRegistry.registerExecution(
                sessionId = prepared.sessionId,
                job = executionJob,
                registerActiveBridge = prepared.registerActiveBridge,
            )
            return prepared.sessionId
        }
    }

@Singleton
internal class DiagnosticsScanExecutionCoordinator
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val activeScanRegistry: ActiveScanRegistry,
        private val bridgeExecutionService: BridgeExecutionService,
        private val bridgePollingService: BridgePollingService,
        private val scanFinalizationService: ScanFinalizationService,
    ) {
        internal suspend fun execute(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
        ) {
            val failure =
                try {
                    val scanBlock: suspend () -> Unit = {
                        bridgePollingService.awaitCompletion(
                            prepared = prepared,
                            handle = handle,
                            activeScanRegistry = activeScanRegistry,
                        ) { reportJson ->
                            scanFinalizationService.finalize(
                                prepared = prepared,
                                reportJson = reportJson,
                            )
                            bridgePollingService.persistPassiveEvents(handle)
                            if (prepared.exposeProgress) {
                                activeScanRegistry.updateProgress(null)
                            }
                        }
                    }

                    when (prepared.pathMode) {
                        ScanPathMode.RAW_PATH -> rawPathRunner(scanBlock)
                        ScanPathMode.IN_PATH -> scanBlock()
                    }
                    null
                } catch (error: CancellationException) {
                    if (activeScanRegistry.isCancellationRequested(prepared.sessionId)) {
                        error
                    } else {
                        throw error
                    }
                } catch (error: Throwable) {
                    error
                }
            try {
                if (failure != null) {
                    DiagnosticsReportPersister.persistScanFailure(
                        prepared.sessionId,
                        failure.summaryForScan(prepared.sessionId, activeScanRegistry),
                        scanRecordStore,
                    )
                }
            } finally {
                activeScanRegistry.removePreparedScan(prepared.sessionId)
                if (prepared.exposeProgress) {
                    activeScanRegistry.updateProgress(null)
                }
                runCatching { bridgeExecutionService.destroy(handle) }
            }
        }
    }

private fun Throwable.summaryForScan(
    sessionId: String,
    activeScanRegistry: ActiveScanRegistry,
): String =
    if (activeScanRegistry.isCancellationRequested(sessionId)) {
        "Diagnostics scan canceled"
    } else {
        message ?: "Diagnostics scan failed"
    }
