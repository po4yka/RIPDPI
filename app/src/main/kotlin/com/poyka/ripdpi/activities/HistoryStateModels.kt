package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession

internal data class HistoryConnectionFilterState(
    val modeFilter: String? = null,
    val statusFilter: String? = null,
    val search: String = "",
)

internal data class HistoryDiagnosticsFilterState(
    val pathModeFilter: String? = null,
    val statusFilter: String? = null,
    val search: String = "",
)

internal data class HistoryEventFilterState(
    val sourceFilter: String? = null,
    val severityFilter: String? = null,
    val search: String = "",
    val autoScroll: Boolean = true,
)

internal data class HistoryDetailState(
    val selectedConnectionDetail: HistoryConnectionDetailUiModel? = null,
    val selectedDiagnosticsDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedEventId: String? = null,
)

internal data class HistoryRepositorySnapshot(
    val connectionSessions: List<DiagnosticConnectionSession> = emptyList(),
    val scanSessions: List<DiagnosticScanSession> = emptyList(),
    val nativeEvents: List<DiagnosticEvent> = emptyList(),
)
