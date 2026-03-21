package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsRememberedNetworkUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    onShareArchive: (String, String) -> Unit,
    onSaveArchive: (String, String) -> Unit,
    onShareSummary: (String, String) -> Unit,
    onSaveLogs: () -> Unit,
    onOpenHistory: () -> Unit,
    initialSection: DiagnosticsSection? = null,
    onInitialSectionHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiagnosticsEffect.SaveArchiveRequested -> {
                    onSaveArchive(effect.absolutePath, effect.fileName)
                }

                is DiagnosticsEffect.ShareArchiveRequested -> {
                    onShareArchive(effect.absolutePath, effect.fileName)
                }

                is DiagnosticsEffect.ShareSummaryRequested -> {
                    onShareSummary(effect.title, effect.body)
                }

                is DiagnosticsEffect.ScanStarted -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.scanTypeLabel,
                        tone = RipDpiSnackbarTone.Info,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }

                is DiagnosticsEffect.ScanCompleted -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.summary,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                        tone =
                            when (effect.tone) {
                                DiagnosticsTone.Positive -> RipDpiSnackbarTone.Default
                                DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiSnackbarTone.Warning
                                else -> RipDpiSnackbarTone.Default
                            },
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
        onOpenHistory = onOpenHistory,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    uiState: DiagnosticsUiState,
    pagerState: androidx.compose.foundation.pager.PagerState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onSelectSection: (DiagnosticsSection) -> Unit,
    onSelectProfile: (String) -> Unit,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
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
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout

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
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) { page ->
                    when (DiagnosticsSection.entries[page]) {
                        DiagnosticsSection.Overview -> {
                            OverviewSection(
                                uiState = uiState,
                                onSelectSection = onSelectSection,
                                onSelectSession = onSelectSession,
                                onOpenHistory = onOpenHistory,
                            )
                        }

                        DiagnosticsSection.Scan -> {
                            ScanSection(
                                uiState = uiState,
                                onSelectProfile = onSelectProfile,
                                onRunRawScan = onRunRawScan,
                                onRunInPathScan = onRunInPathScan,
                                onCancelScan = onCancelScan,
                                onKeepResolverRecommendation = onKeepResolverRecommendation,
                                onSaveResolverRecommendation = onSaveResolverRecommendation,
                                onSelectStrategyProbeCandidate = onSelectStrategyProbeCandidate,
                                onSelectProbe = onSelectProbe,
                            )
                        }

                        DiagnosticsSection.Live -> {
                            LiveSection(uiState = uiState)
                        }

                        DiagnosticsSection.Approaches -> {
                            ApproachesSection(
                                uiState = uiState,
                                onSelectMode = onSelectApproachMode,
                                onSelectApproach = onSelectApproach,
                            )
                        }

                        DiagnosticsSection.Share -> {
                            ShareSection(
                                uiState = uiState,
                                onShareSummary = onShareSummary,
                                onShareArchive = onShareArchive,
                                onSaveArchive = onSaveArchive,
                                onSaveLogs = onSaveLogs,
                            )
                        }
                    }
                }
            }
        }
    }

    DiagnosticsBottomSheetHost(
        uiState = uiState,
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

@Composable
private fun OverviewSection(
    uiState: DiagnosticsUiState,
    onSelectSection: (DiagnosticsSection) -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
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
            DiagnosticsHealthHero(uiState = uiState, onSelectSection = onSelectSection)
        }
        uiState.overview.activeProfile?.let { profile ->
            item {
                RipDpiCard {
                    Text(
                        text = stringResource(R.string.diagnostics_profiles_title),
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
        uiState.overview.latestSnapshot?.let { snapshot ->
            item {
                SnapshotCard(snapshot = snapshot)
            }
        }
        uiState.overview.contextSummary?.let { contextSummary ->
            item {
                ContextGroupCard(group = contextSummary)
            }
        }
        uiState.overview.latestSession?.let { session ->
            item {
                SessionRow(
                    session = session,
                    onClick = { onSelectSession(session.id) },
                )
            }
        }
        if (uiState.overview.rememberedNetworks.isNotEmpty()) {
            item {
                RememberedNetworkPoliciesCard(policies = uiState.overview.rememberedNetworks)
            }
        }
        item {
            HistoryCalloutCard(onOpenHistory = onOpenHistory)
        }
        if (uiState.overview.warnings.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_attention_section))
                    uiState.overview.warnings.forEach { warning ->
                        EventRow(event = warning, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun RememberedNetworkPoliciesCard(policies: List<DiagnosticsRememberedNetworkUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard {
        Text(
            text = stringResource(R.string.diagnostics_remembered_networks_title),
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
    uiState: DiagnosticsUiState,
    onSelectSection: (DiagnosticsSection) -> Unit,
) {
    val overview = uiState.overview
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val tone = warningBannerTone(overview.health)
    val isActiveScan = uiState.scan.activeProgress != null

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
        RipDpiCard(variant = RipDpiCardVariant.Elevated) {
            StatusIndicator(
                label = overview.health.displayLabel(),
                tone = overview.health.statusTone(),
            )
            Text(
                text = stringResource(R.string.diagnostics_overview_section),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = colors.mutedForeground,
            )
            MetricsRow(metrics = overview.metrics)
        }
    }
}

@Composable
private fun ApproachesSection(
    uiState: DiagnosticsUiState,
    onSelectMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
) {
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
                    text = stringResource(R.string.diagnostics_approaches_title),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_profiles),
                        selected = uiState.approaches.selectedMode == DiagnosticsApproachMode.Profiles,
                        onClick = { onSelectMode(DiagnosticsApproachMode.Profiles) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles),
                            ),
                    )
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_strategies),
                        selected = uiState.approaches.selectedMode == DiagnosticsApproachMode.Strategies,
                        onClick = { onSelectMode(DiagnosticsApproachMode.Strategies) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Strategies),
                            ),
                    )
                }
            }
        }
        items(uiState.approaches.rows, key = { it.id }) { row ->
            RipDpiCard(
                onClick = { onSelectApproach(row.id) },
                variant =
                    if (row.id ==
                        uiState.approaches.focusedApproachId
                    ) {
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

@Composable
private fun SessionsSection(
    uiState: DiagnosticsUiState,
    onSelectSession: (String) -> Unit,
    onPathModeFilter: (String?) -> Unit,
    onStatusFilter: (String?) -> Unit,
    onSearch: (String) -> Unit,
) {
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
            RipDpiCard {
                Text(
                    text = stringResource(R.string.diagnostics_history_section),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                RipDpiTextField(
                    value = uiState.sessions.filters.query,
                    onValueChange = onSearch,
                    label = stringResource(R.string.diagnostics_search_label),
                    placeholder = stringResource(R.string.diagnostics_search_placeholder),
                    testTag = RipDpiTestTags.DiagnosticsSessionsSearch,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.sessions.pathModes, key = { it }) { pathMode ->
                        RipDpiChip(
                            text = pathMode,
                            selected = uiState.sessions.filters.pathMode == pathMode,
                            onClick = { onPathModeFilter(pathMode) },
                            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsSessionPathFilter(pathMode)),
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.sessions.statuses, key = { it }) { status ->
                        RipDpiChip(
                            text = status.replaceFirstChar { it.uppercase() },
                            selected = uiState.sessions.filters.status == status,
                            onClick = { onStatusFilter(status) },
                            modifier =
                                Modifier.ripDpiTestTag(
                                    RipDpiTestTags.diagnosticsSessionStatusFilter(status),
                                ),
                        )
                    }
                }
            }
        }
        if (uiState.sessions.sessions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.diagnostics_history_empty_title),
                    body = stringResource(R.string.diagnostics_history_empty),
                )
            }
        } else {
            items(uiState.sessions.sessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { onSelectSession(session.id) })
            }
        }
    }
}

@Composable
private fun EventsSection(
    uiState: DiagnosticsUiState,
    onSelectEvent: (String) -> Unit,
    onToggleFilter: (String?, String?) -> Unit,
    onSearch: (String) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
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
        if (
            uiState.events.filters.autoScroll &&
            uiState.events.events.isNotEmpty() &&
            isAtLiveEdge
        ) {
            listState.animateScrollToItem(0)
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
            RipDpiCard {
                Text(
                    text = stringResource(R.string.diagnostics_events_title),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                RipDpiTextField(
                    value = uiState.events.filters.search,
                    onValueChange = onSearch,
                    label = stringResource(R.string.diagnostics_search_label),
                    placeholder = stringResource(R.string.diagnostics_events_search_placeholder),
                    testTag = RipDpiTestTags.DiagnosticsEventsSearch,
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
                    items(uiState.events.availableSources, key = { it }) { source ->
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
                    items(uiState.events.availableSeverities, key = { it }) { severity ->
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
                )
            }
        } else {
            items(uiState.events.events, key = { it.id }) { event ->
                EventRow(event = event, onClick = { onSelectEvent(event.id) })
            }
        }
    }
}

@Composable
private fun ShareSection(
    uiState: DiagnosticsUiState,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
) {
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
                title = uiState.share.previewTitle,
                body = uiState.share.previewBody,
                metrics = uiState.share.metrics,
                archiveStateMessage = uiState.share.archiveStateMessage,
                archiveStateTone = uiState.share.archiveStateTone,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_share_archive_title),
                body = stringResource(R.string.diagnostics_share_archive_body),
                buttonLabel = stringResource(R.string.diagnostics_share_archive_action),
                onClick = { onShareArchive(uiState.share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.foreground,
                variant = RipDpiButtonVariant.Primary,
                enabled = !uiState.share.isArchiveBusy,
                buttonModifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsShareArchive),
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_archive_title),
                body =
                    stringResource(
                        R.string.diagnostics_save_archive_body,
                        uiState.share.latestArchiveFileName ?: "latest archive",
                    ),
                buttonLabel = stringResource(R.string.diagnostics_save_archive_action),
                onClick = { onSaveArchive(uiState.share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                variant = RipDpiButtonVariant.Outline,
                enabled = !uiState.share.isArchiveBusy,
                buttonModifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSaveArchive),
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_share_summary_title),
                body = stringResource(R.string.diagnostics_share_summary_body),
                buttonLabel = stringResource(R.string.diagnostics_share_summary_action),
                onClick = { onShareSummary(uiState.share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                variant = RipDpiButtonVariant.Outline,
                buttonModifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsShareSummary),
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_logs_title),
                body = stringResource(R.string.diagnostics_save_logs_body),
                buttonLabel = stringResource(R.string.save_logs),
                onClick = onSaveLogs,
                iconTint = RipDpiThemeTokens.colors.warning,
                variant = RipDpiButtonVariant.Outline,
                buttonModifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSaveLogs),
            )
        }
    }
}
