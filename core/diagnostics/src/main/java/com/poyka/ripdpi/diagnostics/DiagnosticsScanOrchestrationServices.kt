package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceStrategyProbe
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal data class BridgeSessionHandle(
    val bridge: NetworkDiagnosticsBridge,
    val sessionId: String,
    val registerActiveBridge: Boolean,
)

@Singleton
class ScanAdmissionService
    @Inject
    constructor(
        private val appSettingsRepository: com.poyka.ripdpi.data.AppSettingsRepository,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val activeScanRegistry: ActiveScanRegistry,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            const val AutomaticProbeProfileId = "automatic-probing"
        }

        suspend fun admitManualStart(): Pair<com.poyka.ripdpi.proto.AppSettings, com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity> {
            check(!activeScanRegistry.hasActiveScan()) { "Diagnostics scan already active" }
            val settings = appSettingsRepository.snapshot()
            val profileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" }
            val profile =
                requireNotNull(profileCatalog.getProfile(profileId)) {
                    "Unknown diagnostics profile: $profileId"
                }
            return settings to profile
        }

        suspend fun admitAutomaticProbe(
            settings: com.poyka.ripdpi.proto.AppSettings,
        ): com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity? {
            if (activeScanRegistry.hasActiveScan()) {
                return null
            }
            val profile = profileCatalog.getProfile(AutomaticProbeProfileId) ?: return null
            val request = json.decodeProfileSpecWireCompat(profile.requestJson)
            return profile.takeIf { request.executionPolicyOrCompat().allowBackground }
        }

        suspend fun assertProfileExists(profileId: String) {
            requireNotNull(profileCatalog.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        }
    }

@Singleton
class ActiveScanRegistry
    @Inject
    constructor(
        private val timelineSource: DefaultDiagnosticsTimelineSource,
    ) {
        private val bridgeMutex = Mutex()
        private var activeDiagnosticsBridge: NetworkDiagnosticsBridge? = null
        private val hiddenScanCount = AtomicInteger(0)
        private val scanSessionFingerprints = ConcurrentHashMap<String, NetworkFingerprint>()
        private val scanSessionPreferredDnsPaths = ConcurrentHashMap<String, EncryptedDnsPathCandidate>()

        @Volatile
        private var hasRegisteredActiveBridge = false

        internal fun rememberPreparedScan(prepared: PreparedDiagnosticsScan) {
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
class BridgeExecutionService
    @Inject
    constructor(
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val activeScanRegistry: ActiveScanRegistry,
    ) {
        internal suspend fun createHandle(
            sessionId: String,
            registerActiveBridge: Boolean,
        ): BridgeSessionHandle {
            val bridge = networkDiagnosticsBridgeFactory.create()
            activeScanRegistry.registerBridge(bridge, registerActiveBridge)
            return BridgeSessionHandle(
                bridge = bridge,
                sessionId = sessionId,
                registerActiveBridge = registerActiveBridge,
            )
        }

        internal suspend fun start(
            handle: BridgeSessionHandle,
            requestJson: String,
        ) {
            handle.bridge.startScan(
                requestJson = requestJson,
                sessionId = handle.sessionId,
            )
        }

        internal suspend fun destroy(handle: BridgeSessionHandle) {
            try {
                handle.bridge.destroy()
            } finally {
                activeScanRegistry.clearBridge(handle.bridge, handle.registerActiveBridge)
            }
        }
    }

@Singleton
class PassiveEventPersistenceService
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun persist(
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
    }

@Singleton
class BridgePollingService
    @Inject
    constructor(
        private val passiveEventPersistenceService: PassiveEventPersistenceService,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            private const val FinishedReportPollAttempts = 5
            private const val FinishedReportPollDelayMs = 100L
            const val PollScanResultTimeoutMs = 300_000L
            const val PollScanIntervalMs = 400L
        }

        internal suspend fun persistPassiveEvents(handle: BridgeSessionHandle) {
            passiveEventPersistenceService.persist(handle.sessionId, handle.bridge)
        }

        internal suspend fun pollProgress(handle: BridgeSessionHandle): ScanProgress? =
            handle.bridge
                .pollProgressJson()
                ?.let(json::decodeEngineProgressWireCompat)
                ?.toLegacyScanProgressCompat()

        internal suspend fun awaitFinishedReportJson(handle: BridgeSessionHandle): String? {
            repeat(FinishedReportPollAttempts) { attempt ->
                handle.bridge.takeReportJson()?.let { return it }
                if (attempt < FinishedReportPollAttempts - 1) {
                    delay(FinishedReportPollDelayMs)
                }
            }
            return null
        }

        internal suspend fun awaitCompletion(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            activeScanRegistry: ActiveScanRegistry,
            onFinishedReportJson: suspend (String) -> Unit,
        ) {
            withTimeout(PollScanResultTimeoutMs) {
                while (true) {
                    persistPassiveEvents(handle)
                    val progress = pollProgress(handle)
                    if (prepared.exposeProgress) {
                        activeScanRegistry.updateProgress(progress)
                    }
                    if (progress?.isFinished == true) {
                        val reportJson =
                            checkNotNull(awaitFinishedReportJson(handle)) {
                                "Diagnostics scan completed without a report"
                            }
                        onFinishedReportJson(reportJson)
                        break
                    }
                    delay(PollScanIntervalMs)
                }
            }
        }
    }

@Singleton
class ScanFinalizationService
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
        private val findingProjector: DiagnosticsFindingProjector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun finalize(
            prepared: PreparedDiagnosticsScan,
            reportJson: String,
        ): com.poyka.ripdpi.diagnostics.domain.DerivedScanReport {
            val rawReport = json.decodeEngineScanReportWireCompat(reportJson)
            val finalizedWire =
                rawReport.copy(
                    diagnoses =
                        if (rawReport.observations.isNotEmpty()) {
                            findingProjector.classify(rawReport.toLegacyScanReportCompat())
                        } else {
                            rawReport.diagnoses
                        },
                    classifierVersion =
                        if (rawReport.observations.isNotEmpty()) {
                            DiagnosticsFindingProjector.ClassifierVersion
                        } else {
                            rawReport.classifierVersion
                        },
                )
            val finalReport =
                maybeApplyTemporaryResolverOverride(
                    DiagnosticsScanWorkflow.enrichScanReport(
                        report = finalizedWire.toLegacyScanReportCompat(),
                        settings = prepared.settings,
                        preferredDnsPath = prepared.preferredDnsPath,
                    ),
                    prepared.settings,
                )
            val derived = com.poyka.ripdpi.diagnostics.domain.DerivedScanReport(finalReport.toEngineScanReportWire())
            DiagnosticsReportPersister.persistScanReport(
                report = derived.report,
                scanRecordStore = scanRecordStore,
                artifactWriteStore = artifactWriteStore,
                serviceStateStore = serviceStateStore,
                json = json,
            )
            rememberNetworkDnsPathPreference(prepared.networkFingerprint, finalReport.resolverRecommendation)
            rememberStrategyProbeRecommendation(finalReport, prepared.settings)
            persistPostScanArtifacts(prepared.sessionId)
            return derived
        }

        private suspend fun persistPostScanArtifacts(sessionId: String) {
            val now = System.currentTimeMillis()
            artifactWriteStore.upsertSnapshot(
                com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity(
                    id = UUID.randomUUID().toString(),
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
                    id = UUID.randomUUID().toString(),
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
