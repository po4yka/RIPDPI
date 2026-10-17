package com.poyka.ripdpi.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogsUiState
import com.poyka.ripdpi.activities.LogsViewModel
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
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
        onToggleSubsystemFilter = remember(viewModel) { viewModel::toggleSubsystemFilter },
        onToggleSeverityFilter = remember(viewModel) { viewModel::toggleSeverityFilter },
        onAutoScrollChanged = remember(viewModel) { viewModel::setAutoScroll },
        onActiveSessionOnlyChanged = remember(viewModel) { viewModel::setActiveSessionOnly },
        onClearLogs = remember(viewModel) { viewModel::clearLogs },
        onSaveLogs = onSaveLogs,
        onShareSupportBundle = onShareSupportBundle,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsScreen(
    uiState: LogsUiState,
    onToggleSubsystemFilter: (LogSubsystem) -> Unit,
    onToggleSeverityFilter: (LogSeverity) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
    onActiveSessionOnlyChanged: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareSupportBundle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("LogsScreen")
    val snackbarHostState = remember { SnackbarHostState() }
    val onShowCopiedSnackbar = rememberCopiedToClipboardSnackbar(snackbarHostState)

    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.LogsScreen)
                .fillMaxSize()
                .ripDpiScreenBackground(),
        snackbarHost = { RipDpiSnackbarHost(snackbarHostState) },
        topBar = { RipDpiTopAppBar(title = stringResource(R.string.logs)) },
    ) { innerPadding ->
        LogsContent(
            uiState = uiState,
            innerPadding = innerPadding,
            onShowFilters = { showFilters = true },
            onShowActions = { showActions = true },
            onShowCopiedSnackbar = onShowCopiedSnackbar,
        )
    }
    if (showFilters) {
        RipDpiBottomSheet(
            onDismissRequest = { showFilters = false },
            title = stringResource(R.string.logs_filters_section),
            testTag = RipDpiTestTags.LogsFiltersSheet,
            primaryAction =
                RipDpiSheetAction(
                    label = stringResource(R.string.app_picker_done),
                    onClick = { showFilters = false },
                    testTag = RipDpiTestTags.LogsSheetDone,
                    wrapLabel = true,
                ),
        ) {
            LogsFiltersSection(
                uiState = uiState,
                onToggleSubsystemFilter = onToggleSubsystemFilter,
                onToggleSeverityFilter = onToggleSeverityFilter,
                onAutoScrollChanged = onAutoScrollChanged,
                onActiveSessionOnlyChanged = onActiveSessionOnlyChanged,
            )
        }
    }
    if (showActions) {
        LogsActionsSheet(
            uiState = uiState,
            onDismiss = { showActions = false },
            onSaveLogs = {
                showActions = false
                onSaveLogs()
            },
            onShareSupportBundle = {
                showActions = false
                onShareSupportBundle()
            },
            onClearLogs = {
                showActions = false
                onClearLogs()
            },
        )
    }
}

@Composable
private fun LogsContent(
    uiState: LogsUiState,
    innerPadding: PaddingValues,
    onShowFilters: () -> Unit,
    onShowActions: () -> Unit,
    onShowCopiedSnackbar: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = layout.contentMaxWidth)
                    .fillMaxSize()
                    .padding(horizontal = layout.horizontalPadding, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LogsToolbar(
                uiState = uiState,
                onShowFilters = onShowFilters,
                onShowActions = onShowActions,
            )
            LogsStreamSection(
                uiState = uiState,
                onShowCopiedSnackbar = onShowCopiedSnackbar,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun LogsToolbar(
    uiState: LogsUiState,
    onShowFilters: () -> Unit,
    onShowActions: () -> Unit,
) {
    val filterSummary =
        stringResource(
            R.string.logs_filters_summary_compact,
            uiState.activeSubsystems.size,
            uiState.activeSeverities.size,
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { LogsStreamStatus(streaming = uiState.logs.isNotEmpty()) }
        RipDpiIconButton(
            icon = RipDpiIcons.Config,
            contentDescription = stringResource(R.string.logs_filters_section),
            onClick = onShowFilters,
            modifier =
                Modifier.ripDpiTestTag(RipDpiTestTags.LogsFilters).semantics {
                    stateDescription = filterSummary
                },
        )
        RipDpiIconButton(
            icon = RipDpiIcons.Overflow,
            contentDescription = stringResource(R.string.logs_actions_title),
            onClick = onShowActions,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.LogsActions),
        )
    }
}

@Composable
private fun LogsStreamSection(
    uiState: LogsUiState,
    onShowCopiedSnackbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredLogs = uiState.filteredLogs
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager =
        remember(context) {
            context.getSystemService(ClipboardManager::class.java)
        }
    val performHaptic = rememberRipDpiHapticPerformer()
    val clipLabel = stringResource(R.string.clipboard_label_log_entry)
    // Capture the old layout before the new entries are measured. Checking the new
    // item count can lose a live edge on a batch append or move a history reader.
    val followLatest =
        remember(filteredLogs, uiState.isAutoScroll) {
            uiState.isAutoScroll && !listState.isScrollInProgress && !listState.canScrollForward
        }
    LaunchedEffect(uiState.isAutoScroll, filteredLogs.lastOrNull()?.id) {
        if (followLatest && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    Column(
        modifier =
            if (filteredLogs.isEmpty()) {
                modifier.verticalScroll(rememberScrollState())
            } else {
                modifier
            },
    ) {
        if (filteredLogs.isEmpty()) {
            LogsEmptyStateCard(
                hasBufferedLogs = uiState.logs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.LogsEmptyState),
            )
        } else {
            LogsStreamCard(
                entries = filteredLogs,
                listState = listState,
                onCopyEntry = { entry ->
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText(
                            clipLabel,
                            "${entry.timestamp} [${entry.subsystem.name}] ${entry.message}",
                        ),
                    )
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                    onShowCopiedSnackbar()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
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
                testTag = RipDpiTestTags.LogsActiveSessionOnly,
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
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
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

@Suppress("LongMethod")
@Composable
private fun LogsStreamStatus(streaming: Boolean) {
    StatusIndicator(
        label = stringResource(if (streaming) R.string.logs_status_live else R.string.logs_status_empty),
        tone = if (streaming) StatusIndicatorTone.Active else StatusIndicatorTone.Idle,
        pulsing = streaming,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsActionsSheet(
    uiState: LogsUiState,
    onDismiss: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareSupportBundle: () -> Unit,
    onClearLogs: () -> Unit,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.logs_actions_title),
        primaryAction =
            RipDpiSheetAction(
                label = stringResource(R.string.app_picker_done),
                onClick = onDismiss,
                testTag = RipDpiTestTags.LogsSheetDone,
                wrapLabel = true,
            ),
    ) {
        RipDpiButton(
            text = stringResource(R.string.settings_support_debug_bundle_title),
            onClick = onShareSupportBundle,
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Primary,
            wrapLabel = true,
        )
        RipDpiButton(
            text = stringResource(R.string.save_logs),
            onClick = onSaveLogs,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.LogsSave),
            variant = RipDpiButtonVariant.Outline,
            wrapLabel = true,
        )
        RipDpiButton(
            text = stringResource(R.string.logs_clear),
            onClick = onClearLogs,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.LogsClear),
            variant = RipDpiButtonVariant.Destructive,
            enabled = uiState.logs.isNotEmpty(),
            wrapLabel = true,
        )
        SettingsRow(
            title = stringResource(R.string.logs_buffer_title),
            subtitle =
                stringResource(
                    R.string.logs_filters_summary_compact,
                    uiState.activeSubsystems.size,
                    uiState.activeSeverities.size,
                ),
            value = stringResource(R.string.logs_buffer_value, uiState.filteredLogs.size, uiState.bufferCapacity),
            monospaceValue = true,
        )
    }
}

@Composable
internal fun LogsStreamCard(
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
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
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
                contentType = { _, _ -> "log_entry" },
            ) { index, entry ->
                LogsStreamEntry(
                    entry = entry,
                    copyContentDescription =
                        stringResource(
                            R.string.logs_copy_entry,
                            subsystemLabel(entry.subsystem),
                            entry.timestamp,
                        ),
                    showDivider = index < entries.lastIndex,
                    onCopy = { onCopyEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun LogsStreamEntry(
    entry: LogEntry,
    copyContentDescription: String,
    showDivider: Boolean,
    onCopy: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.logsEntry(entry.id)),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            LogRow(
                timestamp = entry.timestamp,
                type = subsystemLabel(entry.subsystem),
                message = entry.message,
                modifier = Modifier.weight(1f),
                tone = logRowTone(entry),
                metadataChips = metadataChips(entry),
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Copy,
                contentDescription = copyContentDescription,
                onClick = onCopy,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.logsEntryCopy(entry.id)),
                density = RipDpiControlDensity.Compact,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

private fun metadataChips(entry: LogEntry): ImmutableList<String> =
    buildList {
        add(entry.source.lowercase())
        add(severityLabelValue(entry.severity))
        entry.runtimeId?.let { add("runtime:$it") }
        entry.diagnosticsSessionId?.let { add("scan:$it") }
        if (entry.isActiveSession) {
            add("active")
        }
    }.toPersistentList()

@Composable
private fun rememberCopiedToClipboardSnackbar(snackbarHostState: SnackbarHostState): () -> Unit {
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    return remember(scope, snackbarHostState, copiedMessage) {
        {
            scope.launch {
                snackbarHostState.showRipDpiSnackbar(
                    message = copiedMessage,
                    tone = RipDpiSnackbarTone.Default,
                )
            }
        }
    }
}

@Composable
private fun LogsEmptyStateCard(
    hasBufferedLogs: Boolean,
    modifier: Modifier = Modifier,
) {
    RipDpiEmptyStateCard(
        title =
            stringResource(
                if (hasBufferedLogs) {
                    R.string.logs_filtered_empty_title
                } else {
                    R.string.logs_empty_title
                },
            ),
        body =
            stringResource(
                if (hasBufferedLogs) {
                    R.string.logs_filtered_empty_body
                } else {
                    R.string.logs_empty_body
                },
            ),
        modifier = modifier,
    )
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
