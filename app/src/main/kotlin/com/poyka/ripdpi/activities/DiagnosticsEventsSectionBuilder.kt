package com.poyka.ripdpi.activities

import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.buildEventsUiModel(
    eventModels: List<DiagnosticsEventUiModel>,
    selectedEventId: String?,
    eventSource: String?,
    eventSeverity: String?,
    eventSearch: String,
    eventAutoScroll: Boolean,
): Pair<DiagnosticsEventsUiModel, DiagnosticsEventUiModel?> {
    val filteredEvents =
        eventModels.filter { event ->
            (eventSource == null || event.source.equals(eventSource, ignoreCase = true)) &&
                (eventSeverity == null || event.severity.equals(eventSeverity, ignoreCase = true)) &&
                event.matchesQuery(eventSearch)
        }
    val selectedEvent = filteredEvents.firstOrNull { it.id == selectedEventId }
    return DiagnosticsEventsUiModel(
        filters =
            DiagnosticsEventFiltersUiModel(
                source = eventSource,
                severity = eventSeverity,
                search = eventSearch,
                autoScroll = eventAutoScroll,
            ),
        events = filteredEvents.toImmutableList(),
        availableSources = eventModels.map { it.source }.distinct().toImmutableList(),
        availableSeverities = eventModels.map { it.severity }.distinct().toImmutableList(),
        focusedEventId = selectedEvent?.id,
    ) to selectedEvent
}
