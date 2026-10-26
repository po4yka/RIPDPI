package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.inputs.tabOverflowFade
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.launch

private const val liveEdgeScrollOffsetThreshold = 24

private enum class EventFollowIntent {
    None,
    Auto,
    Jump,
}

@Composable
internal fun HistorySectionChips(
    selectedSection: HistorySection,
    onSelectSection: (HistorySection) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val selectedIndex = HistorySection.entries.indexOf(selectedSection)
    val scrollState = rememberScrollState()
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.sm)
                // At large font the last tab is cut; the fade shows that the row scrolls.
                .tabOverflowFade(scrollState, colors.background, spacing.xxl),
        scrollState = scrollState,
        containerColor = colors.background,
        contentColor = colors.foreground,
        edgePadding = spacing.sm,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
                width = Dp.Unspecified,
                color = colors.foreground,
            )
        },
        divider = { HorizontalDivider(color = colors.divider) },
    ) {
        HistorySection.entries.forEach { section ->
            Tab(
                selected = selectedSection == section,
                onClick = { onSelectSection(section) },
                modifier = Modifier.heightIn(min = 48.dp).ripDpiTestTag(RipDpiTestTags.historySection(section)),
                selectedContentColor = colors.foreground,
                unselectedContentColor = colors.mutedForeground,
            ) {
                Text(
                    text =
                        when (section) {
                            HistorySection.Connections -> stringResource(R.string.history_connections_section)
                            HistorySection.Diagnostics -> stringResource(R.string.history_diagnostics_section)
                            HistorySection.Events -> stringResource(R.string.history_events_section)
                        },
                    modifier =
                        Modifier
                            .width(
                                IntrinsicSize.Max,
                            ).padding(horizontal = spacing.lg, vertical = spacing.sm),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    maxLines = 1,
                    softWrap = false,
                )
            }
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
    onOpenConnection: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    LazyColumn(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryConnectionsList),
        contentPadding = PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (uiState.hasConnectionHistory) {
            item {
                FilterCard(
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryConnectionsStateContent),
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
        }

        if (!uiState.hasConnectionHistory) {
            item {
                HistoryEmptyStateCard(
                    title = stringResource(R.string.history_connections_empty_title),
                    body = stringResource(R.string.history_connections_empty_body),
                    actionLabel = stringResource(R.string.vpn_connect),
                    actionTestTag = RipDpiTestTags.HistoryEmptyOpenConnection,
                    onAction = onOpenConnection,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryConnectionsStateEmpty),
                )
            }
        } else if (uiState.connections.sessions.isEmpty()) {
            item { HistoryNoResultsCard(onClearFilters = onClearFilters) }
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
    onOpenDiagnostics: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    LazyColumn(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryDiagnosticsList),
        contentPadding = PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (uiState.hasDiagnosticsHistory) {
            item {
                FilterCard(
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryDiagnosticsStateContent),
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
        }

        if (!uiState.hasDiagnosticsHistory) {
            item {
                HistoryEmptyStateCard(
                    title = stringResource(R.string.history_diagnostics_empty_title),
                    body = stringResource(R.string.history_diagnostics_empty_body),
                    actionLabel = stringResource(R.string.diagnostics_simple_funnel_check_action),
                    actionTestTag = RipDpiTestTags.HistoryEmptyOpenDiagnostics,
                    onAction = onOpenDiagnostics,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryDiagnosticsStateEmpty),
                )
            }
        } else if (uiState.diagnostics.sessions.isEmpty()) {
            item { HistoryNoResultsCard(onClearFilters = onClearFilters) }
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

@Composable
internal fun EventsSection(
    uiState: HistoryUiState,
    onToggleFilter: (String?, String?) -> Unit,
    onSearch: (String) -> Unit,
    onClearFilters: () -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onSelectEvent: (String) -> Unit,
    onOpenConnection: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latestEventId =
        uiState.groupedEvents
            .firstOrNull()
            ?.representative
            ?.id
    var previousLatestEventId by remember { mutableStateOf<String?>(null) }
    var arrivalTracker by rememberSaveable(stateSaver = EventArrivalTrackerSaver) {
        mutableStateOf(EventArrivalTracker())
    }
    // Keep the request across replacement scrolls until it finishes or the reader scrolls.
    var followIntent by remember { mutableStateOf(EventFollowIntent.None) }
    val userScrollConnection = rememberEventUserScrollConnection { followIntent = EventFollowIntent.None }
    val isAtLiveEdge by rememberEventLiveEdge(listState)

    LaunchedEffect(uiState.events.filters, latestEventId, uiState.eventTimeline) {
        val firstVisibleKey =
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == listState.firstVisibleItemIndex }
                ?.key
        val wasFollowingLatest =
            previousLatestEventId != null && firstVisibleKey == previousLatestEventId &&
                listState.firstVisibleItemScrollOffset <= liveEdgeScrollOffsetThreshold
        val shouldFollow =
            followIntent == EventFollowIntent.Jump ||
                (
                    uiState.events.filters.autoScroll &&
                        (isAtLiveEdge || wasFollowingLatest || followIntent == EventFollowIntent.Auto)
                )
        arrivalTracker =
            arrivalTracker.observe(uiState.eventTimeline, uiState.events.filters, isAtLiveEdge)
        previousLatestEventId = latestEventId
        if (latestEventId != null && shouldFollow) {
            val targetIndex =
                if (followIntent == EventFollowIntent.Jump || listState.firstVisibleItemIndex > 0) 1 else 0
            if (followIntent == EventFollowIntent.None) followIntent = EventFollowIntent.Auto
            listState.scrollToEvent(targetIndex, motion)
            arrivalTracker = arrivalTracker.clearPending()
            followIntent = EventFollowIntent.None
        } else {
            followIntent = EventFollowIntent.None
        }
    }

    LaunchedEffect(isAtLiveEdge) {
        if (isAtLiveEdge) arrivalTracker = arrivalTracker.clearPending()
    }

    Column(Modifier.fillMaxSize()) {
        val listModifier = Modifier.weight(1f).nestedScroll(userScrollConnection)
        LazyColumn(
            modifier = listModifier.ripDpiTestTag(RipDpiTestTags.HistoryEventsList),
            state = listState,
            contentPadding = PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (uiState.hasEventHistory) {
                item(key = "events_filters", contentType = "filters") {
                    HistoryEventsFilterCard(uiState, onToggleFilter, onSearch, onClearFilters, onAutoScroll)
                }
            }
            historyEventRows(uiState, onClearFilters, onSelectEvent, onOpenConnection)
        }
        if (latestEventId != null && !isAtLiveEdge) {
            HistoryLatestEventsButton(
                pendingCount = arrivalTracker.pendingCount,
                onClick = {
                    scope.launch {
                        followIntent = EventFollowIntent.Jump
                        listState.scrollToEvent(1, motion)
                        arrivalTracker = arrivalTracker.clearPending()
                        followIntent = EventFollowIntent.None
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberEventLiveEdge(listState: LazyListState): State<Boolean> =
    remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 1 &&
                listState.firstVisibleItemScrollOffset <= liveEdgeScrollOffsetThreshold
        }
    }

@Composable
private fun HistoryLatestEventsButton(
    pendingCount: Int,
    onClick: () -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    RipDpiButton(
        text =
            if (pendingCount > 0) {
                stringResource(R.string.history_events_jump_to_latest_count, pendingCount)
            } else {
                stringResource(R.string.history_events_jump_to_latest)
            },
        onClick = onClick,
        variant = RipDpiButtonVariant.Secondary,
        modifier =
            Modifier
                .padding(horizontal = layout.horizontalPadding, vertical = spacing.sm)
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.HistoryEventsJumpToLatest),
    )
}

@Composable
private fun rememberEventUserScrollConnection(onUserScroll: () -> Unit): NestedScrollConnection {
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available != Offset.Zero) currentOnUserScroll()
                return Offset.Zero
            }
        }
    }
}

private suspend fun LazyListState.scrollToEvent(
    index: Int,
    motion: RipDpiMotion,
) {
    if (motion.animationsEnabled && !motion.reducedMotion) {
        animateScrollToItem(index)
    } else {
        scrollToItem(index)
    }
}

private fun LazyListScope.historyEventRows(
    uiState: HistoryUiState,
    onClearFilters: () -> Unit,
    onSelectEvent: (String) -> Unit,
    onOpenConnection: () -> Unit,
) {
    if (!uiState.hasEventHistory) {
        item {
            HistoryEmptyStateCard(
                title = stringResource(R.string.history_events_empty_title),
                body = stringResource(R.string.history_events_empty_body),
                actionLabel = stringResource(R.string.vpn_connect),
                actionTestTag = RipDpiTestTags.HistoryEmptyOpenConnection,
                onAction = onOpenConnection,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryEventsStateEmpty),
            )
        }
    } else if (uiState.groupedEvents.isEmpty()) {
        item { HistoryNoResultsCard(onClearFilters = onClearFilters) }
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
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyEvent(grouped.representative.id)),
            )
        }
    }
}

@Composable
private fun HistoryEventsFilterCard(
    uiState: HistoryUiState,
    onToggleFilter: (String?, String?) -> Unit,
    onSearch: (String) -> Unit,
    onClearFilters: () -> Unit,
    onAutoScroll: (Boolean) -> Unit,
) {
    FilterCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryEventsStateContent),
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
