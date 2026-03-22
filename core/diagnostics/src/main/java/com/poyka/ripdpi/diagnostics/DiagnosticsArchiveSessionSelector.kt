package com.poyka.ripdpi.diagnostics

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
                requestedSessionId != null -> requestedSession
                else -> sessions.firstOrNull { it.reportJson != null } ?: sessions.firstOrNull()
            }

        internal fun buildSelection(
            primarySession: ScanSessionEntity?,
            primaryResults: List<ProbeResultEntity>,
            sourceData: DiagnosticsArchiveSourceData,
        ): DiagnosticsArchiveSelection {
            val primaryReport = DiagnosticsSessionQueries.decodeScanReport(json, primarySession?.reportJson)
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
            val payload =
                DiagnosticsArchivePayload(
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    scope = DiagnosticsArchiveFormat.scope,
                    privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    session = primarySession,
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
                includedFiles = DiagnosticsArchiveFormat.includedFiles(sourceData.logcatSnapshot != null),
                logcatSnapshot = sourceData.logcatSnapshot,
            )
        }
    }
