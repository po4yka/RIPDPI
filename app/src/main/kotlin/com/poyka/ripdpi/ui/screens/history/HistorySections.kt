package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val liveEdgeScrollOffsetThreshold = 24

@Composable
internal fun HistorySectionChips(
    selectedSection: HistorySection,
    onSelectSection: (HistorySection) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        HistorySection.entries.forEach { section ->
            RipDpiChip(
                text =
                    when (section) {
                        HistorySection.Connections -> stringResource(R.string.history_connections_section)
                        HistorySection.Diagnostics -> stringResource(R.string.history_diagnostics_section)
                        HistorySection.Events -> stringResource(R.string.history_events_section)
                    },
                selected = selectedSection == section,
                onClick = { onSelectSection(section) },
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historySection(section)),
            )
        }
    }
}

@Composable
internal fun ConnectionsSection(
    uiState: HistoryUiState,
    onModeFilter: (String?) -> Unit,
    onStatusFilter: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSelectConnection: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    LazyColumn(
        contentPadding = PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            FilterCard(
                modifier =
                    Modifier.ripDpiTestTag(
                        if (uiState.connections.sessions.isEmpty()) {
                            RipDpiTestTags.HistoryConnectionsStateEmpty
                        } else {
                            RipDpiTestTags.HistoryConnectionsStateContent
                        },
                    ),
                title = stringResource(R.string.history_connections_section),
                searchValue = uiState.connections.filters.query,
                searchPlaceholder = stringResource(R.string.history_connection_search_placeholder),
                onSearch = onSearch,
                searchTestTag = RipDpiTestTags.HistoryConnectionsSearch,
                primaryFilter =
                    HistoryFilterChipsConfig(
                        options = uiState.connections.modes,
                        selected = uiState.connections.filters.mode,
                        onSelect = onModeFilter,
                        tagForOption = { RipDpiTestTags.historyConnectionsModeFilter(it) },
                    ),
                secondaryFilter =
                    HistoryFilterChipsConfig(
                        options = uiState.connections.statuses,
                        selected = uiState.connections.filters.status,
                        onSelect = onStatusFilter,
                        tagForOption = { RipDpiTestTags.historyConnectionsStatusFilter(it) },
                    ),
                onClearFilters = onClearFilters,
            )
        }

        if (uiState.connections.sessions.isEmpty()) {
            item {
                RipDpiEmptyStateCard(
                    title = stringResource(R.string.history_connections_empty_title),
                    body = stringResource(R.string.history_connections_empty_body),
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryConnectionsStateEmpty),
                )
            }
        } else {
            items(uiState.connections.sessions, key = { it.id }, contentType = { "connection_session" }) { session ->
                ConnectionSessionCard(
                    session = session,
                    onClick = { onSelectConnection(session.id) },
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyConnection(session.id)),
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticsSection(
    uiState: HistoryUiState,
    onPathFilter: (String?) -> Unit,
    onStatusFilter: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSelectSession: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    LazyColumn(
        contentPadding = PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            FilterCard(
                modifier =
                    Modifier.ripDpiTestTag(
                        if (uiState.diagnostics.sessions.isEmpty()) {
                            RipDpiTestTags.HistoryDiagnosticsStateEmpty
                        } else {
                            RipDpiTestTags.HistoryDiagnosticsStateContent
                        },
                    ),
                title = stringResource(R.string.history_diagnostics_section),
                searchValue = uiState.diagnostics.filters.query,
                searchPlaceholder = stringResource(R.string.diagnostics_search_placeholder),
                onSearch = onSearch,
                searchTestTag = RipDpiTestTags.HistoryDiagnosticsSearch,
                primaryFilter =
                    HistoryFilterChipsConfig(
                        options = uiState.diagnostics.pathModes,
                        selected = uiState.diagnostics.filters.pathMode,
                        onSelect = onPathFilter,
                        tagForOption = { RipDpiTestTags.historyDiagnosticsPathFilter(it) },
                    ),
                secondaryFilter =
                    HistoryFilterChipsConfig(
                        options = uiState.diagnostics.statuses,
                        selected = uiState.diagnostics.filters.status,
                        onSelect = onStatusFilter,
                        tagForOption = { RipDpiTestTags.historyDiagnosticsStatusFilter(it) },
                    ),
                onClearFilters = onClearFilters,
            )
        }

        if (uiState.diagnostics.sessions.isEmpty()) {
            item {
                RipDpiEmptyStateCard(
                    title = stringResource(R.string.history_diagnostics_empty_title),
                    body = stringResource(R.string.history_diagnostics_empty_body),
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryDiagnosticsStateEmpty),
                )
            }
        } else {
            items(uiState.diagnostics.sessions, key = { it.id }, contentType = { "diagnostics_session" }) { session ->
                DiagnosticsSessionCard(
                    session = session,
                    onClick = { onSelectSession(session.id) },
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyDiagnosticsSession(session.id)),
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
internal fun EventsSection(
    uiState: HistoryUiState,
    onToggleFilter: (String?, String?) -> Unit,
    onSearch: (String) -> Unit,
    onClearFilters: () -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onSelectEvent: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val listState = rememberLazyListState()
    val isAtLiveEdge by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= liveEdgeScrollOffsetThreshold
        }
    }

    LaunchedEffect(
        uiState.events.filters.autoScroll,
        uiState.events.events
            .firstOrNull()
            ?.id,
    ) {
        if (uiState.events.filters.autoScroll && uiState.events.events.isNotEmpty() && isAtLiveEdge) {
            if (motion.animationsEnabled) {
                listState.animateScrollToItem(0)
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            FilterCard(
                modifier =
                    Modifier.ripDpiTestTag(
                        if (uiState.events.events.isEmpty()) {
                            RipDpiTestTags.HistoryEventsStateEmpty
                        } else {
                            RipDpiTestTags.HistoryEventsStateContent
                        },
                    ),
                title = stringResource(R.string.history_events_section),
                searchValue = uiState.events.filters.search,
                searchPlaceholder = stringResource(R.string.diagnostics_events_search_placeholder),
                onSearch = onSearch,
                searchTestTag = RipDpiTestTags.HistoryEventsSearch,
                primaryFilter =
                    HistoryFilterChipsConfig(
                        options = uiState.events.availableSources,
                        selected = uiState.events.filters.source,
                        onSelect = { onToggleFilter(it, null) },
                        tagForOption = { RipDpiTestTags.historyEventSourceFilter(it) },
                    ),
                secondaryFilter =
                    HistoryFilterChipsConfig(
                        options = uiState.events.availableSeverities,
                        selected = uiState.events.filters.severity,
                        onSelect = { onToggleFilter(null, it) },
                        tagForOption = { RipDpiTestTags.historyEventSeverityFilter(it) },
                    ),
                onClearFilters = onClearFilters,
                autoScroll = uiState.events.filters.autoScroll,
                onAutoScroll = onAutoScroll,
                autoScrollTestTag = RipDpiTestTags.HistoryEventsAutoScroll,
            )
        }

        if (uiState.groupedEvents.isEmpty()) {
            item {
                RipDpiEmptyStateCard(
                    title = stringResource(R.string.history_events_empty_title),
                    body = stringResource(R.string.history_events_empty_body),
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryEventsStateEmpty),
                )
            }
        } else {
            items(
                uiState.groupedEvents,
                key = { it.representative.id },
                contentType = { "event" },
            ) { grouped ->
                EventRow(
                    event = grouped.representative,
                    occurrenceCount = grouped.count,
                    lastTimestampLabel = grouped.lastTimestampLabel,
                    onClick = { onSelectEvent(grouped.representative.id) },
                    modifier =
                        Modifier.ripDpiTestTag(
                            RipDpiTestTags.historyEvent(grouped.representative.id),
                        ),
                )
            }
        }
    }
}
