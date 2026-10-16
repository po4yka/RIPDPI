package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsAutomaticProbeCalloutUiModel
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsRememberedNetworkUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.diagnosticsOutcome
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal enum class DiagnosticsSimpleFunnelAction {
    Inspect,
    Review,
    Unavailable,
}

internal fun DiagnosticsScanUiModel.simpleFunnelAction(isActiveScan: Boolean): DiagnosticsSimpleFunnelAction {
    val hasMatchingSession = selectedProfileId == null || latestSession?.profileId == selectedProfileId
    if (strategyProbeReport == null || latestSession == null || isActiveScan || !hasMatchingSession) {
        return DiagnosticsSimpleFunnelAction.Unavailable
    }
    return if (strategyProbeReport.auditAssessment?.confidence?.level == StrategyProbeAuditConfidenceLevel.HIGH) {
        DiagnosticsSimpleFunnelAction.Inspect
    } else {
        DiagnosticsSimpleFunnelAction.Review
    }
}

@Composable
internal fun DiagnosticsSectionSwitcher(
    selectedSection: DiagnosticsSection,
    onSelectSection: (DiagnosticsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val selectedTabIndex = DiagnosticsSection.entries.indexOf(selectedSection)
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier.fillMaxWidth().padding(bottom = spacing.sm),
        containerColor = colors.background,
        contentColor = colors.foreground,
        edgePadding = RipDpiThemeTokens.spacing.sm,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
                width = Dp.Unspecified,
                color = colors.foreground,
            )
        },
        divider = { HorizontalDivider(color = colors.divider) },
    ) {
        DiagnosticsSection.entries.forEach { section ->
            Tab(
                selected = selectedSection == section,
                onClick = { onSelectSection(section) },
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .ripDpiTestTag(RipDpiTestTags.diagnosticsSection(section)),
                selectedContentColor = colors.foreground,
                unselectedContentColor = colors.mutedForeground,
            ) {
                Text(
                    text = section.label(),
                    modifier =
                        Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = spacing.lg, vertical = spacing.sm),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
internal fun OverviewSection(
    overview: DiagnosticsOverviewUiModel,
    scan: DiagnosticsScanUiModel,
    live: com.poyka.ripdpi.activities.DiagnosticsLiveUiModel,
    isActiveScan: Boolean,
    onSelectSection: (DiagnosticsSection) -> Unit,
    onRunScan: () -> Unit,
    onReviewRecommendedPath: () -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenHistory: () -> Unit,
    expertMode: Boolean = true,
    homeDiagnostics: HomeDiagnosticsUiState = HomeDiagnosticsUiState(),
    onStartVerifiedVpn: () -> Unit = {},
    runNetworkCheck: Boolean = false,
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
        item(key = "outcome", contentType = "outcome") {
            DiagnosticsOutcomeCard(
                outcome = diagnosticsOutcome(overview, scan, homeDiagnostics, isActiveScan),
                runNetworkCheck = runNetworkCheck,
                homeDiagnostics = homeDiagnostics,
                onRunScan = {
                    onSelectSection(DiagnosticsSection.Scan)
                    onRunScan()
                },
                onOpenScan = { onSelectSection(DiagnosticsSection.Scan) },
                onStartVerifiedVpn = onStartVerifiedVpn,
            )
        }
        if (scan.strategyProbeReport != null) {
            item(key = "candidate_review", contentType = "candidate_review") {
                DiagnosticsSimpleFunnelCard(
                    scan = scan,
                    isActiveScan = isActiveScan,
                    onReviewChoices = { onSelectSection(DiagnosticsSection.Scan) },
                    onReviewRecommendedPath = onReviewRecommendedPath,
                )
            }
        }
        if (expertMode && overview.metrics.isNotEmpty()) {
            item(key = "technical_metrics", contentType = "technical_metrics") {
                CollapsibleSection(title = stringResource(R.string.diagnostics_technical_details)) {
                    OverviewStatGrid(metrics = overview.metrics)
                }
            }
        }
        if (live.health != DiagnosticsHealth.Idle && live.metrics.isNotEmpty()) {
            item(key = "live_runtime", contentType = "live_runtime") {
                RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                    StatusIndicator(
                        label = live.statusLabel,
                        tone = statusTone(live.statusTone),
                        pulsing = live.health != DiagnosticsHealth.Idle,
                    )
                    MetricsRow(metrics = live.highlights)
                }
            }
        }
        overview.activeProfile?.takeIf { expertMode }?.let { profile ->
            item(key = "active_profile", contentType = "active_profile") {
                RipDpiCard {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.diagnostics_profiles_title).uppercase(),
                        style = RipDpiThemeTokens.type.sectionTitle,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                    androidx.compose.material3.Text(
                        text = profile.name,
                        style = RipDpiThemeTokens.type.screenTitle,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                    androidx.compose.material3.Text(
                        text = profile.source.replaceFirstChar { it.uppercase() },
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }
        }
        networkDetailsItem(overview)
        if (overview.latestSession != null || overview.recentAutomaticProbe != null) {
            item(key = "recent_activity", contentType = "recent_activity") {
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_recent_activity_section),
                    defaultExpanded = expertMode,
                ) {
                    overview.latestSession?.let { session ->
                        SessionRow(session = session, onClick = { onSelectSession(session.id) })
                    }
                    overview.recentAutomaticProbe?.let { automaticProbe ->
                        AutomaticProbeHistoryCard(
                            callout = automaticProbe,
                            onOpenHistory = onOpenHistory,
                        )
                    }
                }
            }
        }
        if (overview.rememberedNetworks.isNotEmpty()) {
            item(key = "remembered_networks", contentType = "remembered_networks") {
                CollapsibleSection(
                    title = stringResource(R.string.diagnostics_remembered_networks_section),
                    badgeCount = overview.rememberedNetworks.size,
                ) {
                    RememberedNetworkPoliciesCard(policies = overview.rememberedNetworks)
                }
            }
        }
        if (overview.warnings.isNotEmpty()) {
            item(key = "warnings", contentType = "warnings") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_attention_section))
                    overview.warnings.forEach { warning ->
                        EventRow(event = diagnosticWarningPresentation(warning, expertMode), onClick = null)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.networkDetailsItem(overview: DiagnosticsOverviewUiModel) {
    if (overview.latestSnapshot != null || overview.contextSummary != null) {
        item(key = "network_details", contentType = "network_details") {
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
}

@Composable
private fun DiagnosticsSimpleFunnelCard(
    scan: DiagnosticsScanUiModel,
    isActiveScan: Boolean,
    onReviewChoices: () -> Unit,
    onReviewRecommendedPath: () -> Unit,
) {
    val report = scan.strategyProbeReport
    val action = scan.simpleFunnelAction(isActiveScan)
    val hasHighConfidence = action == DiagnosticsSimpleFunnelAction.Inspect
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSimpleFunnel),
    ) {
        scan.latestSession?.let { session ->
            Text(
                text = session.title,
                style = RipDpiThemeTokens.type.sectionTitle,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            Text(
                text = stringResource(R.string.diagnostics_outcome_time_format, session.startedAtLabel),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        report?.recommendation?.let { recommendation ->
            Text(
                text = recommendation.headline,
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.foreground,
            )
        }
        RipDpiButton(
            text =
                stringResource(
                    if (hasHighConfidence) {
                        R.string.diagnostics_guided_review_recommendation
                    } else {
                        R.string.diagnostics_simple_funnel_review_action
                    },
                ),
            onClick = if (hasHighConfidence) onReviewRecommendedPath else onReviewChoices,
            enabled = action != DiagnosticsSimpleFunnelAction.Unavailable,
            variant = RipDpiButtonVariant.Outline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsSimpleApply),
        )
    }
}

@Composable
private fun RememberedNetworkPoliciesCard(policies: List<DiagnosticsRememberedNetworkUiModel>) {
    TrackRecomposition("RememberedNetworkPoliciesCard")
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard {
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_remembered_networks_title).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            policies.forEachIndexed { index, policy ->
                if (index > 0) {
                    androidx.compose.material3.HorizontalDivider(color = colors.divider)
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    StatusIndicator(
                        label = policy.status,
                        tone =
                            when (policy.statusTone) {
                                DiagnosticsTone.Positive -> StatusIndicatorTone.Active
                                DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
                                DiagnosticsTone.Negative -> StatusIndicatorTone.Error
                                DiagnosticsTone.Info, DiagnosticsTone.Neutral -> StatusIndicatorTone.Idle
                            },
                    )
                    androidx.compose.material3.Text(
                        text = policy.title,
                        style = type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    androidx.compose.material3.Text(
                        text = policy.subtitle,
                        style = type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    androidx.compose.material3.Text(
                        text = policy.strategyLabel,
                        style = type.caption,
                        color = colors.foreground,
                    )
                    androidx.compose.material3.Text(
                        text =
                            listOfNotNull(
                                stringResource(R.string.diagnostics_overview_policy_success, policy.successCount),
                                stringResource(R.string.diagnostics_overview_policy_failures, policy.failureCount),
                                policy.lastValidatedLabel?.let {
                                    stringResource(R.string.diagnostics_overview_policy_validated, it)
                                },
                                policy.lastAppliedLabel?.let {
                                    stringResource(R.string.diagnostics_overview_policy_applied, it)
                                },
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
        androidx.compose.material3.Text(
            text = callout.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        androidx.compose.material3.Text(
            text = callout.summary,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
        androidx.compose.material3.Text(
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
