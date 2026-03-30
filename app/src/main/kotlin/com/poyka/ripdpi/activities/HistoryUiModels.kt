package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable

enum class HistorySection {
    Connections,
    Diagnostics,
    Events,
}

@Immutable
data class HistoryConnectionFiltersUiModel(
    val mode: String? = null,
    val status: String? = null,
    val query: String = "",
)

@Immutable
data class HistoryConnectionRowUiModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val serviceMode: String,
    val connectionState: String,
    val networkType: String,
    val startedAtLabel: String,
    val summary: String,
    val rememberedPolicyBadge: String? = null,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

@Immutable
data class HistoryConnectionDetailUiModel(
    val session: HistoryConnectionRowUiModel,
    val highlights: List<DiagnosticsMetricUiModel>,
    val contextGroups: List<DiagnosticsContextGroupUiModel>,
    val snapshots: List<DiagnosticsNetworkSnapshotUiModel>,
    val events: List<DiagnosticsEventUiModel>,
)

@Immutable
data class HistoryConnectionsUiModel(
    val filters: HistoryConnectionFiltersUiModel = HistoryConnectionFiltersUiModel(),
    val sessions: List<HistoryConnectionRowUiModel> = emptyList(),
    val modes: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val focusedSessionId: String? = null,
)

@Immutable
data class GroupedEventUiModel(
    val representative: DiagnosticsEventUiModel,
    val count: Int,
    val lastTimestampLabel: String?,
)

@Immutable
data class HistoryUiState(
    val selectedSection: HistorySection = HistorySection.Connections,
    val connections: HistoryConnectionsUiModel = HistoryConnectionsUiModel(),
    val diagnostics: DiagnosticsSessionsUiModel = DiagnosticsSessionsUiModel(),
    val events: DiagnosticsEventsUiModel = DiagnosticsEventsUiModel(),
    val groupedEvents: List<GroupedEventUiModel> = emptyList(),
    val selectedConnectionDetail: HistoryConnectionDetailUiModel? = null,
    val selectedDiagnosticsDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedEvent: DiagnosticsEventUiModel? = null,
    val isRefreshing: Boolean = false,
)
