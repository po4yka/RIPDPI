package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRunType
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSessionSelectionStatus
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceCounts
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
        ): DiagnosticsArchiveSelection {
            val primary = buildPrimarySessionData(primarySession, primaryResults, sourceData)
            val isComposite = compositeOutcome != null && request.homeRunId != null && request.sessionIds.isNotEmpty()
            val compositeStages =
                buildCompositeStages(isComposite, compositeOutcome, compositeSessions, sourceData, loadProbeResults)
            val includedFiles = buildIncludedFiles(isComposite, compositeStages, sourceData)
            val payload =
                DiagnosticsArchivePayload(
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
                selectedApproachSummary = primary.selectedApproachSummary,
                latestSnapshotModel = primary.latestSnapshotModel,
                latestContextModel = primary.latestContextModel,
                sessionContextModel = primary.sessionContextModel,
                buildProvenance = sourceData.buildProvenance,
                sessionSelectionStatus = resolveSessionSelectionStatus(request, isComposite, primarySession),
                homeRunId = request.homeRunId,
                homeCompositeOutcome = compositeOutcome,
                compositeStages = compositeStages,
                effectiveStrategySignature = primary.effectiveStrategySignature,
                appSettings = sourceData.appSettings,
                sourceCounts =
                    DiagnosticsArchiveSourceCounts(
                        telemetrySamples = sourceData.telemetry.size,
                        nativeEvents = sourceData.events.size,
                        snapshots = sourceData.snapshots.size,
                        contexts = sourceData.contexts.size,
                        sessionResults = primaryResults.size,
                        sessionSnapshots = primary.snapshots.size,
                        sessionContexts = primary.contexts.size,
                        sessionEvents = primary.events.size,
                    ),
                collectionWarnings = sourceData.collectionWarnings,
                includedFiles = includedFiles,
                logcatSnapshot = sourceData.logcatSnapshot,
                fileLogSnapshot = sourceData.fileLogSnapshot,
            )
        }

        private fun buildPrimarySessionData(
            primarySession: ScanSessionEntity?,
            @Suppress("UnusedParameter") primaryResults: List<ProbeResultEntity>,
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
                    ?.let { sessionId -> sourceData.snapshots.filter { it.sessionId == sessionId } }
                    .orEmpty()
            val contexts =
                primarySession
                    ?.id
                    ?.let { sessionId -> sourceData.contexts.filter { it.sessionId == sessionId } }
                    .orEmpty()
            val events =
                primarySession
                    ?.id
                    ?.let { sessionId -> sourceData.events.filter { it.sessionId == sessionId } }
                    .orEmpty()
            val latestPassiveSnapshot = sourceData.snapshots.firstOrNull { it.sessionId == null }
            val latestPassiveContext = sourceData.contexts.firstOrNull { it.sessionId == null }
            val globalEvents =
                sourceData.events
                    .filter { it.sessionId == null || it.sessionId != primarySession?.id }
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
            val latestSnapshotModel =
                redactor.decodeNetworkSnapshot(latestPassiveSnapshot) ?: latestPrimarySnapshotModel
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
                events = events,
                latestPassiveSnapshot = latestPassiveSnapshot,
                latestPassiveContext = latestPassiveContext,
                globalEvents = globalEvents,
                selectedApproachSummary = selectedApproachSummary,
                latestSnapshotModel = latestSnapshotModel,
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
        ): List<DiagnosticsArchiveCompositeStageSelection> {
            if (!isComposite || compositeOutcome == null) return emptyList()
            return compositeOutcome.stageSummaries.map { stageSummary ->
                val session = compositeSessions.firstOrNull { it.id == stageSummary.sessionId }
                val report =
                    session
                        ?.reportJson
                        ?.takeIf(String::isNotBlank)
                        ?.let(json::decodeEngineScanReportWire)
                DiagnosticsArchiveCompositeStageSelection(
                    stageSummary = stageSummary,
                    session = session,
                    report = report,
                    results =
                        session
                            ?.id
                            ?.let { sessionId -> loadProbeResults(sessionId) }
                            .orEmpty(),
                    snapshots = sourceData.snapshots.filter { it.sessionId == session?.id },
                    contexts = sourceData.contexts.filter { it.sessionId == session?.id },
                    events = sourceData.events.filter { it.sessionId == session?.id },
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
            if (!isComposite) {
                return DiagnosticsArchiveFormat.includedFiles(
                    logcatIncluded = logcatIncluded,
                    fileLogIncluded = fileLogIncluded,
                )
            }
            return DiagnosticsArchiveFormat.includedFiles(
                logcatIncluded = logcatIncluded,
                fileLogIncluded = fileLogIncluded,
                composite = true,
            ) +
                compositeStages.flatMap { stage ->
                    val prefix = "stages/${stage.stageSummary.stageKey}"
                    listOf(
                        "$prefix/report.json",
                        "$prefix/probe-results.csv",
                        "$prefix/strategy-matrix.json",
                        "$prefix/network-snapshots.json",
                        "$prefix/diagnostic-context.json",
                        "$prefix/native-events.csv",
                        "$prefix/telemetry.csv",
                    )
                }
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
