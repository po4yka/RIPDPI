@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

internal class HomeCompositeFinalizationRuntime(
    val progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    val completedRuns: ConcurrentHashMap<String, DiagnosticsHomeCompositeOutcome>,
    val scope: CoroutineScope,
)

internal data class HomeCompositeFinalizationRequest(
    val runId: String,
    val auditOutcome: DiagnosticsHomeAuditOutcome?,
    val coverageNote: String?,
    val dnsIssuesDetected: Boolean,
    val networkChanged: Boolean,
    val detectionResult: HomeDetectionStageOutcome?,
    val previousOutcome: DiagnosticsHomeCompositeOutcome?,
    val packetCaptureDisposition: DiagnosticsHomePacketCaptureDisposition,
)

internal class HomeCompositeOutcomeFinalizer(
    private val detectorCatalogSource: HomeDetectorCatalogSource,
    private val analysisAugmentationSource: HomeAnalysisAugmentationSource,
    private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
    private val persistencePorts: HomeCompositePersistencePorts,
    private val serviceStateStore: ServiceStateStore,
    private val json: Json,
    private val runtime: HomeCompositeFinalizationRuntime,
) {
    suspend fun finalize(request: HomeCompositeFinalizationRequest) {
        val baseOutcome = buildBaseOutcome(request)
        val augmentedOutcome = augmentOutcome(request, baseOutcome)
        val reproAction = augmentedOutcome.internetLossReproAction()
        val (synthHeadline, synthSteps) = synthesizeActionableSummary(augmentedOutcome)
        publish(
            augmentedOutcome.copy(
                actionableHeadline = synthHeadline,
                actionableNextSteps = synthSteps,
                internetLossReproAction = reproAction,
                packetCaptureDisposition = request.packetCaptureDisposition,
            ),
        )
    }

    private suspend fun buildBaseOutcome(request: HomeCompositeFinalizationRequest): DiagnosticsHomeCompositeOutcome =
        withContext(Dispatchers.Default) {
            buildHomeCompositeOutcome(
                request.runId,
                request.auditOutcome,
                request.coverageNote,
                request.dnsIssuesDetected,
                request.networkChanged,
                runtime.progressState,
            )
        }

    private suspend fun augmentOutcome(
        request: HomeCompositeFinalizationRequest,
        baseOutcome: DiagnosticsHomeCompositeOutcome,
    ): DiagnosticsHomeCompositeOutcome {
        val catalogSnapshot =
            withContext(Dispatchers.Default) {
                runCatching { detectorCatalogSource.snapshot() }.getOrDefault(HomeDetectorCatalogSnapshot())
            }
        val effectivenessLedger =
            baseOutcome.fingerprintHash
                ?.let { fingerprint ->
                    runCatching {
                        loadStrategyEffectiveness(
                            networkEdgePreferenceStore = networkEdgePreferenceStore,
                            fingerprintHash = fingerprint,
                        )
                    }.getOrDefault(emptyList())
                }.orEmpty()
        val detectionResult = request.detectionResult
        return baseOutcome.copy(
            detectionVerdict = detectionResult?.verdict,
            detectionFindings = detectionResult?.findings.orEmpty(),
            detectionRuleApplied = detectionResult?.ruleApplied,
            detectionEvidenceScopes = detectionResult?.evidenceScopes.orEmpty(),
            detectionSignalCount = detectionResult?.detectedSignalCount,
            detectionLocalFindings = detectionResult?.localFindings.orEmpty(),
            detectionNetworkFindings = detectionResult?.networkFindings.orEmpty(),
            detectionDecisionSignals = detectionResult?.decisionSignals.orEmpty(),
            installedVpnDetectorCount = catalogSnapshot.installedVpnDetectorCount.takeIf { it >= 0 },
            installedVpnDetectorTopApps = catalogSnapshot.topDetectorPackages,
            networkCharacter = runCatching { analysisAugmentationSource.networkCharacter() }.getOrNull(),
            strategyEffectiveness = effectivenessLedger,
            routingSanity = runCatching { analysisAugmentationSource.routingSanity() }.getOrNull(),
            regressionDelta = computeRegressionDelta(baseOutcome, request.previousOutcome),
            bufferbloat = runCatching { analysisAugmentationSource.bufferbloat() }.getOrNull(),
            dnsCharacterization = runCatching { analysisAugmentationSource.dnsCharacterization() }.getOrNull(),
            connectivityAssessment =
                buildConnectivityAssessment(
                    runId = request.runId,
                    progressState = runtime.progressState,
                    scanRecordStore = persistencePorts.scanRecordStore,
                    json = json,
                    serviceStateStore = serviceStateStore,
                    comparisonScanCoordinator = persistencePorts.comparisonScanCoordinator,
                ),
        )
    }

    private suspend fun publish(outcome: DiagnosticsHomeCompositeOutcome) {
        val persistedOutcome = persistencePorts.homeRunPersistence.persistCompletedRun(outcome)
        runtime.completedRuns[persistedOutcome.runId] = persistedOutcome
        cacheOutcome(persistedOutcome)
        runtime.progressState.update { current ->
            current.updatedRun(persistedOutcome.runId) { progress ->
                progress.copy(
                    fingerprintHash = persistedOutcome.fingerprintHash,
                    status = DiagnosticsHomeCompositeRunStatus.COMPLETED,
                    activeStageIndex = null,
                    activeSessionId = null,
                    stages = persistedOutcome.stageSummaries,
                    outcome = persistedOutcome,
                )
            }
        }
    }

    private fun cacheOutcome(outcome: DiagnosticsHomeCompositeOutcome) {
        if (outcome.fingerprintHash == null || outcome.completedStageCount <= 0) return
        runtime.scope.launch {
            runCatching {
                persistencePorts.probeResultCache.store(
                    CachedProbeOutcome(
                        fingerprintHash = outcome.fingerprintHash,
                        headline = outcome.headline,
                        summary = outcome.summary,
                        appliedSettings = outcome.appliedSettings,
                        completedStageCount = outcome.completedStageCount,
                        failedStageCount = outcome.failedStageCount,
                        cachedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}

private fun DiagnosticsHomeCompositeOutcome.internetLossReproAction(): HomeReproAction? =
    connectivityAssessment
        ?.takeIf { it.assessmentCode == ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE }
        ?.let {
            HomeReproAction(
                actionId = "internet_loss_repro",
                label = "Run internet-loss repro",
                summary = "Retry a paired raw-path and in-path comparison on complaint-specific targets.",
            )
        }
