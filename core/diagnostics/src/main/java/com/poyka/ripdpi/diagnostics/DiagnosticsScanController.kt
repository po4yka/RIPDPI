package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
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
            activeScanRegistry.cancelActiveScan()
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

            scope.launch {
                executionCoordinator.execute(
                    prepared = prepared,
                    handle = handle,
                    rawPathRunner = rawPathRunner,
                )
            }
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
                runCatching {
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
                }.exceptionOrNull()
            try {
                if (failure != null) {
                    DiagnosticsReportPersister.persistScanFailure(
                        prepared.sessionId,
                        failure.message ?: "Diagnostics scan failed",
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
