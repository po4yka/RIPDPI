@file:Suppress("LongMethod")

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
                repositorySnapshot.connectionSessions.map(
                    connectionDetailUiFactory::toConnectionRowUiModel,
                )
            val filteredConnections =
                connectionRows.filter { session ->
                    (connectionFilters.modeFilter == null || session.serviceMode == connectionFilters.modeFilter) &&
                        (
                            connectionFilters.statusFilter == null ||
                                session.connectionState.equals(connectionFilters.statusFilter, ignoreCase = true)
                        ) &&
                        session.matchesQuery(connectionFilters.search)
                }

            val diagnosticsRows = repositorySnapshot.scanSessions.map(coreSupport::toSessionRowUiModel)
            val filteredDiagnostics =
                diagnosticsRows.filter { session ->
                    (
                        diagnosticsFilters.pathModeFilter == null ||
                            session.pathMode == diagnosticsFilters.pathModeFilter
                    ) &&
                        (
                            diagnosticsFilters.statusFilter == null ||
                                session.status.equals(diagnosticsFilters.statusFilter, ignoreCase = true)
                        ) &&
                        session.matchesQuery(diagnosticsFilters.search)
                }

            val eventModels = repositorySnapshot.nativeEvents.map(coreSupport::toEventUiModel)
            val filteredEvents =
                eventModels.filter { event ->
                    (
                        eventFilters.sourceFilter == null ||
                            event.source.equals(eventFilters.sourceFilter, ignoreCase = true)
                    ) &&
                        (
                            eventFilters.severityFilter == null ||
                                event.severity.equals(eventFilters.severityFilter, ignoreCase = true)
                        ) &&
                        event.matchesQuery(eventFilters.search)
                }

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
                selectedConnectionDetail = detailState.selectedConnectionDetail,
                selectedDiagnosticsDetail = detailState.selectedDiagnosticsDetail,
                selectedEvent = eventModels.firstOrNull { it.id == detailState.selectedEventId },
            )
        }
    }
