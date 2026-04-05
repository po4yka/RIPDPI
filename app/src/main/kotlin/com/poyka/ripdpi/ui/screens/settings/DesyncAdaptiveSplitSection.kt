package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.poyka.ripdpi.activities.AdaptiveSplitPresetCustom
import com.poyka.ripdpi.activities.AdaptiveSplitPresetManual
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerSniExt
import com.poyka.ripdpi.data.formatOffsetExpressionLabel
import com.poyka.ripdpi.data.primaryTcpChainStep
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
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class AdaptiveSplitStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun rememberAdaptiveSplitPresetOptions(
    uiState: SettingsUiState,
    includeCustom: Boolean = uiState.desync.hasCustomAdaptiveSplitPreset,
): List<AdaptiveSplitPresetUiModel> =
    buildList {
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveSplitPresetManual,
                title = stringResource(R.string.adaptive_split_preset_manual),
                body = stringResource(R.string.adaptive_split_preset_manual_body),
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerBalanced,
                title = stringResource(R.string.adaptive_split_preset_balanced),
                body = stringResource(R.string.adaptive_split_preset_balanced_body),
                isRecommended = true,
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerHost,
                title = stringResource(R.string.adaptive_split_preset_host),
                body = stringResource(R.string.adaptive_split_preset_host_body),
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerEndHost,
                title = stringResource(R.string.adaptive_split_preset_endhost),
                body = stringResource(R.string.adaptive_split_preset_endhost_body),
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerSniExt,
                title = stringResource(R.string.adaptive_split_preset_sniext),
                body = stringResource(R.string.adaptive_split_preset_sniext_body),
            ),
        )
        if (includeCustom) {
            add(
                1,
                AdaptiveSplitPresetUiModel(
                    value = AdaptiveSplitPresetCustom,
                    title = stringResource(R.string.adaptive_split_preset_custom),
                    body =
                        stringResource(
                            R.string.adaptive_split_preset_custom_body,
                            formatOffsetExpressionLabel(uiState.desync.splitMarker),
                        ),
                ),
            )
        }
    }

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun AdaptiveSplitProfileCard(
    uiState: SettingsUiState,
    onResetAdaptiveSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberAdaptiveSplitStatus(uiState)
    val profileSummary =
        when (uiState.desync.adaptiveSplitPreset) {
            AdaptiveSplitPresetManual -> {
                stringResource(R.string.adaptive_split_profile_manual)
            }

            AdaptiveSplitPresetCustom -> {
                stringResource(
                    R.string.adaptive_split_profile_custom,
                    formatOffsetExpressionLabel(uiState.desync.splitMarker),
                )
            }

            else -> {
                formatOffsetExpressionLabel(uiState.desync.splitMarker)
            }
        }
    val targetSummary =
        if (uiState.settings.tcpChainStepsCount > 0 && primaryTcpChainStep(uiState.desync.tcpChainSteps) != null) {
            stringResource(R.string.adaptive_split_target_chain_step)
        } else {
            stringResource(R.string.adaptive_split_target_legacy)
        }
    val focusSummary =
        when (uiState.desync.adaptiveSplitPreset) {
            AdaptiveSplitPresetManual -> stringResource(R.string.adaptive_split_focus_manual)
            AdaptiveSplitPresetCustom -> stringResource(R.string.adaptive_split_focus_custom)
            AdaptiveMarkerBalanced -> stringResource(R.string.adaptive_split_focus_balanced)
            AdaptiveMarkerHost -> stringResource(R.string.adaptive_split_focus_host)
            AdaptiveMarkerEndHost -> stringResource(R.string.adaptive_split_focus_endhost)
            AdaptiveMarkerSniExt -> stringResource(R.string.adaptive_split_focus_sniext)
            else -> stringResource(R.string.adaptive_split_focus_custom)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.adaptive_split_scope_cli)
            !uiState.desyncEnabled -> stringResource(R.string.adaptive_split_scope_disabled)
            !uiState.desync.adaptiveSplitVisualEditorSupported -> stringResource(R.string.adaptive_split_scope_hostfake)
            uiState.desync.hasAdaptiveSplitPreset -> stringResource(R.string.adaptive_split_scope_active)
            else -> stringResource(R.string.adaptive_split_scope_manual)
        }
    val protocolSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled -> {
                stringResource(R.string.adaptive_split_protocol_http_https)
            }

            uiState.desyncHttpEnabled -> {
                stringResource(R.string.adaptive_split_protocol_http)
            }

            uiState.desyncHttpsEnabled -> {
                stringResource(R.string.adaptive_split_protocol_https)
            }

            else -> {
                stringResource(R.string.adaptive_split_protocol_none)
            }
        }
    val dslSummary = stringResource(R.string.adaptive_split_dsl_only_summary)
    val badges =
        buildList {
            add(
                (
                    if (uiState.desync.hasAdaptiveSplitPreset) {
                        stringResource(R.string.adaptive_split_badge_adaptive)
                    } else {
                        stringResource(R.string.adaptive_split_badge_manual)
                    }
                ) to
                    if (uiState.desync.hasAdaptiveSplitPreset) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            if (uiState.desync.hasCustomAdaptiveSplitPreset) {
                add(stringResource(R.string.adaptive_split_badge_custom) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpsEnabled) {
                add(stringResource(R.string.adaptive_split_badge_https) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpEnabled) {
                add(stringResource(R.string.adaptive_split_badge_http) to SummaryCapsuleTone.Info)
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
                label = stringResource(R.string.adaptive_split_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_target),
                value = targetSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_focus),
                value = focusSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_protocols),
                value = protocolSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_dsl),
                value = dslSummary,
            )
        }
        Text(
            text = stringResource(R.string.adaptive_split_editor_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetAdaptiveSplitPreset) {
            var showResetDialog by remember { mutableStateOf(false) }

            if (showResetDialog) {
                RipDpiDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = stringResource(R.string.confirm_reset_adaptive_split_title),
                    dismissAction =
                        RipDpiDialogAction(
                            label = stringResource(R.string.confirm_reset_adaptive_split_dismiss),
                            onClick = { showResetDialog = false },
                        ),
                    confirmAction =
                        RipDpiDialogAction(
                            label = stringResource(R.string.confirm_reset_adaptive_split_confirm),
                            onClick = {
                                showResetDialog = false
                                onResetAdaptiveSplit()
                            },
                        ),
                    visuals =
                        RipDpiDialogVisuals(
                            message = stringResource(R.string.confirm_reset_adaptive_split_body),
                            tone = RipDpiDialogTone.Destructive,
                        ),
                )
            }

            RipDpiButton(
                text = stringResource(R.string.adaptive_split_reset_action),
                onClick = { showResetDialog = true },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberAdaptiveSplitStatus(uiState: SettingsUiState): AdaptiveSplitStatusContent =
    when {
        uiState.enableCmdSettings -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_cli_title),
                body = stringResource(R.string.adaptive_split_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.desync.adaptiveSplitVisualEditorSupported -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_hostfake_title),
                body = stringResource(R.string.adaptive_split_hostfake_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.desyncEnabled && uiState.desync.hasAdaptiveSplitPreset -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_saved_title),
                body = stringResource(R.string.adaptive_split_saved_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.desyncEnabled -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_off_title),
                body = stringResource(R.string.adaptive_split_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.isServiceRunning && uiState.desync.hasAdaptiveSplitPreset -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_restart_title),
                body = stringResource(R.string.adaptive_split_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.desync.hasAdaptiveSplitPreset -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_ready_title),
                body = stringResource(R.string.adaptive_split_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_manual_title),
                body = stringResource(R.string.adaptive_split_manual_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }

@Composable
internal fun AdaptiveSplitPresetSelector(
    uiState: SettingsUiState,
    presets: List<AdaptiveSplitPresetUiModel>,
    enabled: Boolean,
    onPresetSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.adaptive_split_selector_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.adaptive_split_selector_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        presets.forEach { preset ->
            AdaptiveSplitPresetCard(
                preset = preset,
                selected = uiState.desync.adaptiveSplitPreset == preset.value,
                enabled = enabled && preset.value != AdaptiveSplitPresetCustom,
                onClick = { onPresetSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun AdaptiveSplitPresetCard(
    preset: AdaptiveSplitPresetUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badgeLabel =
        when {
            selected -> stringResource(R.string.adaptive_split_preset_selected)
            preset.isRecommended -> stringResource(R.string.adaptive_split_preset_recommended)
            preset.value == AdaptiveSplitPresetCustom -> stringResource(R.string.adaptive_split_preset_dsl_only)
            else -> null
        }
    val badgeTone =
        when {
            selected -> StatusIndicatorTone.Active
            preset.isRecommended -> StatusIndicatorTone.Idle
            else -> StatusIndicatorTone.Idle
        }

    RipDpiCard(
        modifier = modifier,
        variant = if (selected) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = preset.title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = preset.body,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = badgeTone,
                )
            }
        }
    }
}
