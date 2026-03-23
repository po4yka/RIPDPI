package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsNetworkSnapshotUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HistoryConnectionDetailUiModel
import com.poyka.ripdpi.activities.HistoryConnectionRowUiModel
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.activities.HistoryViewModel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectSection = viewModel::selectSection,
        onConnectionModeFilter = viewModel::setConnectionModeFilter,
        onConnectionStatusFilter = viewModel::setConnectionStatusFilter,
        onConnectionSearch = viewModel::setConnectionSearch,
        onSelectConnection = viewModel::selectConnection,
        onDismissConnectionDetail = viewModel::dismissConnectionDetail,
        onDiagnosticsPathFilter = viewModel::setDiagnosticsPathModeFilter,
        onDiagnosticsStatusFilter = viewModel::setDiagnosticsStatusFilter,
        onDiagnosticsSearch = viewModel::setDiagnosticsSearch,
        onSelectDiagnosticsSession = viewModel::selectDiagnosticsSession,
        onDismissDiagnosticsDetail = viewModel::dismissDiagnosticsDetail,
        onToggleEventFilter = viewModel::toggleEventFilter,
        onEventSearch = viewModel::setEventSearch,
        onEventAutoScroll = viewModel::setEventAutoScroll,
        onSelectEvent = viewModel::selectEvent,
        onDismissEventDetail = viewModel::dismissEventDetail,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(
    uiState: HistoryUiState,
    onBack: () -> Unit,
    onSelectSection: (HistorySection) -> Unit,
    onConnectionModeFilter: (String?) -> Unit,
    onConnectionStatusFilter: (String?) -> Unit,
    onConnectionSearch: (String) -> Unit,
    onSelectConnection: (String) -> Unit,
    onDismissConnectionDetail: () -> Unit,
    onDiagnosticsPathFilter: (String?) -> Unit,
    onDiagnosticsStatusFilter: (String?) -> Unit,
    onDiagnosticsSearch: (String) -> Unit,
    onSelectDiagnosticsSession: (String) -> Unit,
    onDismissDiagnosticsDetail: () -> Unit,
    onToggleEventFilter: (String?, String?) -> Unit,
    onEventSearch: (String) -> Unit,
    onEventAutoScroll: (Boolean) -> Unit,
    onSelectEvent: (String) -> Unit,
    onDismissEventDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.History))
                .fillMaxSize()
                .background(colors.background),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.history_title),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = layout.contentMaxWidth),
            ) {
                HistorySectionChips(
                    selectedSection = uiState.selectedSection,
                    onSelectSection = onSelectSection,
                )
                when (uiState.selectedSection) {
                    HistorySection.Connections -> {
                        ConnectionsSection(
                            uiState = uiState,
                            onModeFilter = onConnectionModeFilter,
                            onStatusFilter = onConnectionStatusFilter,
                            onSearch = onConnectionSearch,
                            onSelectConnection = onSelectConnection,
                        )
                    }

                    HistorySection.Diagnostics -> {
                        DiagnosticsSection(
                            uiState = uiState,
                            onPathFilter = onDiagnosticsPathFilter,
                            onStatusFilter = onDiagnosticsStatusFilter,
                            onSearch = onDiagnosticsSearch,
                            onSelectSession = onSelectDiagnosticsSession,
                        )
                    }

                    HistorySection.Events -> {
                        EventsSection(
                            uiState = uiState,
                            onToggleFilter = onToggleEventFilter,
                            onSearch = onEventSearch,
                            onAutoScroll = onEventAutoScroll,
                            onSelectEvent = onSelectEvent,
                        )
                    }
                }
            }
        }
    }

    uiState.selectedConnectionDetail?.let { detail ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissConnectionDetail,
            title = detail.session.title,
            message = detail.session.subtitle,
            icon = RipDpiIcons.Logs,
            testTag = RipDpiTestTags.HistoryConnectionDetailSheet,
        ) {
            StatusIndicator(
                label = detail.session.connectionState,
                tone = statusTone(detail.session.tone),
            )
            if (detail.highlights.isNotEmpty()) {
                MetricList(detail.highlights)
            }
            detail.contextGroups.forEach { group ->
                ContextGroupCard(group = group)
            }
            detail.snapshots.forEach { snapshot ->
                SnapshotCard(snapshot = snapshot)
            }
            if (detail.events.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.history_events_section),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                detail.events.forEach { event ->
                    EventRow(
                        event = event,
                        onClick = {},
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyEvent(event.id)),
                    )
                }
            }
        }
    }

    uiState.selectedDiagnosticsDetail?.let { detail ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissDiagnosticsDetail,
            title = detail.session.title,
            message = detail.session.subtitle,
            icon = RipDpiIcons.Search,
            testTag = RipDpiTestTags.HistoryDiagnosticsDetailSheet,
        ) {
            StatusIndicator(
                label = detail.session.status,
                tone = statusTone(detail.session.tone),
            )
            detail.contextGroups.forEach { group ->
                ContextGroupCard(group = group)
            }
            detail.probeGroups.forEach { group ->
                ProbeGroupCard(group = group)
            }
            detail.snapshots.forEach { snapshot ->
                SnapshotCard(snapshot = snapshot)
            }
            if (detail.events.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.history_events_section),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                detail.events.forEach { event ->
                    EventRow(
                        event = event,
                        onClick = {},
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyEvent(event.id)),
                    )
                }
            }
        }
    }

    uiState.selectedEvent?.let { event ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissEventDetail,
            title = event.source,
            message = event.createdAtLabel,
            icon = RipDpiIcons.Info,
            testTag = RipDpiTestTags.HistoryEventDetailSheet,
        ) {
            StatusIndicator(
                label = event.severity,
                tone = statusTone(event.tone),
            )
            Text(
                text = event.message,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
        }
    }
}

@Composable
private fun HistorySectionChips(
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
private fun ConnectionsSection(
    uiState: HistoryUiState,
    onModeFilter: (String?) -> Unit,
    onStatusFilter: (String?) -> Unit,
    onSearch: (String) -> Unit,
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
            )
        }

        if (uiState.connections.sessions.isEmpty()) {
            item {
                EmptyStateCard(
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
private fun DiagnosticsSection(
    uiState: HistoryUiState,
    onPathFilter: (String?) -> Unit,
    onStatusFilter: (String?) -> Unit,
    onSearch: (String) -> Unit,
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
            )
        }

        if (uiState.diagnostics.sessions.isEmpty()) {
            item {
                EmptyStateCard(
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
private fun EventsSection(
    uiState: HistoryUiState,
    onToggleFilter: (String?, String?) -> Unit,
    onSearch: (String) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onSelectEvent: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val listState = rememberLazyListState()
    val isAtLiveEdge by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24
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
                autoScroll = uiState.events.filters.autoScroll,
                onAutoScroll = onAutoScroll,
                autoScrollTestTag = RipDpiTestTags.HistoryEventsAutoScroll,
            )
        }

        if (uiState.events.events.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.history_events_empty_title),
                    body = stringResource(R.string.history_events_empty_body),
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryEventsStateEmpty),
                )
            }
        } else {
            items(uiState.events.events, key = { it.id }, contentType = { "event" }) { event ->
                EventRow(
                    event = event,
                    onClick = { onSelectEvent(event.id) },
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyEvent(event.id)),
                )
            }
        }
    }
}

@Composable
private fun FilterCard(
    modifier: Modifier = Modifier,
    title: String,
    searchValue: String,
    searchPlaceholder: String,
    onSearch: (String) -> Unit,
    searchTestTag: String,
    primaryFilter: HistoryFilterChipsConfig,
    secondaryFilter: HistoryFilterChipsConfig,
    autoScroll: Boolean? = null,
    onAutoScroll: ((Boolean) -> Unit)? = null,
    autoScrollTestTag: String? = null,
) {
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(modifier = modifier) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiTextField(
            value = searchValue,
            onValueChange = onSearch,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_search_label),
                    placeholder = searchPlaceholder,
                    testTag = searchTestTag,
                ),
        )
        if (autoScroll != null && onAutoScroll != null) {
            SettingsRow(
                title = stringResource(R.string.logs_auto_scroll_title),
                subtitle = stringResource(R.string.logs_auto_scroll_body),
                checked = autoScroll,
                onCheckedChange = onAutoScroll,
                showDivider = true,
                testTag = autoScrollTestTag,
            )
        }
        HistoryChips(
            config = primaryFilter,
        )
        if (secondaryFilter.options.isNotEmpty()) {
            HistoryChips(
                config = secondaryFilter,
            )
        }
    }
}

private data class HistoryFilterChipsConfig(
    val options: List<String>,
    val selected: String?,
    val onSelect: (String?) -> Unit,
    val tagForOption: (String) -> String,
)

@Composable
private fun HistoryChips(config: HistoryFilterChipsConfig) {
    val spacing = RipDpiThemeTokens.spacing
    if (config.options.isEmpty()) {
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        config.options.forEach { option ->
            RipDpiChip(
                text = option.replaceFirstChar { it.uppercase() },
                selected = config.selected == option,
                onClick = { config.onSelect(option) },
                modifier = Modifier.ripDpiTestTag(config.tagForOption(option)),
            )
        }
    }
}

@Composable
private fun ConnectionSessionCard(
    session: HistoryConnectionRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = session.connectionState,
            tone = statusTone(session.tone),
        )
        Text(
            text = session.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = session.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = session.summary,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
        MetricList(session.metrics)
    }
}

@Composable
private fun DiagnosticsSessionCard(
    session: DiagnosticsSessionRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = session.status,
            tone = statusTone(session.tone),
        )
        Text(
            text = session.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = session.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = session.summary,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
        MetricList(session.metrics)
    }
}

@Composable
private fun EventRow(
    event: DiagnosticsEventUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = event.severity,
            tone = statusTone(event.tone),
        )
        Text(
            text = "${event.source} · ${event.createdAtLabel}",
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = event.message,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
    }
}

@Composable
private fun ProbeGroupCard(group: DiagnosticsProbeGroupUiModel) {
    RipDpiCard {
        Text(
            text = group.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        group.items.forEach { probe ->
            SettingsRow(
                title = probe.target,
                subtitle = probe.probeType,
                value = probe.outcome,
                showDivider = probe != group.items.last(),
            )
        }
    }
}

@Composable
private fun ContextGroupCard(group: DiagnosticsContextGroupUiModel) {
    RipDpiCard {
        Text(
            text = group.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        group.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                showDivider = index != group.fields.lastIndex,
            )
        }
    }
}

@Composable
private fun SnapshotCard(snapshot: DiagnosticsNetworkSnapshotUiModel) {
    RipDpiCard {
        Text(
            text = snapshot.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = snapshot.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        snapshot.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                showDivider = index != snapshot.fields.lastIndex,
            )
        }
    }
}

@Composable
private fun MetricList(metrics: List<com.poyka.ripdpi.activities.DiagnosticsMetricUiModel>) {
    metrics.forEachIndexed { index, metric ->
        SettingsRow(
            title = metric.label,
            value = metric.value,
            showDivider = index != metrics.lastIndex,
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = body,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

private fun statusTone(tone: DiagnosticsTone): StatusIndicatorTone =
    when (tone) {
        DiagnosticsTone.Positive -> StatusIndicatorTone.Active
        DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
        DiagnosticsTone.Negative -> StatusIndicatorTone.Error
        DiagnosticsTone.Neutral, DiagnosticsTone.Info -> StatusIndicatorTone.Idle
    }
