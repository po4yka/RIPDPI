package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
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
        @param:Named("diagnosticsJson")
        private val json: Json,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsScanController,
        AutomaticProbeLauncher {
        private companion object {
            const val HiddenProbeCancellationTimeoutMs = 10_000L
            const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
        }

        private val startMutex = Mutex()
        private var pendingHiddenConflictRequest: PendingHiddenConflictRequest? = null

        override val hiddenAutomaticProbeActive: StateFlow<Boolean> = activeScanRegistry.hiddenAutomaticProbeActive

        override suspend fun startScan(
            pathMode: ScanPathMode,
            selectedProfileId: String?,
        ): DiagnosticsManualScanStartResult =
            startMutex.withLock {
                when (val admission = scanAdmissionService.admitManualStart(selectedProfileId)) {
                    is ManualStartAdmission.Admitted -> {
                        pendingHiddenConflictRequest = null
                        DiagnosticsManualScanStartResult.Started(
                            startPreparedScan(
                                prepared =
                                    scanRequestFactory.prepareScan(
                                        profile = admission.profile,
                                        settings = admission.settings,
                                        pathMode = pathMode,
                                        scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                                        launchTrigger = null,
                                        exposeProgress = true,
                                        registerActiveBridge = true,
                                    ),
                                rawPathRunner = { block -> runtimeCoordinator.runRawPathScan(block) },
                            ),
                        )
                    }

                    is ManualStartAdmission.HiddenAutomaticProbeConflict -> {
                        createPendingHiddenConflictRequest(
                            profile = admission.profile,
                            settings = admission.settings,
                            pathMode = pathMode,
                        ).also { pendingRequest ->
                            pendingHiddenConflictRequest = pendingRequest
                        }.toConflictResult()
                    }
                }
            }

        override suspend fun resolveHiddenProbeConflict(
            requestId: String,
            action: HiddenProbeConflictAction,
        ): DiagnosticsManualScanResolution =
            startMutex.withLock {
                val pendingRequest =
                    pendingHiddenConflictRequest
                        ?.takeIf { it.requestId == requestId }
                        ?: return@withLock DiagnosticsManualScanResolution.Failed(
                            DiagnosticsManualScanResolutionFailureReason.REQUEST_NOT_FOUND,
                        )

                when (action) {
                    HiddenProbeConflictAction.WAIT -> {
                        if (activeScanRegistry.hasHiddenActiveScan()) {
                            return@withLock DiagnosticsManualScanResolution.Failed(
                                DiagnosticsManualScanResolutionFailureReason.HIDDEN_PROBE_STILL_ACTIVE,
                            )
                        }
                    }

                    HiddenProbeConflictAction.CANCEL_AND_RUN -> {
                        if (activeScanRegistry.hasHiddenActiveScan()) {
                            val cancellation =
                                activeScanRegistry.cancelHiddenAutomaticProbe(
                                    cancellationSummary =
                                    BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary,
                                    timeoutMs = HiddenProbeCancellationTimeoutMs,
                                )
                            if (cancellation !is HiddenProbeCancellationResult.Cancelled) {
                                return@withLock DiagnosticsManualScanResolution.Failed(
                                    DiagnosticsManualScanResolutionFailureReason.CANCELLATION_FAILED,
                                )
                            }
                            val cancelledSession =
                                scanRecordStore.getScanSession(cancellation.sessionId)
                                    ?: return@withLock DiagnosticsManualScanResolution.Failed(
                                        DiagnosticsManualScanResolutionFailureReason.CANCELLATION_FAILED,
                                    )
                            if (cancelledSession.status == "running") {
                                DiagnosticsReportPersister.persistScanFailure(
                                    cancellation.sessionId,
                                    BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary,
                                    scanRecordStore,
                                )
                            }
                        }
                    }
                }

                pendingHiddenConflictRequest = null
                runCatching {
                    startPreparedScan(
                        prepared =
                            scanRequestFactory.prepareScan(
                                profile = pendingRequest.profile,
                                settings = pendingRequest.settings,
                                pathMode = pendingRequest.pathMode,
                                scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                                launchTrigger = null,
                                exposeProgress = true,
                                registerActiveBridge = true,
                            ),
                        rawPathRunner = { block -> runtimeCoordinator.runRawPathScan(block) },
                    )
                }.fold(
                    onSuccess = { sessionId -> DiagnosticsManualScanResolution.Started(sessionId) },
                    onFailure = {
                        DiagnosticsManualScanResolution.Failed(
                            DiagnosticsManualScanResolutionFailureReason.START_FAILED,
                        )
                    },
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
                            scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                            launchTrigger = event.toLaunchTrigger(),
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

        private fun createPendingHiddenConflictRequest(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
        ): PendingHiddenConflictRequest {
            val projection = json.decodeProfileSpecWire(profile.requestJson).toProfileProjection()
            return PendingHiddenConflictRequest(
                requestId = UUID.randomUUID().toString(),
                profile = profile,
                settings = settings,
                pathMode = pathMode,
                profileName = profile.name,
                scanKind = projection.kind,
                isFullAudit = projection.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1,
            )
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
        activeScanRegistry.cancellationSummary(sessionId) ?: "Diagnostics scan canceled"
    } else {
        message ?: "Diagnostics scan failed"
    }

private data class PendingHiddenConflictRequest(
    val requestId: String,
    val profile: DiagnosticProfileEntity,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val profileName: String,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
)

private fun PendingHiddenConflictRequest.toConflictResult():
    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution =
    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
        requestId = requestId,
        profileName = profileName,
        pathMode = pathMode,
        scanKind = scanKind,
        isFullAudit = isFullAudit,
    )
