package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.activities.DiagnosticsAutomaticProbeCalloutUiModel
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsRememberedNetworkUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

private const val liveEdgeScrollOffsetThreshold = 24

@Suppress("LongMethod", "LongParameterList", "UnusedParameter")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    uiState: DiagnosticsUiState,
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onSelectSection: (DiagnosticsSection) -> Unit,
    onSelectProfile: (String) -> Unit,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onWaitForHiddenProbeAndRun: () -> Unit = {},
    onCancelHiddenProbeAndRun: () -> Unit = {},
    onDismissHiddenProbeConflictDialog: () -> Unit = {},
    onConfirmSensitiveProfileRun: () -> Unit = {},
    onDismissSensitiveProfileConsentDialog: () -> Unit = {},
    onCancelScan: () -> Unit,
    onKeepResolverRecommendation: (String?) -> Unit,
    onSaveResolverRecommendation: (String?) -> Unit,
    onSelectSession: (String) -> Unit,
    onDismissSessionDetail: () -> Unit,
    onSelectStrategyProbeCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
    onDismissStrategyProbeCandidate: () -> Unit,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
    onDismissApproachDetail: () -> Unit,
    onSelectEvent: (String) -> Unit,
    onDismissEventDetail: () -> Unit,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
    onDismissProbeDetail: () -> Unit,
    onToggleSensitiveSessionDetails: () -> Unit,
    onSessionPathFilter: (String?) -> Unit,
    onSessionStatusFilter: (String?) -> Unit,
    onSessionSearch: (String) -> Unit,
    onToggleEventFilter: (String?, String?) -> Unit,
    onEventSearch: (String) -> Unit,
    onEventAutoScroll: (Boolean) -> Unit,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenDetectionCheck: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    onOpenHistory: () -> Unit,
    pcapRecording: Boolean = false,
    onTogglePcapRecording: () -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    TrackRecomposition("DiagnosticsScreen")
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    var showDebugInfo by rememberSaveable { mutableStateOf(false) }

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Diagnostics))
                .fillMaxSize()
                .background(colors.background),
        snackbarHost = { RipDpiSnackbarHost(snackbarHostState) },
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.diagnostics_title),
                modifier =
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { if (BuildConfig.DEBUG) showDebugInfo = !showDebugInfo },
                    ),
                actions = {
                    RipDpiIconButton(
                        icon = RipDpiIcons.Logs,
                        contentDescription = stringResource(R.string.history_open_action),
                        onClick = onOpenHistory,
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsTopHistoryAction),
                    )
                },
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
                DiagnosticsSectionSwitcher(
                    selectedSection = uiState.selectedSection,
                    onSelectSection = onSelectSection,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding),
                )
                if (showDebugInfo) {
                    uiState.performance?.let { performance ->
                        DiagnosticsPerformanceCard(
                            performance = performance,
                            selectedSection = uiState.selectedSection,
                            modifier =
                                Modifier.padding(
                                    start = layout.horizontalPadding,
                                    end = layout.horizontalPadding,
                                    bottom = spacing.sm,
                                ),
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RectangleShape),
                ) { page ->
                    when (DiagnosticsSection.entries[page]) {
                        DiagnosticsSection.Dashboard -> {
                            OverviewSection(
                                overview = uiState.overview,
                                live = uiState.live,
                                isActiveScan = uiState.scan.activeProgress != null,
                                onSelectSection = onSelectSection,
                                onSelectSession = onSelectSession,
                                onOpenHistory = onOpenHistory,
                            )
                        }

                        DiagnosticsSection.Scan -> {
                            ScanSection(
                                scan = uiState.scan,
                                onSelectProfile = onSelectProfile,
                                onRunRawScan = onRunRawScan,
                                onRunInPathScan = onRunInPathScan,
                                onCancelScan = onCancelScan,
                                onOpenAdvancedSettings = onOpenAdvancedSettings,
                                onRequestVpnPermission = onRequestVpnPermission,
                                onKeepResolverRecommendation = onKeepResolverRecommendation,
                                onSaveResolverRecommendation = onSaveResolverRecommendation,
                                onSelectStrategyProbeCandidate = onSelectStrategyProbeCandidate,
                                onSelectProbe = onSelectProbe,
                            )
                        }

                        DiagnosticsSection.Tools -> {
                            ToolsSection(
                                approaches = uiState.approaches,
                                share = uiState.share,
                                onSelectApproachMode = onSelectApproachMode,
                                onSelectApproach = onSelectApproach,
                                onShareSummary = onShareSummary,
                                onShareArchive = onShareArchive,
                                onSaveArchive = onSaveArchive,
                                onSaveLogs = onSaveLogs,
                                onOpenDetectionCheck = onOpenDetectionCheck,
                                pcapRecording = pcapRecording,
                                onTogglePcapRecording = onTogglePcapRecording,
                            )
                        }
                    }
                }
            }
        }
    }

    DiagnosticsBottomSheetHost(
        selectedSessionDetail = uiState.selectedSessionDetail,
        selectedApproachDetail = uiState.selectedApproachDetail,
        selectedEvent = uiState.selectedEvent,
        selectedProbe = uiState.selectedProbe,
        selectedStrategyProbeCandidate = uiState.selectedStrategyProbeCandidate,
        onDismissSessionDetail = onDismissSessionDetail,
        onToggleSensitiveSessionDetails = onToggleSensitiveSessionDetails,
        onSelectStrategyProbeCandidate = onSelectStrategyProbeCandidate,
        onDismissStrategyProbeCandidate = onDismissStrategyProbeCandidate,
        onSelectEvent = onSelectEvent,
        onDismissEventDetail = onDismissEventDetail,
        onSelectProbe = onSelectProbe,
        onDismissProbeDetail = onDismissProbeDetail,
        onDismissApproachDetail = onDismissApproachDetail,
    )
    uiState.scan.hiddenProbeConflictDialog?.let { dialogState ->
        RipDpiDialog(
            onDismissRequest = onDismissHiddenProbeConflictDialog,
            title = stringResource(R.string.diagnostics_hidden_probe_conflict_title),
            dialogTestTag = RipDpiTestTags.DiagnosticsHiddenProbeConflictDialog,
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.diagnostics_hidden_probe_wait_action),
                    onClick = onWaitForHiddenProbeAndRun,
                    testTag = RipDpiTestTags.DiagnosticsHiddenProbeConflictWait,
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.diagnostics_hidden_probe_cancel_and_run_action),
                    onClick = onCancelHiddenProbeAndRun,
                    testTag = RipDpiTestTags.DiagnosticsHiddenProbeConflictCancelAndRun,
                ),
            visuals =
                RipDpiDialogVisuals(
                    message =
                        stringResource(
                            R.string.diagnostics_hidden_probe_conflict_body_format,
                            dialogState.profileName,
                        ),
                    tone = RipDpiDialogTone.Info,
                ),
        ) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_hidden_probe_dismiss_action),
                onClick = onDismissHiddenProbeConflictDialog,
                variant = RipDpiButtonVariant.Ghost,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictDismiss),
            )
        }
    }
    uiState.scan.sensitiveProfileConsentDialog?.let { dialogState ->
        RipDpiDialog(
            onDismissRequest = onDismissSensitiveProfileConsentDialog,
            title = stringResource(R.string.diagnostics_sensitive_profile_consent_title),
            dialogTestTag = RipDpiTestTags.DiagnosticsSensitiveProfileConsentDialog,
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.diagnostics_sensitive_profile_consent_dismiss),
                    onClick = onDismissSensitiveProfileConsentDialog,
                    testTag = RipDpiTestTags.DiagnosticsSensitiveProfileConsentDismiss,
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.diagnostics_sensitive_profile_consent_confirm),
                    onClick = onConfirmSensitiveProfileRun,
                    testTag = RipDpiTestTags.DiagnosticsSensitiveProfileConsentConfirm,
                ),
            visuals =
                RipDpiDialogVisuals(
                    message =
                        stringResource(
                            R.string.diagnostics_sensitive_profile_consent_body_format,
                            dialogState.profileName,
                        ),
                    tone = RipDpiDialogTone.Info,
                ),
        ) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_sensitive_profile_consent_dismiss),
                onClick = onDismissSensitiveProfileConsentDialog,
                variant = RipDpiButtonVariant.Ghost,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.DiagnosticsSensitiveProfileConsentDismiss),
            )
        }
    }
}

@Suppress("UnusedPrivateMember")
@Composable
private fun ApproachesSection(
    approaches: DiagnosticsApproachesUiModel,
    onSelectMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
) {
    TrackRecomposition("ApproachesSection")
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                Text(
                    text = stringResource(R.string.diagnostics_approaches_title).uppercase(),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_profiles),
                        selected = approaches.selectedMode == DiagnosticsApproachMode.Profiles,
                        onClick = { onSelectMode(DiagnosticsApproachMode.Profiles) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles),
                            ),
                    )
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_strategies),
                        selected = approaches.selectedMode == DiagnosticsApproachMode.Strategies,
                        onClick = { onSelectMode(DiagnosticsApproachMode.Strategies) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Strategies),
                            ),
                    )
                }
            }
        }
        items(items = approaches.rows, key = { it.id }, contentType = { "approach" }) { row ->
            RipDpiCard(
                onClick = { onSelectApproach(row.id) },
                variant =
                    if (row.id == approaches.focusedApproachId) {
                        RipDpiCardVariant.Elevated
                    } else {
                        RipDpiCardVariant.Outlined
                    },
            ) {
                StatusIndicator(label = row.verificationState, tone = statusTone(row.tone))
                Text(
                    text = row.title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = row.subtitle,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                MetricsRow(metrics = row.metrics)
                Text(
                    text = row.lastValidatedResult,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = row.dominantFailurePattern,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

@Suppress("LongMethod", "UnusedPrivateMember")
@Composable
private fun EventsSection(
    uiState: DiagnosticsUiState,
    onSelectEvent: (String) -> Unit,
    onToggleFilter: (String?, String?) -> Unit,
    onSearch: (String) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
) {
    TrackRecomposition("EventsSection")
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
        if (
            uiState.events.filters.autoScroll &&
            uiState.events.events.isNotEmpty() &&
            isAtLiveEdge
        ) {
            if (motion.animationsEnabled) {
                listState.animateScrollToItem(0)
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            RipDpiCard(
                modifier =
                    Modifier.ripDpiTestTag(
                        if (uiState.events.events.isEmpty()) {
                            RipDpiTestTags.DiagnosticsEventsStateEmpty
                        } else {
                            RipDpiTestTags.DiagnosticsEventsStateContent
                        },
                    ),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_events_title).uppercase(),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                RipDpiTextField(
                    value = uiState.events.filters.search,
                    onValueChange = onSearch,
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.diagnostics_search_label),
                            placeholder = stringResource(R.string.diagnostics_events_search_placeholder),
                            testTag = RipDpiTestTags.DiagnosticsEventsSearch,
                        ),
                )
                SettingsRow(
                    title = stringResource(R.string.logs_auto_scroll_title),
                    subtitle = stringResource(R.string.logs_auto_scroll_body),
                    checked = uiState.events.filters.autoScroll,
                    onCheckedChange = onAutoScroll,
                    showDivider = true,
                    testTag = RipDpiTestTags.DiagnosticsEventsAutoScroll,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.events.availableSources, key = { it }, contentType = { "source_chip" }) { source ->
                        RipDpiChip(
                            text = source,
                            selected = uiState.events.filters.source == source,
                            onClick = { onToggleFilter(source, null) },
                            modifier =
                                Modifier.ripDpiTestTag(
                                    RipDpiTestTags.diagnosticsEventSourceFilter(source),
                                ),
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(
                        uiState.events.availableSeverities,
                        key = { it },
                        contentType = { "severity_chip" },
                    ) { severity ->
                        RipDpiChip(
                            text = severity,
                            selected = uiState.events.filters.severity == severity,
                            onClick = { onToggleFilter(null, severity) },
                            modifier =
                                Modifier.ripDpiTestTag(
                                    RipDpiTestTags.diagnosticsEventSeverityFilter(severity),
                                ),
                        )
                    }
                }
            }
        }
        if (uiState.events.events.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.logs_filtered_empty_title),
                    body = stringResource(R.string.logs_filtered_empty_body),
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsEventsStateEmpty),
                )
            }
        } else {
            items(uiState.events.events, key = { it.id }, contentType = { "event" }) { event ->
                EventRow(
                    event = event,
                    onClick = { onSelectEvent(event.id) },
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsEvent(event.id)),
                )
            }
        }
    }
}

@Suppress("UnusedPrivateMember")
@Composable
private fun ShareSection(
    share: DiagnosticsShareUiModel,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
) {
    TrackRecomposition("ShareSection")
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            DiagnosticsPreviewCard(
                title = share.previewTitle,
                body = share.previewBody,
                metrics = share.metrics,
                archiveStateMessage = share.archiveStateMessage,
                archiveStateTone = share.archiveStateTone,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_share_archive_title),
                body = stringResource(R.string.diagnostics_share_archive_body),
                buttonLabel = stringResource(R.string.diagnostics_share_archive_action),
                onClick = { onShareArchive(share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.foreground,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsShareArchive),
                variant = RipDpiButtonVariant.Primary,
                enabled = !share.isArchiveBusy,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_archive_title),
                body =
                    stringResource(
                        R.string.diagnostics_save_archive_body,
                        share.latestArchiveFileName ?: "latest archive",
                    ),
                buttonLabel = stringResource(R.string.diagnostics_save_archive_action),
                onClick = { onSaveArchive(share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSaveArchive),
                variant = RipDpiButtonVariant.Outline,
                enabled = !share.isArchiveBusy,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_share_summary_title),
                body = stringResource(R.string.diagnostics_share_summary_body),
                buttonLabel = stringResource(R.string.diagnostics_share_summary_action),
                onClick = { onShareSummary(share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsShareSummary),
                variant = RipDpiButtonVariant.Outline,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_logs_title),
                body = stringResource(R.string.diagnostics_save_logs_body),
                buttonLabel = stringResource(R.string.save_logs),
                onClick = onSaveLogs,
                iconTint = RipDpiThemeTokens.colors.warning,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSaveLogs),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}
