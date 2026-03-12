package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsLiveUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsNetworkSnapshotUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { DiagnosticsSection.entries.size }

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

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiagnosticsEffect.SaveArchiveRequested -> onSaveArchive(effect.absolutePath, effect.fileName)
                is DiagnosticsEffect.ShareArchiveRequested -> onShareArchive(effect.absolutePath, effect.fileName)
                is DiagnosticsEffect.ShareSummaryRequested -> onShareSummary(effect.title, effect.body)
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
        onSelectSection = viewModel::selectSection,
        onSelectProfile = viewModel::selectProfile,
        onRunRawScan = viewModel::startRawScan,
        onRunInPathScan = viewModel::startInPathScan,
        onCancelScan = viewModel::cancelScan,
        onSelectSession = viewModel::selectSession,
        onDismissSessionDetail = viewModel::dismissSessionDetail,
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
    onSelectSection: (DiagnosticsSection) -> Unit,
    onSelectProfile: (String) -> Unit,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDismissSessionDetail: () -> Unit,
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
                .fillMaxSize()
                .background(colors.background),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.diagnostics_title),
                actions = {
                    RipDpiIconButton(
                        icon = RipDpiIcons.Logs,
                        contentDescription = stringResource(R.string.history_open_action),
                        onClick = onOpenHistory,
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
                        DiagnosticsSection.Overview ->
                            OverviewSection(
                                uiState = uiState,
                                onSelectSession = onSelectSession,
                                onOpenHistory = onOpenHistory,
                            )

                        DiagnosticsSection.Scan ->
                            ScanSection(
                                uiState = uiState,
                                onSelectProfile = onSelectProfile,
                                onRunRawScan = onRunRawScan,
                                onRunInPathScan = onRunInPathScan,
                                onCancelScan = onCancelScan,
                                onSelectProbe = onSelectProbe,
                            )

                        DiagnosticsSection.Live ->
                            LiveSection(uiState = uiState)

                        DiagnosticsSection.Approaches ->
                            ApproachesSection(
                                uiState = uiState,
                                onSelectMode = onSelectApproachMode,
                                onSelectApproach = onSelectApproach,
                            )

                        DiagnosticsSection.Share ->
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

    uiState.selectedSessionDetail?.let { detail ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissSessionDetail,
            title = detail.session.title,
            message = detail.session.subtitle,
            icon = RipDpiIcons.Info,
        ) {
            StatusIndicator(
                label = detail.session.status,
                tone = statusTone(detail.session.tone),
            )
            if (detail.hasSensitiveDetails) {
                RipDpiButton(
                    text =
                        if (detail.sensitiveDetailsVisible) {
                            stringResource(R.string.diagnostics_sensitive_hide)
                        } else {
                            stringResource(R.string.diagnostics_sensitive_show)
                        },
                    onClick = onToggleSensitiveSessionDetails,
                    variant = RipDpiButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            detail.strategyProbeReport?.let { report ->
                StrategyProbeReportCard(report = report)
            }
            detail.contextGroups.forEach { group ->
                ContextGroupCard(group = group)
            }
            detail.probeGroups.forEach { group ->
                Text(
                    text = group.title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                group.items.forEach { probe ->
                    ProbeResultRow(
                        probe = probe,
                        onClick = { onSelectProbe(probe) },
                    )
                }
            }
            detail.snapshots.forEach { snapshot ->
                SnapshotCard(snapshot = snapshot)
            }
            if (detail.events.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_events_title),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                detail.events.take(6).forEach { event ->
                    EventRow(event = event, onClick = { onSelectEvent(event.id) })
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
        ) {
            StatusIndicator(label = event.severity, tone = statusTone(event.tone))
            Text(
                text = event.message,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
        }
    }

    uiState.selectedApproachDetail?.let { detail ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissApproachDetail,
            title = detail.approach.title,
            message = detail.approach.subtitle,
            icon = RipDpiIcons.Search,
        ) {
            StatusIndicator(label = detail.approach.verificationState, tone = statusTone(detail.approach.tone))
            if (detail.signature.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_approaches_signature_title),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                detail.signature.forEach { item ->
                    SettingsRow(title = item.label, value = item.value, monospaceValue = false)
                }
            }
            if (detail.breakdown.isNotEmpty()) {
                MetricsRow(metrics = detail.breakdown)
            }
            if (detail.runtimeSummary.isNotEmpty()) {
                MetricsRow(metrics = detail.runtimeSummary)
            }
            detail.recentSessions.forEach { session ->
                SessionRow(session = session, onClick = {})
            }
            detail.recentUsageNotes.forEach { note ->
                Text(
                    text = note,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            detail.failureNotes.forEach { note ->
                Text(
                    text = note,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.foreground,
                )
            }
        }
    }

    uiState.selectedProbe?.let { probe ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissProbeDetail,
            title = probe.target,
            message = probe.probeType,
            icon = RipDpiIcons.Search,
        ) {
            StatusIndicator(label = probe.outcome, tone = statusTone(probe.tone))
            probe.details.forEach { detail ->
                SettingsRow(
                    title = detail.label,
                    value = detail.value,
                    monospaceValue = true,
                )
            }
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
            )
        }
    }
}

@Composable
private fun OverviewSection(
    uiState: DiagnosticsUiState,
    onSelectSession: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            DiagnosticsHealthHero(uiState = uiState)
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticsHealthHero(uiState: DiagnosticsUiState) {
    val overview = uiState.overview
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val tone = warningBannerTone(overview.health)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        WarningBanner(
            title = overview.headline,
            message = overview.body,
            tone = tone,
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
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
                    )
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_strategies),
                        selected = uiState.approaches.selectedMode == DiagnosticsApproachMode.Strategies,
                        onClick = { onSelectMode(DiagnosticsApproachMode.Strategies) },
                    )
                }
            }
        }
        items(uiState.approaches.rows, key = { it.id }) { row ->
            RipDpiCard(
                onClick = { onSelectApproach(row.id) },
                variant = if (row.id == uiState.approaches.focusedApproachId) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
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
private fun ScanSection(
    uiState: DiagnosticsUiState,
    onSelectProfile: (String) -> Unit,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                Text(
                    text = stringResource(R.string.diagnostics_profiles_title),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.scan.profiles, key = { it.id }) { profile ->
                        RipDpiChip(
                            text = profile.name,
                            selected = profile.id == uiState.scan.selectedProfileId,
                            onClick = { onSelectProfile(profile.id) },
                        )
                    }
                }
                uiState.scan.selectedProfileScopeLabel?.let { label ->
                    Text(
                        text = label,
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
                uiState.scan.runRawHint?.let { hint ->
                    WarningBanner(
                        title = stringResource(R.string.diagnostics_probe_profile_title),
                        message = hint,
                        tone =
                            if (uiState.scan.runRawEnabled) {
                                WarningBannerTone.Info
                            } else {
                                WarningBannerTone.Restricted
                            },
                    )
                }
                uiState.scan.runInPathHint?.let { hint ->
                    WarningBanner(
                        title = stringResource(R.string.diagnostics_probe_path_title),
                        message = hint,
                        tone = WarningBannerTone.Restricted,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.diagnostics_action_raw),
                        onClick = onRunRawScan,
                        modifier = Modifier.weight(1f),
                        enabled = uiState.scan.runRawEnabled,
                    )
                    RipDpiButton(
                        text = stringResource(R.string.diagnostics_action_in_path),
                        onClick = onRunInPathScan,
                        modifier = Modifier.weight(1f),
                        variant = RipDpiButtonVariant.Outline,
                        enabled = uiState.scan.runInPathEnabled,
                    )
                }
                RipDpiButton(
                    text = stringResource(R.string.diagnostics_action_cancel),
                    onClick = onCancelScan,
                    enabled = uiState.scan.isBusy,
                    variant = RipDpiButtonVariant.Destructive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        uiState.scan.activeProgress?.let { progress ->
            item {
                RipDpiCard {
                    StatusIndicator(
                        label = stringResource(R.string.diagnostics_status_running),
                        tone = StatusIndicatorTone.Warning,
                    )
                    Text(
                        text = progress.summary,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                    LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${progress.completedSteps}/${progress.totalSteps} · ${progress.phase}",
                        style = RipDpiThemeTokens.type.monoInline,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }
        }
        uiState.scan.latestSession?.let { session ->
            item {
                SessionRow(session = session, onClick = {})
            }
        }
        uiState.scan.strategyProbeReport?.let { report ->
            item {
                StrategyProbeReportCard(report = report)
            }
        }
        if (uiState.scan.latestResults.isNotEmpty()) {
            item {
                SettingsCategoryHeader(title = stringResource(R.string.diagnostics_results_section))
            }
            items(uiState.scan.latestResults, key = { it.id }) { probe ->
                ProbeResultRow(probe = probe, onClick = { onSelectProbe(probe) })
            }
        }
    }
}

@Composable
private fun StrategyProbeReportCard(report: DiagnosticsStrategyProbeReportUiModel) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    RipDpiCard {
        Text(
            text = stringResource(R.string.diagnostics_probe_recommendation_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        Text(
            text = report.recommendation.headline,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = report.recommendation.rationale,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        report.recommendation.fields.forEach { field ->
            SettingsRow(
                title = field.label,
                value = field.value,
            )
        }
        if (report.recommendation.signature.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.diagnostics_probe_signature_title),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            report.recommendation.signature.forEach { field ->
                SettingsRow(
                    title = field.label,
                    value = field.value,
                )
            }
        }
        report.families.forEach { family ->
            HorizontalDivider()
            Text(
                text = family.title,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                family.candidates.forEach { candidate ->
                    StrategyProbeCandidateRow(candidate = candidate)
                }
            }
        }
    }
}

@Composable
private fun StrategyProbeCandidateRow(candidate: DiagnosticsStrategyProbeCandidateUiModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Surface(
        shape = RipDpiThemeTokens.shapes.lg,
        color = colors.inputBackground,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(RipDpiThemeTokens.layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.label,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                StatusIndicator(
                    label = if (candidate.skipped) stringResource(R.string.diagnostics_probe_status_skipped) else candidate.outcome,
                    tone = statusTone(candidate.tone),
                )
            }
            Text(
                text = candidate.rationale,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
            MetricsRow(metrics = candidate.metrics)
        }
    }
}

@Composable
private fun LiveSection(uiState: DiagnosticsUiState) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            LiveHeroCard(
                live = uiState.live,
                health = uiState.overview.health,
            )
        }
        if (uiState.live.metrics.isNotEmpty()) {
            item {
                MetricsRow(metrics = uiState.live.metrics)
            }
        }
        if (uiState.live.trends.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_trends_section))
                    uiState.live.trends.forEach { trend ->
                        TelemetrySparkline(trend = trend)
                    }
                }
            }
        }
        uiState.live.snapshot?.let { snapshot ->
            item { SnapshotCard(snapshot = snapshot) }
        }
        if (uiState.live.contextGroups.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_context_section))
                    uiState.live.contextGroups.forEach { group ->
                        ContextGroupCard(group = group)
                    }
                }
            }
        }
        if (uiState.live.passiveEvents.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_passive_events_section))
                    uiState.live.passiveEvents.forEach { event ->
                        EventRow(event = event, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHeroCard(
    live: DiagnosticsLiveUiModel,
    health: DiagnosticsHealth,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    val palette = liveHeroPalette(health)
    val liveBadgeText = live.networkLabel ?: live.modeLabel ?: "Standby"
    val animatedContainer by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHeroContainer",
    )
    val animatedAccent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHeroAccent",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = animatedContainer,
        contentColor = colors.foreground,
        shape = RipDpiThemeTokens.shapes.xl,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIndicator(
                    label = live.statusLabel,
                    tone = health.statusTone(),
                )
                EventBadge(
                    text = liveBadgeText,
                    tone = if (live.networkLabel == null) DiagnosticsTone.Neutral else DiagnosticsTone.Info,
                )
            }
            AnimatedContent(
                targetState = live.headline,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    )
                },
                label = "liveHeroHeadline",
            ) { headline ->
                Text(
                    text = headline,
                    style = RipDpiThemeTokens.type.screenTitle,
                    color = colors.foreground,
                )
            }
            AnimatedContent(
                targetState = live.body,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    )
                },
                label = "liveHeroBody",
            ) { body ->
                Text(
                    text = body,
                    style = RipDpiThemeTokens.type.body,
                    color = colors.foreground.copy(alpha = 0.92f),
                )
            }
            if (live.highlights.isNotEmpty()) {
                LiveHighlightsGrid(highlights = live.highlights.take(4))
            }
            HorizontalDivider(color = animatedAccent.copy(alpha = 0.14f))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                AnimatedContent(
                    targetState = live.signalLabel,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        ) togetherWith androidx.compose.animation.fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                    },
                    label = "liveHeroSignalLabel",
                ) { signalLabel ->
                    Text(
                        text = signalLabel,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = animatedAccent,
                    )
                }
                AnimatedContent(
                    targetState = live.eventSummaryLabel to live.freshnessLabel,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        ) togetherWith androidx.compose.animation.fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                    },
                    label = "liveHeroMeta",
                ) { meta ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = meta.first,
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.foreground.copy(alpha = 0.82f),
                        )
                        Text(
                            text = meta.second,
                            style = RipDpiThemeTokens.type.monoSmall,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHighlightsGrid(highlights: List<DiagnosticsMetricUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    val rows = remember(highlights) { highlights.chunked(2) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                row.forEach { metric ->
                    LiveHighlightCard(
                        metric = metric,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LiveHighlightCard(
    metric: DiagnosticsMetricUiModel,
    modifier: Modifier = Modifier,
) {
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(metric.tone)
    val animatedContainer by animateColorAsState(
        targetValue = palette.container.copy(alpha = 0.92f),
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHighlightContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHighlightContent",
    )
    Surface(
        modifier = modifier,
        color = animatedContainer,
        contentColor = animatedContent,
        shape = RipDpiThemeTokens.shapes.lg,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = metric.label.uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = animatedContent.copy(alpha = 0.72f),
            )
            AnimatedContent(
                targetState = metric.value,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    )
                },
                label = "liveHighlightValue",
            ) { value ->
                Text(
                    text = value,
                    style = RipDpiThemeTokens.type.monoValue,
                    color = animatedContent,
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
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
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.sessions.pathModes, key = { it }) { pathMode ->
                        RipDpiChip(
                            text = pathMode,
                            selected = uiState.sessions.filters.pathMode == pathMode,
                            onClick = { onPathModeFilter(pathMode) },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.sessions.statuses, key = { it }) { status ->
                        RipDpiChip(
                            text = status.replaceFirstChar { it.uppercase() },
                            selected = uiState.sessions.filters.status == status,
                            onClick = { onStatusFilter(status) },
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

    LaunchedEffect(uiState.events.filters.autoScroll, uiState.events.events.firstOrNull()?.id) {
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
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
                )
                SettingsRow(
                    title = stringResource(R.string.logs_auto_scroll_title),
                    subtitle = stringResource(R.string.logs_auto_scroll_body),
                    checked = uiState.events.filters.autoScroll,
                    onCheckedChange = onAutoScroll,
                    showDivider = true,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.events.availableSources, key = { it }) { source ->
                        RipDpiChip(
                            text = source,
                            selected = uiState.events.filters.source == source,
                            onClick = { onToggleFilter(source, null) },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    items(uiState.events.availableSeverities, key = { it }) { severity ->
                        RipDpiChip(
                            text = severity,
                            selected = uiState.events.filters.severity == severity,
                            onClick = { onToggleFilter(null, severity) },
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                Text(
                    text = uiState.share.previewTitle,
                    style = RipDpiThemeTokens.type.screenTitle,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = uiState.share.previewBody,
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                MetricsRow(metrics = uiState.share.metrics)
                uiState.share.archiveStateMessage?.let { message ->
                    StatusIndicator(
                        label = message,
                        tone = statusTone(uiState.share.archiveStateTone),
                    )
                }
            }
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
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_archive_title),
                body = stringResource(R.string.diagnostics_save_archive_body, uiState.share.latestArchiveFileName ?: "latest archive"),
                buttonLabel = stringResource(R.string.diagnostics_save_archive_action),
                onClick = { onSaveArchive(uiState.share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                variant = RipDpiButtonVariant.Outline,
                enabled = !uiState.share.isArchiveBusy,
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
            )
        }
    }
}

@Composable
private fun ShareActionCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color,
    variant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    enabled: Boolean = true,
) {
    RipDpiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = body,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            androidx.compose.material3.Icon(
                imageVector = RipDpiIcons.Share,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(RipDpiIconSizes.Medium),
            )
        }
        RipDpiButton(
            text = buttonLabel,
            onClick = onClick,
            variant = variant,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SnapshotCard(snapshot: DiagnosticsNetworkSnapshotUiModel) {
    RipDpiCard {
        Text(
            text = snapshot.title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = snapshot.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.foreground,
        )
        snapshot.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                monospaceValue = field.value.length > 18,
                showDivider = index != snapshot.fields.lastIndex,
            )
        }
    }
}

@Composable
private fun ContextGroupCard(group: DiagnosticsContextGroupUiModel) {
    RipDpiCard {
        Text(
            text = group.title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        group.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                monospaceValue = field.value.length > 18,
                showDivider = index != group.fields.lastIndex,
            )
        }
    }
}

@Composable
private fun MetricsRow(metrics: List<DiagnosticsMetricUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    if (metrics.isEmpty()) {
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        itemsIndexed(
            items = metrics,
            key = { index, metric -> "${metric.label}-$index" },
            contentType = { _, _ -> "metric" },
        ) { _, metric ->
            TelemetryMetricCard(metric = metric)
        }
    }
}

@Composable
private fun TelemetryMetricCard(metric: DiagnosticsMetricUiModel) {
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(metric.tone)
    val animatedContainer by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "telemetryMetricContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "telemetryMetricContent",
    )
    Surface(
        color = animatedContainer,
        contentColor = animatedContent,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = metric.label.uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = animatedContent.copy(alpha = 0.75f),
            )
            AnimatedContent(
                targetState = metric.value,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    )
                },
                label = "telemetryMetricValue",
            ) { value ->
                Text(
                    text = value,
                    style = RipDpiThemeTokens.type.monoValue,
                    color = animatedContent,
                )
            }
        }
    }
}

@Composable
private fun TelemetrySparkline(trend: com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel) {
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(trend.tone)
    val dividerColor = RipDpiThemeTokens.colors.divider
    val animatedStrokeColor by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "telemetrySparklineStroke",
    )
    var previousValues by remember(trend.label) { mutableStateOf(trend.values) }
    var currentValues by remember(trend.label) { mutableStateOf(trend.values) }
    val transitionProgress = remember(trend.label) { Animatable(1f) }

    LaunchedEffect(trend.values) {
        previousValues = currentValues.ifEmpty { trend.values }
        currentValues = trend.values
        transitionProgress.snapTo(0f)
        transitionProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
        )
    }
    RipDpiCard {
        Text(
            text = trend.label,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(84.dp),
        ) {
            val values = interpolatedSeries(previousValues, currentValues, transitionProgress.value)
            if (values.isEmpty()) {
                return@Canvas
            }
            val min = values.minOrNull() ?: 0f
            val max = values.maxOrNull() ?: 0f
            val range = (max - min).takeIf { it > 0f } ?: 1f
            val path =
                Path().apply {
                    values.forEachIndexed { index, value ->
                        val x =
                            if (values.size == 1) {
                                0f
                            } else {
                                size.width * index.toFloat() / (values.lastIndex.toFloat())
                            }
                        val y = size.height - ((value - min) / range) * size.height
                        if (index == 0) {
                            moveTo(x, y)
                        } else {
                            lineTo(x, y)
                        }
                    }
                }
            drawPath(
                path = path,
                color = animatedStrokeColor,
                style = Stroke(width = 4f, cap = StrokeCap.Round),
            )
            drawLine(
                color = dividerColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }
    }
}

private fun interpolatedSeries(
    from: List<Float>,
    to: List<Float>,
    progress: Float,
): List<Float> {
    if (to.isEmpty()) {
        return from
    }
    if (from.isEmpty()) {
        return to
    }

    val pointCount = max(from.size, to.size)
    return List(pointCount) { index ->
        val samplePosition =
            if (pointCount == 1) {
                0f
            } else {
                index.toFloat() / (pointCount - 1).toFloat()
            }
        lerpFloat(
            start = sampleSeries(from, samplePosition),
            stop = sampleSeries(to, samplePosition),
            fraction = progress,
        )
    }
}

private fun sampleSeries(
    values: List<Float>,
    position: Float,
): Float {
    if (values.isEmpty()) {
        return 0f
    }
    if (values.size == 1) {
        return values.first()
    }

    val scaledIndex = position.coerceIn(0f, 1f) * values.lastIndex
    val lowerIndex = floor(scaledIndex).toInt()
    val upperIndex = ceil(scaledIndex).toInt().coerceAtMost(values.lastIndex)
    val localFraction = scaledIndex - lowerIndex
    return lerpFloat(values[lowerIndex], values[upperIndex], localFraction)
}

private fun lerpFloat(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction

@Composable
private fun SessionRow(
    session: DiagnosticsSessionRowUiModel,
    onClick: () -> Unit,
) {
    RipDpiCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
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
            }
            StatusIndicator(
                label = session.status,
                tone = statusTone(session.tone),
            )
        }
        MetricsRow(metrics = session.metrics)
    }
}

@Composable
private fun ProbeResultRow(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = probe.target,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = probe.probeType,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            StatusIndicator(
                label = probe.outcome,
                tone = statusTone(probe.tone),
            )
        }
        probe.details.take(2).forEach { detail ->
            SettingsRow(
                title = detail.label,
                value = detail.value,
                monospaceValue = true,
            )
        }
    }
}

@Composable
private fun EventRow(
    event: DiagnosticsEventUiModel,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(
        onClick = onClick,
        paddingValues = androidx.compose.foundation.layout.PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EventBadge(text = event.source, tone = event.tone)
                    EventBadge(text = event.severity, tone = event.tone)
                }
                Text(
                    text = event.message,
                    style = RipDpiThemeTokens.type.body,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.createdAtLabel,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun EventBadge(
    text: String,
    tone: DiagnosticsTone,
) {
    val palette = metricPalette(tone)
    Surface(
        color = palette.container,
        contentColor = palette.content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = RipDpiThemeTokens.type.monoSmall,
            color = palette.content,
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
) {
    RipDpiCard {
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

@Composable
private fun DiagnosticsSection.label(): String =
    when (this) {
        DiagnosticsSection.Overview -> stringResource(R.string.diagnostics_overview_section)
        DiagnosticsSection.Scan -> stringResource(R.string.diagnostics_scan_section)
        DiagnosticsSection.Live -> stringResource(R.string.diagnostics_monitor_section)
        DiagnosticsSection.Approaches -> stringResource(R.string.diagnostics_approaches_title)
        DiagnosticsSection.Share -> stringResource(R.string.diagnostics_share_section)
    }

@Composable
private fun DiagnosticsHealth.displayLabel(): String =
    when (this) {
        DiagnosticsHealth.Healthy -> stringResource(R.string.diagnostics_health_healthy)
        DiagnosticsHealth.Attention -> stringResource(R.string.diagnostics_health_attention)
        DiagnosticsHealth.Degraded -> stringResource(R.string.diagnostics_health_degraded)
        DiagnosticsHealth.Idle -> stringResource(R.string.diagnostics_health_idle)
    }

private fun DiagnosticsHealth.statusTone(): StatusIndicatorTone =
    when (this) {
        DiagnosticsHealth.Healthy -> StatusIndicatorTone.Active
        DiagnosticsHealth.Attention -> StatusIndicatorTone.Warning
        DiagnosticsHealth.Degraded -> StatusIndicatorTone.Error
        DiagnosticsHealth.Idle -> StatusIndicatorTone.Idle
    }

private fun statusTone(tone: DiagnosticsTone): StatusIndicatorTone =
    when (tone) {
        DiagnosticsTone.Positive -> StatusIndicatorTone.Active
        DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
        DiagnosticsTone.Negative -> StatusIndicatorTone.Error
        DiagnosticsTone.Neutral, DiagnosticsTone.Info -> StatusIndicatorTone.Idle
    }

@Composable
private fun warningBannerTone(health: DiagnosticsHealth): WarningBannerTone =
    when (health) {
        DiagnosticsHealth.Healthy -> WarningBannerTone.Info
        DiagnosticsHealth.Attention -> WarningBannerTone.Warning
        DiagnosticsHealth.Degraded -> WarningBannerTone.Error
        DiagnosticsHealth.Idle -> WarningBannerTone.Restricted
    }

@Composable
private fun liveHeroPalette(health: DiagnosticsHealth): MetricPalette {
    val colors = RipDpiThemeTokens.colors
    return when (health) {
        DiagnosticsHealth.Healthy ->
            MetricPalette(
                container = colors.muted,
                content = colors.success,
            )

        DiagnosticsHealth.Attention ->
            MetricPalette(
                container = colors.warningContainer,
                content = colors.warning,
            )

        DiagnosticsHealth.Degraded ->
            MetricPalette(
                container = colors.destructiveContainer,
                content = colors.destructive,
            )

        DiagnosticsHealth.Idle ->
            MetricPalette(
                container = colors.accent,
                content = colors.foreground,
            )
    }
}

@Composable
private fun metricPalette(tone: DiagnosticsTone): MetricPalette {
    val colors = RipDpiThemeTokens.colors
    return when (tone) {
        DiagnosticsTone.Positive ->
            MetricPalette(
                container = colors.muted,
                content = colors.success,
            )

        DiagnosticsTone.Warning ->
            MetricPalette(
                container = colors.warningContainer,
                content = colors.warning,
            )

        DiagnosticsTone.Negative ->
            MetricPalette(
                container = colors.destructiveContainer,
                content = colors.destructive,
            )

        DiagnosticsTone.Info ->
            MetricPalette(
                container = colors.infoContainer,
                content = colors.info,
            )

        DiagnosticsTone.Neutral ->
            MetricPalette(
                container = colors.inputBackground,
                content = colors.foreground,
            )
    }
}

private data class MetricPalette(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)
