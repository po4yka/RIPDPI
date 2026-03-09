package com.poyka.ripdpi.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogType
import com.poyka.ripdpi.activities.LogsUiState
import com.poyka.ripdpi.activities.LogsViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun LogsRoute(
    onSaveLogs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LogsScreen(
        uiState = uiState,
        onToggleFilter = viewModel::toggleFilter,
        onAutoScrollChanged = viewModel::setAutoScroll,
        onClearLogs = viewModel::clearLogs,
        onSaveLogs = onSaveLogs,
        modifier = modifier,
    )
}

@Composable
internal fun LogsScreen(
    uiState: LogsUiState,
    onToggleFilter: (LogType) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val filteredLogs = uiState.filteredLogs
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.isAutoScroll, filteredLogs.size, uiState.activeFilters) {
        if (uiState.isAutoScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
    ) {
        RipDpiTopAppBar(title = stringResource(R.string.logs))

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = layout.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Spacer(modifier = Modifier.height(spacing.sm))

            LogsOverviewCard(
                uiState = uiState,
                onSaveLogs = onSaveLogs,
                onClearLogs = onClearLogs,
            )

            LogsFiltersSection(
                uiState = uiState,
                onToggleFilter = onToggleFilter,
                onAutoScrollChanged = onAutoScrollChanged,
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(spacing.sm))
        }
    }
}

@Composable
private fun LogsFiltersSection(
    uiState: LogsUiState,
    onToggleFilter: (LogType) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
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
            )
            Text(
                text = stringResource(R.string.logs_filters_helper),
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                LogType.entries.forEach { type ->
                    RipDpiChip(
                        text = logBadgeLabel(type),
                        selected = type in uiState.activeFilters,
                        onClick = { onToggleFilter(type) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsOverviewCard(
    uiState: LogsUiState,
    onSaveLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val latestLog = uiState.latestLog
    val activeFilterSummary =
        if (uiState.activeFilters.size == LogType.entries.size) {
            stringResource(R.string.logs_filters_summary_all)
        } else {
            stringResource(R.string.logs_filters_summary_count, uiState.activeFilters.size)
        }

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
                text = stringResource(R.string.save_logs),
                onClick = onSaveLogs,
                modifier = Modifier.weight(1f),
                variant =
                    if (uiState.logs.isEmpty()) {
                        RipDpiButtonVariant.Outline
                    } else {
                        RipDpiButtonVariant.Primary
                    },
            )
            RipDpiButton(
                text = stringResource(R.string.logs_clear),
                onClick = onClearLogs,
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Destructive,
                enabled = uiState.logs.isNotEmpty(),
            )
        }
        HorizontalDivider(color = colors.divider)
        SettingsRow(
            title = stringResource(R.string.logs_buffer_title),
            subtitle = activeFilterSummary,
            value =
                stringResource(
                    R.string.logs_buffer_value,
                    uiState.logs.size,
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

@Composable
private fun LogsStreamCard(
    entries: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = modifier,
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
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    LogRow(
                        timestamp = entry.timestamp,
                        type = logBadgeLabel(entry.type),
                        message = entry.message,
                        tone = logRowTone(entry.type),
                    )
                    if (index < entries.lastIndex) {
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
        }
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
private fun logBadgeLabel(type: LogType): String =
    stringResource(
        when (type) {
            LogType.DNS -> R.string.logs_type_dns
            LogType.CONN -> R.string.logs_type_conn
            LogType.ERR -> R.string.logs_type_err
            LogType.WARN -> R.string.logs_type_warn
        },
    )

private fun logRowTone(type: LogType): LogRowTone =
    when (type) {
        LogType.DNS -> LogRowTone.Dns
        LogType.CONN -> LogRowTone.Connection
        LogType.ERR -> LogRowTone.Error
        LogType.WARN -> LogRowTone.Warning
    }

private val previewLogs =
    listOf(
        LogEntry(
            id = 1,
            timestamp = "12:31:04",
            type = LogType.CONN,
            message = "VPN service started",
        ),
        LogEntry(
            id = 2,
            timestamp = "12:31:08",
            type = LogType.DNS,
            message = "DNS resolver switched to 1.1.1.1",
        ),
        LogEntry(
            id = 3,
            timestamp = "12:31:16",
            type = LogType.WARN,
            message = "Fallback resolver is active on the current network",
        ),
        LogEntry(
            id = 4,
            timestamp = "12:31:22",
            type = LogType.ERR,
            message = "Proxy service failed to start",
        ),
    )

@Preview(showBackground = true)
@Composable
private fun LogsScreenPreview() {
    RipDpiTheme {
        LogsScreen(
            uiState = LogsUiState(logs = previewLogs),
            onToggleFilter = {},
            onAutoScrollChanged = {},
            onClearLogs = {},
            onSaveLogs = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        LogsScreen(
            uiState = LogsUiState(),
            onToggleFilter = {},
            onAutoScrollChanged = {},
            onClearLogs = {},
            onSaveLogs = {},
        )
    }
}
