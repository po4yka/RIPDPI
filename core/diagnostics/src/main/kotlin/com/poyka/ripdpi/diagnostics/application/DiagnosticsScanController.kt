@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.policyHandoverScanSessionId
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanRequestFactory
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        private val serviceStateStore: ServiceStateStore,
        private val scanRequestFactory: DiagnosticsScanRequestFactory,
        private val scanAdmissionService: ScanAdmissionService,
        private val activeScanRegistry: ActiveScanRegistry,
        private val bridgeExecutionService: BridgeExecutionService,
        private val executionCoordinator: DiagnosticsScanExecutionCoordinator,
        private val hiddenProbeConflictRequestFactory: HiddenProbeConflictRequestFactory,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsScanController,
        AutomaticProbeLauncher {
        private companion object {
            const val HiddenProbeCancellationTimeoutMs = 10_000L
            const val StartupCanceledSummary = "Diagnostics scan canceled during startup"
            const val StartupFailedSummary = "Diagnostics scan failed to start"
        }

        private val startMutex = Mutex()
        private var pendingHiddenConflictRequest: PendingHiddenConflictRequest? = null

        override val hiddenAutomaticProbeActive: StateFlow<Boolean> = activeScanRegistry.hiddenAutomaticProbeActive

        override suspend fun startScan(
            pathMode: ScanPathMode,
            selectedProfileId: String?,
            skipActiveScanCheck: Boolean,
            allowSensitiveProfileStart: Boolean,
            scanDeadlineMs: Long?,
            maxCandidates: Int?,
            targetOverrides: DiagnosticsScanTargetOverrides?,
        ): DiagnosticsManualScanStartResult =
            startManualScan(
                ownerId = null,
                pathMode = pathMode,
                selectedProfileId = selectedProfileId,
                skipActiveScanCheck = skipActiveScanCheck,
                allowSensitiveProfileStart = allowSensitiveProfileStart,
                scanDeadlineMs = scanDeadlineMs,
                maxCandidates = maxCandidates,
                targetOverrides = targetOverrides,
            )

        override suspend fun startScanOwnedBy(
            ownerId: String,
            pathMode: ScanPathMode,
            selectedProfileId: String?,
            skipActiveScanCheck: Boolean,
            allowSensitiveProfileStart: Boolean,
            scanDeadlineMs: Long?,
            maxCandidates: Int?,
            targetOverrides: DiagnosticsScanTargetOverrides?,
            resumeRuntimeAfterRawPath: Boolean,
        ): DiagnosticsManualScanStartResult =
            startManualScan(
                ownerId = ownerId,
                pathMode = pathMode,
                selectedProfileId = selectedProfileId,
                skipActiveScanCheck = skipActiveScanCheck,
                allowSensitiveProfileStart = allowSensitiveProfileStart,
                scanDeadlineMs = scanDeadlineMs,
                maxCandidates = maxCandidates,
                targetOverrides = targetOverrides,
                resumeRuntimeAfterRawPath = resumeRuntimeAfterRawPath,
            )

        private suspend fun startManualScan(
            ownerId: String?,
            pathMode: ScanPathMode,
            selectedProfileId: String?,
            skipActiveScanCheck: Boolean,
            allowSensitiveProfileStart: Boolean,
            scanDeadlineMs: Long?,
            maxCandidates: Int?,
            targetOverrides: DiagnosticsScanTargetOverrides?,
            resumeRuntimeAfterRawPath: Boolean = false,
        ): DiagnosticsManualScanStartResult =
            startMutex.withLock {
                when (
                    val admission =
                        scanAdmissionService.admitManualStart(
                            selectedProfileId = selectedProfileId,
                            skipActiveScanCheck = skipActiveScanCheck,
                            allowSensitiveProfileStart = allowSensitiveProfileStart,
                        )
                ) {
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
                                        scanDeadlineMs = scanDeadlineMs,
                                        targetOverrides = targetOverrides,
                                        maxCandidates = maxCandidates,
                                    ),
                                rawPathRunner = { block ->
                                    if (resumeRuntimeAfterRawPath) {
                                        runtimeCoordinator.runAutomaticRawPathScan(block)
                                    } else {
                                        runtimeCoordinator.runRawPathScan(block)
                                    }
                                },
                                ownerId = ownerId,
                            ),
                        )
                    }

                    is ManualStartAdmission.HiddenAutomaticProbeConflict -> {
                        hiddenProbeConflictRequestFactory
                            .create(
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
                        if (activeScanRegistry.hasHiddenActiveScan) {
                            return@withLock DiagnosticsManualScanResolution.Failed(
                                DiagnosticsManualScanResolutionFailureReason.HIDDEN_PROBE_STILL_ACTIVE,
                            )
                        }
                    }

                    HiddenProbeConflictAction.CANCEL_AND_RUN -> {
                        if (activeScanRegistry.hasHiddenActiveScan) {
                            val cancellation =
                                activeScanRegistry.cancelHiddenAutomaticProbe(
                                    cancellationSummary =
                                    BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary,
                                    timeoutMs = HiddenProbeCancellationTimeoutMs,
                                    beforeCancel = { sessionId ->
                                        DiagnosticsReportPersister.persistScanCancellationCause(
                                            sessionId,
                                            BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary,
                                            scanRecordStore,
                                        )
                                    },
                                )
                            if (cancellation is HiddenProbeCancellationResult.Failed) {
                                return@withLock DiagnosticsManualScanResolution.Failed(
                                    DiagnosticsManualScanResolutionFailureReason.CANCELLATION_FAILED,
                                )
                            }
                            if (cancellation is HiddenProbeCancellationResult.Cancelled) {
                                if (scanRecordStore.getScanSession(cancellation.sessionId) == null) {
                                    return@withLock DiagnosticsManualScanResolution.Failed(
                                        DiagnosticsManualScanResolutionFailureReason.CANCELLATION_FAILED,
                                    )
                                }
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
            val cancellation = activeScanRegistry.cancelActiveScan() ?: return
            persistCancellationResult(cancellation)
        }

        override suspend fun cancelScan(sessionId: String) {
            val cancellation = activeScanRegistry.cancelScan(sessionId) ?: return
            persistCancellationResult(cancellation)
        }

        override fun activeSessionIdsOwnedBy(ownerId: String): Set<String> =
            activeScanRegistry.sessionOwnership.activeSessionIds(ownerId)

        override suspend fun releaseSessionsOwnedBy(ownerId: String) {
            val ownedSessionIds = activeScanRegistry.sessionOwnership.activeSessionIds(ownerId)
            activeScanRegistry.ownerExecutions.cancel(ownerId)
            ownedSessionIds.forEach { sessionId ->
                val runningSession = scanRecordStore.getScanSession(sessionId)?.takeIf { it.status == "running" }
                if (runningSession != null) {
                    DiagnosticsReportPersister.persistScanFailure(
                        sessionId,
                        "Diagnostics scan canceled during startup",
                        scanRecordStore,
                    )
                }
                activeScanRegistry.removePreparedScan(sessionId)
            }
        }

        private suspend fun persistCancellationResult(cancellation: ActiveScanCancellation) {
            var failure = cancellation.failure
            withContext(NonCancellable) {
                runCatching { persistCancelledScan(cancellation) }
                    .exceptionOrNull()
                    ?.let { persistenceFailure ->
                        if (failure == null) {
                            failure = persistenceFailure
                        } else {
                            failure?.addSuppressed(persistenceFailure)
                        }
                    }
            }
            failure?.let { throw it }
        }

        private suspend fun persistCancelledScan(cancellation: ActiveScanCancellation) {
            val session =
                scanRecordStore
                    .getScanSession(cancellation.sessionId)
                    ?.takeIf { it.status == "running" }
                    ?: return

            val partialReportJson =
                cancellation.partialReportJson
                    ?: activeScanRegistry.consumeCancelledSessionReport(cancellation.sessionId)
            if (partialReportJson != null) {
                persistPartialScanSession(session, partialReportJson, scanRecordStore)
            } else {
                DiagnosticsReportPersister.persistScanFailure(
                    cancellation.sessionId,
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
                val sessionId = policyHandoverScanSessionId(event.deliveryId)
                val existingSession = scanRecordStore.getScanSession(sessionId)
                if (existingSession != null && existingSession.status != "running") return@withLock true
                if (existingSession?.status == "running" && activeScanRegistry.hasRegisteredExecution(sessionId)) {
                    return@withLock false
                }
                val profile = scanAdmissionService.admitAutomaticProbe(settings) ?: return@withLock false
                val prepared =
                    scanRequestFactory.prepareScan(
                        profile = profile,
                        settings = settings,
                        pathMode = ScanPathMode.RAW_PATH,
                        scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                        launchTrigger = event.toLaunchTrigger(),
                        exposeProgress = false,
                        registerActiveBridge = false,
                        sessionIdOverride = sessionId,
                    )
                val preparedFingerprintHash = prepared.networkFingerprint?.scopeKey() ?: return@withLock false
                val modeMatches = prepared.context.serviceMode.equals(event.mode.name, ignoreCase = true)
                if (preparedFingerprintHash != event.currentFingerprintHash || !modeMatches) {
                    return@withLock true
                }
                startPreparedScan(
                    prepared = prepared,
                    rawPathRunner = { block -> runtimeCoordinator.runAutomaticRawPathScan(block) },
                )
                false
            }

        private suspend fun startPreparedScan(
            prepared: PreparedDiagnosticsScan,
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
            ownerId: String? = null,
        ): String {
            val routedPrepared =
                prepared.bindCurrentInPathRoute(
                    serviceStateStore = serviceStateStore,
                    runtimeCoordinator = runtimeCoordinator,
                    scanRequestFactory = scanRequestFactory,
                )
            val startup = StartupTransactionResources()
            val startupFailure =
                runCatching {
                    persistPreparedScan(routedPrepared, ownerId)
                    ensureStartupActive(scope)
                    val bridgeSession = createStartedBridge(routedPrepared, startup)
                    ensureStartupActive(scope)
                    launchPreparedScan(routedPrepared, bridgeSession, rawPathRunner, startup)
                }.exceptionOrNull()
            if (startupFailure != null) {
                val summary =
                    if (startupFailure is CancellationException) StartupCanceledSummary else StartupFailedSummary
                cleanupStartupFailure(routedPrepared, startup, summary, startupFailure)
                throw startupFailure
            }
            return routedPrepared.sessionId
        }

        private suspend fun persistPreparedScan(
            prepared: PreparedDiagnosticsScan,
            ownerId: String?,
        ) {
            if (ownerId != null) {
                prepared
                    .inPathPreflightFailure(serviceStateStore.status.value)
                    ?.let { failure -> throw InPathRuntimeUnavailableException(failure.summary, failure.reason) }
            }
            activeScanRegistry.rememberPreparedScan(prepared, ownerId)
            ensureStartupActive(scope)
            scanRecordStore.upsertScanSession(prepared.initialSession)
            ensureStartupActive(scope)
            artifactWriteStore.upsertSnapshot(prepared.preScanSnapshot)
            ensureStartupActive(scope)
            artifactWriteStore.upsertContextSnapshot(prepared.preScanContext)
            ensureStartupActive(scope)

            val failure = prepared.inPathPreflightFailure() ?: return
            activeScanRegistry.removePreparedScan(prepared.sessionId)
            clearPreparedProgress(prepared)
            DiagnosticsReportPersister.persistScanFailure(
                prepared.sessionId,
                failure.summary,
                scanRecordStore,
            )
            throw IllegalStateException(failure.summary)
        }

        private suspend fun createStartedBridge(
            prepared: PreparedDiagnosticsScan,
            startup: StartupTransactionResources,
        ): PreparedBridgeSession {
            prepared.inPathRouteLease?.let { lease ->
                if (!runtimeCoordinator.isInPathRouteLeaseCurrent(lease)) {
                    throw InPathRuntimeUnavailableException(
                        "In-path diagnostics unavailable: the active VPN route changed before scan start",
                        DiagnosticsHomeCompositeStageUnavailableReason.RUNTIME_CHANGED_OR_UNAVAILABLE,
                    )
                }
            }
            val handle =
                bridgeExecutionService.createHandle(
                    sessionId = prepared.sessionId,
                    registerActiveBridge = prepared.registerActiveBridge,
                )
            startup.handle = handle
            ensureStartupActive(scope)
            val startBridgeBeforeAwait = prepared.pathMode == ScanPathMode.RAW_PATH
            if (startBridgeBeforeAwait) return PreparedBridgeSession(handle, true)

            bridgeExecutionService.start(
                handle = handle,
                requestJson = prepared.requestJson,
            )
            ensureStartupActive(scope)
            return PreparedBridgeSession(handle, false)
        }

        private suspend fun launchPreparedScan(
            prepared: PreparedDiagnosticsScan,
            bridgeSession: PreparedBridgeSession,
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
            startup: StartupTransactionResources,
        ) {
            if (prepared.exposeProgress) {
                activeScanRegistry.updateProgress(
                    prepared.sessionId,
                    ScanProgress(
                        sessionId = prepared.sessionId,
                        phase = "preparing",
                        completedSteps = 0,
                        totalSteps = 1,
                        message = "Preparing diagnostics session",
                    ),
                )
                ensureStartupActive(scope)
            }
            ensureStartupActive(scope)

            val executionJob =
                scope.launch(start = CoroutineStart.LAZY) {
                    executionCoordinator.execute(
                        prepared = prepared,
                        handle = bridgeSession.handle,
                        rawPathRunner = rawPathRunner,
                        startBridgeBeforeAwait = bridgeSession.startBeforeAwait,
                    )
                }
            startup.executionJob = executionJob
            ensureStartupActive(scope)
            val executionRegistered =
                activeScanRegistry.registerExecution(
                    sessionId = prepared.sessionId,
                    job = executionJob,
                    registerActiveBridge = prepared.registerActiveBridge,
                )
            ensureStartupActive(scope)
            if (!executionRegistered || !executionJob.start()) {
                throw CancellationException(StartupCanceledSummary)
            }
        }

        private suspend fun cleanupStartupFailure(
            prepared: PreparedDiagnosticsScan,
            startup: StartupTransactionResources,
            summary: String,
            primaryFailure: Throwable,
        ) {
            withContext(NonCancellable) {
                startup.executionJob?.cancel()
                runCleanupStep(primaryFailure) {
                    startup.executionJob?.cancelAndJoin()
                }
                runCleanupStep(primaryFailure) {
                    scanRecordStore
                        .getScanSession(prepared.sessionId)
                        ?.takeIf { session -> session.status == "running" }
                        ?.let {
                            DiagnosticsReportPersister.persistScanFailure(prepared.sessionId, summary, scanRecordStore)
                        }
                }
                startup.handle?.let { handle ->
                    runCatching { bridgeExecutionService.retireAfterStartupFailure(handle) }
                        .exceptionOrNull()
                        ?.takeIf { it !== primaryFailure }
                        ?.let(primaryFailure::addSuppressed)
                }
                activeScanRegistry.removePreparedScan(prepared.sessionId)
                runCleanupStep(primaryFailure) { clearPreparedProgress(prepared) }
            }
        }

        private suspend fun clearPreparedProgress(prepared: PreparedDiagnosticsScan) {
            if (prepared.exposeProgress) {
                activeScanRegistry.updateProgress(prepared.sessionId, null)
            }
        }
    }

private data class PreparedBridgeSession(
    val handle: BridgeSessionHandle,
    val startBeforeAwait: Boolean,
)

private data class StartupTransactionResources(
    var handle: BridgeSessionHandle? = null,
    var executionJob: Job? = null,
)

private const val StartupCleanupStepTimeoutMs = 2_000L

private suspend fun ensureStartupActive(scope: CoroutineScope) {
    if (!currentCoroutineContext().isActive || !scope.isActive) {
        throw CancellationException("Diagnostics scan canceled during startup")
    }
}

private suspend fun runCleanupStep(
    primaryFailure: Throwable,
    block: suspend () -> Unit,
) {
    runCatching {
        withTimeout(StartupCleanupStepTimeoutMs) { block() }
    }.exceptionOrNull()
        ?.takeIf { it !== primaryFailure }
        ?.let(primaryFailure::addSuppressed)
}

internal class HiddenProbeConflictRequestFactory
    @Inject
    constructor(
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private var requestIdGenerator: () -> String = { UUID.randomUUID().toString() }

        internal constructor(
            json: Json,
            requestIdGenerator: () -> String,
        ) : this(json) {
            this.requestIdGenerator = requestIdGenerator
        }

        private companion object {
            const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
        }

        fun create(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
        ): PendingHiddenConflictRequest {
            val projection = json.decodeProfileSpecWire(profile.requestJson).toProfileProjection()
            return PendingHiddenConflictRequest(
                requestId = requestIdGenerator(),
                profile = profile,
                settings = settings,
                pathMode = pathMode,
                profileName = profile.name,
                scanKind = projection.kind,
                isFullAudit = projection.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1,
            )
        }
    }

internal suspend fun persistPartialScanSession(
    session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
    partialReportJson: String,
    scanRecordStore: DiagnosticsScanRecordStore,
) {
    scanRecordStore.upsertScanSession(
        session.copy(
            status = "completed",
            summary = "Scan completed with partial results",
            reportJson = partialReportJson,
            finishedAt = System.currentTimeMillis(),
        ),
    )
}

private const val InPathServiceUnavailableAction = "start the RIPDPI service before scanning"

internal class InPathRuntimeUnavailableException(
    message: String,
    val reason: DiagnosticsHomeCompositeStageUnavailableReason =
        DiagnosticsHomeCompositeStageUnavailableReason.RUNTIME_CHANGED_OR_UNAVAILABLE,
) : IllegalStateException(message)

private data class InPathPreflightFailure(
    val summary: String,
    val reason: DiagnosticsHomeCompositeStageUnavailableReason,
)

internal suspend fun PreparedDiagnosticsScan.bindCurrentInPathRoute(
    serviceStateStore: ServiceStateStore,
    runtimeCoordinator: DiagnosticsRuntimeCoordinator,
    scanRequestFactory: DiagnosticsScanRequestFactory,
): PreparedDiagnosticsScan {
    val liveRuntime = serviceStateStore.status.value
    val needsOwnedVpnRoute =
        pathMode == ScanPathMode.IN_PATH &&
            liveRuntime.first == AppStatus.Running &&
            liveRuntime.second == Mode.VPN
    return if (needsOwnedVpnRoute) {
        val lease =
            runtimeCoordinator.acquireInPathRouteLease()
                ?: throw InPathRuntimeUnavailableException(
                    "In-path diagnostics unavailable: the active VPN route is not currently observable",
                    DiagnosticsHomeCompositeStageUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED,
                )
        scanRequestFactory.bindInPathRoute(this, lease)
    } else {
        this
    }
}

private fun PreparedDiagnosticsScan.inPathPreflightFailure(
    liveRuntime: Pair<AppStatus, Mode>? = null,
): InPathPreflightFailure? {
    val service = context.contextSnapshot.service
    val liveStatus = liveRuntime?.first
    val expectedEndpoint = expectedProxyEndpoint()
    val listenerAddress = service.proxy?.listenerAddress?.takeIf { it.isNotBlank() }
    return when {
        pathMode != ScanPathMode.IN_PATH -> {
            null
        }

        liveStatus != null && liveStatus != AppStatus.Running -> {
            InPathPreflightFailure(
                summary =
                    "In-path diagnostics unavailable: local proxy service is ${liveStatus.name}; " +
                        InPathServiceUnavailableAction,
                reason = DiagnosticsHomeCompositeStageUnavailableReason.SERVICE_NOT_RUNNING,
            )
        }

        liveStatus == null && !service.serviceStatus.equals(AppStatus.Running.name, ignoreCase = true) -> {
            val status = service.serviceStatus
            InPathPreflightFailure(
                summary =
                    "In-path diagnostics unavailable: local proxy service is $status; " +
                        InPathServiceUnavailableAction,
                reason = DiagnosticsHomeCompositeStageUnavailableReason.SERVICE_NOT_RUNNING,
            )
        }

        preparedRouteAbsent() && listenerAddress != null && listenerAddress != expectedEndpoint -> {
            InPathPreflightFailure(
                summary =
                    "In-path diagnostics unavailable: proxy listener is $listenerAddress, " +
                        "expected $expectedEndpoint",
                reason = DiagnosticsHomeCompositeStageUnavailableReason.PROXY_ENDPOINT_MISMATCH,
            )
        }

        else -> {
            null
        }
    }
}

private fun PreparedDiagnosticsScan.preparedRouteAbsent(): Boolean = inPathRouteLease == null

private fun PreparedDiagnosticsScan.expectedProxyEndpoint(): String =
    plan.proxyHost?.let { host ->
        plan.proxyPort?.let { port -> "$host:$port" } ?: host
    } ?: "unknown"

internal data class PendingHiddenConflictRequest(
    val requestId: String,
    val profile: DiagnosticProfileEntity,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val profileName: String,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
)

internal fun PendingHiddenConflictRequest.toConflictResult():
    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution =
    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
        requestId = requestId,
        profileName = profileName,
        pathMode = pathMode,
        scanKind = scanKind,
        isFullAudit = isFullAudit,
    )
