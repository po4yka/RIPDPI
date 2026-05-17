package com.poyka.ripdpi.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ControlPlaneHealthSeverityUiModel
import com.poyka.ripdpi.activities.ControlPlaneHealthSummaryUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsLatestAuditUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.indicators.AnalysisProgressIndicator
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun HomeApproachCard(
    summary: HomeApproachSummaryUiState,
    onOpenDiagnostics: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeApproachCard),
        onClick = onOpenDiagnostics,
        variant = RipDpiCardVariant.Elevated,
    ) {
        Text(
            text = stringResource(R.string.home_approach_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
        )
        Text(
            text = summary.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = "${summary.verification} · ${summary.successRate}",
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = summary.supportingText,
            style = RipDpiThemeTokens.type.monoConfig,
            color = colors.foreground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.home_approach_cta),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
internal fun HomeHistoryCard(onOpenHistory: () -> Unit) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeHistoryCard),
        onClick = onOpenHistory,
        variant = RipDpiCardVariant.Outlined,
    ) {
        Text(
            text = stringResource(R.string.history_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.home_history_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.home_history_body),
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.home_history_cta),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
internal fun HomeDiagnosticsCard(
    uiState: MainUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onRunFullAnalysis: () -> Unit,
    onRunQuickAnalysis: () -> Unit,
    onStartVerifiedVpn: () -> Unit,
    onTogglePcapRecording: () -> Unit,
) {
    DiagnosticsSummaryCard(
        uiState = uiState,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenHistory = onOpenHistory,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenModeEditor = onOpenModeEditor,
        onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
        onRunFullAnalysis = onRunFullAnalysis,
        onRunQuickAnalysis = onRunQuickAnalysis,
        onStartVerifiedVpn = onStartVerifiedVpn,
        onTogglePcapRecording = onTogglePcapRecording,
    )
}

@Composable
private fun DiagnosticsSummaryCard(
    uiState: MainUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onRunFullAnalysis: () -> Unit,
    onRunQuickAnalysis: () -> Unit,
    onStartVerifiedVpn: () -> Unit,
    onTogglePcapRecording: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsCard),
        variant = RipDpiCardVariant.Elevated,
    ) {
        HomeDiagnosticsCardHeader()
        HomeDiagnosticsCardStatusSections(
            uiState = uiState,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
        )
        Spacer(modifier = Modifier.height(spacing.md))
        HorizontalDivider(color = colors.divider)
        Spacer(modifier = Modifier.height(spacing.md))
        AnalysisStatusPanel(
            uiState = uiState,
            onRunFullAnalysis = onRunFullAnalysis,
            onRunQuickAnalysis = onRunQuickAnalysis,
            onTogglePcapRecording = onTogglePcapRecording,
        )
        HomeVerifiedVpnAction(uiState = uiState, onStartVerifiedVpn = onStartVerifiedVpn)
    }
}

@Composable
private fun HomeDiagnosticsCardHeader() {
    val colors = RipDpiThemeTokens.colors
    Text(
        text = stringResource(R.string.home_diagnostics_section),
        style = RipDpiThemeTokens.type.sectionTitle,
        color = colors.mutedForeground,
    )
    Text(
        text = stringResource(R.string.home_diagnostics_title),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    Text(
        text = stringResource(R.string.home_diagnostics_body),
        style = RipDpiThemeTokens.type.body,
        color = colors.foreground,
    )
}

@Composable
private fun HomeDiagnosticsCardStatusSections(
    uiState: MainUiState,
    onOpenAdvancedSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    uiState.controlPlaneHealthSummary?.let { summary ->
        Spacer(modifier = Modifier.height(spacing.sm))
        HomeControlPlaneHealthCard(
            summary = summary,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
        )
    }
    uiState.homeDiagnostics.latestAudit?.let { result ->
        Spacer(modifier = Modifier.height(spacing.sm))
        HomeLatestAuditSection(result = result)
    }
    uiState.homeDiagnostics.remediationLadder?.let { ladder ->
        Spacer(modifier = Modifier.height(spacing.sm))
        HomeRemediationSection(
            ladder = ladder,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
        )
    }
}

@Composable
private fun HomeVerifiedVpnAction(
    uiState: MainUiState,
    onStartVerifiedVpn: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val action = uiState.homeDiagnostics.verifiedVpnAction
    Spacer(modifier = Modifier.height(spacing.md))
    Text(
        text = action.supportingText,
        style = RipDpiThemeTokens.type.secondaryBody,
        color = if (!action.enabled) colors.mutedForeground else colors.foreground,
    )
    Spacer(modifier = Modifier.height(spacing.sm))
    RipDpiButton(
        text = action.label,
        onClick = onStartVerifiedVpn,
        enabled = action.enabled,
        variant = RipDpiButtonVariant.Outline,
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsVerifiedVpn),
    )
}

@Composable
private fun HomeLatestAuditSection(result: HomeDiagnosticsLatestAuditUiState) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val allStagesCompleted = result.completedStageCount == result.totalStageCount && result.totalStageCount > 0
    val headlineColor =
        when {
            result.failedStageCount > 0 -> colors.destructive
            allStagesCompleted -> colors.success
            else -> colors.foreground
        }
    Text(text = result.headline, style = RipDpiThemeTokens.type.bodyEmphasis, color = headlineColor)
    if (result.totalStageCount > 0) {
        Spacer(modifier = Modifier.height(spacing.xs))
        StageProgressIndicator(
            completedCount = result.completedStageCount,
            failedCount = result.failedStageCount,
            totalCount = result.totalStageCount,
        )
    }
    result.recommendationSummary?.let { recommendation ->
        Text(text = recommendation, style = RipDpiThemeTokens.type.secondaryBody, color = colors.foreground)
    }
    if (result.stale) {
        Text(
            text = stringResource(R.string.home_diagnostics_run_again),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.warning,
        )
    }
}

@Composable
private fun HomeRemediationSection(
    ladder: DiagnosticsRemediationLadderUiModel,
    onOpenAdvancedSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
) {
    DiagnosticsRemediationLadderCard(
        ladder = ladder,
        onAction = { action ->
            when (action.kind) {
                DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS -> {
                    onOpenAdvancedSettings()
                }

                DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS -> {
                    onOpenDiagnostics()
                }

                DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY -> {
                    onOpenHistory()
                }

                DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR -> {
                    onOpenModeEditor()
                }

                DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER -> {
                    action.targetUrl?.let(onOpenOwnedStackBrowser)
                }

                DiagnosticsRemediationActionKindUiModel.OPEN_VPN_PERMISSION,
                DiagnosticsRemediationActionKindUiModel.OPEN_DNS_SETTINGS,
                -> {
                    Unit
                }
            }
        },
        cardTestTag = RipDpiTestTags.HomeDiagnosticsRemediationCard,
        actionTestTag = RipDpiTestTags.HomeDiagnosticsRemediationAction,
    )
}

@Composable
private fun AnalysisStatusPanel(
    uiState: MainUiState,
    onRunFullAnalysis: () -> Unit,
    onRunQuickAnalysis: () -> Unit,
    onTogglePcapRecording: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val analysisProgress = uiState.homeDiagnostics.analysisProgress
    val isQuickScan = uiState.homeDiagnostics.quickScanBusy
    val showFullAnalysisProgress =
        uiState.homeDiagnostics.analysisAction.busy && analysisProgress != null && !isQuickScan
    Crossfade(
        targetState = showFullAnalysisProgress,
        animationSpec = motion.stateTween(),
        label = "analysisProgressSwitch",
    ) { showProgress ->
        if (showProgress && analysisProgress != null) {
            AnalysisProgressIndicator(
                stages = analysisProgress.stages,
                activeStageIndex = analysisProgress.activeStageIndex,
                stageLabel = uiState.homeDiagnostics.analysisAction.supportingText,
            )
        } else {
            Text(
                text = uiState.homeDiagnostics.analysisAction.supportingText,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
    Spacer(modifier = Modifier.height(spacing.sm))
    DiagnosticsActionRow(
        primaryLabel = uiState.homeDiagnostics.analysisAction.label,
        primaryEnabled = uiState.homeDiagnostics.analysisAction.enabled,
        quickScanBusy = isQuickScan,
        onRunFullAnalysis = onRunFullAnalysis,
        onRunQuickAnalysis = onRunQuickAnalysis,
        quickStatusContent = { HomeQuickScanStatus(uiState = uiState) },
    )
    if (uiState.homeDiagnostics.pcapToggleVisible) {
        Spacer(modifier = Modifier.height(spacing.sm))
        RipDpiSwitch(
            checked = uiState.homeDiagnostics.pcapRecordingRequested,
            onCheckedChange = { onTogglePcapRecording() },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.home_diagnostics_pcap_toggle),
            helperText = stringResource(R.string.home_diagnostics_pcap_helper),
            enabled = uiState.homeDiagnostics.analysisAction.enabled,
            testTag = RipDpiTestTags.HomeDiagnosticsPcapToggle,
        )
    }
}

@Composable
private fun DiagnosticsActionRow(
    primaryLabel: String,
    primaryEnabled: Boolean,
    quickScanBusy: Boolean,
    onRunFullAnalysis: () -> Unit,
    onRunQuickAnalysis: () -> Unit,
    quickStatusContent: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiButton(
        text = primaryLabel,
        onClick = onRunFullAnalysis,
        enabled = primaryEnabled,
        variant = RipDpiButtonVariant.Primary,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis),
    )
    Spacer(modifier = Modifier.height(spacing.sm))
    quickStatusContent()
    Spacer(modifier = Modifier.height(spacing.xs))
    RipDpiButton(
        text = stringResource(R.string.home_diagnostics_quick_scan),
        onClick = onRunQuickAnalysis,
        enabled = primaryEnabled,
        loading = quickScanBusy,
        variant = RipDpiButtonVariant.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HomeQuickScanStatus(uiState: MainUiState) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val analysisProgress = uiState.homeDiagnostics.analysisProgress
    val isQuickScan = uiState.homeDiagnostics.quickScanBusy
    val showQuickScanProgress = isQuickScan && analysisProgress != null
    Crossfade(
        targetState = showQuickScanProgress,
        animationSpec = motion.stateTween(),
        label = "quickScanProgressSwitch",
    ) { showProgress ->
        if (showProgress && analysisProgress != null) {
            AnalysisProgressIndicator(
                stages = analysisProgress.stages,
                activeStageIndex = analysisProgress.activeStageIndex,
                stageLabel = uiState.homeDiagnostics.analysisAction.supportingText,
            )
        } else {
            Text(
                text = stringResource(R.string.home_diagnostics_quick_scan_body),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun HomeControlPlaneHealthCard(
    summary: ControlPlaneHealthSummaryUiModel,
    onOpenAdvancedSettings: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val tone =
        when (summary.severity) {
            ControlPlaneHealthSeverityUiModel.Error -> {
                com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone.Error
            }

            ControlPlaneHealthSeverityUiModel.Warning -> {
                com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone.Warning
            }

            ControlPlaneHealthSeverityUiModel.Info -> {
                com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone.Idle
            }
        }

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeControlPlaneHealthCard),
        variant = RipDpiCardVariant.Outlined,
    ) {
        com.poyka.ripdpi.ui.components.indicators.StatusIndicator(
            label = summary.title,
            tone = tone,
        )
        Text(
            text = summary.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.foreground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            summary.items.forEach { item ->
                Text(
                    text = "${item.label}: ${item.summary}",
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
        }
        RipDpiButton(
            text = summary.actionLabel,
            onClick = onOpenAdvancedSettings,
            variant = RipDpiButtonVariant.Secondary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs)
                    .ripDpiTestTag(RipDpiTestTags.HomeControlPlaneHealthAction),
        )
    }
}
