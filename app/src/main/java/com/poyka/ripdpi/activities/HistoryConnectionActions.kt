package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

internal class HistoryConnectionActions(
    private val mutations: HistoryMutationRunner,
    private val historyRepository: DiagnosticsHistoryRepository,
    private val connectionFilters: MutableStateFlow<HistoryConnectionFilterState>,
    private val detailState: MutableStateFlow<HistoryDetailState>,
    private val toConnectionDetail: suspend (String) -> HistoryConnectionDetailUiModel?,
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

    fun selectConnection(sessionId: String) {
        mutations.launch {
            val detail = toConnectionDetail(sessionId)
            detailState.update { it.copy(selectedConnectionDetail = detail) }
        }
    }

    fun dismissDetail() {
        detailState.update { it.copy(selectedConnectionDetail = null) }
    }
}

internal class HistoryDiagnosticsActions(
    private val mutations: HistoryMutationRunner,
    private val diagnosticsFilters: MutableStateFlow<HistoryDiagnosticsFilterState>,
    private val detailState: MutableStateFlow<HistoryDetailState>,
    private val loadSessionDetail: suspend (String) -> DiagnosticsSessionDetailUiModel?,
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

    fun selectSession(sessionId: String) {
        mutations.launch {
            detailState.update { it.copy(selectedDiagnosticsDetail = loadSessionDetail(sessionId)) }
        }
    }

    fun dismissDetail() {
        detailState.update { it.copy(selectedDiagnosticsDetail = null) }
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
