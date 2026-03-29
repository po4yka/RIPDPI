package com.poyka.ripdpi.activities

import javax.inject.Inject

internal class HistoryUiStateFactory
    @Inject
    constructor(
        private val coreSupport: DiagnosticsUiCoreSupport,
        private val connectionDetailUiFactory: HistoryConnectionDetailUiFactory,
    ) {
        fun buildUiState(
            repositorySnapshot: HistoryRepositorySnapshot,
            selectedSection: HistorySection,
            connectionFilters: HistoryConnectionFilterState,
            diagnosticsFilters: HistoryDiagnosticsFilterState,
            eventFilters: HistoryEventFilterState,
            detailState: HistoryDetailState,
        ): HistoryUiState {
            val connectionRows =
                repositorySnapshot.connectionSessions.map(connectionDetailUiFactory::toConnectionRowUiModel)
            val filteredConnections = connectionRows.filter { it.matches(connectionFilters) }
            val diagnosticsRows = repositorySnapshot.scanSessions.map(coreSupport::toSessionRowUiModel)
            val filteredDiagnostics = diagnosticsRows.filter { it.matches(diagnosticsFilters) }
            val eventModels = repositorySnapshot.nativeEvents.map(coreSupport::toEventUiModel)
            val filteredEvents = eventModels.filter { it.matches(eventFilters) }

            val groupedEvents = groupConsecutiveEvents(filteredEvents)

            return HistoryUiState(
                selectedSection = selectedSection,
                connections =
                    HistoryConnectionsUiModel(
                        filters =
                            HistoryConnectionFiltersUiModel(
                                mode = connectionFilters.modeFilter,
                                status = connectionFilters.statusFilter,
                                query = connectionFilters.search,
                            ),
                        sessions = filteredConnections,
                        modes = connectionRows.map { it.serviceMode }.distinct(),
                        statuses = connectionRows.map { it.connectionState }.distinct(),
                        focusedSessionId = detailState.selectedConnectionDetail?.session?.id,
                    ),
                diagnostics =
                    DiagnosticsSessionsUiModel(
                        filters =
                            DiagnosticsSessionFiltersUiModel(
                                pathMode = diagnosticsFilters.pathModeFilter,
                                status = diagnosticsFilters.statusFilter,
                                query = diagnosticsFilters.search,
                            ),
                        sessions = filteredDiagnostics,
                        pathModes = diagnosticsRows.map { it.pathMode }.distinct(),
                        statuses = diagnosticsRows.map { it.status }.distinct(),
                        focusedSessionId = detailState.selectedDiagnosticsDetail?.session?.id,
                    ),
                events =
                    DiagnosticsEventsUiModel(
                        filters =
                            DiagnosticsEventFiltersUiModel(
                                source = eventFilters.sourceFilter,
                                severity = eventFilters.severityFilter,
                                search = eventFilters.search,
                                autoScroll = eventFilters.autoScroll,
                            ),
                        events = filteredEvents,
                        availableSources = eventModels.map { it.source }.distinct(),
                        availableSeverities = eventModels.map { it.severity }.distinct(),
                        focusedEventId = detailState.selectedEventId,
                    ),
                groupedEvents = groupedEvents,
                selectedConnectionDetail = detailState.selectedConnectionDetail,
                selectedDiagnosticsDetail = detailState.selectedDiagnosticsDetail,
                selectedEvent = eventModels.firstOrNull { it.id == detailState.selectedEventId },
            )
        }
    }

private fun HistoryConnectionRowUiModel.matches(filters: HistoryConnectionFilterState): Boolean =
    (filters.modeFilter == null || serviceMode == filters.modeFilter) &&
        (filters.statusFilter == null || connectionState.equals(filters.statusFilter, ignoreCase = true)) &&
        matchesQuery(filters.search)

private fun DiagnosticsSessionRowUiModel.matches(filters: HistoryDiagnosticsFilterState): Boolean =
    (filters.pathModeFilter == null || pathMode == filters.pathModeFilter) &&
        (filters.statusFilter == null || status.equals(filters.statusFilter, ignoreCase = true)) &&
        matchesQuery(filters.search)

private fun DiagnosticsEventUiModel.matches(filters: HistoryEventFilterState): Boolean =
    (filters.sourceFilter == null || source.equals(filters.sourceFilter, ignoreCase = true)) &&
        (filters.severityFilter == null || severity.equals(filters.severityFilter, ignoreCase = true)) &&
        matchesQuery(filters.search)

/**
 * Collapses consecutive events that share the same severity, source, and message
 * into [GroupedEventUiModel] entries. Single occurrences get count = 1 and no
 * last-timestamp label.
 */
internal fun groupConsecutiveEvents(events: List<DiagnosticsEventUiModel>): List<GroupedEventUiModel> {
    if (events.isEmpty()) return emptyList()

    val result = mutableListOf<GroupedEventUiModel>()
    var currentFirst = events.first()
    var currentLast = currentFirst
    var count = 1

    for (i in 1 until events.size) {
        val event = events[i]
        if (event.severity == currentFirst.severity &&
            event.source == currentFirst.source &&
            event.message == currentFirst.message
        ) {
            count++
            currentLast = event
        } else {
            result +=
                GroupedEventUiModel(
                    representative = currentFirst,
                    count = count,
                    lastTimestampLabel =
                        if (count > 1 && currentLast.createdAtLabel != currentFirst.createdAtLabel) {
                            currentLast.createdAtLabel
                        } else {
                            null
                        },
                )
            currentFirst = event
            currentLast = event
            count = 1
        }
    }
    result +=
        GroupedEventUiModel(
            representative = currentFirst,
            count = count,
            lastTimestampLabel =
                if (count > 1 && currentLast.createdAtLabel != currentFirst.createdAtLabel) {
                    currentLast.createdAtLabel
                } else {
                    null
                },
        )
    return result
}
