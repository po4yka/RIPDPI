package com.poyka.ripdpi.ui.screens.history

import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.compareSelectiveMatrixSessions
import com.poyka.ripdpi.diagnostics.toSelectiveMatrixLeg
import com.poyka.ripdpi.ui.screens.diagnostics.state.loadSessionDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class HistoryConnectionActions(
    private val scope: CoroutineScope,
    private val connectionFilters: MutableStateFlow<HistoryConnectionFilterState>,
    private val detailState: MutableStateFlow<HistoryDetailState>,
    private val loadConnectionDetail: suspend (String) -> DiagnosticConnectionDetail?,
) {
    fun setModeFilter(mode: String?) {
        connectionFilters.update { it.copy(modeFilter = toggleValue(it.modeFilter, mode)) }
    }

    fun setStatusFilter(status: String?) {
        connectionFilters.update { it.copy(statusFilter = toggleValue(it.statusFilter, status)) }
    }

    fun setSearch(query: String) {
        connectionFilters.update { it.copy(search = query) }
    }

    fun clearFilters() {
        connectionFilters.update { it.copy(modeFilter = null, statusFilter = null, search = "") }
    }

    // A detail still loading when the section changes or the detail is closed is stale: drop its result.
    private var detailRequest = 0

    fun selectConnection(sessionId: String) {
        val request = ++detailRequest
        scope.launch {
            val detail = loadConnectionDetail(sessionId)
            if (request != detailRequest) return@launch
            detailState.update { it.copy(selectedConnectionDetail = detail) }
        }
    }

    fun dismissDetail() {
        detailRequest++
        detailState.update { it.copy(selectedConnectionDetail = null) }
    }
}

internal class HistoryDiagnosticsActions(
    private val scope: CoroutineScope,
    private val diagnosticsFilters: MutableStateFlow<HistoryDiagnosticsFilterState>,
    private val detailState: MutableStateFlow<HistoryDetailState>,
    private val loadSessionDetail: suspend (String) -> DiagnosticSessionDetail?,
) {
    fun setPathModeFilter(pathMode: String?) {
        diagnosticsFilters.update { it.copy(pathModeFilter = toggleValue(it.pathModeFilter, pathMode)) }
    }

    fun setStatusFilter(status: String?) {
        diagnosticsFilters.update { it.copy(statusFilter = toggleValue(it.statusFilter, status)) }
    }

    fun setSearch(query: String) {
        diagnosticsFilters.update { it.copy(search = query) }
    }

    fun clearFilters() {
        diagnosticsFilters.update { it.copy(pathModeFilter = null, statusFilter = null, search = "") }
    }

    private var detailRequest = 0
    private var pairRequest = 0

    fun selectSession(sessionId: String) {
        val request = ++detailRequest
        pairRequest++
        scope.launch {
            val detail = loadSessionDetail(sessionId)
            if (request != detailRequest) return@launch
            detailState.update { it.copy(selectedDiagnosticsDetail = detail, matrixPair = null) }
        }
    }

    /** Compares the open session with [sessionId]; never runs a scan or switches networks. */
    fun compareMatrixSession(sessionId: String) {
        val current = detailState.value.selectedDiagnosticsDetail ?: return
        // A separate counter, so a compare never drops a selection that is still loading.
        val request = ++pairRequest
        scope.launch {
            val other = loadSessionDetail(sessionId) ?: return@launch
            val open =
                detailState.value.selectedDiagnosticsDetail
                    ?.session
                    ?.id
            if (request != pairRequest || open != current.session.id) return@launch
            val comparison =
                compareSelectiveMatrixSessions(
                    current.session.toSelectiveMatrixLeg(current.results),
                    other.session.toSelectiveMatrixLeg(other.results),
                )
            detailState.update { it.copy(matrixPair = HistoryMatrixPairState(sessionId, comparison)) }
        }
    }

    fun dismissDetail() {
        detailRequest++
        pairRequest++
        detailState.update { it.copy(selectedDiagnosticsDetail = null, matrixPair = null) }
    }
}

internal class HistoryEventActions(
    private val eventFilters: MutableStateFlow<HistoryEventFilterState>,
    private val detailState: MutableStateFlow<HistoryDetailState>,
) {
    fun toggleFilter(
        source: String? = null,
        severity: String? = null,
    ) {
        eventFilters.update {
            var updated = it
            if (source != null) {
                updated = updated.copy(sourceFilter = toggleValue(it.sourceFilter, source))
            }
            if (severity != null) {
                updated = updated.copy(severityFilter = toggleValue(it.severityFilter, severity))
            }
            updated
        }
    }

    fun setSearch(query: String) {
        eventFilters.update { it.copy(search = query) }
    }

    fun clearFilters() {
        eventFilters.update { it.copy(sourceFilter = null, severityFilter = null, search = "") }
    }

    fun setAutoScroll(enabled: Boolean) {
        eventFilters.update { it.copy(autoScroll = enabled) }
    }

    fun selectEvent(eventId: String) {
        detailState.update { it.copy(selectedEventId = eventId) }
    }

    fun dismissDetail() {
        detailState.update { it.copy(selectedEventId = null) }
    }
}

private fun toggleValue(
    current: String?,
    next: String?,
): String? = if (current == next) null else next
