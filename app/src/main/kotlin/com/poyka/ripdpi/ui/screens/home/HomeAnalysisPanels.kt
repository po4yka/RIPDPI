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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ControlPlaneHealthSeverityUiModel
import com.poyka.ripdpi.activities.ControlPlaneHealthSummaryUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.indicators.AnalysisProgressIndicator
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val stageContainerAlpha = 0.06f
private const val homeDiagnosticsDetectorsShown = 5

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
@Suppress("LongMethod")
internal fun HomeDiagnosticsCard(
    uiState: MainUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onRunFullAnalysis: () -> Unit,
    onRunQuickAnalysis: () -> Unit,
    onStartVerifiedVpn: () -> Unit,
    onTogglePcapRecording: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsCard),
        variant = RipDpiCardVariant.Elevated,
    ) {
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
        uiState.controlPlaneHealthSummary?.let { summary ->
            Spacer(modifier = Modifier.height(spacing.sm))
            HomeControlPlaneHealthCard(
                summary = summary,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
            )
        }
        uiState.homeDiagnostics.latestAudit?.let { result ->
            Spacer(modifier = Modifier.height(spacing.sm))
            val allStagesCompleted =
                result.completedStageCount == result.totalStageCount && result.totalStageCount > 0
            val headlineColor =
                when {
                    result.failedStageCount > 0 -> colors.destructive
                    allStagesCompleted -> colors.success
                    else -> colors.foreground
                }
            Text(
                text = result.headline,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = headlineColor,
            )
            if (result.totalStageCount > 0) {
                Spacer(modifier = Modifier.height(spacing.xs))
                StageProgressIndicator(
                    completedCount = result.completedStageCount,
                    failedCount = result.failedStageCount,
                    totalCount = result.totalStageCount,
                )
            }
            result.recommendationSummary?.let { recommendation ->
                Text(
                    text = recommendation,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.foreground,
                )
            }
            if (result.stale) {
                Text(
                    text = stringResource(R.string.home_diagnostics_run_again),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.warning,
                )
            }
        }
        uiState.homeDiagnostics.remediationLadder?.let { ladder ->
            Spacer(modifier = Modifier.height(spacing.sm))
            DiagnosticsRemediationLadderCard(
                ladder = ladder,
                onAction = { action ->
                    when (action.kind) {
                        DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS -> onOpenAdvancedSettings()

                        DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS -> onOpenDiagnostics()

                        DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY -> onOpenHistory()

                        DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR -> onOpenModeEditor()

                        DiagnosticsRemediationActionKindUiModel.OPEN_VPN_PERMISSION,
                        DiagnosticsRemediationActionKindUiModel.OPEN_DNS_SETTINGS,
                        DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER,
                        -> Unit
                    }
                },
                cardTestTag = RipDpiTestTags.HomeDiagnosticsRemediationCard,
                actionTestTag = RipDpiTestTags.HomeDiagnosticsRemediationAction,
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        HorizontalDivider(color = colors.divider)
        Spacer(modifier = Modifier.height(spacing.md))
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
        RipDpiButton(
            text = uiState.homeDiagnostics.analysisAction.label,
            onClick = onRunFullAnalysis,
            enabled = uiState.homeDiagnostics.analysisAction.enabled,
            variant = RipDpiButtonVariant.Primary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis),
        )
        Spacer(modifier = Modifier.height(spacing.sm))
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
        Spacer(modifier = Modifier.height(spacing.xs))
        RipDpiButton(
            text = stringResource(R.string.home_diagnostics_quick_scan),
            onClick = onRunQuickAnalysis,
            enabled = uiState.homeDiagnostics.analysisAction.enabled,
            loading = isQuickScan,
            variant = RipDpiButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
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
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = uiState.homeDiagnostics.verifiedVpnAction.supportingText,
            style = RipDpiThemeTokens.type.secondaryBody,
            color =
                if (!uiState.homeDiagnostics.verifiedVpnAction.enabled) {
                    colors.mutedForeground
                } else {
                    colors.foreground
                },
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        RipDpiButton(
            text = uiState.homeDiagnostics.verifiedVpnAction.label,
            onClick = onStartVerifiedVpn,
            enabled = uiState.homeDiagnostics.verifiedVpnAction.enabled,
            variant = RipDpiButtonVariant.Outline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsVerifiedVpn),
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun HomeDiagnosticsBottomSheetHost(
    uiState: MainUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onShareAnalysis: () -> Unit,
    onDismissAnalysisSheet: () -> Unit,
    onDismissVerificationSheet: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors

    uiState.homeDiagnostics.analysisSheet?.let { sheet ->
        val openDiagnosticsFromSheet = {
            onDismissAnalysisSheet()
            onOpenDiagnostics()
        }
        RipDpiBottomSheet(
            onDismissRequest = onDismissAnalysisSheet,
            title = stringResource(R.string.home_diagnostics_analysis_sheet_title),
            message = sheet.headline,
            icon = RipDpiIcons.Search,
            testTag = RipDpiTestTags.HomeDiagnosticsAnalysisSheet,
            primaryAction =
                RipDpiSheetAction(
                    label = stringResource(R.string.home_diagnostics_share_action),
                    onClick = onShareAnalysis,
                    testTag = RipDpiTestTags.HomeDiagnosticsShareAction,
                    enabled = !sheet.shareBusy,
                ),
            secondaryAction =
                sheet.remediationLadder
                    ?.takeIf { it.primaryAction.kind != DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS }
                    ?.let {
                        RipDpiSheetAction(
                            label = stringResource(R.string.home_diagnostics_open_diagnostics_action),
                            onClick = openDiagnosticsFromSheet,
                            testTag = RipDpiTestTags.HomeDiagnosticsOpenDiagnosticsAction,
                            variant = RipDpiButtonVariant.Outline,
                        )
                    }
                    ?: if (sheet.remediationLadder == null) {
                        RipDpiSheetAction(
                            label = stringResource(R.string.home_diagnostics_open_diagnostics_action),
                            onClick = openDiagnosticsFromSheet,
                            testTag = RipDpiTestTags.HomeDiagnosticsOpenDiagnosticsAction,
                            variant = RipDpiButtonVariant.Outline,
                        )
                    } else {
                        null
                    },
        ) {
            sheet.remediationLadder?.let { ladder ->
                DiagnosticsRemediationLadderCard(
                    ladder = ladder,
                    onAction = { action ->
                        onDismissAnalysisSheet()
                        when (action.kind) {
                            DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS -> onOpenAdvancedSettings()

                            DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS -> onOpenDiagnostics()

                            DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY -> onOpenHistory()

                            DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR -> onOpenModeEditor()

                            DiagnosticsRemediationActionKindUiModel.OPEN_VPN_PERMISSION,
                            DiagnosticsRemediationActionKindUiModel.OPEN_DNS_SETTINGS,
                            DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER,
                            -> Unit
                        }
                    },
                    cardTestTag = RipDpiTestTags.HomeDiagnosticsRemediationCard,
                    actionTestTag = RipDpiTestTags.HomeDiagnosticsRemediationAction,
                )
            } ?: sheet.actionableHeadline?.takeIf { it.isNotBlank() }?.let { headline ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.home_diagnostics_actionable_section),
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Text(
                        text = headline,
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                    sheet.actionableNextSteps.forEach { step ->
                        Text(
                            text = "• $step",
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
            Text(
                text = sheet.summary,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            if (sheet.stageSummaries.isNotEmpty()) {
                StageProgressIndicator(
                    completedCount = sheet.completedStageCount,
                    failedCount = sheet.failedStageCount,
                    totalCount = sheet.stageSummaries.size,
                )
            }
            sheet.confidenceSummary?.let { value ->
                val confidenceColor =
                    when {
                        value.contains("low", ignoreCase = true) -> colors.destructive
                        value.contains("medium", ignoreCase = true) -> colors.warning
                        value.contains("high", ignoreCase = true) -> colors.success
                        else -> colors.foreground
                    }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = RipDpiThemeTokens.spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_diagnostics_confidence_label),
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                    Text(
                        text = value,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = confidenceColor,
                    )
                }
            }
            sheet.coverageSummary?.let { value ->
                SettingsRow(title = stringResource(R.string.home_diagnostics_coverage_label), value = value)
            }
            sheet.recommendationSummary?.let { value ->
                SettingsRow(title = stringResource(R.string.home_diagnostics_recommendation_label), value = value)
            }
            HorizontalDivider(color = colors.divider)
            if (sheet.appliedSettings.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.home_diagnostics_applied_settings_label),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                sheet.appliedSettings.forEach { applied ->
                    SettingsRow(title = applied.label, value = applied.value)
                }
            } else {
                Text(
                    text = stringResource(R.string.home_diagnostics_no_settings_applied),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            if (sheet.capabilityEvidence.isNotEmpty()) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_capability_evidence_label),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                ) {
                    sheet.capabilityEvidence.forEach { evidence ->
                        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
                            Text(
                                text = evidence.authority,
                                style = RipDpiThemeTokens.type.bodyEmphasis,
                                color = colors.foreground,
                            )
                            Text(
                                text = evidence.summary,
                                style = RipDpiThemeTokens.type.secondaryBody,
                                color = colors.mutedForeground,
                            )
                            evidence.fields.forEach { field ->
                                SettingsRow(title = field.label, value = field.value, monospaceValue = false)
                            }
                        }
                    }
                }
            }
            if (sheet.detectionVerdict != null) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_detection_section_label),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsDetectionSummary)
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    Text(
                        text = sheet.detectionVerdict,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    sheet.detectionFindings.forEach { finding ->
                        Text(
                            text = "• $finding",
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
            sheet.installedVpnDetectorCount?.let { count ->
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_vpn_detectors_title),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsVpnDetectorsCard)
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.home_diagnostics_vpn_detectors_summary, count),
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                    sheet.installedVpnDetectorTopApps.take(homeDiagnosticsDetectorsShown).forEach { pkg ->
                        Text(
                            text = pkg,
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
            if (sheet.networkCharacterRows.isNotEmpty() || sheet.networkCharacterNotes.isNotEmpty()) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_network_section),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    sheet.networkCharacterRows.forEach { row ->
                        SettingsRow(title = row.label, value = row.value, monospaceValue = false)
                    }
                    sheet.networkCharacterNotes.forEach { note ->
                        Text(
                            text = "• $note",
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
            if (sheet.bufferbloatSummary != null || sheet.dnsCharacterizationSummary != null) {
                HorizontalDivider(color = colors.divider)
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    sheet.bufferbloatSummary?.let { bloat ->
                        Text(
                            text = stringResource(R.string.home_diagnostics_bufferbloat_section),
                            style = RipDpiThemeTokens.type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text = bloat,
                            style = RipDpiThemeTokens.type.body,
                            color = colors.foreground,
                        )
                    }
                    sheet.dnsCharacterizationSummary?.let { dns ->
                        Text(
                            text = stringResource(R.string.home_diagnostics_dns_section),
                            style = RipDpiThemeTokens.type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text = dns,
                            style = RipDpiThemeTokens.type.body,
                            color = colors.foreground,
                        )
                        sheet.dnsCharacterizationNotes.forEach { note ->
                            Text(
                                text = "• $note",
                                style = RipDpiThemeTokens.type.secondaryBody,
                                color = colors.mutedForeground,
                            )
                        }
                    }
                }
            }
            if (sheet.strategyEffectivenessRows.isNotEmpty()) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_effectiveness_section),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    sheet.strategyEffectivenessRows.forEach { row ->
                        SettingsRow(title = row.label, value = row.value, monospaceValue = false)
                    }
                }
            }
            if (sheet.routingSanitySummary != null || sheet.routingSanityFindings.isNotEmpty()) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_routing_sanity_section),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    sheet.routingSanitySummary?.let { summary ->
                        Text(
                            text = summary,
                            style = RipDpiThemeTokens.type.body,
                            color = colors.foreground,
                        )
                    }
                    sheet.routingSanityFindings.forEach { row ->
                        SettingsRow(title = row.label, value = row.value, monospaceValue = false)
                    }
                }
            }
            sheet.regressionDeltaSummary?.let { summary ->
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_regression_section),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    Text(
                        text = summary,
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                    if (sheet.regressionDeltaFailures.isNotEmpty()) {
                        Text(
                            text =
                                stringResource(R.string.home_diagnostics_regression_failed_label) +
                                    ": " + sheet.regressionDeltaFailures.joinToString(", "),
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.destructive,
                        )
                    }
                    if (sheet.regressionDeltaRecoveries.isNotEmpty()) {
                        Text(
                            text =
                                stringResource(R.string.home_diagnostics_regression_recovered_label) +
                                    ": " + sheet.regressionDeltaRecoveries.joinToString(", "),
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.success,
                        )
                    }
                }
            }
            if (sheet.stageSummaries.isNotEmpty()) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(R.string.home_diagnostics_stage_results_label),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.muted.copy(alpha = stageContainerAlpha),
                                shape = RipDpiThemeTokens.shapes.lg,
                            ).padding(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    sheet.stageSummaries.forEach { stage ->
                        StageResultRow(
                            label = stage.label,
                            summary = stage.summary,
                            failed = stage.failed,
                            skipped = stage.skipped,
                            recommendationContributor = stage.recommendationContributor,
                        )
                    }
                }
            }
        }
    }

    uiState.homeDiagnostics.verificationSheet?.let { sheet ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissVerificationSheet,
            title = stringResource(R.string.home_diagnostics_verified_sheet_title),
            message = sheet.headline,
            icon = if (sheet.success) RipDpiIcons.Connected else RipDpiIcons.Warning,
            testTag = RipDpiTestTags.HomeDiagnosticsVerificationSheet,
            primaryAction =
                RipDpiSheetAction(
                    label = stringResource(R.string.home_diagnostics_open_diagnostics_action),
                    onClick = {
                        onDismissVerificationSheet()
                        onOpenDiagnostics()
                    },
                    testTag = RipDpiTestTags.HomeDiagnosticsVerificationOpenDiagnosticsAction,
                ),
        ) {
            Text(
                text = sheet.summary,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            sheet.detail?.let { detail ->
                Text(
                    text = detail,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
internal fun StageResultRow(
    label: String,
    summary: String,
    failed: Boolean,
    skipped: Boolean,
    recommendationContributor: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val containerColor =
        when {
            failed -> colors.destructive.copy(alpha = stageContainerAlpha)
            recommendationContributor -> colors.accent.copy(alpha = stageContainerAlpha)
            else -> Color.Transparent
        }
    val statusIcon =
        when {
            failed -> RipDpiIcons.Error
            skipped -> RipDpiIcons.Warning
            else -> RipDpiIcons.Check
        }
    val statusColor =
        when {
            failed -> colors.destructive
            skipped -> colors.mutedForeground
            else -> colors.success
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(containerColor, RipDpiThemeTokens.shapes.sm)
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = label,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = summary,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
}
