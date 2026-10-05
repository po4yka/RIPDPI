package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSessionSelector
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSourceLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus

/** Captures the same immutable archive selection for normal and prepared exports. */
internal class DiagnosticsArchiveSelectionBuilder(
    private val sourceLoader: DiagnosticsArchiveSourceLoader,
    private val sessionSelector: DiagnosticsArchiveSessionSelector,
) {
    suspend fun build(request: DiagnosticsArchiveRequest): DiagnosticsArchiveSelection {
        val sourceData = sourceLoader.load()
        val compositeOutcome = loadCompositeOutcome(request)
        val compositeSessions = loadCompositeSessions(request, compositeOutcome)
        val primarySession = selectPrimarySession(request, sourceData, compositeOutcome, compositeSessions)
        val primaryResults = primarySession?.id?.let { sourceLoader.getProbeResults(it) }.orEmpty()
        val selectedSessionIds =
            (listOfNotNull(primarySession?.id) + compositeSessions.map { it.id }).distinct()
        val selectionSourceData = hydrateSelectedArtifacts(sourceData, selectedSessionIds)
        val selection =
            sessionSelector
                .buildSelection(
                    request = request,
                    primarySession = primarySession,
                    primaryResults = primaryResults,
                    sourceData = selectionSourceData,
                    compositeOutcome = compositeOutcome,
                    compositeSessions = compositeSessions,
                    loadProbeResults = { sessionId -> sourceLoader.getProbeResults(sessionId) },
                    loadNativeEventSource = { sessionId -> sourceLoader.getNativeEventArchiveSource(sessionId) },
                    loadRelayAttemptTraceEvents = { key ->
                        sourceLoader.getRelayAttemptTraceEvents(
                            connectionSessionId = key.connectionSessionId,
                            runtimeId = key.runtimeId,
                            attemptId = key.attemptId,
                        )
                    },
                    loadStageTelemetry = { session, connectionSessionIds ->
                        sourceLoader.getStageTelemetry(session, connectionSessionIds)
                    },
                )
        return finalizeSelection(selection)
    }

    private suspend fun loadCompositeOutcome(request: DiagnosticsArchiveRequest): DiagnosticsHomeCompositeOutcome? =
        request.homeRunId?.let { runId ->
            requireNotNull(sourceLoader.getCompletedHomeRun(runId)) {
                "Requested completed home diagnostics run is unavailable: $runId"
            }.also(::validateCompositeStageKeys)
        }

    private fun validateCompositeStageKeys(outcome: DiagnosticsHomeCompositeOutcome) {
        val stageKeys = outcome.stageSummaries.map { it.stageKey }
        require(stageKeys.distinct().size == stageKeys.size) {
            "Completed home diagnostics run contains duplicate stage keys"
        }
        require(stageKeys.all { it.matches(ArchiveStageKeyRegex) }) {
            "Completed home diagnostics run contains an unsafe stage key"
        }
    }

    private suspend fun loadCompositeSessions(
        request: DiagnosticsArchiveRequest,
        outcome: DiagnosticsHomeCompositeOutcome?,
    ): List<ScanSessionEntity> {
        if (outcome == null) return emptyList()
        require(request.requestedSessionId == null) {
            "Home diagnostics archive cannot select a caller-provided primary session"
        }
        val unexpectedSessionIds = request.sessionIds - outcome.bundleSessionIds.toSet()
        require(unexpectedSessionIds.isEmpty()) {
            "Home diagnostics archive contains session IDs outside the completed run: " +
                unexpectedSessionIds.joinToString()
        }
        return sourceLoader.getScanSessions(outcome.bundleSessionIds.distinct())
    }

    private suspend fun selectPrimarySession(
        request: DiagnosticsArchiveRequest,
        sourceData: DiagnosticsArchiveSourceData,
        outcome: DiagnosticsHomeCompositeOutcome?,
        compositeSessions: List<ScanSessionEntity>,
    ): ScanSessionEntity? {
        if (outcome == null) {
            return sessionSelector.selectPrimarySession(
                requestedSessionId = request.requestedSessionId,
                requestedSession = request.requestedSessionId?.let { sourceLoader.getScanSession(it) },
                sessions = sourceData.sessions,
            )
        }
        val recommendedSessionId = outcome.recommendedSessionId
        require(recommendedSessionId != null || !outcome.actionable) {
            "Actionable home diagnostics run has no recommended session: ${outcome.runId}"
        }
        return if (recommendedSessionId != null) {
            require(
                outcome.stageSummaries.any { stage ->
                    stage.sessionId == recommendedSessionId &&
                        stage.status == DiagnosticsHomeCompositeStageStatus.COMPLETED
                },
            ) {
                "Recommended session does not belong to a completed home diagnostics stage: $recommendedSessionId"
            }
            require(recommendedSessionId in outcome.bundleSessionIds) {
                "Primary session is outside the completed home diagnostics run: $recommendedSessionId"
            }
            requireNotNull(compositeSessions.firstOrNull { it.id == recommendedSessionId }) {
                "Primary home diagnostics session is unavailable: $recommendedSessionId"
            }
        } else {
            val stageSessions =
                outcome.stageSummaries
                    .asSequence()
                    .mapNotNull { stage ->
                        stage.sessionId
                            ?.takeIf { sessionId -> sessionId in outcome.bundleSessionIds }
                            ?.let { sessionId -> compositeSessions.firstOrNull { it.id == sessionId } }
                            ?.let { session -> stage to session }
                    }.toList()
            stageSessions
                .asSequence()
                .firstOrNull { (stage) -> stage.status == DiagnosticsHomeCompositeStageStatus.COMPLETED }
                ?.second
                ?: stageSessions
                    .asSequence()
                    .map { (_, session) -> session }
                    .firstOrNull { session -> !session.reportJson.isNullOrBlank() }
                ?: stageSessions.firstOrNull()?.second
        }
    }

    private suspend fun hydrateSelectedArtifacts(
        sourceData: DiagnosticsArchiveSourceData,
        selectedSessionIds: List<String>,
    ): DiagnosticsArchiveSourceData =
        sourceData.copy(
            snapshots =
                mergeArchiveArtifacts(
                    sourceData.snapshots,
                    selectedSessionIds.flatMap { sessionId -> sourceLoader.getSnapshots(sessionId) },
                ) { it.id },
            contexts =
                mergeArchiveArtifacts(
                    sourceData.contexts,
                    selectedSessionIds.flatMap { sessionId -> sourceLoader.getContexts(sessionId) },
                ) { it.id },
        )

    private fun finalizeSelection(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveSelection {
        val missingCompletedStageWarnings =
            selection.compositeStages
                .filter { stage -> stage.stageSummary.status.name == "COMPLETED" && stage.session == null }
                .map { stage -> "completed_stage_evidence_unavailable:${stage.stageSummary.stageKey}" }
        return selection.copy(
            pcapFiles = emptyList(),
            collectionWarnings = selection.collectionWarnings + missingCompletedStageWarnings,
            includedFiles =
                DiagnosticsArchiveFormat.includedFiles(
                    logcatIncluded = selection.logcatSnapshot != null,
                    fileLogIncluded = selection.fileLogSnapshot != null,
                    startupJournalIncluded = selection.startupJournalSnapshot != null,
                    composite = selection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE,
                    compositeStageKeys = selection.compositeStages.map { it.stageSummary.stageKey },
                    replayIncluded = selection.replayResults.isNotEmpty(),
                ),
        )
    }

    private fun <T> mergeArchiveArtifacts(
        recent: List<T>,
        selected: List<T>,
        key: (T) -> String,
    ): List<T> = (recent + selected).distinctBy(key)
}

private val ArchiveStageKeyRegex = Regex("[a-z0-9][a-z0-9_]{0,63}")
