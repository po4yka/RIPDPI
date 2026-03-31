package com.poyka.ripdpi.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogsUiState
import com.poyka.ripdpi.activities.LogsViewModel
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

@Composable
fun LogsRoute(
    onSaveLogs: () -> Unit,
    onShareSupportBundle: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LogsScreen(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onToggleSubsystemFilter = viewModel::toggleSubsystemFilter,
        onToggleSeverityFilter = viewModel::toggleSeverityFilter,
        onAutoScrollChanged = viewModel::setAutoScroll,
        onActiveSessionOnlyChanged = viewModel::setActiveSessionOnly,
        onClearLogs = viewModel::clearLogs,
        onSaveLogs = onSaveLogs,
        onShareSupportBundle = onShareSupportBundle,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LogsScreen(
    uiState: LogsUiState,
    onRefresh: () -> Unit,
    onToggleSubsystemFilter: (LogSubsystem) -> Unit,
    onToggleSeverityFilter: (LogSeverity) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
    onActiveSessionOnlyChanged: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareSupportBundle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val filteredLogs = uiState.filteredLogs
    val listState = rememberLazyListState()
    val clipboardManager = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val performHaptic = rememberRipDpiHapticPerformer()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val isAtLiveEdge by remember(listState) {
        derivedStateOf {
            val lastVisibleItemIndex =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index
                    ?: return@derivedStateOf true
            lastVisibleItemIndex >= (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)
        }
    }

    LaunchedEffect(uiState.isAutoScroll, uiState.latestLog?.id, filteredLogs.size) {
        val latestLog = uiState.latestLog ?: return@LaunchedEffect
        if (
            uiState.isAutoScroll &&
            latestLog in filteredLogs &&
            filteredLogs.isNotEmpty() &&
            isAtLiveEdge
        ) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.LogsScreen)
                .fillMaxSize()
                .background(colors.background),
        snackbarHost = { RipDpiSnackbarHost(snackbarHostState) },
        topBar = {
            RipDpiTopAppBar(title = stringResource(R.string.logs))
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .widthIn(max = layout.contentMaxWidth)
                            .padding(
                                start = layout.horizontalPadding,
                                top = spacing.sm,
                                end = layout.horizontalPadding,
                                bottom = spacing.sm,
                            ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    LogsOverviewCard(
                        uiState = uiState,
                        onSaveLogs = onSaveLogs,
                        onShareSupportBundle = onShareSupportBundle,
                        onClearLogs = onClearLogs,
                    )

                    LogsFiltersSection(
                        uiState = uiState,
                        onToggleSubsystemFilter = onToggleSubsystemFilter,
                        onToggleSeverityFilter = onToggleSeverityFilter,
                        onAutoScrollChanged = onAutoScrollChanged,
                        onActiveSessionOnlyChanged = onActiveSessionOnlyChanged,
                    )

                    SettingsCategoryHeader(title = stringResource(R.string.logs_stream_section))

                    if (filteredLogs.isEmpty()) {
                        LogsEmptyStateCard(
                            hasBufferedLogs = uiState.logs.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LogsStreamCard(
                            entries = filteredLogs,
                            listState = listState,
                            onCopyEntry = { entry ->
                                clipboardManager?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "log",
                                        "${entry.timestamp} [${entry.subsystem.name}] ${entry.message}",
                                    ),
                                )
                                performHaptic(RipDpiHapticFeedback.Acknowledge)
                                scope.launch {
                                    snackbarHostState.showRipDpiSnackbar(
                                        message = copiedMessage,
                                        tone = RipDpiSnackbarTone.Default,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsFiltersSection(
    uiState: LogsUiState,
    onToggleSubsystemFilter: (LogSubsystem) -> Unit,
    onToggleSeverityFilter: (LogSeverity) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
    onActiveSessionOnlyChanged: (Boolean) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.logs_filters_section))
        RipDpiCard {
            SettingsRow(
                title = stringResource(R.string.logs_auto_scroll_title),
                subtitle = stringResource(R.string.logs_auto_scroll_body),
                checked = uiState.isAutoScroll,
                onCheckedChange = onAutoScrollChanged,
                showDivider = true,
                testTag = RipDpiTestTags.LogsAutoScroll,
            )
            SettingsRow(
                title = stringResource(R.string.logs_active_session_title),
                subtitle = stringResource(R.string.logs_active_session_body),
                checked = uiState.showActiveSessionOnly,
                onCheckedChange = onActiveSessionOnlyChanged,
                showDivider = true,
            )
            Text(
                text = stringResource(R.string.logs_filters_helper),
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
            FilterChipRow(
                title = stringResource(R.string.logs_subsystems_title),
                items = LogSubsystem.entries,
                isSelected = { subsystem -> subsystem in uiState.activeSubsystems },
                label = ::subsystemLabel,
                testTag = { subsystem -> RipDpiTestTags.logsSubsystemFilter(subsystem) },
                onClick = onToggleSubsystemFilter,
            )
            FilterChipRow(
                title = stringResource(R.string.logs_severity_title),
                items = LogSeverity.entries,
                isSelected = { severity -> severity in uiState.activeSeverities },
                label = ::severityLabel,
                testTag = { severity -> RipDpiTestTags.logsSeverityFilter(severity) },
                onClick = onToggleSeverityFilter,
            )
        }
    }
}

@Composable
private fun <T> FilterChipRow(
    title: String,
    items: List<T>,
    isSelected: (T) -> Boolean,
    label: @Composable (T) -> String,
    testTag: (T) -> String,
    onClick: (T) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items.forEach { item ->
                RipDpiChip(
                    text = label(item),
                    selected = isSelected(item),
                    onClick = { onClick(item) },
                    modifier = Modifier.ripDpiTestTag(testTag(item)),
                )
            }
        }
    }
}

@Composable
private fun LogsOverviewCard(
    uiState: LogsUiState,
    onSaveLogs: () -> Unit,
    onShareSupportBundle: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val latestLog = uiState.filteredLogs.lastOrNull() ?: uiState.latestLog

    RipDpiCard(
        variant = if (uiState.logs.isEmpty()) RipDpiCardVariant.Outlined else RipDpiCardVariant.Elevated,
    ) {
        Text(
            text = stringResource(R.string.logs_overview_section),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
        )
        StatusIndicator(
            label =
                stringResource(
                    if (uiState.logs.isEmpty()) {
                        R.string.logs_status_empty
                    } else {
                        R.string.logs_status_live
                    },
                ),
            tone =
                if (uiState.logs.isEmpty()) {
                    StatusIndicatorTone.Idle
                } else {
                    StatusIndicatorTone.Active
                },
        )
        Text(
            text = stringResource(R.string.logs_overview_title),
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.logs_overview_body),
            style = RipDpiThemeTokens.type.body,
            color = colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.settings_support_debug_bundle_title),
                onClick = onShareSupportBundle,
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Primary,
            )
            RipDpiButton(
                text = stringResource(R.string.save_logs),
                onClick = onSaveLogs,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.LogsSave),
                variant = RipDpiButtonVariant.Outline,
            )
        }
        RipDpiButton(
            text = stringResource(R.string.logs_clear),
            onClick = onClearLogs,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.LogsClear),
            variant = RipDpiButtonVariant.Destructive,
            enabled = uiState.logs.isNotEmpty(),
        )
        HorizontalDivider(color = colors.divider)
        SettingsRow(
            title = stringResource(R.string.logs_buffer_title),
            subtitle =
                stringResource(
                    R.string.logs_filters_summary_compact,
                    uiState.activeSubsystems.size,
                    uiState.activeSeverities.size,
                ),
            value =
                stringResource(
                    R.string.logs_buffer_value,
                    uiState.filteredLogs.size,
                    uiState.bufferCapacity,
                ),
            monospaceValue = true,
            showDivider = true,
        )
        SettingsRow(
            title = stringResource(R.string.logs_latest_title),
            subtitle = latestLog?.message ?: stringResource(R.string.logs_latest_empty),
            value = latestLog?.timestamp ?: stringResource(R.string.logs_latest_none),
            monospaceValue = latestLog != null,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogsStreamCard(
    entries: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCopyEntry: (LogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.LogsStream),
        paddingValues =
            PaddingValues(
                horizontal = layout.cardPadding,
                vertical = spacing.md,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.logs_stream_title),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.logs_entries_count, entries.size),
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            state = listState,
        ) {
            itemsIndexed(
                items = entries,
                key = { _, entry -> entry.id },
            ) { index, entry ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { onCopyEntry(entry) },
                            ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    LogRow(
                        timestamp = entry.timestamp,
                        type = subsystemLabel(entry.subsystem),
                        message = entry.message,
                        tone = logRowTone(entry),
                        metadataChips = metadataChips(entry),
                    )
                    if (index < entries.lastIndex) {
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
        }
    }
}

private fun metadataChips(entry: LogEntry): List<String> =
    buildList {
        add(entry.source.lowercase())
        add(severityLabelValue(entry.severity))
        entry.runtimeId?.let { add("runtime:$it") }
        entry.diagnosticsSessionId?.let { add("scan:$it") }
        if (entry.isActiveSession) {
            add("active")
        }
    }

@Composable
private fun LogsEmptyStateCard(
    hasBufferedLogs: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors

    RipDpiCard(modifier = modifier) {
        Text(
            text =
                stringResource(
                    if (hasBufferedLogs) {
                        R.string.logs_filtered_empty_title
                    } else {
                        R.string.logs_empty_title
                    },
                ),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text =
                stringResource(
                    if (hasBufferedLogs) {
                        R.string.logs_filtered_empty_body
                    } else {
                        R.string.logs_empty_body
                    },
                ),
            style = RipDpiThemeTokens.type.body,
            color = colors.mutedForeground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun subsystemLabel(subsystem: LogSubsystem): String =
    stringResource(
        when (subsystem) {
            LogSubsystem.Service -> R.string.logs_subsystem_service
            LogSubsystem.Proxy -> R.string.logs_subsystem_proxy
            LogSubsystem.Tunnel -> R.string.logs_subsystem_tunnel
            LogSubsystem.Diagnostics -> R.string.logs_subsystem_diagnostics
        },
    )

@Composable
private fun severityLabel(severity: LogSeverity): String =
    stringResource(
        when (severity) {
            LogSeverity.Debug -> R.string.logs_severity_debug
            LogSeverity.Info -> R.string.logs_severity_info
            LogSeverity.Warn -> R.string.logs_severity_warn
            LogSeverity.Error -> R.string.logs_severity_error
        },
    )

private fun severityLabelValue(severity: LogSeverity): String =
    when (severity) {
        LogSeverity.Debug -> "debug"
        LogSeverity.Info -> "info"
        LogSeverity.Warn -> "warn"
        LogSeverity.Error -> "error"
    }

private fun logRowTone(entry: LogEntry): LogRowTone =
    when {
        entry.severity == LogSeverity.Error -> LogRowTone.Error
        entry.severity == LogSeverity.Warn -> LogRowTone.Warning
        entry.subsystem == LogSubsystem.Diagnostics -> LogRowTone.Dns
        else -> LogRowTone.Connection
    }

private val previewLogs =
    persistentListOf(
        LogEntry(
            id = "1",
            createdAtMs = 1,
            timestamp = "12:31:04",
            subsystem = LogSubsystem.Service,
            severity = LogSeverity.Info,
            message = "VPN service started",
            source = "service",
            isActiveSession = true,
        ),
        LogEntry(
            id = "2",
            createdAtMs = 2,
            timestamp = "12:31:08",
            subsystem = LogSubsystem.Proxy,
            severity = LogSeverity.Info,
            message = "listener started addr=127.0.0.1:1080",
            source = "proxy",
            runtimeId = "vpn-runtime-7",
            isActiveSession = true,
        ),
        LogEntry(
            id = "3",
            createdAtMs = 3,
            timestamp = "12:31:16",
            subsystem = LogSubsystem.Diagnostics,
            severity = LogSeverity.Warn,
            message = "probe failed target=example.org",
            source = "dns",
            diagnosticsSessionId = "diag-42",
            isActiveSession = true,
        ),
        LogEntry(
            id = "4",
            createdAtMs = 4,
            timestamp = "12:31:22",
            subsystem = LogSubsystem.Tunnel,
            severity = LogSeverity.Error,
            message = "tunnel error: worker panicked",
            source = "worker",
            runtimeId = "vpn-runtime-7",
            isActiveSession = true,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun LogsScreenPreview() {
    RipDpiTheme {
        LogsScreen(
            uiState = LogsUiState(logs = previewLogs),
            onRefresh = {},
            onToggleSubsystemFilter = {},
            onToggleSeverityFilter = {},
            onAutoScrollChanged = {},
            onActiveSessionOnlyChanged = {},
            onClearLogs = {},
            onSaveLogs = {},
            onShareSupportBundle = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        LogsScreen(
            uiState = LogsUiState(),
            onRefresh = {},
            onToggleSubsystemFilter = {},
            onToggleSeverityFilter = {},
            onAutoScrollChanged = {},
            onActiveSessionOnlyChanged = {},
            onClearLogs = {},
            onSaveLogs = {},
            onShareSupportBundle = {},
        )
    }
}
