@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
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
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan
import com.poyka.ripdpi.diagnostics.application.hasCurrentNetworkScope
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsScopeLoss
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementContextKind
import com.poyka.ripdpi.diagnostics.finalization.revokePersistedNetworkScope
import com.poyka.ripdpi.diagnostics.finalization.withoutNetworkScopeAuthority
import com.poyka.ripdpi.diagnostics.finalization.withoutScopeAuthority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal data class ScanFinalizationResult(
    val derived: com.poyka.ripdpi.diagnostics.domain.DerivedScanReport,
    val shouldReprobeWithCorrectedDns: Boolean,
    val correctedDnsPath: EncryptedDnsPathCandidate?,
)

internal class ScanEvidenceAuthority
    @Inject
    constructor(
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
    ) {
        fun isNetworkCurrent(prepared: PreparedDiagnosticsScan): Boolean =
            prepared.hasCurrentNetworkScope(networkFingerprintProvider)

        fun trackScopeLoss(
            prepared: PreparedDiagnosticsScan,
            ownedInPathRouteAtCompletion: Boolean,
        ): () -> DiagnosticsScopeLoss? {
            var lostScope: DiagnosticsScopeLoss? = null
            return {
                if (lostScope == null) {
                    lostScope =
                        when {
                            !isNetworkCurrent(prepared) -> DiagnosticsScopeLoss.NETWORK

                            prepared.inPathRouteLease?.let {
                                !ownedInPathRouteAtCompletion || runtimeCoordinator.validateInPathRouteLease(it) != null
                            } == true -> DiagnosticsScopeLoss.VPN_ROUTE

                            else -> null
                        }
                }
                lostScope
            }
        }
    }

@Singleton
class ScanFinalizationService
    @Inject
    internal constructor(
        @param:ApplicationContext
        private val context: Context,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val evidenceAuthority: ScanEvidenceAuthority,
        private val serviceStateStore: ServiceStateStore,
        private val resolverOverrideStore: ResolverOverrideStore,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val serverCapabilityStore: ServerCapabilityStore,
        private val rawPathSettlementBarrier: RawPathSettlementBarrier,
        private val postScanRecorder: DiagnosticsPostScanRecorder,
        private val targetOptimizer: DiagnosticsTargetOptimizer,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun finalize(
            prepared: PreparedDiagnosticsScan,
            reportJson: String,
            ownedInPathRouteAtCompletion: Boolean = false,
        ): ScanFinalizationResult =
            withContext(NonCancellable) {
                val scopeLoss = evidenceAuthority.trackScopeLoss(prepared, ownedInPathRouteAtCompletion)

                fun scope(report: ScanReport): ScanReport = scopeLoss()?.let(report::withoutScopeAuthority) ?: report

                val scopedReport = scope(prepareReport(prepared, reportJson, ownedInPathRouteAtCompletion))
                val (plannedReport, resolverOverride) =
                    planTemporaryResolverOverride(
                        report = scopedReport,
                        settings = prepared.settings,
                        pathMode = prepared.pathMode,
                        networkScopeKey = prepared.networkFingerprint?.scopeKey(),
                    )
                // One predicate: a fresh result outranks a remembered policy exactly when its policy is persisted.
                val freshPolicy = buildFreshRememberedPolicy(prepared, plannedReport)
                val winningCombination =
                    resolveWinningCombination(
                        prepared = prepared,
                        report = plannedReport,
                        freshPolicyPersistable = freshPolicy != null,
                    )
                var finalReport = scope(plannedReport)
                DiagnosticsReportPersister.persistScanReport(
                    report = finalReport.toEngineScanReportWire(),
                    scanRecordStore = scanRecordStore,
                    artifactWriteStore = artifactWriteStore,
                    serviceStateStore = serviceStateStore,
                    json = json,
                    deferTerminal = true,
                )
                postScanRecorder.persist(prepared)
                // Report and context persistence can suspend while the physical network changes.
                finalReport = scope(finalReport)
                if (scopeLoss() == null) {
                    persistNetworkEvidence(
                        prepared,
                        finalReport,
                        winningCombination,
                        freshPolicy,
                    ) { scopeLoss() == null }
                }
                if (prepared.pathMode == ScanPathMode.IN_PATH && scopeLoss() == null) {
                    completeInPathSession(prepared.sessionId, finalReport.finishedAt) { scopeLoss() == null }
                }
                scopeLoss()?.let { loss ->
                    finalReport = finalReport.withoutScopeAuthority(loss)
                    revokePersistedNetworkScope(
                        prepared.sessionId,
                        scanRecordStore,
                        json,
                        loss,
                        inPathFinishedAt = finalReport.finishedAt.takeIf { prepared.pathMode == ScanPathMode.IN_PATH },
                    )
                }
                val derived =
                    com.poyka.ripdpi.diagnostics.domain
                        .DerivedScanReport(finalReport.toEngineScanReportWire())
                val correctedDnsPath =
                    with(ResolverRecommendationEngine) {
                        finalReport.resolverRecommendationForAutomaticUse()?.toEncryptedDnsPathCandidate()
                    }
                val appliedOverride = resolverOverride?.takeIf { scopeLoss() == null && correctedDnsPath != null }
                if (appliedOverride != null) resolverOverrideStore.setTemporaryOverride(appliedOverride)
                ScanFinalizationResult(
                    derived = derived,
                    shouldReprobeWithCorrectedDns =
                        DiagnosticsScanWorkflow.shouldReprobeWithCorrectedDns(
                            report = finalReport,
                            pathMode = prepared.pathMode,
                            resolverOverrideApplied = appliedOverride != null && scopeLoss() == null,
                        ),
                    correctedDnsPath = correctedDnsPath,
                )
            }

        private fun prepareReport(
            prepared: PreparedDiagnosticsScan,
            reportJson: String,
            ownedInPathRouteAtCompletion: Boolean,
        ): ScanReport {
            val rawReport = json.decodeEngineScanReportWire(reportJson)
            requireReportMatchesPreparedScan(prepared, rawReport)
            val finalizedWire =
                DiagnosticsDiagnosisAuthority
                    .finalizeReport(rawReport.withLocalNetworkDeferrals(prepared))
                    .withOwnedInPathRouteAuthority(prepared, ownedInPathRouteAtCompletion)
            return DiagnosticsScanWorkflow.enrichScanReport(
                report = finalizedWire.toScanReport(),
                settings = prepared.settings,
                preferredDnsPath = prepared.preferredDnsPath,
            )
        }

        private suspend fun completeInPathSession(
            sessionId: String,
            finishedAt: Long,
            isScopeCurrent: () -> Boolean,
        ) {
            val staged = checkNotNull(scanRecordStore.getScanSession(sessionId))
            if (!staged.hasAuthoritativeManualConflictCancellation() && isScopeCurrent()) {
                check(staged.status == "running") { "In-path scan changed status during finalization" }
                scanRecordStore.upsertScanSession(staged.copy(status = "completed", finishedAt = finishedAt))
            }
        }

        private suspend fun persistNetworkEvidence(
            prepared: PreparedDiagnosticsScan,
            report: ScanReport,
            winningCombination: BypassCombinationCandidate?,
            freshPolicy: RememberedNetworkPolicyJson?,
            isScopeCurrent: () -> Boolean,
        ) {
            val fingerprint = prepared.networkFingerprint ?: return
            if (
                prepared.scanOrigin == DiagnosticsScanOrigin.USER_INITIATED &&
                isScopeCurrent()
            ) {
                targetOptimizer.record(fingerprint.scopeKey(), report, prepared.settings)
            }
            // An in-path scan measured the active tunnel or proxy, not the raw network's direct path.
            if (prepared.pathMode == ScanPathMode.RAW_PATH && isScopeCurrent()) {
                rememberEdgeProbeResults(fingerprint, report, isScopeCurrent)
                if (isScopeCurrent()) {
                    rememberCapabilityEvidence(fingerprint, report, isScopeCurrent)
                }
            }
            if (winningCombination?.id != "remembered" && isScopeCurrent()) {
                rememberNetworkDnsPathPreference(fingerprint, report.resolverRecommendationForAutomaticUse())
                if (freshPolicy != null && isScopeCurrent()) {
                    rememberedNetworkPolicyStore.rememberValidatedPolicy(
                        policy = freshPolicy,
                        source = prepared.rememberedPolicySource(),
                        validatedAt = report.finishedAt,
                    )
                }
            }
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
            val rawReport = json.decodeEngineScanReportWire(reportJson).withLocalNetworkDeferrals(prepared)
            val report =
                if (evidenceAuthority.isNetworkCurrent(prepared)) {
                    rawReport
                } else {
                    rawReport.withoutNetworkScopeAuthority()
                }
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
            if (!evidenceAuthority.isNetworkCurrent(prepared)) {
                revokePersistedNetworkScope(prepared.sessionId, scanRecordStore, json)
            }
        }

        private suspend fun resolveWinningCombination(
            prepared: PreparedDiagnosticsScan,
            report: ScanReport,
            freshPolicyPersistable: Boolean,
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
                            report.resolverRecommendationForAutomaticUse()?.toEncryptedDnsPathCandidate()
                        },
                    currentDnsProtocol = prepared.settings.activeDnsSettings().encryptedDnsProtocol,
                    currentTcpFamily = laneFamilies.tcpStrategyFamily,
                    currentQuicFamily = laneFamilies.quicStrategyFamily,
                    preferredEdges = preferredEdges,
                    persistable = freshPolicyPersistable,
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
            isScopeCurrent: () -> Boolean,
        ) {
            report.results.forEach { result ->
                val connectedIp = result.detailValue("connectedIp")?.takeIf { it.isNotBlank() } ?: return@forEach
                val host = result.detailValue("targetHost") ?: result.inferEdgeHost() ?: return@forEach
                val transportKind = result.edgeTransportKind() ?: return@forEach
                if (!isScopeCurrent()) return
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
            isScopeCurrent: () -> Boolean,
        ) {
            val existingRecords =
                serverCapabilityStore.directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
            buildPersistableDirectPathObservations(
                report = report,
                existingRecords = existingRecords,
            ).forEach { (authority, observation) ->
                if (!isScopeCurrent()) return
                serverCapabilityStore.rememberDirectPathObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    observation = observation,
                    source = "diagnostics",
                    recordedAt = report.finishedAt,
                )
            }
        }

        private fun planTemporaryResolverOverride(
            report: ScanReport,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            networkScopeKey: String?,
        ): Pair<ScanReport, TemporaryResolverOverride?> {
            val recommendation = report.resolverRecommendationForAutomaticUse()
            if (recommendation == null || networkScopeKey == null) return report to null
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
                val override = DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation, networkScopeKey)
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

        /** The strategy policy this scan will remember, or null when it must not be persisted. */
        private fun buildFreshRememberedPolicy(
            prepared: PreparedDiagnosticsScan,
            report: ScanReport,
        ): RememberedNetworkPolicyJson? {
            val strategyProbe = report.strategyProbeReport?.takeIf { report.isCompleteScan() }
            val fingerprint = prepared.networkFingerprint
            if (strategyProbe == null || fingerprint == null || !prepared.remembersStrategyPolicy()) return null
            // The builder applies the auto-persist eligibility bar, quick_v1 suite and valid recommendation checks.
            return DiagnosticsScanWorkflow.buildRememberedNetworkPolicy(
                strategyProbe = strategyProbe,
                settings = prepared.settings,
                fingerprint = fingerprint,
                hostAutolearnStorePath =
                    prepared.settings
                        .takeIf { it.hostAutolearnEnabled }
                        ?.let { resolveHostAutolearnStorePath(context) },
                json = json,
            )
        }
    }

private fun PreparedDiagnosticsScan.remembersStrategyPolicy(): Boolean =
    settings.networkStrategyMemoryEnabled &&
        !settings.enableCmdSettings &&
        when (intent.executionPolicy.probePersistencePolicy) {
            ProbePersistencePolicy.MANUAL_ONLY -> {
                false
            }

            ProbePersistencePolicy.BACKGROUND_ONLY -> {
                scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND
            }

            ProbePersistencePolicy.ALWAYS -> {
                true
            }
        }

private fun ScanReport.isCompleteScan(): Boolean =
    completionKind == ScanCompletionKind.NORMAL && terminationReason == null

private fun PreparedDiagnosticsScan.rememberedPolicySource(): RememberedNetworkPolicySource =
    when {
        scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND -> {
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND
        }

        initialSession.profileId == AutomaticProbeProfileId -> {
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_MANUAL
        }

        initialSession.profileId == AutomaticAuditProfileId -> {
            RememberedNetworkPolicySource.AUTOMATIC_AUDIT_MANUAL
        }

        else -> {
            RememberedNetworkPolicySource.STRATEGY_PROBE_MANUAL
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
