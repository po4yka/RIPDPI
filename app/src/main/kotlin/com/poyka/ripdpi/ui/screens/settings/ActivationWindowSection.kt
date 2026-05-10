package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun LazyListScope.activationWindowSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onResetActivationWindow: () -> Unit,
    onSaveActivationRange: (ActivationWindowDimension, Long?, Long?) -> Unit,
) {
    if (uiState.showActivationWindowProfile) {
        item(key = "advanced_activation_window") {
            val spacing = RipDpiThemeTokens.spacing
            AdvancedSettingsSection(
                title = stringResource(R.string.activation_window_section_title),
                testTag = RipDpiTestTags.advancedSection("activation_window"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ActivationWindowProfileCard(
                        uiState = uiState,
                        onResetActivationWindow = onResetActivationWindow,
                    )
                    ActivationRangeEditorCard(
                        title = stringResource(R.string.activation_window_round_card_title),
                        description = stringResource(R.string.activation_window_round_body),
                        currentRange = uiState.desync.groupActivationFilter.round,
                        emptySummary = stringResource(R.string.activation_window_range_unbounded),
                        effectSummary = stringResource(R.string.activation_window_round_effect),
                        enabled = visualEditorEnabled,
                        minValue = 1L,
                        dimension = ActivationWindowDimension.Round,
                        onSave = { start, end -> onSaveActivationRange(ActivationWindowDimension.Round, start, end) },
                    )
                    ActivationRangeEditorCard(
                        title = stringResource(R.string.activation_window_payload_card_title),
                        description = stringResource(R.string.activation_window_payload_body),
                        currentRange = uiState.desync.groupActivationFilter.payloadSize,
                        emptySummary = stringResource(R.string.activation_window_range_unbounded),
                        effectSummary = stringResource(R.string.activation_window_payload_effect),
                        enabled = visualEditorEnabled,
                        minValue = 0L,
                        dimension = ActivationWindowDimension.PayloadSize,
                        onSave = { start, end ->
                            onSaveActivationRange(ActivationWindowDimension.PayloadSize, start, end)
                        },
                    )
                    ActivationRangeEditorCard(
                        title = stringResource(R.string.activation_window_stream_card_title),
                        description = stringResource(R.string.activation_window_stream_body),
                        currentRange = uiState.desync.groupActivationFilter.streamBytes,
                        emptySummary = stringResource(R.string.activation_window_range_unbounded),
                        effectSummary = stringResource(R.string.activation_window_stream_effect),
                        enabled = visualEditorEnabled,
                        minValue = 0L,
                        dimension = ActivationWindowDimension.StreamBytes,
                        onSave = { start, end ->
                            onSaveActivationRange(ActivationWindowDimension.StreamBytes, start, end)
                        },
                    )
                }
            }
        }
    }
}

private data class ActivationWindowStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class ActivationWindowSummaryLines(
    val window: String,
    val scope: String,
    val stepFilters: String,
)

@Composable
private fun ActivationWindowProfileCard(
    uiState: SettingsUiState,
    onResetActivationWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberActivationWindowStatus(uiState)
    val summary = activationWindowSummaryLines(uiState)
    val badges = activationWindowBadges(uiState)

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_summary_label),
                value = summary.window,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_scope_label),
                value = summary.scope,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_step_filters_label),
                value = summary.stepFilters,
            )
        }
        Text(
            text = stringResource(R.string.activation_window_section_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetActivationWindow) {
            ActivationWindowResetAction(
                onResetActivationWindow = onResetActivationWindow,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun activationWindowSummaryLines(uiState: SettingsUiState): ActivationWindowSummaryLines =
    ActivationWindowSummaryLines(
        window = uiState.desync.activationWindowSummary,
        scope = activationWindowScopeSummary(uiState),
        stepFilters = activationWindowStepFilterSummary(uiState),
    )

@Composable
private fun activationWindowScopeSummary(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.activation_window_scope_cli)
        !uiState.activationWindowControlsRelevant -> stringResource(R.string.activation_window_scope_inactive)
        uiState.desync.hasCustomActivationWindow -> stringResource(R.string.activation_window_scope_filtered)
        else -> stringResource(R.string.activation_window_scope_open)
    }

@Composable
private fun activationWindowStepFilterSummary(uiState: SettingsUiState): String =
    if (uiState.desync.hasStepActivationFilters) {
        stringResource(
            R.string.activation_window_step_filters_present,
            uiState.desync.stepActivationFilterCount,
        )
    } else {
        stringResource(R.string.activation_window_step_filters_none)
    }

@Composable
private fun activationWindowBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    buildList {
        add(activationWindowModeBadge(uiState))
        if (!uiState.desync.groupActivationFilter.round.isEmpty) {
            add(stringResource(R.string.activation_window_badge_round) to SummaryCapsuleTone.Active)
        }
        if (!uiState.desync.groupActivationFilter.payloadSize.isEmpty) {
            add(stringResource(R.string.activation_window_badge_payload) to SummaryCapsuleTone.Active)
        }
        if (!uiState.desync.groupActivationFilter.streamBytes.isEmpty) {
            add(stringResource(R.string.activation_window_badge_stream) to SummaryCapsuleTone.Active)
        }
        if (uiState.desync.hasStepActivationFilters) {
            add(
                stringResource(
                    R.string.activation_window_badge_step_filters,
                    uiState.desync.stepActivationFilterCount,
                ) to SummaryCapsuleTone.Info,
            )
        }
    }

@Composable
private fun activationWindowModeBadge(uiState: SettingsUiState): Pair<String, SummaryCapsuleTone> =
    if (uiState.desync.hasCustomActivationWindow) {
        stringResource(R.string.activation_window_badge_custom) to SummaryCapsuleTone.Active
    } else {
        stringResource(R.string.activation_window_badge_default) to SummaryCapsuleTone.Neutral
    }

@Composable
private fun ActivationWindowResetAction(
    onResetActivationWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        ActivationWindowResetDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                onResetActivationWindow()
            },
        )
    }

    RipDpiButton(
        text = stringResource(R.string.activation_window_reset_action),
        onClick = { showResetDialog = true },
        variant = RipDpiButtonVariant.Outline,
        modifier = modifier,
    )
}

@Composable
private fun ActivationWindowResetDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.confirm_reset_activation_window_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.confirm_reset_activation_window_dismiss),
                onClick = onDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.confirm_reset_activation_window_confirm),
                onClick = onConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.confirm_reset_activation_window_body),
                tone = RipDpiDialogTone.Destructive,
            ),
    )
}

@Composable
private fun rememberActivationWindowStatus(uiState: SettingsUiState): ActivationWindowStatusContent =
    when {
        uiState.enableCmdSettings -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_cli_title),
                body = stringResource(R.string.activation_window_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.activationWindowControlsRelevant && uiState.desync.hasCustomActivationWindow -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_group_disabled_title),
                body = stringResource(R.string.activation_window_group_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.activationWindowControlsRelevant -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_group_off_title),
                body = stringResource(R.string.activation_window_group_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.isServiceRunning && uiState.desync.hasCustomActivationWindow -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_restart_title),
                body = stringResource(R.string.activation_window_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.desync.hasCustomActivationWindow -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_custom_title),
                body = stringResource(R.string.activation_window_custom_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        uiState.desync.hasStepActivationFilters -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_step_only_title),
                body = stringResource(R.string.activation_window_step_only_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        else -> {
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_default_title),
                body = stringResource(R.string.activation_window_default_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }
