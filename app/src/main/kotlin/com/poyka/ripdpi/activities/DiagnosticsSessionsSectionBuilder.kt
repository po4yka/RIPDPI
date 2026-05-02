package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.buildSessionsUiModel(
    sessions: List<DiagnosticScanSession>,
    sessionRows: List<DiagnosticsSessionRowUiModel>,
    sessionPathMode: String?,
    sessionStatus: String?,
    sessionSearch: String,
    selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
): DiagnosticsSessionsUiModel {
    val filteredSessions =
        sessionRows.filter { session ->
            (sessionPathMode == null || session.pathMode == sessionPathMode) &&
                (sessionStatus == null || session.status.equals(sessionStatus, ignoreCase = true)) &&
                session.matchesQuery(sessionSearch)
        }
    return DiagnosticsSessionsUiModel(
        filters =
            DiagnosticsSessionFiltersUiModel(
                pathMode = sessionPathMode,
                status = sessionStatus,
                query = sessionSearch,
            ),
        sessions = filteredSessions.toImmutableList(),
        pathModes = sessions.map { it.pathMode }.distinct().toImmutableList(),
        statuses = sessions.map { it.status }.distinct().toImmutableList(),
        focusedSessionId = selectedSessionDetail?.session?.id,
    )
}
