package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsNetworkScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

internal class HomeNetworkScopeFixture(
    scope: CoroutineScope,
    val epoch: FakeDiagnosticsNetworkEpochProvider = FakeDiagnosticsNetworkEpochProvider(),
    val fingerprint: MutableNetworkFingerprintProvider = MutableNetworkFingerprintProvider(),
    private val detection: HomeDetectionStageOutcome? = null,
) {
    val source = FakeHomeScopeAugmentationSource()
    private val originalScope = DiagnosticsNetworkScope(epoch, fingerprint)
    private val completed = ConcurrentHashMap<String, DiagnosticsHomeCompositeOutcome>()
    val hasCompletedOutcome: Boolean get() = completed.isNotEmpty()
    private val stores = FakeDiagnosticsHistoryStores()
    private val json = diagnosticsTestJson()
    private val finalizer =
        HomeCompositeOutcomeFinalizer(
            detectorCatalogSource =
                object : HomeDetectorCatalogSource {
                    override fun snapshot() = HomeDetectorCatalogSnapshot(installedVpnDetectorCount = 1)
                },
            analysisAugmentationSource = source,
            networkEdgePreferenceStore = NoopNetworkEdgePreferenceStore,
            persistencePorts =
                HomeCompositePersistencePorts(
                    scanRecordStore = stores,
                    comparisonScanCoordinator = ComparisonScanCoordinator(stores, json),
                    homeRunPersistence = HomeDiagnosticsRunPersistence(stores, TestDiagnosticsHistoryClock(), json),
                    probeResultCache = FakeHomeScopeProbeResultCache(),
                ),
            serviceStateStore = FakeServiceStateStore(),
            json = json,
            runtime =
                HomeCompositeFinalizationRuntime(
                    progressState =
                        MutableStateFlow(
                            mapOf(
                                "run" to
                                    DiagnosticsHomeCompositeProgress(
                                        runId = "run",
                                        stages =
                                            listOf(homeScopeStage(DiagnosticsHomeCompositeStageStatus.COMPLETED)) +
                                                listOfNotNull(detection?.let { homeScopeDetectionStage() }),
                                    ),
                            ),
                        ),
                    completedRuns = completed,
                    completionOrder = HomeCompositeCompletionOrder(),
                    scope = scope,
                ),
        )

    suspend fun finalize(
        networkChanged: Boolean = false,
        networkScope: DiagnosticsNetworkScope? = originalScope,
        previousOutcome: DiagnosticsHomeCompositeOutcome? = null,
    ): DiagnosticsHomeCompositeOutcome {
        finalizer.finalize(
            HomeCompositeFinalizationRequest(
                runId = "run",
                auditOutcome =
                    DiagnosticsHomeAuditOutcome(
                        sessionId = "audit",
                        fingerprintHash = fingerprint.capture()?.scopeKey(),
                        actionable = true,
                        headline = "Recommended bypass",
                        summary = "Bypass selected",
                    ),
                coverageNote = null,
                dnsIssuesDetected = false,
                networkChanged = networkChanged,
                detectionResult = detection,
                previousOutcome = previousOutcome,
                packetCaptureDisposition = DiagnosticsHomePacketCaptureDisposition.notRequested(),
                networkScope = networkScope,
            ),
        )
        return completed.getValue("run")
    }
}

internal class FakeHomeScopeAugmentationSource : HomeAnalysisAugmentationSource {
    var networkCalls = 0
    var afterDns: () -> Unit = {}

    override suspend fun networkCharacter(): HomeNetworkCharacterSummary {
        networkCalls++
        return HomeNetworkCharacterSummary(transport = "wifi")
    }

    override suspend fun routingSanity() = HomeRoutingSanitySummary(totalConfiguredApps = 2)

    override suspend fun bufferbloat(): HomeBufferbloatResult {
        networkCalls++
        return HomeBufferbloatResult(grade = HomeBufferbloatGrade.A)
    }

    override suspend fun dnsCharacterization(): HomeDnsCharacterization {
        networkCalls++
        afterDns()
        return HomeDnsCharacterization(resolverClass = HomeDnsResolverClass.SYSTEM_RESOLVER_OK)
    }
}

internal class FakeHomeScopeProbeResultCache : ProbeResultCache {
    override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun store(outcome: CachedProbeOutcome) = Unit

    override suspend fun evict(fingerprintHash: String) = Unit

    override suspend fun clear() = Unit
}

internal fun homeScopeStage(status: DiagnosticsHomeCompositeStageStatus) =
    DiagnosticsHomeCompositeStageSummary(
        stageKey = "automatic_audit",
        stageLabel = "Web",
        profileId = "automatic-audit",
        sessionId = "audit",
        pathMode = ScanPathMode.RAW_PATH,
        status = status,
        headline = "Web result",
        summary = "Web result",
    )

internal fun previousHomeScopeOutcome(fingerprintHash: String?) =
    DiagnosticsHomeCompositeOutcome(
        runId = "previous",
        fingerprintHash = fingerprintHash,
        actionable = false,
        headline = "Previous result",
        summary = "Previous result",
        stageSummaries = listOf(homeScopeStage(DiagnosticsHomeCompositeStageStatus.FAILED)),
    )

private fun homeScopeDetectionStage() =
    DiagnosticsHomeCompositeStageSummary(
        stageKey = "detection_signals",
        stageLabel = "Detection",
        profileId = DetectionStageProfileId,
        pathMode = ScanPathMode.RAW_PATH,
        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
        headline = "Detection completed",
        summary = "Detection completed",
    )

internal fun mixedHomeScopeDetectionOutcome(): HomeDetectionStageOutcome {
    val signals =
        listOf(
            HomeDetectionDecisionSignal(
                category = HomeDetectionSignalCategory.LOCAL_INVENTORY_REVIEW,
                semantics = HomeDetectionSignalSemantics.LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW,
                source = "INSTALLED_APP",
                confidence = "MEDIUM",
                scope = "LOCAL_INVENTORY",
            ),
            HomeDetectionDecisionSignal(
                category = HomeDetectionSignalCategory.NETWORK_OBSERVATION,
                semantics = HomeDetectionSignalSemantics.NETWORK_OBSERVATION_PRESENT,
                source = "GEO_IP",
                confidence = "MEDIUM",
                scope = "NETWORK_OBSERVATION",
            ),
        )
    return HomeDetectionStageOutcome(
        verdict = DiagnosticsHomeDetectionVerdict.DETECTED,
        detectedSignalCount = signals.size,
        findings = listOf("Local app", "Network observation"),
        evidenceScopes =
            signals.map {
                com.poyka.ripdpi.core.detection.DetectionScope
                    .valueOf(it.scope)
            },
        localFindings = listOf("Local app"),
        networkFindings = listOf("Network observation"),
        decisionSignals = signals,
    )
}

internal fun stableHomeNetworkScopeFactory() =
    DiagnosticsNetworkScopeFactory(FakeDiagnosticsNetworkEpochProvider(), MutableNetworkFingerprintProvider())
