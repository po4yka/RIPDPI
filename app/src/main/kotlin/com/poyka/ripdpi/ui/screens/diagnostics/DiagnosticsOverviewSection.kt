package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsAutomaticProbeCalloutUiModel
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsRememberedNetworkUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DiagnosticsSectionSwitcher(
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
@Suppress("LongMethod")
internal fun OverviewSection(
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
                        SessionRow(session = session, onClick = { onSelectSession(session.id) })
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

@Composable
private fun HistoryCalloutCard(onOpenHistory: () -> Unit) {
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_open_history_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        androidx.compose.material3.Text(
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
            androidx.compose.material3.Text(
                text = stringResource(R.string.diagnostics_overview_section).uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = colors.mutedForeground,
            )
            MetricsRow(metrics = overview.metrics)
        }
    }
}
