@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveArchiveWideCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePrimarySessionCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRootSourceCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRunType
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveScopedCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSessionSelectionStatus
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSnapshotSource
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceData
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
            loadNativeEvents: suspend (String) -> List<NativeSessionEventEntity>,
            loadStageTelemetry: suspend (ScanSessionEntity, Set<String>) -> List<TelemetrySampleEntity> = { _, _ ->
                emptyList()
            },
            loadRunStageTelemetry: suspend (String, String) -> List<TelemetrySampleEntity> = { _, _ -> emptyList() },
        ): DiagnosticsArchiveSelection {
            val eventCache = mutableMapOf<String, List<NativeSessionEventEntity>>()

            suspend fun loadSessionEvents(sessionId: String): List<NativeSessionEventEntity> =
                eventCache[sessionId] ?: loadNativeEvents(sessionId).also { eventCache[sessionId] = it }

            val primaryEvents = primarySession?.id?.let { sessionId -> loadSessionEvents(sessionId) }.orEmpty()
            val primary = buildPrimarySessionData(primarySession, primaryResults, primaryEvents, sourceData)
            val rootSourceCounts = buildRootSourceCounts(sourceData, primarySession)
            val isComposite = compositeOutcome != null && request.homeRunId != null
            val compositeStages =
                buildCompositeStages(
                    isComposite,
                    compositeOutcome,
                    compositeSessions,
                    sourceData,
                    loadProbeResults,
                    ::loadSessionEvents,
                    loadStageTelemetry,
                    loadRunStageTelemetry,
                )
            val includedFiles = buildIncludedFiles(isComposite, compositeStages, sourceData)
            val payload = buildArchivePayload(primarySession, primaryResults, primary, sourceData)
            return DiagnosticsArchiveSelection(
                runType =
                    if (isComposite) {
                        DiagnosticsArchiveRunType.HOME_COMPOSITE
                    } else {
                        DiagnosticsArchiveRunType.SINGLE_SESSION
                    },
                request = request,
                payload = payload,
                primarySession = primarySession,
                primaryReport = primary.report,
                primaryResults = primaryResults,
                primarySnapshots = primary.snapshots,
                primaryContexts = primary.contexts,
                primaryEvents = primary.events,
                latestPassiveSnapshot = primary.latestPassiveSnapshot,
                latestPassiveContext = primary.latestPassiveContext,
                globalEvents = primary.globalEvents,
                rootSourceCounts = rootSourceCounts,
                selectedApproachSummary = primary.selectedApproachSummary,
                latestSnapshotModel = primary.latestSnapshotModel,
                latestSnapshotSource = primary.latestSnapshotSource,
                latestContextModel = primary.latestContextModel,
                sessionContextModel = primary.sessionContextModel,
                buildProvenance = sourceData.buildProvenance,
                installedArtifact = sourceData.installedArtifact,
                sessionSelectionStatus = resolveSessionSelectionStatus(request, isComposite, primarySession),
                homeRunId = request.homeRunId,
                homeCompositeOutcome = compositeOutcome,
                compositeStages = compositeStages,
                effectiveStrategySignature = primary.effectiveStrategySignature,
                appSettings = sourceData.appSettings,
                replayResults = sourceData.replayResults,
                sourceCounts =
                    buildSourceCounts(
                        sourceData,
                        primaryResults,
                        primaryEvents,
                        rootSourceCounts,
                        compositeStages,
                    ),
                collectionWarnings = sourceData.collectionWarnings,
                includedFiles = includedFiles,
                logcatSnapshot = sourceData.logcatSnapshot,
                fileLogSnapshot = sourceData.fileLogSnapshot,
            )
        }

        private fun buildSourceCounts(
            sourceData: DiagnosticsArchiveSourceData,
            primaryResults: List<ProbeResultEntity>,
            primaryEvents: List<NativeSessionEventEntity>,
            rootSourceCounts: DiagnosticsArchiveRootSourceCounts,
            compositeStages: List<DiagnosticsArchiveCompositeStageSelection>,
        ) = DiagnosticsArchiveScopedCounts(
            archiveWide =
                DiagnosticsArchiveArchiveWideCounts(
                    telemetrySamples =
                        (
                            sourceData.telemetry.map { it.id } + compositeStages.flatMap { it.sourceTelemetryIds }
                        ).toSet()
                            .size,
                    nativeEvents =
                        (
                            sourceData.events.map { it.id } +
                                primaryEvents.map { it.id } +
                                compositeStages.flatMap { it.sourceEventIds }
                        ).toSet()
                            .size,
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
                    events = primaryEvents.size,
                ),
        )

        private fun buildRootSourceCounts(
            sourceData: DiagnosticsArchiveSourceData,
            primarySession: ScanSessionEntity?,
        ): DiagnosticsArchiveRootSourceCounts {
            val sessionId = primarySession?.id
            return DiagnosticsArchiveRootSourceCounts(
                telemetrySamples = sourceData.telemetry.size,
                primarySnapshots = sourceData.snapshots.count { it.sessionId == sessionId && sessionId != null },
                primaryContexts = sourceData.contexts.count { it.sessionId == sessionId && sessionId != null },
                globalEvents = sourceData.events.count { it.sessionId == null },
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
            primaryEvents: List<NativeSessionEventEntity>,
            sourceData: DiagnosticsArchiveSourceData,
        ): PrimarySessionData {
            val report =
                primarySession
                    ?.reportJson
                    ?.takeIf(String::isNotBlank)
                    ?.let(json::decodeEngineScanReportWire)
            val snapshots =
                primarySession
                    ?.id
                    ?.let { sessionId ->
                        sourceData.snapshots
                            .filter { it.sessionId == sessionId }
                            .take(DiagnosticsArchiveFormat.snapshotLimit)
                    }.orEmpty()
            val contexts =
                primarySession
                    ?.id
                    ?.let { sessionId ->
                        sourceData.contexts
                            .filter { it.sessionId == sessionId }
                            .take(DiagnosticsArchiveFormat.snapshotLimit)
                    }.orEmpty()
            val latestPassiveSnapshot = sourceData.snapshots.firstOrNull { it.sessionId == null }
            val latestPassiveContext = sourceData.contexts.firstOrNull { it.sessionId == null }
            val globalEvents =
                sourceData.events
                    .filter { it.sessionId == null }
                    .take(DiagnosticsArchiveFormat.globalEventLimit)
            val selectedApproachSummary =
                primarySession?.strategyId?.let { strategyId ->
                    sourceData.approachSummaries.firstOrNull {
                        it.approachId.kind == BypassApproachKind.Strategy &&
                            it.approachId.value == strategyId
                    }
                }
            val latestPrimarySnapshotModel =
                snapshots.maxByOrNull { it.capturedAt }?.let(redactor::decodeNetworkSnapshot)
            val latestPassiveSnapshotModel = redactor.decodeNetworkSnapshot(latestPassiveSnapshot)
            val latestSnapshotModel = latestPrimarySnapshotModel ?: latestPassiveSnapshotModel
            val latestSnapshotSource =
                when {
                    latestPrimarySnapshotModel != null -> DiagnosticsArchiveSnapshotSource.SESSION
                    latestPassiveSnapshotModel != null -> DiagnosticsArchiveSnapshotSource.PASSIVE
                    else -> null
                }
            val latestContextModel = redactor.decodeDiagnosticContext(latestPassiveContext)
            val sessionContextModel =
                contexts.maxByOrNull(DiagnosticContextEntity::capturedAt)?.let(redactor::decodeDiagnosticContext)
            val routeGroup = sessionContextModel?.service?.routeGroup ?: latestContextModel?.service?.routeGroup
            val modeOverride =
                primarySession
                    ?.serviceMode
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Mode::fromString)
            val effectiveStrategySignature =
                runCatching {
                    deriveBypassStrategySignature(
                        settings = sourceData.appSettings,
                        routeGroup = routeGroup,
                        modeOverride = modeOverride,
                    )
                }.getOrNull()
            return PrimarySessionData(
                report = report,
                snapshots = snapshots,
                contexts = contexts,
                events = primaryEvents.take(DiagnosticsArchiveFormat.sessionEventLimit),
                latestPassiveSnapshot = latestPassiveSnapshot,
                latestPassiveContext = latestPassiveContext,
                globalEvents = globalEvents,
                selectedApproachSummary = selectedApproachSummary,
                latestSnapshotModel = latestSnapshotModel,
                latestSnapshotSource = latestSnapshotSource,
                latestContextModel = latestContextModel,
                sessionContextModel = sessionContextModel,
                effectiveStrategySignature = effectiveStrategySignature,
            )
        }

        private suspend fun buildCompositeStages(
            isComposite: Boolean,
            compositeOutcome: DiagnosticsHomeCompositeOutcome?,
            compositeSessions: List<ScanSessionEntity>,
            sourceData: DiagnosticsArchiveSourceData,
            loadProbeResults: suspend (String) -> List<ProbeResultEntity>,
            loadNativeEvents: suspend (String) -> List<NativeSessionEventEntity>,
            loadStageTelemetry: suspend (ScanSessionEntity, Set<String>) -> List<TelemetrySampleEntity>,
            loadRunStageTelemetry: suspend (String, String) -> List<TelemetrySampleEntity>,
        ): List<DiagnosticsArchiveCompositeStageSelection> {
            if (!isComposite || compositeOutcome == null) return emptyList()
            return compositeOutcome.stageSummaries.map { stageSummary ->
                val explicitlyScopedTelemetry =
                    (
                        loadRunStageTelemetry(compositeOutcome.runId, stageSummary.stageKey) +
                            sourceData.telemetry.filter { sample ->
                                sample.diagnosticsRunId == compositeOutcome.runId &&
                                    sample.diagnosticsStageKey == stageSummary.stageKey
                            }
                    ).distinctBy(TelemetrySampleEntity::id)
                        .sortedByDescending(TelemetrySampleEntity::createdAt)
                val session = compositeSessions.firstOrNull { it.id == stageSummary.sessionId }
                if (session == null) {
                    return@map DiagnosticsArchiveCompositeStageSelection(
                        stageSummary = stageSummary,
                        session = null,
                        report = null,
                        results = emptyList(),
                        snapshots = emptyList(),
                        contexts = emptyList(),
                        events = emptyList(),
                        telemetry = explicitlyScopedTelemetry.take(DiagnosticsArchiveFormat.telemetryLimit),
                        sourceTelemetryCount = explicitlyScopedTelemetry.size,
                        sourceTelemetryIds = explicitlyScopedTelemetry.mapTo(linkedSetOf()) { it.id },
                    )
                }
                val report =
                    session
                        .reportJson
                        ?.takeIf(String::isNotBlank)
                        ?.let(json::decodeEngineScanReportWire)
                val events = loadNativeEvents(session.id)
                val snapshots = sourceData.snapshots.filter { it.sessionId == session.id }
                val contexts = sourceData.contexts.filter { it.sessionId == session.id }
                val connectionSessionIds =
                    buildSet {
                        snapshots.mapNotNullTo(this) { it.connectionSessionId }
                        contexts.mapNotNullTo(this) { it.connectionSessionId }
                        events.mapNotNullTo(this) { it.connectionSessionId }
                    }
                val telemetry =
                    (loadStageTelemetry(session, connectionSessionIds) + explicitlyScopedTelemetry)
                        .distinctBy(TelemetrySampleEntity::id)
                        .sortedByDescending(TelemetrySampleEntity::createdAt)
                DiagnosticsArchiveCompositeStageSelection(
                    stageSummary = stageSummary,
                    session = session,
                    report = report,
                    results = loadProbeResults(session.id),
                    snapshots = snapshots.take(DiagnosticsArchiveFormat.snapshotLimit),
                    contexts = contexts.take(DiagnosticsArchiveFormat.snapshotLimit),
                    events = events.take(DiagnosticsArchiveFormat.sessionEventLimit),
                    telemetry = telemetry.take(DiagnosticsArchiveFormat.telemetryLimit),
                    sourceSnapshotCount = snapshots.size,
                    sourceContextCount = contexts.size,
                    sourceEventCount = events.size,
                    sourceTelemetryCount = telemetry.size,
                    sourceEventIds = events.mapTo(linkedSetOf()) { it.id },
                    sourceTelemetryIds = telemetry.mapTo(linkedSetOf()) { it.id },
                )
            }
        }

        private fun buildIncludedFiles(
            isComposite: Boolean,
            compositeStages: List<DiagnosticsArchiveCompositeStageSelection>,
            sourceData: DiagnosticsArchiveSourceData,
        ): List<String> {
            val logcatIncluded = sourceData.logcatSnapshot != null
            val fileLogIncluded = sourceData.fileLogSnapshot != null
            val replayIncluded = sourceData.replayResults.isNotEmpty()
            if (!isComposite) {
                return DiagnosticsArchiveFormat.includedFiles(
                    logcatIncluded = logcatIncluded,
                    fileLogIncluded = fileLogIncluded,
                    replayIncluded = replayIncluded,
                )
            }
            return DiagnosticsArchiveFormat.includedFiles(
                logcatIncluded = logcatIncluded,
                fileLogIncluded = fileLogIncluded,
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
            val selectedApproachSummary: BypassApproachSummary?,
            val latestSnapshotModel: NetworkSnapshotModel?,
            val latestSnapshotSource: DiagnosticsArchiveSnapshotSource?,
            val latestContextModel: DiagnosticContextModel?,
            val sessionContextModel: DiagnosticContextModel?,
            val effectiveStrategySignature: BypassStrategySignature?,
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
