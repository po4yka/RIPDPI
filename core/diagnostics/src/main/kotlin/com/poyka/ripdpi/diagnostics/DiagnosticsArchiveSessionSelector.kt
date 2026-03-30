package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
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

        @Suppress("LongMethod")
        internal fun buildSelection(
            request: DiagnosticsArchiveRequest,
            primarySession: ScanSessionEntity?,
            primaryResults: List<ProbeResultEntity>,
            sourceData: DiagnosticsArchiveSourceData,
        ): DiagnosticsArchiveSelection {
            val primaryReport =
                primarySession
                    ?.reportJson
                    ?.takeIf(String::isNotBlank)
                    ?.let(json::decodeEngineScanReportWire)
            val primarySnapshots =
                primarySession
                    ?.id
                    ?.let { sessionId ->
                        sourceData.snapshots.filter { it.sessionId == sessionId }
                    }.orEmpty()
            val primaryContexts =
                primarySession
                    ?.id
                    ?.let { sessionId ->
                        sourceData.contexts.filter { it.sessionId == sessionId }
                    }.orEmpty()
            val primaryEvents =
                primarySession
                    ?.id
                    ?.let { sessionId ->
                        sourceData.events.filter { it.sessionId == sessionId }
                    }.orEmpty()
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
                primarySnapshots.maxByOrNull { it.capturedAt }?.let(redactor::decodeNetworkSnapshot)
            val latestSnapshotModel =
                redactor.decodeNetworkSnapshot(latestPassiveSnapshot) ?: latestPrimarySnapshotModel
            val latestContextModel =
                redactor.decodeDiagnosticContext(latestPassiveContext)
            val sessionContextModel =
                primaryContexts.maxByOrNull(DiagnosticContextEntity::capturedAt)?.let(redactor::decodeDiagnosticContext)
            val routeGroup =
                sessionContextModel?.service?.routeGroup ?: latestContextModel?.service?.routeGroup
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
            val payload =
                DiagnosticsArchivePayload(
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    scope = DiagnosticsArchiveFormat.scope,
                    privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    session = primarySession,
                    primaryReport = primaryReport,
                    results = primaryResults,
                    sessionSnapshots = primarySnapshots,
                    sessionContexts = primaryContexts,
                    sessionEvents = primaryEvents,
                    latestPassiveSnapshot = latestPassiveSnapshot,
                    latestPassiveContext = latestPassiveContext,
                    telemetry = sourceData.telemetry.take(DiagnosticsArchiveFormat.telemetryLimit),
                    globalEvents = globalEvents,
                    approachSummaries = sourceData.approachSummaries,
                )
            return DiagnosticsArchiveSelection(
                request = request,
                payload = payload,
                primarySession = primarySession,
                primaryReport = primaryReport,
                primaryResults = primaryResults,
                primarySnapshots = primarySnapshots,
                primaryContexts = primaryContexts,
                primaryEvents = primaryEvents,
                latestPassiveSnapshot = latestPassiveSnapshot,
                latestPassiveContext = latestPassiveContext,
                globalEvents = globalEvents,
                selectedApproachSummary = selectedApproachSummary,
                latestSnapshotModel = latestSnapshotModel,
                latestContextModel = latestContextModel,
                sessionContextModel = sessionContextModel,
                buildProvenance = sourceData.buildProvenance,
                sessionSelectionStatus =
                    when {
                        request.reason == DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE -> {
                            DiagnosticsArchiveSessionSelectionStatus.SUPPORT_BUNDLE
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
                    },
                effectiveStrategySignature = effectiveStrategySignature,
                appSettings = sourceData.appSettings,
                sourceCounts =
                    DiagnosticsArchiveSourceCounts(
                        telemetrySamples = sourceData.telemetry.size,
                        nativeEvents = sourceData.events.size,
                        snapshots = sourceData.snapshots.size,
                        contexts = sourceData.contexts.size,
                        sessionResults = primaryResults.size,
                        sessionSnapshots = primarySnapshots.size,
                        sessionContexts = primaryContexts.size,
                        sessionEvents = primaryEvents.size,
                    ),
                collectionWarnings = sourceData.collectionWarnings,
                includedFiles = DiagnosticsArchiveFormat.includedFiles(sourceData.logcatSnapshot != null),
                logcatSnapshot = sourceData.logcatSnapshot,
            )
        }
    }
