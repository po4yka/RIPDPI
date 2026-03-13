package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
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
            AdvancedSettingsSection(title = stringResource(R.string.activation_window_section_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ActivationWindowProfileCard(
                        uiState = uiState,
                        onResetActivationWindow = onResetActivationWindow,
                    )
                    ActivationRangeEditorCard(
                        title = stringResource(R.string.activation_window_round_card_title),
                        description = stringResource(R.string.activation_window_round_body),
                        currentRange = uiState.groupActivationFilter.round,
                        emptySummary = stringResource(R.string.activation_window_range_unbounded),
                        effectSummary = stringResource(R.string.activation_window_round_effect),
                        enabled = visualEditorEnabled,
                        minValue = 1L,
                        onSave = { start, end -> onSaveActivationRange(ActivationWindowDimension.Round, start, end) },
                    )
                    ActivationRangeEditorCard(
                        title = stringResource(R.string.activation_window_payload_card_title),
                        description = stringResource(R.string.activation_window_payload_body),
                        currentRange = uiState.groupActivationFilter.payloadSize,
                        emptySummary = stringResource(R.string.activation_window_range_unbounded),
                        effectSummary = stringResource(R.string.activation_window_payload_effect),
                        enabled = visualEditorEnabled,
                        minValue = 0L,
                        onSave = { start, end ->
                            onSaveActivationRange(ActivationWindowDimension.PayloadSize, start, end)
                        },
                    )
                    ActivationRangeEditorCard(
                        title = stringResource(R.string.activation_window_stream_card_title),
                        description = stringResource(R.string.activation_window_stream_body),
                        currentRange = uiState.groupActivationFilter.streamBytes,
                        emptySummary = stringResource(R.string.activation_window_range_unbounded),
                        effectSummary = stringResource(R.string.activation_window_stream_effect),
                        enabled = visualEditorEnabled,
                        minValue = 0L,
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
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.activation_window_scope_cli)
            !uiState.activationWindowControlsRelevant ->
                stringResource(R.string.activation_window_scope_inactive)
            uiState.hasCustomActivationWindow ->
                stringResource(R.string.activation_window_scope_filtered)
            else -> stringResource(R.string.activation_window_scope_open)
        }
    val stepFilterSummary =
        if (uiState.hasStepActivationFilters) {
            stringResource(
                R.string.activation_window_step_filters_present,
                uiState.stepActivationFilterCount,
            )
        } else {
            stringResource(R.string.activation_window_step_filters_none)
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasCustomActivationWindow) {
                        stringResource(R.string.activation_window_badge_custom)
                    } else {
                        stringResource(R.string.activation_window_badge_default)
                    }
                ) to
                    if (uiState.hasCustomActivationWindow) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            if (!uiState.groupActivationFilter.round.isEmpty) {
                add(stringResource(R.string.activation_window_badge_round) to SummaryCapsuleTone.Active)
            }
            if (!uiState.groupActivationFilter.payloadSize.isEmpty) {
                add(stringResource(R.string.activation_window_badge_payload) to SummaryCapsuleTone.Active)
            }
            if (!uiState.groupActivationFilter.streamBytes.isEmpty) {
                add(stringResource(R.string.activation_window_badge_stream) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasStepActivationFilters) {
                add(
                    stringResource(
                        R.string.activation_window_badge_step_filters,
                        uiState.stepActivationFilterCount,
                    ) to SummaryCapsuleTone.Info,
                )
            }
        }

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
                value = uiState.activationWindowSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_scope_label),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_step_filters_label),
                value = stepFilterSummary,
            )
        }
        Text(
            text = stringResource(R.string.activation_window_section_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetActivationWindow) {
            RipDpiButton(
                text = stringResource(R.string.activation_window_reset_action),
                onClick = onResetActivationWindow,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberActivationWindowStatus(uiState: SettingsUiState): ActivationWindowStatusContent =
    when {
        uiState.enableCmdSettings ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_cli_title),
                body = stringResource(R.string.activation_window_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.activationWindowControlsRelevant && uiState.hasCustomActivationWindow ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_group_disabled_title),
                body = stringResource(R.string.activation_window_group_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.activationWindowControlsRelevant ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_group_off_title),
                body = stringResource(R.string.activation_window_group_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasCustomActivationWindow ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_restart_title),
                body = stringResource(R.string.activation_window_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomActivationWindow ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_custom_title),
                body = stringResource(R.string.activation_window_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.hasStepActivationFilters ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_step_only_title),
                body = stringResource(R.string.activation_window_step_only_body),
                tone = StatusIndicatorTone.Idle,
            )

        else ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_default_title),
                body = stringResource(R.string.activation_window_default_body),
                tone = StatusIndicatorTone.Idle,
            )
    }
