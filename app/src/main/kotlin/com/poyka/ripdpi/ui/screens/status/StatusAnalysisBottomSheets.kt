package com.poyka.ripdpi.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiFeatureSurface

private const val sheetStageContainerAlpha = 0.06f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusDiagnosticsBottomSheetHost(
    homeDiagnostics: StatusDiagnosticsUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onShareAnalysis: () -> Unit,
    onDismissAnalysisSheet: () -> Unit,
    onDismissVerificationSheet: () -> Unit,
) {
    homeDiagnostics.analysisSheet?.let { sheet ->
        RemediationBottomSheet(
            sheet = sheet,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
            onShareAnalysis = onShareAnalysis,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
        )
    }

    homeDiagnostics.verificationSheet?.let { sheet ->
        VerificationBottomSheet(
            sheet = sheet,
            onOpenDiagnostics = onOpenDiagnostics,
            onDismissVerificationSheet = onDismissVerificationSheet,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemediationBottomSheet(
    sheet: StatusDiagnosticsAnalysisSheetUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onShareAnalysis: () -> Unit,
    onDismissAnalysisSheet: () -> Unit,
) {
    val openDiagnosticsFromSheet = {
        onDismissAnalysisSheet()
        onOpenDiagnostics()
    }
    RipDpiBottomSheet(
        onDismissRequest = onDismissAnalysisSheet,
        title = stringResource(R.string.status_diagnostics_analysis_sheet_title),
        message = sheet.headline,
        icon = RipDpiIcons.Search,
        testTag = RipDpiTestTags.StatusDiagnosticsAnalysisSheet,
        primaryAction =
            RipDpiSheetAction(
                label = stringResource(R.string.status_diagnostics_share_action),
                onClick = onShareAnalysis,
                testTag = RipDpiTestTags.StatusDiagnosticsShareAction,
                enabled = !sheet.shareBusy,
            ),
        secondaryAction = StatusAnalysisSecondaryAction(sheet, openDiagnosticsFromSheet),
    ) {
        StatusAnalysisSheetLeadContent(
            sheet = sheet,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
        )
        StatusAnalysisSheetCoreContent(sheet)
        StatusAnalysisSheetEvidenceContent(sheet)
        StatusAnalysisSheetNetworkContent(sheet)
        StatusAnalysisSheetResultContent(sheet)
    }
}

@Composable
private fun StatusAnalysisSecondaryAction(
    sheet: StatusDiagnosticsAnalysisSheetUiState,
    onOpenDiagnosticsFromSheet: () -> Unit,
): RipDpiSheetAction? {
    val shouldShowAction =
        sheet.remediationLadder == null ||
            sheet.remediationLadder.primaryAction.kind != DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS
    return if (shouldShowAction) {
        RipDpiSheetAction(
            label = stringResource(R.string.status_diagnostics_open_diagnostics_action),
            onClick = onOpenDiagnosticsFromSheet,
            testTag = RipDpiTestTags.StatusDiagnosticsOpenDiagnosticsAction,
            variant = RipDpiButtonVariant.Outline,
        )
    } else {
        null
    }
}

@Composable
private fun StatusAnalysisSheetLeadContent(
    sheet: StatusDiagnosticsAnalysisSheetUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onDismissAnalysisSheet: () -> Unit,
) {
    sheet.remediationLadder?.let { ladder ->
        StatusSheetRemediationLadder(
            ladder = ladder,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
        )
    } ?: sheet.actionableHeadline?.takeIf { it.isNotBlank() }?.let { headline ->
        StatusActionableHeadlineCard(headline = headline, nextSteps = sheet.actionableNextSteps)
    }
}

@Composable
private fun StatusSheetRemediationLadder(
    ladder: DiagnosticsRemediationLadderUiModel,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onDismissAnalysisSheet: () -> Unit,
) {
    DiagnosticsRemediationLadderCard(
        ladder = ladder,
        onAction = { action ->
            onDismissAnalysisSheet()
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
                }
            }
        },
        cardTestTag = RipDpiTestTags.StatusDiagnosticsRemediationCard,
        actionTestTag = RipDpiTestTags.StatusDiagnosticsRemediationAction,
    )
}

@Composable
private fun StatusActionableHeadlineCard(
    headline: String,
    nextSteps: List<String>,
) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiFeatureSurface(colors.muted.copy(alpha = sheetStageContainerAlpha), RipDpiThemeTokens.shapes.lg)
                .padding(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.status_diagnostics_actionable_section),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(text = headline, style = RipDpiThemeTokens.type.body, color = colors.foreground)
        nextSteps.forEach { step ->
            Text(text = "• $step", style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
        }
    }
}

@Composable
private fun StatusAnalysisSheetCoreContent(sheet: StatusDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    Text(text = sheet.summary, style = RipDpiThemeTokens.type.body, color = colors.foreground)
    if (sheet.stageSummaries.isNotEmpty()) {
        StageProgressIndicator(
            completedCount = sheet.completedStageCount,
            failedCount = sheet.failedStageCount,
            totalCount = sheet.stageSummaries.size,
        )
    }
    StatusConfidenceRow(sheet.confidenceSummary, sheet.confidenceLevel)
    sheet.coverageSummary?.let { value ->
        SettingsRow(title = stringResource(R.string.status_diagnostics_coverage_label), value = value)
    }
    sheet.recommendationSummary?.let { value ->
        SettingsRow(title = stringResource(R.string.status_diagnostics_recommendation_label), value = value)
    }
    StatusAppliedSettingsSection(sheet)
}

@Composable
private fun StatusConfidenceRow(
    value: String?,
    level: StatusConfidenceLevel?,
) {
    val colors = RipDpiThemeTokens.colors
    val confidenceColor =
        when (level) {
            StatusConfidenceLevel.LOW -> colors.destructive
            StatusConfidenceLevel.MEDIUM -> colors.warning
            StatusConfidenceLevel.HIGH -> colors.success
            null -> colors.foreground
        }
    value?.let {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = RipDpiThemeTokens.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.status_diagnostics_confidence_label),
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            Text(text = it, style = RipDpiThemeTokens.type.bodyEmphasis, color = confidenceColor)
        }
    }
}

@Composable
private fun StatusAppliedSettingsSection(sheet: StatusDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    HorizontalDivider(color = colors.divider)
    if (sheet.appliedSettings.isNotEmpty()) {
        Text(
            text = stringResource(R.string.status_diagnostics_applied_settings_label),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        sheet.appliedSettings.forEach { applied ->
            SettingsRow(title = applied.label, value = applied.value)
        }
    } else {
        Text(
            text = stringResource(R.string.status_diagnostics_no_settings_applied),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerificationBottomSheet(
    sheet: StatusDiagnosticsVerificationSheetUiState,
    onOpenDiagnostics: () -> Unit,
    onDismissVerificationSheet: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiBottomSheet(
        onDismissRequest = onDismissVerificationSheet,
        title = stringResource(R.string.status_diagnostics_verified_sheet_title),
        message = sheet.headline,
        icon = if (sheet.success) RipDpiIcons.Connected else RipDpiIcons.Warning,
        testTag = RipDpiTestTags.StatusDiagnosticsVerificationSheet,
        primaryAction =
            RipDpiSheetAction(
                label = stringResource(R.string.status_diagnostics_open_diagnostics_action),
                onClick = {
                    onDismissVerificationSheet()
                    onOpenDiagnostics()
                },
                testTag = RipDpiTestTags.StatusDiagnosticsVerificationOpenDiagnosticsAction,
            ),
    ) {
        Text(text = sheet.summary, style = RipDpiThemeTokens.type.body, color = colors.foreground)
        sheet.detail?.let { detail ->
            Text(text = detail, style = RipDpiThemeTokens.type.secondaryBody, color = colors.mutedForeground)
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
            failed -> colors.destructive.copy(alpha = sheetStageContainerAlpha)
            recommendationContributor -> colors.accent.copy(alpha = sheetStageContainerAlpha)
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
    val statusDescription =
        when {
            failed -> stringResource(R.string.status_diagnostics_stage_status_failed)
            skipped -> stringResource(R.string.status_diagnostics_stage_status_skipped)
            else -> stringResource(R.string.status_diagnostics_stage_status_passed)
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiFeatureSurface(containerColor, RipDpiThemeTokens.shapes.sm)
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = statusDescription,
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
