@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveClassCounts
import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveSource
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveArchiveWideCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeEventCompleteness
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeEventSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePrimarySessionCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRelayAttemptKey
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRootSourceCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRunType
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveScopedCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSessionSelectionStatus
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSnapshotSource
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceData
import com.poyka.ripdpi.diagnostics.export.isPostRuntimeRestoreContext
import com.poyka.ripdpi.diagnostics.export.selectArchiveNativeEvents
import com.poyka.ripdpi.diagnostics.export.toArchiveNativeEventRetention
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveSessionSelector
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private val compositeStageSelector = DiagnosticsArchiveCompositeStageSelector(json)

        internal fun selectPrimarySession(
            requestedSessionId: String?,
            requestedSession: ScanSessionEntity?,
            sessions: List<ScanSessionEntity>,
        ): ScanSessionEntity? =
            when {
                requestedSessionId != null -> {
                    requireNotNull(requestedSession) {
                        "Requested diagnostics session '$requestedSessionId' is no longer available"
                    }
                }

                else -> {
                    sessions.firstOrNull { it.reportJson != null } ?: sessions.firstOrNull()
                }
            }

        internal suspend fun buildSelection(
            request: DiagnosticsArchiveRequest,
            primarySession: ScanSessionEntity?,
            primaryResults: List<ProbeResultEntity>,
            sourceData: DiagnosticsArchiveSourceData,
            compositeOutcome: DiagnosticsHomeCompositeOutcome? = null,
            compositeSessions: List<ScanSessionEntity> = emptyList(),
            loadProbeResults: suspend (String) -> List<ProbeResultEntity>,
            loadNativeEventSource: suspend (String) -> DiagnosticsNativeEventArchiveSource,
            loadRelayAttemptTraceEvents: suspend (
                DiagnosticsArchiveRelayAttemptKey,
            ) -> List<NativeSessionEventEntity> = {
                emptyList()
            },
            loadStageTelemetry: suspend (ScanSessionEntity, Set<String>) -> List<TelemetrySampleEntity> = { _, _ ->
                emptyList()
            },
        ): DiagnosticsArchiveSelection {
            val inputs =
                prepareSelectionInputs(
                    request = request,
                    primarySession = primarySession,
                    primaryResults = primaryResults,
                    sourceData = sourceData,
                    compositeOutcome = compositeOutcome,
                    compositeSessions = compositeSessions,
                    loadProbeResults = loadProbeResults,
                    loadNativeEventSource = loadNativeEventSource,
                    loadRelayAttemptTraceEvents = loadRelayAttemptTraceEvents,
                    loadStageTelemetry = loadStageTelemetry,
                )
            val payload = buildArchivePayload(primarySession, primaryResults, inputs.primary, sourceData)
            return DiagnosticsArchiveSelection(
                runType =
                    if (inputs.isComposite) {
                        DiagnosticsArchiveRunType.HOME_COMPOSITE
                    } else {
                        DiagnosticsArchiveRunType.SINGLE_SESSION
                    },
                request = request,
                payload = payload,
                primarySession = primarySession,
                primaryReport = inputs.primary.report,
                primaryResults = primaryResults,
                primarySnapshots = inputs.primary.snapshots,
                primaryContexts = inputs.primary.contexts,
                primaryEvents = inputs.primary.events,
                latestPassiveSnapshot = inputs.primary.latestPassiveSnapshot,
                latestPassiveContext = inputs.primary.latestPassiveContext,
                globalEvents = inputs.primary.globalEvents,
                relayTraceEvents = inputs.relayTraceHydration.events,
                relayTraceBudgetOmittedAttemptKeys = inputs.relayTraceHydration.budgetOmittedAttemptKeys,
                relayTraceSourceWindowOmittedAttemptKeys = inputs.relayTraceHydration.sourceWindowOmittedAttemptKeys,
                relayTraceHydrationApplied = true,
                rootSourceCounts = inputs.rootSourceCounts,
                selectedApproachSummary = inputs.primary.selectedApproachSummary,
                latestSnapshotModel = inputs.primary.latestSnapshotModel,
                latestSnapshotSource = inputs.primary.latestSnapshotSource,
                latestContextModel = inputs.primary.latestContextModel,
                sessionContextModel = inputs.primary.sessionContextModel,
                buildProvenance = sourceData.buildProvenance,
                installedArtifact = sourceData.installedArtifact,
                sessionSelectionStatus = resolveSessionSelectionStatus(request, inputs.isComposite, primarySession),
                homeRunId = request.homeRunId,
                homeCompositeOutcome = compositeOutcome,
                compositeStages = inputs.compositeStages,
                effectiveStrategySignature = inputs.primary.effectiveStrategySignature,
                appSettings = sourceData.appSettings,
                replayResults = sourceData.replayResults,
                sourceCounts =
                    buildSourceCounts(
                        sourceData,
                        primaryResults,
                        primarySession,
                        inputs.primaryEventSource.sourceCounts.total,
                        inputs.rootSourceCounts,
                        inputs.compositeStages,
                    ),
                nativeEventCompleteness = buildNativeEventCompleteness(inputs.primary, inputs.compositeStages),
                collectionWarnings = sourceData.collectionWarnings,
                includedFiles = inputs.includedFiles,
                logcatSnapshot = sourceData.logcatSnapshot,
                fileLogSnapshot = sourceData.fileLogSnapshot,
                startupJournalSnapshot = sourceData.startupJournalSnapshot,
                runtimeSnapshots = selectRuntimeSnapshots(sourceData),
            )
        }

        private suspend fun prepareSelectionInputs(
            request: DiagnosticsArchiveRequest,
            primarySession: ScanSessionEntity?,
            primaryResults: List<ProbeResultEntity>,
            sourceData: DiagnosticsArchiveSourceData,
            compositeOutcome: DiagnosticsHomeCompositeOutcome?,
            compositeSessions: List<ScanSessionEntity>,
            loadProbeResults: suspend (String) -> List<ProbeResultEntity>,
            loadNativeEventSource: suspend (String) -> DiagnosticsNativeEventArchiveSource,
            loadRelayAttemptTraceEvents: suspend (
                DiagnosticsArchiveRelayAttemptKey,
            ) -> List<NativeSessionEventEntity>,
            loadStageTelemetry: suspend (ScanSessionEntity, Set<String>) -> List<TelemetrySampleEntity>,
        ): SelectionInputs {
            val eventCache = mutableMapOf<String, DiagnosticsNativeEventArchiveSource>()

            suspend fun loadSessionEventSource(sessionId: String): DiagnosticsNativeEventArchiveSource =
                eventCache[sessionId] ?: loadNativeEventSource(sessionId).also { eventCache[sessionId] = it }
            val primaryEventSource =
                primarySession?.id?.let { sessionId -> loadSessionEventSource(sessionId) }
                    ?: emptyNativeEventArchiveSource()
            val primary = buildPrimarySessionData(primarySession, primaryResults, primaryEventSource, sourceData)
            val isComposite = compositeOutcome != null && request.homeRunId != null
            val compositeStages =
                compositeStageSelector.build(
                    isComposite,
                    compositeOutcome,
                    compositeSessions,
                    sourceData,
                    loadProbeResults,
                    ::loadSessionEventSource,
                    loadStageTelemetry,
                )
            return SelectionInputs(
                primaryEventSource = primaryEventSource,
                primary = primary,
                relayTraceHydration =
                    hydrateArchiveRelayAttemptTraces(
                        primaryEvents = primaryEventSource.events,
                        primarySourceTruncated = primaryEventSource.sourceCounts.total > primaryEventSource.events.size,
                        globalEvents = sourceData.events,
                        globalSourceTruncated = sourceData.globalEventSourceCounts.total > sourceData.events.size,
                        loadRelayAttemptTraceEvents = loadRelayAttemptTraceEvents,
                    ),
                rootSourceCounts = buildRootSourceCounts(sourceData, primarySession),
                isComposite = isComposite,
                compositeStages = compositeStages,
                includedFiles = buildIncludedFiles(isComposite, compositeStages, sourceData),
            )
        }

        private fun buildNativeEventCompleteness(
            primary: PrimarySessionData,
            compositeStages: List<DiagnosticsArchiveCompositeStageSelection>,
        ): DiagnosticsArchiveNativeEventCompleteness =
            DiagnosticsArchiveNativeEventCompleteness(
                global = primary.globalEventSelection.toArchiveNativeEventRetention(),
                primarySession = primary.primaryEventSelection.toArchiveNativeEventRetention(),
                compositeStages =
                    compositeStages.associate { stage ->
                        stage.stageSummary.stageKey to stage.nativeEventRetention
                    },
            )

        private fun buildSourceCounts(
            sourceData: DiagnosticsArchiveSourceData,
            primaryResults: List<ProbeResultEntity>,
            primarySession: ScanSessionEntity?,
            primaryEventCount: Int,
            rootSourceCounts: DiagnosticsArchiveRootSourceCounts,
            compositeStages: List<DiagnosticsArchiveCompositeStageSelection>,
        ): DiagnosticsArchiveScopedCounts {
            val sessionEventCounts =
                buildMap {
                    primarySession?.let { session -> put(session.id, primaryEventCount) }
                    compositeStages.forEach { stage ->
                        stage.session?.let { session -> put(session.id, stage.sourceEventCount) }
                    }
                }
            return DiagnosticsArchiveScopedCounts(
                archiveWide =
                    DiagnosticsArchiveArchiveWideCounts(
                        telemetrySamples =
                            (
                                sourceData.telemetry.map { it.id } + compositeStages.flatMap { it.sourceTelemetryIds }
                            ).toSet()
                                .size,
                        nativeEvents = sourceData.globalEventSourceCounts.total + sessionEventCounts.values.sum(),
                        snapshots =
                            (sourceData.snapshots + compositeStages.flatMap { it.snapshots })
                                .distinctBy { it.id }
                                .size,
                        contexts =
                            (sourceData.contexts + compositeStages.flatMap { it.contexts })
                                .distinctBy { it.id }
                                .size,
                    ),
                primarySession =
                    DiagnosticsArchivePrimarySessionCounts(
                        results = primaryResults.size,
                        snapshots = rootSourceCounts.primarySnapshots,
                        contexts = rootSourceCounts.primaryContexts,
                        events = primaryEventCount,
                    ),
            )
        }

        private fun buildRootSourceCounts(
            sourceData: DiagnosticsArchiveSourceData,
            primarySession: ScanSessionEntity?,
        ): DiagnosticsArchiveRootSourceCounts {
            val sessionId = primarySession?.id
            return DiagnosticsArchiveRootSourceCounts(
                telemetrySamples = sourceData.telemetry.size,
                primarySnapshots = sourceData.snapshots.count { it.sessionId == sessionId && sessionId != null },
                primaryContexts = sourceData.contexts.count { it.sessionId == sessionId && sessionId != null },
                globalEvents = sourceData.globalEventSourceCounts.total,
            )
        }

        private fun buildArchivePayload(
            primarySession: ScanSessionEntity?,
            primaryResults: List<ProbeResultEntity>,
            primary: PrimarySessionData,
            sourceData: DiagnosticsArchiveSourceData,
        ) = DiagnosticsArchivePayload(
            schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
            scope = DiagnosticsArchiveFormat.scope,
            privacyMode = DiagnosticsArchiveFormat.privacyMode,
            session = primarySession,
            primaryReport = primary.report,
            results = primaryResults,
            sessionSnapshots = primary.snapshots,
            sessionContexts = primary.contexts,
            sessionEvents = primary.events,
            latestPassiveSnapshot = primary.latestPassiveSnapshot,
            latestPassiveContext = primary.latestPassiveContext,
            telemetry = sourceData.telemetry.take(DiagnosticsArchiveFormat.telemetryLimit),
            globalEvents = primary.globalEvents,
            approachSummaries = sourceData.approachSummaries,
        )

        private fun buildPrimarySessionData(
            primarySession: ScanSessionEntity?,
            @Suppress("UnusedParameter") primaryResults: List<ProbeResultEntity>,
            primaryEventSource: DiagnosticsNativeEventArchiveSource,
            sourceData: DiagnosticsArchiveSourceData,
        ): PrimarySessionData {
            val report =
                primarySession
                    ?.reportJson
                    ?.takeIf(String::isNotBlank)
                    ?.let(json::decodeStoredEngineScanReportWire)
            val artifacts = selectPrimaryArtifacts(primarySession, primaryEventSource, sourceData)
            val aggregateSelectedApproachSummary =
                primarySession?.strategyId?.let { strategyId ->
                    sourceData.approachSummaries.firstOrNull {
                        it.approachId.kind == BypassApproachKind.Strategy &&
                            it.approachId.value == strategyId
                    }
                }
            val models = projectPrimaryModels(primarySession, artifacts)
            val selectedApproachSummary =
                sessionScopedApproachSummary(
                    primarySession = primarySession,
                    report = report,
                    effectiveStrategySignature = models.effectiveStrategySignature,
                    aggregateSummary = aggregateSelectedApproachSummary,
                )
            return PrimarySessionData(
                report = report,
                snapshots = artifacts.snapshots,
                contexts = artifacts.contexts,
                events = artifacts.primaryEventSelection.events,
                latestPassiveSnapshot = artifacts.latestPassiveSnapshot,
                latestPassiveContext = artifacts.latestPassiveContext,
                globalEvents = artifacts.globalEventSelection.events,
                primaryEventSelection = artifacts.primaryEventSelection,
                globalEventSelection = artifacts.globalEventSelection,
                selectedApproachSummary = selectedApproachSummary,
                latestSnapshotModel = models.latestSnapshotModel,
                latestSnapshotSource = models.latestSnapshotSource,
                latestContextModel = models.latestContextModel,
                sessionContextModel = models.sessionContextModel,
                effectiveStrategySignature = models.effectiveStrategySignature,
            )
        }

        private fun selectPrimaryArtifacts(
            primarySession: ScanSessionEntity?,
            primaryEventSource: DiagnosticsNativeEventArchiveSource,
            sourceData: DiagnosticsArchiveSourceData,
        ): PrimaryArtifacts {
            val sessionId = primarySession?.id
            return PrimaryArtifacts(
                snapshots =
                    sourceData.snapshots
                        .filter { snapshot -> sessionId != null && snapshot.sessionId == sessionId }
                        .take(DiagnosticsArchiveFormat.snapshotLimit),
                contexts =
                    sourceData.contexts
                        .filter { context -> sessionId != null && context.sessionId == sessionId }
                        .selectArchiveContexts(),
                latestPassiveSnapshot = sourceData.snapshots.firstOrNull { it.sessionId == null },
                latestPassiveContext = sourceData.contexts.firstOrNull { it.sessionId == null },
                primaryEventSelection =
                    selectArchiveNativeEvents(
                        source = primaryEventSource,
                        limit = DiagnosticsArchiveFormat.sessionEventLimit,
                    ),
                globalEventSelection =
                    selectArchiveNativeEvents(
                        source =
                            DiagnosticsNativeEventArchiveSource(
                                events = sourceData.events.filter { it.sessionId == null },
                                sourceCounts = sourceData.globalEventSourceCounts,
                            ),
                        limit = DiagnosticsArchiveFormat.globalEventLimit,
                    ),
            )
        }

        private fun projectPrimaryModels(
            primarySession: ScanSessionEntity?,
            artifacts: PrimaryArtifacts,
        ): PrimaryModels {
            val latestPrimarySnapshotModel =
                artifacts.snapshots.maxByOrNull { it.capturedAt }?.let(redactor::decodeNetworkSnapshot)
            val latestPassiveSnapshotModel = redactor.decodeNetworkSnapshot(artifacts.latestPassiveSnapshot)
            return PrimaryModels(
                latestSnapshotModel = latestPrimarySnapshotModel ?: latestPassiveSnapshotModel,
                latestSnapshotSource =
                    when {
                        latestPrimarySnapshotModel != null -> DiagnosticsArchiveSnapshotSource.SESSION
                        latestPassiveSnapshotModel != null -> DiagnosticsArchiveSnapshotSource.PASSIVE
                        else -> null
                    },
                latestContextModel = redactor.decodeDiagnosticContext(artifacts.latestPassiveContext),
                sessionContextModel =
                    artifacts.contexts
                        .maxByOrNull(DiagnosticContextEntity::capturedAt)
                        ?.let(redactor::decodeDiagnosticContext),
                effectiveStrategySignature = decodeStrategySignature(primarySession?.strategyJson),
            )
        }

        private fun decodeStrategySignature(payload: String?): BypassStrategySignature? =
            payload
                ?.takeIf(String::isNotBlank)
                ?.let { encoded ->
                    runCatching {
                        json.decodeFromString(BypassStrategySignature.serializer(), encoded)
                    }.getOrNull()
                }

        private fun sessionScopedApproachSummary(
            primarySession: ScanSessionEntity?,
            report: EngineScanReportWire?,
            effectiveStrategySignature: BypassStrategySignature?,
            aggregateSummary: BypassApproachSummary?,
        ): BypassApproachSummary? {
            val strategyId = primarySession?.strategyId
            val assessment =
                if (report != null && strategyId != null && effectiveStrategySignature != null) {
                    CurrentStrategyVerdictEvaluator.evaluate(
                        report = report.toScanReport(),
                        sessionStrategyId = strategyId,
                        expectedStrategyId = strategyId,
                        expectedStrategySignature = effectiveStrategySignature,
                    )
                } else {
                    null
                }
            if (strategyId == null) return null
            val strategyEvaluated = assessment?.isEvaluatedStrategyApproachEvidence() == true
            val strategyWorking = assessment?.isConfirmedWorkingStrategyApproach() == true
            val sessionVerificationState =
                when {
                    strategyWorking -> BypassApproachVerificationState.CONFIRMED_WORKING
                    strategyEvaluated -> BypassApproachVerificationState.EVALUATED_NO_SUCCESS
                    else -> BypassApproachVerificationState.INCOMPLETE_EVIDENCE
                }
            val base =
                aggregateSummary ?: BypassApproachSummary(
                    approachId = BypassApproachId(BypassApproachKind.Strategy, strategyId),
                    displayName = "Current strategy",
                    secondaryLabel = "Strategy",
                    verificationState = sessionVerificationState,
                    validatedScanCount = 0,
                    validatedSuccessCount = 0,
                    validatedSuccessRate = null,
                    lastValidatedResult = null,
                    usageCount = 0,
                    totalRuntimeDurationMs = 0L,
                    recentRuntimeHealth = BypassRuntimeHealthSummary(),
                    lastUsedAt = null,
                )
            return base.copy(
                verificationState = sessionVerificationState,
                validatedScanCount = if (strategyEvaluated) 1 else 0,
                validatedSuccessCount = if (strategyWorking) 1 else 0,
                validatedSuccessRate =
                    if (strategyEvaluated) {
                        if (strategyWorking) {
                            1.0f
                        } else {
                            0.0f
                        }
                    } else {
                        null
                    },
                lastValidatedResult = null,
                usageCount = 0,
                totalRuntimeDurationMs = 0L,
                recentRuntimeHealth = BypassRuntimeHealthSummary(),
                lastUsedAt = null,
                topFailureOutcomes = emptyList(),
                outcomeBreakdown = emptyList(),
                currentStrategyAssessment = assessment,
            )
        }

        private fun buildIncludedFiles(
            isComposite: Boolean,
            compositeStages: List<DiagnosticsArchiveCompositeStageSelection>,
            sourceData: DiagnosticsArchiveSourceData,
        ): List<String> {
            val logcatIncluded = sourceData.logcatSnapshot != null
            val fileLogIncluded = sourceData.fileLogSnapshot != null
            val startupJournalIncluded = sourceData.startupJournalSnapshot != null
            val replayIncluded = sourceData.replayResults.isNotEmpty()
            if (!isComposite) {
                return DiagnosticsArchiveFormat.includedFiles(
                    logcatIncluded = logcatIncluded,
                    fileLogIncluded = fileLogIncluded,
                    startupJournalIncluded = startupJournalIncluded,
                    replayIncluded = replayIncluded,
                )
            }
            return DiagnosticsArchiveFormat.includedFiles(
                logcatIncluded = logcatIncluded,
                fileLogIncluded = fileLogIncluded,
                startupJournalIncluded = startupJournalIncluded,
                composite = true,
                compositeStageKeys = compositeStages.map { it.stageSummary.stageKey },
                replayIncluded = replayIncluded,
            )
        }

        private data class PrimarySessionData(
            val report: EngineScanReportWire?,
            val snapshots: List<NetworkSnapshotEntity>,
            val contexts: List<DiagnosticContextEntity>,
            val events: List<NativeSessionEventEntity>,
            val latestPassiveSnapshot: NetworkSnapshotEntity?,
            val latestPassiveContext: DiagnosticContextEntity?,
            val globalEvents: List<NativeSessionEventEntity>,
            val primaryEventSelection: DiagnosticsArchiveNativeEventSelection,
            val globalEventSelection: DiagnosticsArchiveNativeEventSelection,
            val selectedApproachSummary: BypassApproachSummary?,
            val latestSnapshotModel: NetworkSnapshotModel?,
            val latestSnapshotSource: DiagnosticsArchiveSnapshotSource?,
            val latestContextModel: DiagnosticContextModel?,
            val sessionContextModel: DiagnosticContextModel?,
            val effectiveStrategySignature: BypassStrategySignature?,
        )

        private data class PrimaryArtifacts(
            val snapshots: List<NetworkSnapshotEntity>,
            val contexts: List<DiagnosticContextEntity>,
            val latestPassiveSnapshot: NetworkSnapshotEntity?,
            val latestPassiveContext: DiagnosticContextEntity?,
            val primaryEventSelection: DiagnosticsArchiveNativeEventSelection,
            val globalEventSelection: DiagnosticsArchiveNativeEventSelection,
        )

        private data class PrimaryModels(
            val latestSnapshotModel: NetworkSnapshotModel?,
            val latestSnapshotSource: DiagnosticsArchiveSnapshotSource?,
            val latestContextModel: DiagnosticContextModel?,
            val sessionContextModel: DiagnosticContextModel?,
            val effectiveStrategySignature: BypassStrategySignature?,
        )

        private data class SelectionInputs(
            val primaryEventSource: DiagnosticsNativeEventArchiveSource,
            val primary: PrimarySessionData,
            val relayTraceHydration: RelayTraceHydration,
            val rootSourceCounts: DiagnosticsArchiveRootSourceCounts,
            val isComposite: Boolean,
            val compositeStages: List<DiagnosticsArchiveCompositeStageSelection>,
            val includedFiles: List<String>,
        )

        private fun resolveSessionSelectionStatus(
            request: DiagnosticsArchiveRequest,
            isComposite: Boolean,
            primarySession: ScanSessionEntity?,
        ): DiagnosticsArchiveSessionSelectionStatus =
            when {
                request.reason == DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE -> {
                    DiagnosticsArchiveSessionSelectionStatus.SUPPORT_BUNDLE
                }

                isComposite && primarySession == null -> {
                    DiagnosticsArchiveSessionSelectionStatus.UNAVAILABLE
                }

                isComposite -> {
                    DiagnosticsArchiveSessionSelectionStatus.LATEST_COMPLETED_SESSION
                }

                request.requestedSessionId != null -> {
                    DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION
                }

                primarySession?.reportJson != null -> {
                    DiagnosticsArchiveSessionSelectionStatus.LATEST_COMPLETED_SESSION
                }

                else -> {
                    DiagnosticsArchiveSessionSelectionStatus.LATEST_LIVE_STATE
                }
            }
    }

private fun emptyNativeEventArchiveSource(): DiagnosticsNativeEventArchiveSource =
    DiagnosticsNativeEventArchiveSource(
        events = emptyList(),
        sourceCounts = DiagnosticsNativeEventArchiveClassCounts(),
    )

private fun selectRuntimeSnapshots(sourceData: DiagnosticsArchiveSourceData): List<NetworkSnapshotEntity> =
    sourceData.snapshots
        .filter { it.sessionId == null && !it.connectionSessionId.isNullOrBlank() }
        .take(DiagnosticsArchiveFormat.snapshotLimit)

internal fun List<DiagnosticContextEntity>.selectArchiveContexts(): List<DiagnosticContextEntity> {
    val postRuntimeRestore =
        filter(DiagnosticContextEntity::isPostRuntimeRestoreContext)
            .maxWithOrNull(compareBy(DiagnosticContextEntity::capturedAt).thenBy(DiagnosticContextEntity::id))
    return buildList {
        postRuntimeRestore?.let(::add)
        addAll(
            this@selectArchiveContexts
                .asSequence()
                .filterNot(DiagnosticContextEntity::isPostRuntimeRestoreContext)
                .take(DiagnosticsArchiveFormat.snapshotLimit)
                .toList(),
        )
    }
}
