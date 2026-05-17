package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsVerificationSheetUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val sheetStageContainerAlpha = 0.06f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDiagnosticsBottomSheetHost(
    uiState: MainUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onShareAnalysis: () -> Unit,
    onDismissAnalysisSheet: () -> Unit,
    onDismissVerificationSheet: () -> Unit,
) {
    uiState.homeDiagnostics.analysisSheet?.let { sheet ->
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

    uiState.homeDiagnostics.verificationSheet?.let { sheet ->
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
    sheet: HomeDiagnosticsAnalysisSheetUiState,
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
        secondaryAction = HomeAnalysisSecondaryAction(sheet, openDiagnosticsFromSheet),
    ) {
        HomeAnalysisSheetLeadContent(
            sheet = sheet,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
        )
        HomeAnalysisSheetCoreContent(sheet)
        HomeAnalysisSheetEvidenceContent(sheet)
        HomeAnalysisSheetNetworkContent(sheet)
        HomeAnalysisSheetResultContent(sheet)
    }
}

@Composable
private fun HomeAnalysisSecondaryAction(
    sheet: HomeDiagnosticsAnalysisSheetUiState,
    onOpenDiagnosticsFromSheet: () -> Unit,
): RipDpiSheetAction? {
    val shouldShowAction =
        sheet.remediationLadder == null ||
            sheet.remediationLadder.primaryAction.kind != DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS
    return if (shouldShowAction) {
        RipDpiSheetAction(
            label = stringResource(R.string.home_diagnostics_open_diagnostics_action),
            onClick = onOpenDiagnosticsFromSheet,
            testTag = RipDpiTestTags.HomeDiagnosticsOpenDiagnosticsAction,
            variant = RipDpiButtonVariant.Outline,
        )
    } else {
        null
    }
}

@Composable
private fun HomeAnalysisSheetLeadContent(
    sheet: HomeDiagnosticsAnalysisSheetUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onDismissAnalysisSheet: () -> Unit,
) {
    sheet.remediationLadder?.let { ladder ->
        HomeSheetRemediationLadder(
            ladder = ladder,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
        )
    } ?: sheet.actionableHeadline?.takeIf { it.isNotBlank() }?.let { headline ->
        HomeActionableHeadlineCard(headline = headline, nextSteps = sheet.actionableNextSteps)
    }
}

@Composable
private fun HomeSheetRemediationLadder(
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
                    Unit
                }
            }
        },
        cardTestTag = RipDpiTestTags.HomeDiagnosticsRemediationCard,
        actionTestTag = RipDpiTestTags.HomeDiagnosticsRemediationAction,
    )
}

@Composable
private fun HomeActionableHeadlineCard(
    headline: String,
    nextSteps: List<String>,
) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.muted.copy(alpha = sheetStageContainerAlpha), RipDpiThemeTokens.shapes.lg)
                .padding(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.home_diagnostics_actionable_section),
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
private fun HomeAnalysisSheetCoreContent(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
    Text(text = sheet.summary, style = RipDpiThemeTokens.type.body, color = colors.foreground)
    if (sheet.stageSummaries.isNotEmpty()) {
        StageProgressIndicator(
            completedCount = sheet.completedStageCount,
            failedCount = sheet.failedStageCount,
            totalCount = sheet.stageSummaries.size,
        )
    }
    HomeConfidenceRow(sheet.confidenceSummary)
    sheet.coverageSummary?.let { value ->
        SettingsRow(title = stringResource(R.string.home_diagnostics_coverage_label), value = value)
    }
    sheet.recommendationSummary?.let { value ->
        SettingsRow(title = stringResource(R.string.home_diagnostics_recommendation_label), value = value)
    }
    HomeAppliedSettingsSection(sheet)
}

@Composable
private fun HomeConfidenceRow(value: String?) {
    val colors = RipDpiThemeTokens.colors
    val confidenceColor =
        when {
            value == null -> colors.foreground
            value.contains("low", ignoreCase = true) -> colors.destructive
            value.contains("medium", ignoreCase = true) -> colors.warning
            value.contains("high", ignoreCase = true) -> colors.success
            else -> colors.foreground
        }
    value?.let {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = RipDpiThemeTokens.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_diagnostics_confidence_label),
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            Text(text = it, style = RipDpiThemeTokens.type.bodyEmphasis, color = confidenceColor)
        }
    }
}

@Composable
private fun HomeAppliedSettingsSection(sheet: HomeDiagnosticsAnalysisSheetUiState) {
    val colors = RipDpiThemeTokens.colors
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerificationBottomSheet(
    sheet: HomeDiagnosticsVerificationSheetUiState,
    onOpenDiagnostics: () -> Unit,
    onDismissVerificationSheet: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
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
