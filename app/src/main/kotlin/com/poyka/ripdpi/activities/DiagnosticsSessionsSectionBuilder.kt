package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.buildSessionsUiModel(
    sessions: List<DiagnosticScanSession>,
    sessionRows: List<DiagnosticsSessionRowUiModel>,
    sessionSearch: String,
    selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
): DiagnosticsSessionsUiModel {
    val filteredSessions = sessionRows.filter { session -> session.matchesQuery(sessionSearch) }
    return DiagnosticsSessionsUiModel(
        filters = DiagnosticsSessionFiltersUiModel(query = sessionSearch),
        sessions = filteredSessions.toImmutableList(),
        pathModes = sessions.map { it.pathMode }.distinct().toImmutableList(),
        statuses = sessions.map { it.status }.distinct().toImmutableList(),
        focusedSessionId = selectedSessionDetail?.session?.id,
    )
}
