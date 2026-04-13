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
private const val timingBreakdownDisplayCount = 4

@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    onShareArchive: (String, String) -> Unit,
    onSaveArchive: (String, String) -> Unit,
    onShareSummary: (String, String) -> Unit,
    onSaveLogs: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenDnsSettings: () -> Unit = {},
    onOpenDetectionCheck: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    onOpenHistory: () -> Unit,
    initialSection: DiagnosticsSection? = null,
    onInitialSectionHandled: () -> Unit = {},
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pcapRecording by viewModel.pcapRecording.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { DiagnosticsSection.entries.size }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.selectedSection) {
        if (pagerState.currentPage != uiState.selectedSection.ordinal) {
            pagerState.animateScrollToPage(uiState.selectedSection.ordinal)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val section = DiagnosticsSection.entries[pagerState.settledPage]
        if (uiState.selectedSection != section) {
            viewModel.selectSection(section)
        }
    }

    val currentOnSaveArchive by rememberUpdatedState(onSaveArchive)
    val currentOnShareArchive by rememberUpdatedState(onShareArchive)
    val currentOnShareSummary by rememberUpdatedState(onShareSummary)
    val currentOnOpenDnsSettings by rememberUpdatedState(onOpenDnsSettings)
    val performHaptic = rememberRipDpiHapticPerformer()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiagnosticsEffect.SaveArchiveRequested -> {
                    currentOnSaveArchive(effect.absolutePath, effect.fileName)
                }

                is DiagnosticsEffect.ShareArchiveRequested -> {
                    currentOnShareArchive(effect.absolutePath, effect.fileName)
                }

                is DiagnosticsEffect.ShareSummaryRequested -> {
                    currentOnShareSummary(effect.title, effect.body)
                }

                is DiagnosticsEffect.ScanStarted -> {
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.scanTypeLabel,
                        tone = RipDpiSnackbarTone.Info,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }

                is DiagnosticsEffect.ScanQueued -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.message,
                        tone = RipDpiSnackbarTone.Info,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }

                is DiagnosticsEffect.ScanCompleted -> {
                    performHaptic(
                        when (effect.tone) {
                            DiagnosticsTone.Positive -> RipDpiHapticFeedback.Success
                            DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiHapticFeedback.Error
                            else -> RipDpiHapticFeedback.Acknowledge
                        },
                    )
                    val result =
                        snackbarHostState.showRipDpiSnackbar(
                            message = effect.summary,
                            actionLabel = effect.actionLabel,
                            testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                            tone =
                                when (effect.tone) {
                                    DiagnosticsTone.Positive -> RipDpiSnackbarTone.Default
                                    DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiSnackbarTone.Warning
                                    else -> RipDpiSnackbarTone.Default
                                },
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        when (effect.action) {
                            DiagnosticsEffect.SnackbarAction.OpenDnsSettings -> currentOnOpenDnsSettings()
                            null -> Unit
                        }
                    }
                }

                is DiagnosticsEffect.ScanStartFailed -> {
                    performHaptic(RipDpiHapticFeedback.Error)
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.message,
                        tone = RipDpiSnackbarTone.Error,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }
            }
        }
    }

    LaunchedEffect(initialSection) {
        initialSection?.let {
            viewModel.selectSection(it)
            onInitialSectionHandled()
        }
    }

    DiagnosticsScreen(
        uiState = uiState,
        pagerState = pagerState,
        snackbarHostState = snackbarHostState,
        onSelectSection = viewModel::selectSection,
        onSelectProfile = viewModel::selectProfile,
        onRunRawScan = viewModel::startRawScan,
        onRunInPathScan = viewModel::startInPathScan,
        onWaitForHiddenProbeAndRun = viewModel::waitForHiddenProbeAndRun,
        onCancelHiddenProbeAndRun = viewModel::cancelHiddenProbeAndRun,
        onDismissHiddenProbeConflictDialog = viewModel::dismissHiddenProbeConflictDialog,
        onCancelScan = viewModel::cancelScan,
        onKeepResolverRecommendation = viewModel::keepResolverRecommendationForSession,
        onSaveResolverRecommendation = viewModel::saveResolverRecommendation,
        onSelectSession = viewModel::selectSession,
        onDismissSessionDetail = viewModel::dismissSessionDetail,
        onSelectStrategyProbeCandidate = viewModel::selectStrategyProbeCandidate,
        onDismissStrategyProbeCandidate = viewModel::dismissStrategyProbeCandidate,
        onSelectApproachMode = viewModel::selectApproachMode,
        onSelectApproach = viewModel::selectApproach,
        onDismissApproachDetail = viewModel::dismissApproachDetail,
        onSelectEvent = viewModel::selectEvent,
        onDismissEventDetail = viewModel::dismissEventDetail,
        onSelectProbe = viewModel::selectProbe,
        onDismissProbeDetail = viewModel::dismissProbeDetail,
        onToggleSensitiveSessionDetails = viewModel::toggleSensitiveSessionDetails,
        onSessionPathFilter = viewModel::setSessionPathModeFilter,
        onSessionStatusFilter = viewModel::setSessionStatusFilter,
        onSessionSearch = viewModel::setSessionSearch,
        onToggleEventFilter = viewModel::toggleEventFilter,
        onEventSearch = viewModel::setEventSearch,
        onEventAutoScroll = viewModel::setEventAutoScroll,
        onShareSummary = viewModel::shareSummary,
        onShareArchive = viewModel::shareArchive,
        onSaveArchive = viewModel::saveArchive,
        onSaveLogs = onSaveLogs,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenDetectionCheck = onOpenDetectionCheck,
        onRequestVpnPermission = onRequestVpnPermission,
        onOpenHistory = onOpenHistory,
        pcapRecording = pcapRecording,
        onTogglePcapRecording = viewModel::togglePcapRecording,
        modifier = modifier,
    )
}

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
}

@Composable
private fun DiagnosticsSectionSwitcher(
    selectedSection: DiagnosticsSection,
    onSelectSection: (DiagnosticsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = spacing.sm, bottom = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        DiagnosticsSection.entries.forEach { section ->
            RipDpiChip(
                text = section.label(),
                selected = selectedSection == section,
                onClick = { onSelectSection(section) },
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsSection(section)),
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun OverviewSection(
    overview: DiagnosticsOverviewUiModel,
    live: com.poyka.ripdpi.activities.DiagnosticsLiveUiModel,
    isActiveScan: Boolean,
    onSelectSection: (DiagnosticsSection) -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    TrackRecomposition("DiagnosticsOverview")
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
            DiagnosticsHealthHero(
                overview = overview,
                isActiveScan = isActiveScan,
                onSelectSection = onSelectSection,
            )
        }
        if (live.health != DiagnosticsHealth.Idle && live.metrics.isNotEmpty()) {
            item {
                RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                    StatusIndicator(
                        label = live.statusLabel,
                        tone = statusTone(live.statusTone),
                    )
                    MetricsRow(metrics = live.highlights)
                }
            }
        }
        overview.activeProfile?.let { profile ->
            item {
                RipDpiCard {
                    Text(
                        text = stringResource(R.string.diagnostics_profiles_title).uppercase(),
                        style = RipDpiThemeTokens.type.sectionTitle,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                    Text(
                        text = profile.name,
                        style = RipDpiThemeTokens.type.screenTitle,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                    Text(
                        text = profile.source.replaceFirstChar { it.uppercase() },
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }
        }
        if (overview.latestSnapshot != null || overview.contextSummary != null) {
            item {
                val fieldCount =
                    (overview.latestSnapshot?.fieldGroups?.sumOf { it.fields.size } ?: 0) +
                        (overview.contextSummary?.fields?.size ?: 0)
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_network_details_section),
                    badgeCount = fieldCount.takeIf { it > 0 },
                ) {
                    overview.latestSnapshot?.let { snapshot ->
                        SnapshotCard(snapshot = snapshot)
                    }
                    overview.contextSummary?.let { contextSummary ->
                        ContextGroupCard(group = contextSummary)
                    }
                }
            }
        }
        if (overview.latestSession != null || overview.recentAutomaticProbe != null) {
            item {
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_recent_activity_section),
                    defaultExpanded = true,
                ) {
                    overview.latestSession?.let { session ->
                        SessionRow(
                            session = session,
                            onClick = { onSelectSession(session.id) },
                        )
                    }
                    overview.recentAutomaticProbe?.let { automaticProbe ->
                        AutomaticProbeHistoryCard(
                            callout = automaticProbe,
                            onOpenHistory = onOpenHistory,
                        )
                    }
                    HistoryCalloutCard(onOpenHistory = onOpenHistory)
                }
            }
        }
        if (overview.rememberedNetworks.isNotEmpty()) {
            item {
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_remembered_networks_section),
                    badgeCount = overview.rememberedNetworks.size,
                ) {
                    RememberedNetworkPoliciesCard(policies = overview.rememberedNetworks)
                }
            }
        }
        if (overview.warnings.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_attention_section))
                    overview.warnings.forEach { warning ->
                        EventRow(event = warning, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun RememberedNetworkPoliciesCard(policies: List<DiagnosticsRememberedNetworkUiModel>) {
    TrackRecomposition("RememberedNetworkPoliciesCard")
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard {
        Text(
            text = stringResource(R.string.diagnostics_remembered_networks_title).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            policies.forEachIndexed { index, policy ->
                if (index > 0) {
                    HorizontalDivider(color = colors.divider)
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    StatusIndicator(
                        label = policy.status,
                        tone =
                            when (policy.statusTone) {
                                DiagnosticsTone.Positive -> StatusIndicatorTone.Active
                                DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
                                DiagnosticsTone.Negative -> StatusIndicatorTone.Error
                                DiagnosticsTone.Info -> StatusIndicatorTone.Idle
                                DiagnosticsTone.Neutral -> StatusIndicatorTone.Idle
                            },
                    )
                    Text(
                        text = policy.title,
                        style = type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Text(
                        text = policy.subtitle,
                        style = type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    Text(
                        text = policy.strategyLabel,
                        style = type.caption,
                        color = colors.foreground,
                    )
                    Text(
                        text =
                            listOfNotNull(
                                "Success ${policy.successCount}",
                                "Failures ${policy.failureCount}",
                                policy.lastValidatedLabel?.let { "Validated $it" },
                                policy.lastAppliedLabel?.let { "Applied $it" },
                            ).joinToString(" · "),
                        style = type.caption,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomaticProbeHistoryCard(
    callout: DiagnosticsAutomaticProbeCalloutUiModel,
    onOpenHistory: () -> Unit,
) {
    RipDpiCard(
        variant = RipDpiCardVariant.Outlined,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsOverviewAutomaticProbeCard),
    ) {
        Text(
            text = callout.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = callout.summary,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = callout.detail,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiButton(
            text = callout.actionLabel,
            onClick = onOpenHistory,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HistoryCalloutCard(onOpenHistory: () -> Unit) {
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.diagnostics_open_history_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.diagnostics_open_history_body),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiButton(
            text = stringResource(R.string.diagnostics_open_history_action),
            onClick = onOpenHistory,
            variant = RipDpiButtonVariant.Outline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsOverviewHistoryAction),
        )
    }
}

@Composable
private fun DiagnosticsHealthHero(
    overview: DiagnosticsOverviewUiModel,
    isActiveScan: Boolean,
    onSelectSection: (DiagnosticsSection) -> Unit,
) {
    TrackRecomposition("DiagnosticsHealthHero")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val tone = warningBannerTone(overview.health)

    Column(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsOverviewHero),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        WarningBanner(
            title = overview.headline,
            message = overview.body,
            tone = tone,
            onClick =
                if (isActiveScan) {
                    { onSelectSection(DiagnosticsSection.Scan) }
                } else {
                    null
                },
        )
        if (!isActiveScan) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_overview_run_scan_action),
                onClick = { onSelectSection(DiagnosticsSection.Scan) },
                variant = RipDpiButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RipDpiCard(variant = RipDpiCardVariant.Elevated) {
            StatusIndicator(
                label = overview.health.displayLabel(),
                tone = overview.health.statusTone(),
            )
            Text(
                text = stringResource(R.string.diagnostics_overview_section).uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = colors.mutedForeground,
            )
            MetricsRow(metrics = overview.metrics)
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ToolsSection(
    approaches: DiagnosticsApproachesUiModel,
    share: DiagnosticsShareUiModel,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
    onOpenDetectionCheck: () -> Unit = {},
    pcapRecording: Boolean = false,
    onTogglePcapRecording: () -> Unit = {},
) {
    TrackRecomposition("ToolsSection")
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
        // Approaches browser
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
                        onClick = { onSelectApproachMode(DiagnosticsApproachMode.Profiles) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles),
                            ),
                    )
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_strategies),
                        selected = approaches.selectedMode == DiagnosticsApproachMode.Strategies,
                        onClick = { onSelectApproachMode(DiagnosticsApproachMode.Strategies) },
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
        // PCAP recording
        item {
            ShareActionCard(
                title = "Packet Capture",
                body =
                    if (pcapRecording) {
                        "Recording packets for diagnostics..."
                    } else {
                        "Record raw packets to a pcap file for analysis in Wireshark."
                    },
                buttonLabel = if (pcapRecording) "Stop Recording" else "Start Recording",
                onClick = onTogglePcapRecording,
                iconTint = if (pcapRecording) RipDpiThemeTokens.colors.destructive else RipDpiThemeTokens.colors.info,
                variant = if (pcapRecording) RipDpiButtonVariant.Destructive else RipDpiButtonVariant.Outline,
            )
        }
        // Share/Export section
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
        item {
            ShareActionCard(
                title = stringResource(R.string.title_detection_check),
                body = stringResource(R.string.detection_check_subtitle),
                buttonLabel = stringResource(R.string.detection_check_start),
                onClick = onOpenDetectionCheck,
                iconTint = RipDpiThemeTokens.colors.foreground,
                variant = RipDpiButtonVariant.Outline,
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

@Composable
private fun DiagnosticsPerformanceCard(
    performance: DiagnosticsPerformanceUiModel,
    selectedSection: DiagnosticsSection,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("DiagnosticsPerformanceCard")
    val colors = RipDpiThemeTokens.colors
    var expanded by remember { mutableStateOf(false) }
    val timingBreakdown =
        remember(performance) {
            listOf(
                "resolve" to performance.resolveDurationMillis,
                "overview" to performance.overviewDurationMillis,
                "scan" to performance.scanDurationMillis,
                "live" to performance.liveDurationMillis,
                "sessions" to performance.sessionsDurationMillis,
                "approaches" to performance.approachesDurationMillis,
                "events" to performance.eventsDurationMillis,
                "share" to performance.shareDurationMillis,
                "event-map" to performance.eventMappingDurationMillis,
            ).sortedByDescending { it.second }
        }
    val slowestStage = timingBreakdown.firstOrNull()
    val timingSummary =
        remember(timingBreakdown) {
            timingBreakdown.take(timingBreakdownDisplayCount).joinToString("  ") { (label, duration) ->
                "$label ${formatDuration(duration)}"
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
        onClick = { expanded = !expanded },
    ) {
        Text(
            text =
                "Debug #${performance.buildSequence} · ${selectedSection.name.lowercase(Locale.US)} · " +
                    formatDuration(performance.totalDurationMillis),
            style = RipDpiThemeTokens.type.monoSmall,
            color = colors.mutedForeground,
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
                HorizontalDivider(color = colors.divider)
                slowestStage?.let { (label, duration) ->
                    Text(
                        text = "Slowest stage: $label ${formatDuration(duration)}",
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                }
                Text(
                    text =
                        "Input: ${performance.telemetryCount} telemetry · " +
                            "${performance.nativeEventCount} events · " +
                            "${performance.sessionCount} sessions",
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
                Text(
                    text = timingSummary,
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.foreground,
                )
            }
        }
    }
}

private fun formatDuration(durationMillis: Double): String = String.format(Locale.US, "%.1f ms", durationMillis)
