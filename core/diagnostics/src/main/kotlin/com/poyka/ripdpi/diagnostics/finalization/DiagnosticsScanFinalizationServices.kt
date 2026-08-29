@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementContextKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
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
        private val rawPathSettlementBarrier: RawPathSettlementBarrier,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun finalize(
            prepared: PreparedDiagnosticsScan,
            reportJson: String,
            ownedInPathRouteAtCompletion: Boolean = false,
        ): ScanFinalizationResult =
            withContext(NonCancellable) {
                val rawReport = json.decodeEngineScanReportWire(reportJson)
                requireReportMatchesPreparedScan(prepared, rawReport)
                val finalizedWire =
                    DiagnosticsDiagnosisAuthority
                        .finalizeReport(rawReport.withLocalNetworkDeferrals(prepared))
                        .withOwnedInPathRouteAuthority(prepared, ownedInPathRouteAtCompletion)
                val enrichedReport =
                    DiagnosticsScanWorkflow.enrichScanReport(
                        report = finalizedWire.toScanReport(),
                        settings = prepared.settings,
                        preferredDnsPath = prepared.preferredDnsPath,
                    )
                val (finalReport, resolverOverride) =
                    planTemporaryResolverOverride(
                        report = enrichedReport,
                        settings = prepared.settings,
                        pathMode = prepared.pathMode,
                    )
                val winningCombination =
                    resolveWinningCombination(
                        prepared = prepared,
                        report = finalReport,
                    )
                val derived =
                    com.poyka.ripdpi.diagnostics.domain
                        .DerivedScanReport(finalReport.toEngineScanReportWire())
                DiagnosticsReportPersister.persistScanReport(
                    report = derived.report,
                    scanRecordStore = scanRecordStore,
                    artifactWriteStore = artifactWriteStore,
                    serviceStateStore = serviceStateStore,
                    json = json,
                    deferTerminal = prepared.pathMode == ScanPathMode.RAW_PATH,
                )
                resolverOverride?.let { resolverOverrideStore.setTemporaryOverride(it) }
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
                        resolverOverrideApplied = resolverOverride != null,
                    )
                ScanFinalizationResult(
                    derived = derived,
                    shouldReprobeWithCorrectedDns = shouldReprobe,
                    correctedDnsPath = correctedDnsPath,
                )
            }

        private fun requireReportMatchesPreparedScan(
            prepared: PreparedDiagnosticsScan,
            report: com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire,
        ) {
            require(report.sessionId == prepared.sessionId) {
                "Diagnostics report session does not match the prepared scan"
            }
            require(report.profileId == prepared.initialSession.profileId) {
                "Diagnostics report profile does not match the prepared scan"
            }
            require(report.pathMode == prepared.pathMode) {
                "Diagnostics report path does not match the prepared scan"
            }
        }

        internal suspend fun persistRawPathSettlement(
            prepared: PreparedDiagnosticsScan,
            result: RawPathExecutionResult,
            finalizationResult: ScanFinalizationResult?,
        ) {
            check(prepared.pathMode == ScanPathMode.RAW_PATH) {
                "Runtime settlement belongs only to raw-path diagnostics: ${prepared.sessionId}"
            }
            val context =
                DiagnosticContextEntity(
                    id = "raw-path-settlement:${prepared.sessionId}",
                    sessionId = prepared.sessionId,
                    contextKind = RawPathSettlementContextKind,
                    payloadJson = json.encodeToString(RawPathExecutionResult.serializer(), result),
                    capturedAt = System.currentTimeMillis(),
                )
            val terminalSession =
                DiagnosticsReportPersister.buildRawPathTerminalSession(
                    sessionId = prepared.sessionId,
                    report = finalizationResult?.derived?.report,
                    result = result,
                    scanRecordStore = scanRecordStore,
                    finishedAt = context.capturedAt,
                )
            rawPathSettlementBarrier.persist(context, terminalSession)
        }

        internal suspend fun persistRawPathRecoveryReport(
            prepared: PreparedDiagnosticsScan,
            reportJson: String,
        ) {
            check(prepared.pathMode == ScanPathMode.RAW_PATH) {
                "Recovery report staging belongs only to raw-path diagnostics: ${prepared.sessionId}"
            }
            val report = json.decodeEngineScanReportWire(reportJson).withLocalNetworkDeferrals(prepared)
            requireReportMatchesPreparedScan(prepared, report)
            check(
                report.reportDisposition in
                    setOf(
                        com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition.CHECKPOINT,
                        com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition.TERMINAL,
                    ),
            ) {
                "Raw-path recovery report requires a known disposition"
            }
            DiagnosticsReportPersister.persistScanReport(
                report = report,
                scanRecordStore = scanRecordStore,
                artifactWriteStore = artifactWriteStore,
                serviceStateStore = serviceStateStore,
                json = json,
                deferTerminal = true,
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
            val existingRecords =
                serverCapabilityStore.directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
            buildPersistableDirectPathObservations(
                report = report,
                existingRecords = existingRecords,
            ).forEach { (authority, observation) ->
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

        private fun planTemporaryResolverOverride(
            report: ScanReport,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
        ): Pair<ScanReport, TemporaryResolverOverride?> {
            val recommendation = report.resolverRecommendation ?: return report to null
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
                val override = DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation)
                report.copy(
                    resolverRecommendation = recommendation.copy(appliedTemporarily = true),
                ) to override
            } else {
                report to null
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

private fun com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire.withOwnedInPathRouteAuthority(
    prepared: PreparedDiagnosticsScan,
    ownedInPathRouteAtCompletion: Boolean,
): com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire {
    val ownsRoute =
        prepared.pathMode == ScanPathMode.IN_PATH && prepared.inPathRouteLease != null && ownedInPathRouteAtCompletion
    return copy(
        strategyProbeReport =
            strategyProbeReport?.let { strategyProbe ->
                val observation = strategyProbe.activePathObservation
                strategyProbe.copy(
                    activePathObservation =
                        observation?.copy(
                            activePathAuthority =
                                if (
                                    ownsRoute &&
                                    observation.role == StrategyProbeObservationRole.ACTIVE_SERVICE_IN_PATH &&
                                    observation.hasOwnedRouteExecutionEvidence()
                                ) {
                                    StrategyActivePathAuthority.OWNED_ROUTE_LEASE_AT_SCAN
                                } else {
                                    StrategyActivePathAuthority.UNVERIFIED
                                },
                        ),
                )
            },
    )
}

private fun StrategyActivePathObservation.hasOwnedRouteExecutionEvidence(): Boolean = hasCoherentResponseCounts()
