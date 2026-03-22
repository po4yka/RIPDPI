package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceStrategyProbe
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsScanController
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
        private val scanRequestFactory: DiagnosticsScanRequestFactory,
        private val executionCoordinator: DiagnosticsScanExecutionCoordinator,
        private val timelineSource: DefaultDiagnosticsTimelineSource,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsScanController,
        AutomaticProbeLauncher {
        private companion object {
            private const val AutomaticProbeProfileId = "automatic-probing"
        }

        private val runtimeState = DiagnosticsScanRuntimeState(timelineSource)
        private val startMutex = Mutex()

        override suspend fun startScan(pathMode: ScanPathMode): String =
            startMutex.withLock {
                check(!runtimeState.hasActiveScan()) { "Diagnostics scan already active" }
                val settings = appSettingsRepository.snapshot()
                val profileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" }
                val profile =
                    requireNotNull(profileCatalog.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
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
            runtimeState.cancelActiveScan()
        }

        override suspend fun setActiveProfile(profileId: String) {
            requireNotNull(profileCatalog.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
            appSettingsRepository.update {
                diagnosticsActiveProfileId = profileId
            }
        }

        override fun hasActiveScan(): Boolean = runtimeState.hasActiveScan()

        override suspend fun launchAutomaticProbe(
            settings: com.poyka.ripdpi.proto.AppSettings,
            event: com.poyka.ripdpi.data.PolicyHandoverEvent,
        ): Boolean =
            startMutex.withLock {
                if (runtimeState.hasActiveScan()) {
                    return@withLock false
                }
                val profile = profileCatalog.getProfile(AutomaticProbeProfileId) ?: return@withLock false
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
            runtimeState.rememberPreparedScan(prepared)
            scanRecordStore.upsertScanSession(prepared.initialSession)
            artifactWriteStore.upsertSnapshot(prepared.preScanSnapshot)
            artifactWriteStore.upsertContextSnapshot(prepared.preScanContext)

            val bridge = networkDiagnosticsBridgeFactory.create()
            runtimeState.registerBridge(bridge, prepared.registerActiveBridge)
            val startFailure =
                runCatching {
                bridge.startScan(
                    requestJson = prepared.requestJson,
                    sessionId = prepared.sessionId,
                )
                }.exceptionOrNull()
            if (startFailure != null) {
                runtimeState.removePreparedScan(prepared.sessionId)
                runtimeState.clearBridge(bridge, prepared.registerActiveBridge)
                runCatching { bridge.destroy() }
                if (prepared.exposeProgress) {
                    runtimeState.updateProgress(null)
                }
                DiagnosticsReportPersister.persistScanFailure(
                    prepared.sessionId,
                    startFailure.message ?: "Diagnostics scan failed to start",
                    scanRecordStore,
                )
                throw startFailure
            }

            if (prepared.exposeProgress) {
                runtimeState.updateProgress(
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
                    bridge = bridge,
                    rawPathRunner = rawPathRunner,
                    runtimeState = runtimeState,
                )
            }
            return prepared.sessionId
        }
    }

class DiagnosticsScanRuntimeState(
    private val timelineSource: DefaultDiagnosticsTimelineSource,
) {
    private val bridgeMutex = Mutex()
    private var activeDiagnosticsBridge: NetworkDiagnosticsBridge? = null
    private val hiddenScanCount = AtomicInteger(0)
    private val scanSessionFingerprints = ConcurrentHashMap<String, NetworkFingerprint>()
    private val scanSessionPreferredDnsPaths = ConcurrentHashMap<String, EncryptedDnsPathCandidate>()

    @Volatile
    private var hasRegisteredActiveBridge = false

    fun rememberPreparedScan(prepared: PreparedDiagnosticsScan) {
        prepared.networkFingerprint?.let { scanSessionFingerprints[prepared.sessionId] = it }
        prepared.preferredDnsPath?.let { scanSessionPreferredDnsPaths[prepared.sessionId] = it }
    }

    fun removePreparedScan(sessionId: String) {
        scanSessionFingerprints.remove(sessionId)
        scanSessionPreferredDnsPaths.remove(sessionId)
    }

    fun fingerprint(sessionId: String): NetworkFingerprint? = scanSessionFingerprints[sessionId]

    fun preferredDnsPath(sessionId: String): EncryptedDnsPathCandidate? = scanSessionPreferredDnsPaths[sessionId]

    fun hasActiveScan(): Boolean =
        timelineSource.activeScanProgress.value != null || hasRegisteredActiveBridge || hiddenScanCount.get() > 0

    suspend fun cancelActiveScan() {
        bridgeMutex.withLock { activeDiagnosticsBridge }?.cancelScan()
        updateProgress(null)
    }

    suspend fun registerBridge(
        bridge: NetworkDiagnosticsBridge,
        registerActiveBridge: Boolean,
    ) {
        if (registerActiveBridge) {
            bridgeMutex.withLock {
                activeDiagnosticsBridge = bridge
                hasRegisteredActiveBridge = true
            }
        } else {
            hiddenScanCount.incrementAndGet()
        }
    }

    suspend fun clearBridge(
        bridge: NetworkDiagnosticsBridge,
        registerActiveBridge: Boolean,
    ) {
        if (registerActiveBridge) {
            bridgeMutex.withLock {
                if (activeDiagnosticsBridge === bridge) {
                    activeDiagnosticsBridge = null
                }
                hasRegisteredActiveBridge = activeDiagnosticsBridge != null
            }
        } else {
            hiddenScanCount.decrementAndGet()
        }
    }

    fun updateProgress(progress: ScanProgress?) {
        timelineSource.updateActiveScanProgress(progress)
    }
}

@Singleton
class DiagnosticsScanExecutionCoordinator
    @Inject
    constructor(
        @param:ApplicationContext
        private val context: Context,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val serviceStateStore: ServiceStateStore,
        private val resolverOverrideStore: ResolverOverrideStore,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            private const val FinishedReportPollAttempts = 5
            private const val FinishedReportPollDelayMs = 100L
            private const val PollScanResultTimeoutMs = 300_000L
            private const val PollScanIntervalMs = 400L
        }

        suspend fun execute(
            prepared: PreparedDiagnosticsScan,
            bridge: NetworkDiagnosticsBridge,
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
            runtimeState: DiagnosticsScanRuntimeState,
        ) {
            val failure =
                runCatching {
                    val scanBlock: suspend () -> Unit = {
                        pollScanResult(
                            prepared = prepared,
                            bridge = bridge,
                            runtimeState = runtimeState,
                        )
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
                runtimeState.removePreparedScan(prepared.sessionId)
                if (prepared.exposeProgress) {
                    runtimeState.updateProgress(null)
                }
                runCatching { bridge.destroy() }
                runtimeState.clearBridge(bridge, prepared.registerActiveBridge)
            }
        }

        private suspend fun pollScanResult(
            prepared: PreparedDiagnosticsScan,
            bridge: NetworkDiagnosticsBridge,
            runtimeState: DiagnosticsScanRuntimeState,
        ) {
            withTimeout(PollScanResultTimeoutMs) {
                while (true) {
                    persistPassiveEvents(prepared.sessionId, bridge)
                    val progress = readProgress(bridge)
                    if (prepared.exposeProgress) runtimeState.updateProgress(progress)
                    if (progress?.isFinished == true) {
                        finishScan(prepared, bridge, runtimeState)
                        break
                    }
                    delay(PollScanIntervalMs)
                }
            }
        }

        private suspend fun finishScan(
            prepared: PreparedDiagnosticsScan,
            bridge: NetworkDiagnosticsBridge,
            runtimeState: DiagnosticsScanRuntimeState,
        ) {
            val report = requireFinishedReport(bridge)
            val finalReport =
                maybeApplyTemporaryResolverOverride(
                    DiagnosticsScanWorkflow.enrichScanReport(
                        report = report,
                        settings = prepared.settings,
                        preferredDnsPath = runtimeState.preferredDnsPath(report.sessionId),
                    ),
                    prepared.settings,
                )
            DiagnosticsReportPersister.persistScanReport(
                finalReport,
                scanRecordStore,
                artifactWriteStore,
                serviceStateStore,
                json,
            )
            rememberNetworkDnsPathPreference(
                runtimeState.fingerprint(prepared.sessionId),
                finalReport.resolverRecommendation,
            )
            rememberStrategyProbeRecommendation(finalReport, prepared.settings)
            persistPostScanArtifacts(prepared.sessionId)
            persistPassiveEvents(prepared.sessionId, bridge)
            if (prepared.exposeProgress) runtimeState.updateProgress(null)
        }

        private suspend fun requireFinishedReport(bridge: NetworkDiagnosticsBridge): ScanReport {
            val reportJson =
                checkNotNull(awaitFinishedReportJson(bridge)) {
                    "Diagnostics scan completed without a report"
                }
            return json.decodeFromString(ScanReport.serializer(), reportJson)
        }

        private suspend fun persistPostScanArtifacts(sessionId: String) {
            val now = System.currentTimeMillis()
            artifactWriteStore.upsertSnapshot(
                com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    snapshotKind = "post_scan",
                    payloadJson =
                        json.encodeToString(
                            NetworkSnapshotModel.serializer(),
                            networkMetadataProvider.captureSnapshot(includePublicIp = true),
                        ),
                    capturedAt = now,
                ),
            )
            artifactWriteStore.upsertContextSnapshot(
                com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    contextKind = "post_scan",
                    payloadJson =
                        json.encodeToString(
                            DiagnosticContextModel.serializer(),
                            diagnosticsContextProvider.captureContext(),
                        ),
                    capturedAt = now,
                ),
            )
        }

        private suspend fun persistPassiveEvents(
            sessionId: String,
            bridge: NetworkDiagnosticsBridge,
        ) {
            DiagnosticsReportPersister.persistNativeEvents(
                sessionId = sessionId,
                payload = bridge.pollPassiveEventsJson(),
                artifactWriteStore = artifactWriteStore,
                json = json,
            )
        }

        private suspend fun readProgress(bridge: NetworkDiagnosticsBridge): ScanProgress? =
            bridge
                .pollProgressJson()
                ?.let { json.decodeFromString(ScanProgress.serializer(), it) }

        private suspend fun awaitFinishedReportJson(bridge: NetworkDiagnosticsBridge): String? {
            repeat(FinishedReportPollAttempts) { attempt ->
                bridge.takeReportJson()?.let { return it }
                if (attempt < FinishedReportPollAttempts - 1) {
                    delay(FinishedReportPollDelayMs)
                }
            }
            return null
        }

        private suspend fun maybeApplyTemporaryResolverOverride(
            report: ScanReport,
            settings: com.poyka.ripdpi.proto.AppSettings,
        ): ScanReport {
            val recommendation = report.resolverRecommendation ?: return report
            val (status, mode) = serviceStateStore.status.value
            val shouldApply =
                DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(report, settings, status, mode)
            return if (shouldApply) {
                resolverOverrideStore.setTemporaryOverride(
                    DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation),
                )
                report.copy(
                    resolverRecommendation = recommendation.copy(appliedTemporarily = true),
                )
            } else {
                report
            }
        }

        private suspend fun rememberNetworkDnsPathPreference(
            fingerprint: NetworkFingerprint?,
            recommendation: ResolverRecommendation?,
        ) {
            val selectedPath =
                with(ResolverRecommendationEngine) { recommendation?.toEncryptedDnsPathCandidate() } ?: return
            fingerprint ?: return
            networkDnsPathPreferenceStore.rememberPreferredPath(
                fingerprint = fingerprint,
                path = selectedPath,
            )
        }

        private suspend fun rememberStrategyProbeRecommendation(
            report: ScanReport,
            settings: com.poyka.ripdpi.proto.AppSettings,
        ) {
            val strategyProbe = report.strategyProbeReport
            val fingerprint = networkFingerprintProvider.capture()
            val shouldRemember = settings.networkStrategyMemoryEnabled && !settings.enableCmdSettings
            val policy =
                if (shouldRemember && strategyProbe != null && fingerprint != null) {
                    DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                        strategyProbe = strategyProbe,
                        settings = settings,
                        fingerprint = fingerprint,
                        hostAutolearnStorePath =
                            settings
                                .takeIf { it.hostAutolearnEnabled }
                                ?.let { resolveHostAutolearnStorePath(context) },
                        json = json,
                    )
                } else {
                    null
                }
            if (policy != null) {
                rememberedNetworkPolicyStore.rememberValidatedPolicy(
                    policy = policy,
                    source = RememberedNetworkPolicySourceStrategyProbe,
                    validatedAt = report.finishedAt,
                )
            }
        }
    }
