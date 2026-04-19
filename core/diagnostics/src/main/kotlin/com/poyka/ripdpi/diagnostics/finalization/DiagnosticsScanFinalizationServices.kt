@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal data class ScanFinalizationResult(
    val derived: com.poyka.ripdpi.diagnostics.domain.DerivedScanReport,
    val shouldReprobeWithCorrectedDns: Boolean,
    val correctedDnsPath: EncryptedDnsPathCandidate?,
)

@Singleton
class ScanFinalizationService
    @Inject
    constructor(
        @param:ApplicationContext
        private val context: Context,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val networkMetadataProvider: NetworkMetadataProvider,
        @Suppress("UnusedPrivateProperty") networkFingerprintProvider: NetworkFingerprintProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val serviceStateStore: ServiceStateStore,
        private val resolverOverrideStore: ResolverOverrideStore,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val serverCapabilityStore: ServerCapabilityStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun finalize(
            prepared: PreparedDiagnosticsScan,
            reportJson: String,
        ): ScanFinalizationResult {
            val rawReport = json.decodeEngineScanReportWire(reportJson)
            val finalizedWire = DiagnosticsDiagnosisAuthority.finalizeReport(rawReport)
            val enrichedReport =
                DiagnosticsScanWorkflow.enrichScanReport(
                    report = finalizedWire.toScanReport(),
                    settings = prepared.settings,
                    preferredDnsPath = prepared.preferredDnsPath,
                )
            val (finalReport, overrideApplied) =
                maybeApplyTemporaryResolverOverride(
                    report = enrichedReport,
                    settings = prepared.settings,
                    pathMode = prepared.pathMode,
                )
            val winningCombination =
                resolveWinningCombination(
                    prepared = prepared,
                    report = finalReport,
                )
            prepared.networkFingerprint?.let { fingerprint ->
                rememberEdgeProbeResults(
                    fingerprint = fingerprint,
                    report = finalReport,
                )
                rememberCapabilityEvidence(
                    fingerprint = fingerprint,
                    report = finalReport,
                )
            }
            val derived =
                com.poyka.ripdpi.diagnostics.domain
                    .DerivedScanReport(finalReport.toEngineScanReportWire())
            DiagnosticsReportPersister.persistScanReport(
                report = derived.report,
                scanRecordStore = scanRecordStore,
                artifactWriteStore = artifactWriteStore,
                serviceStateStore = serviceStateStore,
                json = json,
            )
            if (winningCombination?.id != "remembered") {
                rememberNetworkDnsPathPreference(prepared.networkFingerprint, finalReport.resolverRecommendation)
                rememberStrategyProbeRecommendation(
                    prepared = prepared,
                    report = finalReport,
                )
            }
            persistPostScanArtifacts(prepared.sessionId)
            val correctedDnsPath =
                with(ResolverRecommendationEngine) {
                    finalReport.resolverRecommendation?.toEncryptedDnsPathCandidate()
                }
            val shouldReprobe =
                DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                    report = finalReport,
                    pathMode = prepared.pathMode,
                    resolverOverrideApplied = overrideApplied,
                )
            return ScanFinalizationResult(
                derived = derived,
                shouldReprobeWithCorrectedDns = shouldReprobe,
                correctedDnsPath = correctedDnsPath,
            )
        }

        private suspend fun resolveWinningCombination(
            prepared: PreparedDiagnosticsScan,
            report: ScanReport,
        ): BypassCombinationCandidate? {
            val fingerprintHash = prepared.networkFingerprint?.scopeKey() ?: return null
            val mode = Mode.fromString(prepared.settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })
            val remembered =
                rememberedNetworkPolicyStore.findValidatedMatch(
                    fingerprintHash = fingerprintHash,
                    mode = mode,
                )
            val preferredEdges = networkEdgePreferenceStore.getPreferredEdgesForRuntime(fingerprintHash)
            val laneFamilies = prepared.settings.deriveStrategyLaneFamilies()
            val fresh =
                BypassCombinationScorer.freshCandidate(
                    report = report,
                    resolverPath =
                        with(ResolverRecommendationEngine) {
                            report.resolverRecommendation?.toEncryptedDnsPathCandidate()
                        },
                    currentDnsProtocol = prepared.settings.activeDnsSettings().encryptedDnsProtocol,
                    currentTcpFamily = laneFamilies.tcpStrategyFamily,
                    currentQuicFamily = laneFamilies.quicStrategyFamily,
                    preferredEdges = preferredEdges,
                )
            return BypassCombinationScorer.chooseBest(
                buildList {
                    add(fresh)
                    if (remembered != null) {
                        add(
                            BypassCombinationScorer.rememberedCandidate(
                                resolverPath = prepared.preferredDnsPath,
                                strategyRecommendation = null,
                            ),
                        )
                    }
                },
            )
        }

        private suspend fun rememberEdgeProbeResults(
            fingerprint: NetworkFingerprint,
            report: ScanReport,
        ) {
            report.results.forEach { result ->
                val connectedIp = result.detailValue("connectedIp")?.takeIf { it.isNotBlank() } ?: return@forEach
                val host = result.detailValue("targetHost") ?: result.inferEdgeHost() ?: return@forEach
                val transportKind = result.edgeTransportKind() ?: return@forEach
                networkEdgePreferenceStore.recordEdgeResult(
                    fingerprint = fingerprint,
                    host = host,
                    transportKind = transportKind,
                    ip = connectedIp,
                    success = result.edgeSuccess(),
                    echCapable = result.edgeEchCapable(),
                    cdnProvider = result.detailValue("cdnProvider"),
                )
            }
        }

        private suspend fun rememberCapabilityEvidence(
            fingerprint: NetworkFingerprint,
            report: ScanReport,
        ) {
            collectDirectPathCapabilityObservations(report).forEach { (authority, observation) ->
                serverCapabilityStore.rememberDirectPathObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    observation = observation,
                    source = "diagnostics",
                    recordedAt = report.finishedAt,
                )
            }
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
            pathMode: ScanPathMode,
        ): Pair<ScanReport, Boolean> {
            val recommendation = report.resolverRecommendation ?: return report to false
            val (status, mode) = serviceStateStore.status.value
            val shouldApply =
                DiagnosticsScanWorkflow.shouldApplyTemporaryResolverOverride(
                    report = report,
                    settings = settings,
                    serviceStatus = status,
                    serviceMode = mode,
                    pathMode = pathMode,
                )
            return if (shouldApply) {
                resolverOverrideStore.setTemporaryOverride(
                    DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation),
                )
                report.copy(
                    resolverRecommendation = recommendation.copy(appliedTemporarily = true),
                ) to true
            } else {
                report to false
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
            prepared: PreparedDiagnosticsScan,
            report: ScanReport,
        ) {
            val strategyProbe = report.strategyProbeReport
            val persistencePolicy = prepared.intent.executionPolicy.probePersistencePolicy
            val shouldRemember =
                prepared.settings.networkStrategyMemoryEnabled &&
                    !prepared.settings.enableCmdSettings &&
                    when (persistencePolicy) {
                        ProbePersistencePolicy.MANUAL_ONLY -> {
                            false
                        }

                        ProbePersistencePolicy.BACKGROUND_ONLY -> {
                            prepared.scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND
                        }

                        ProbePersistencePolicy.ALWAYS -> {
                            true
                        }
                    }
            val passesBackgroundEligibilityGate =
                if (
                    shouldRemember &&
                    prepared.scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND &&
                    strategyProbe != null
                ) {
                    DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(strategyProbe) ==
                        DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible
                } else {
                    true
                }
            val canBuildPolicy =
                shouldRemember &&
                    passesBackgroundEligibilityGate &&
                    strategyProbe != null &&
                    prepared.networkFingerprint != null
            val policy =
                if (canBuildPolicy) {
                    DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                        strategyProbe = strategyProbe,
                        settings = prepared.settings,
                        fingerprint = prepared.networkFingerprint,
                        hostAutolearnStorePath =
                            prepared.settings
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
                    source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                    validatedAt = report.finishedAt,
                )
            }
        }
    }
