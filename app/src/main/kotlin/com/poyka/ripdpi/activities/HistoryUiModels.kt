package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
    val metrics: ImmutableList<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

@Immutable
data class HistoryConnectionDetailUiModel(
    val session: HistoryConnectionRowUiModel,
    val highlights: ImmutableList<DiagnosticsMetricUiModel>,
    val contextGroups: ImmutableList<DiagnosticsContextGroupUiModel>,
    val snapshots: ImmutableList<DiagnosticsNetworkSnapshotUiModel>,
    val events: ImmutableList<DiagnosticsEventUiModel>,
)

@Immutable
data class HistoryConnectionsUiModel(
    val filters: HistoryConnectionFiltersUiModel = HistoryConnectionFiltersUiModel(),
    val sessions: ImmutableList<HistoryConnectionRowUiModel> = persistentListOf(),
    val modes: ImmutableList<String> = persistentListOf(),
    val statuses: ImmutableList<String> = persistentListOf(),
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
    val groupedEvents: ImmutableList<GroupedEventUiModel> = persistentListOf(),
    val selectedConnectionDetail: HistoryConnectionDetailUiModel? = null,
    val selectedDiagnosticsDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedEvent: DiagnosticsEventUiModel? = null,
    val isRefreshing: Boolean = false,
)
