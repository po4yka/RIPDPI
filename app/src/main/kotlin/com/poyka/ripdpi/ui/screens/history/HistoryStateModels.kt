package com.poyka.ripdpi.ui.screens.history

import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.SelectiveMatrixComparison

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
    val selectedConnectionDetail: DiagnosticConnectionDetail? = null,
    val selectedDiagnosticsDetail: DiagnosticSessionDetail? = null,
    /** The selected diagnostics session compared with a second selective matrix session. */
    val matrixPair: HistoryMatrixPairState? = null,
    val selectedEventId: String? = null,
)

internal data class HistoryRepositorySnapshot(
    val connectionSessions: List<DiagnosticConnectionSession> = emptyList(),
    val scanSessions: List<DiagnosticScanSession> = emptyList(),
    val nativeEvents: List<DiagnosticEvent> = emptyList(),
)

internal data class HistoryMatrixPairState(
    val pairedSessionId: String,
    val comparison: SelectiveMatrixComparison,
)
