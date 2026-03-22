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
    ) : DiagnosticsScanController, AutomaticProbeLauncher {
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
            try {
                bridge.startScan(
                    requestJson = prepared.requestJson,
                    sessionId = prepared.sessionId,
                )
            } catch (error: Throwable) {
                runtimeState.removePreparedScan(prepared.sessionId)
                runtimeState.clearBridge(bridge, prepared.registerActiveBridge)
                runCatching { bridge.destroy() }
                if (prepared.exposeProgress) {
                    runtimeState.updateProgress(null)
                }
                DiagnosticsReportPersister.persistScanFailure(
                    prepared.sessionId,
                    error.message ?: "Diagnostics scan failed to start",
                    scanRecordStore,
                )
                throw error
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
        }

        suspend fun execute(
            prepared: PreparedDiagnosticsScan,
            bridge: NetworkDiagnosticsBridge,
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
            runtimeState: DiagnosticsScanRuntimeState,
        ) {
            try {
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
            } catch (error: Throwable) {
                DiagnosticsReportPersister.persistScanFailure(
                    prepared.sessionId,
                    error.message ?: "Diagnostics scan failed",
                    scanRecordStore,
                )
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
                    DiagnosticsReportPersister.persistNativeEvents(
                        sessionId = prepared.sessionId,
                        payload = bridge.pollPassiveEventsJson(),
                        artifactWriteStore = artifactWriteStore,
                        json = json,
                    )
                    val progress =
                        bridge
                            .pollProgressJson()
                            ?.let { json.decodeFromString(ScanProgress.serializer(), it) }
                    if (prepared.exposeProgress) {
                        runtimeState.updateProgress(progress)
                    }
                    if (progress != null && progress.isFinished) {
                        val report =
                            awaitFinishedReportJson(bridge)
                                ?.let { json.decodeFromString(ScanReport.serializer(), it) }
                                ?: throw IllegalStateException("Diagnostics scan completed without a report")
                        val enrichedReport = DiagnosticsScanWorkflow.enrichScanReport(
                            report = report,
                            settings = prepared.settings,
                            preferredDnsPath = runtimeState.preferredDnsPath(report.sessionId),
                        )
                        val finalReport = maybeApplyTemporaryResolverOverride(enrichedReport, prepared.settings)
                        DiagnosticsReportPersister.persistScanReport(
                            finalReport,
                            scanRecordStore,
                            artifactWriteStore,
                            serviceStateStore,
                            json,
                        )
                        rememberNetworkDnsPathPreference(runtimeState.fingerprint(prepared.sessionId), finalReport.resolverRecommendation)
                        rememberStrategyProbeRecommendation(finalReport, prepared.settings)
                        val now = System.currentTimeMillis()
                        artifactWriteStore.upsertSnapshot(
                            com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                sessionId = prepared.sessionId,
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
                                sessionId = prepared.sessionId,
                                contextKind = "post_scan",
                                payloadJson =
                                    json.encodeToString(
                                        DiagnosticContextModel.serializer(),
                                        diagnosticsContextProvider.captureContext(),
                                    ),
                                capturedAt = now,
                            ),
                        )
                        DiagnosticsReportPersister.persistNativeEvents(
                            sessionId = prepared.sessionId,
                            payload = bridge.pollPassiveEventsJson(),
                            artifactWriteStore = artifactWriteStore,
                            json = json,
                        )
                        if (prepared.exposeProgress) {
                            runtimeState.updateProgress(null)
                        }
                        break
                    }
                    delay(400L)
                }
            }
        }

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
            if (!DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(report, settings, status, mode)) {
                return report
            }
            resolverOverrideStore.setTemporaryOverride(
                DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation),
            )
            return report.copy(
                resolverRecommendation = recommendation.copy(appliedTemporarily = true),
            )
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
            if (!settings.networkStrategyMemoryEnabled || settings.enableCmdSettings) {
                return
            }
            val strategyProbe = report.strategyProbeReport ?: return
            val fingerprint = networkFingerprintProvider.capture() ?: return
            val policy =
                DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                    strategyProbe = strategyProbe,
                    settings = settings,
                    fingerprint = fingerprint,
                    hostAutolearnStorePath =
                        settings
                            .takeIf { it.hostAutolearnEnabled }
                            ?.let { resolveHostAutolearnStorePath(context) },
                    json = json,
                ) ?: return
            rememberedNetworkPolicyStore.rememberValidatedPolicy(
                policy = policy,
                source = RememberedNetworkPolicySourceStrategyProbe,
                validatedAt = report.finishedAt,
            )
        }
    }
