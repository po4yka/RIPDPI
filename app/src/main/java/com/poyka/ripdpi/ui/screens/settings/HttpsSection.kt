package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange

internal fun LazyListScope.httpsSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
) {
    item(key = "advanced_https") {
        val colors = RipDpiThemeTokens.colors
        val spacing = RipDpiThemeTokens.spacing
        AdvancedSettingsSection(title = stringResource(R.string.desync_https_category)) {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.ripdpi_tls_prelude_section_body),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
                HorizontalDivider(color = colors.divider)
                TlsPreludeProfileCard(
                    uiState = uiState,
                    modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                )
                HorizontalDivider(color = colors.divider)
                TlsPreludeModeSelector(
                    uiState = uiState,
                    enabled = visualEditorEnabled,
                    onModeSelected = {
                        onOptionSelected(AdvancedOptionSetting.TlsPreludeMode, it)
                    },
                )
                if (uiState.tlsPrelude.hasStackedTlsPreludeSteps) {
                    Text(
                        text = stringResource(R.string.ripdpi_tls_prelude_multiple_note),
                        style = RipDpiThemeTokens.type.caption,
                        color = colors.warning,
                    )
                }
                if (uiState.tlsPrelude.tlsPreludeMode != TlsPreludeModeDisabled) {
                    HorizontalDivider(color = colors.divider)
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_tlsrec_position_setting),
                        description = stringResource(R.string.config_tls_record_marker_helper),
                        value = uiState.tlsPrelude.tlsrecMarker,
                        placeholder = stringResource(R.string.config_placeholder_tls_record_marker),
                        enabled = visualEditorEnabled,
                        validator = ::isValidOffsetExpression,
                        invalidMessage = stringResource(R.string.config_error_invalid_marker),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.TlsrecMarker,
                        onConfirm = onTextConfirmed,
                        showDivider = uiState.tlsPrelude.tlsPreludeUsesRandomRecords,
                    )
                }
                if (uiState.tlsPrelude.tlsPreludeUsesRandomRecords) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_tlsrandrec_count_title),
                        description = stringResource(R.string.ripdpi_tlsrandrec_count_body),
                        value = uiState.tlsPrelude.tlsRandRecFragmentCount.toString(),
                        enabled = visualEditorEnabled,
                        validator = { validateIntRange(it, 2, 16) },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.TlsRandRecFragmentCount,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_tlsrandrec_min_title),
                        description = stringResource(R.string.ripdpi_tlsrandrec_min_body),
                        value = uiState.tlsPrelude.tlsRandRecMinFragmentSize.toString(),
                        enabled = visualEditorEnabled,
                        validator = { validateIntRange(it, 1, 4096) },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.TlsRandRecMinFragmentSize,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_tlsrandrec_max_title),
                        description = stringResource(R.string.ripdpi_tlsrandrec_max_body),
                        value = uiState.tlsPrelude.tlsRandRecMaxFragmentSize.toString(),
                        enabled = visualEditorEnabled,
                        validator = { input ->
                            input.toIntOrNull()?.let { value ->
                                value in uiState.tlsPrelude.tlsRandRecMinFragmentSize..4096
                            } == true
                        },
                        invalidMessage = stringResource(R.string.ripdpi_tlsrandrec_max_error),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.TlsRandRecMaxFragmentSize,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                Text(
                    text = stringResource(R.string.ripdpi_tls_prelude_scope_note),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

private data class TlsPreludeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class TlsPreludePresetUiModel(
    val value: String,
    val title: String,
    val body: String,
)

@Composable
private fun TlsPreludeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberTlsPreludeStatus(uiState)
    val modeSummary =
        stringResource(
            when (uiState.tlsPrelude.tlsPreludeMode) {
                TcpChainStepKind.TlsRec.wireName -> R.string.ripdpi_tls_prelude_summary_mode_single
                TcpChainStepKind.TlsRandRec.wireName -> R.string.ripdpi_tls_prelude_summary_mode_random
                else -> R.string.ripdpi_tls_prelude_summary_mode_off
            },
        )
    val markerSummary =
        if (uiState.tlsPrelude.tlsPreludeMode == TlsPreludeModeDisabled) {
            stringResource(R.string.ripdpi_tls_prelude_marker_unused)
        } else {
            uiState.tlsPrelude.tlsrecMarker
        }
    val layoutSummary =
        when (uiState.tlsPrelude.tlsPreludeMode) {
            TcpChainStepKind.TlsRandRec.wireName -> {
                stringResource(
                    R.string.ripdpi_tls_prelude_summary_layout_random,
                    uiState.tlsPrelude.tlsRandRecFragmentCount,
                    uiState.tlsPrelude.tlsRandRecMinFragmentSize,
                    uiState.tlsPrelude.tlsRandRecMaxFragmentSize,
                )
            }

            TcpChainStepKind.TlsRec.wireName -> {
                stringResource(R.string.ripdpi_tls_prelude_summary_layout_single)
            }

            else -> {
                stringResource(R.string.ripdpi_tls_prelude_summary_layout_off)
            }
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.ripdpi_tls_prelude_scope_cli)
            !uiState.tlsPreludeControlsRelevant -> stringResource(R.string.ripdpi_tls_prelude_scope_https_disabled)
            uiState.tlsPrelude.hasStackedTlsPreludeSteps -> stringResource(R.string.ripdpi_tls_prelude_scope_stacked)
            else -> stringResource(R.string.ripdpi_tls_prelude_scope_active)
        }
    val badges =
        buildList {
            add(stringResource(R.string.ripdpi_tls_prelude_badge_https_only) to SummaryCapsuleTone.Info)
            when (uiState.tlsPrelude.tlsPreludeMode) {
                TcpChainStepKind.TlsRec.wireName -> {
                    add(stringResource(R.string.ripdpi_tls_prelude_badge_single) to SummaryCapsuleTone.Active)
                }

                TcpChainStepKind.TlsRandRec.wireName -> {
                    add(
                        stringResource(
                            R.string.ripdpi_tls_prelude_badge_random,
                            uiState.tlsPrelude.tlsRandRecFragmentCount,
                        ) to SummaryCapsuleTone.Active,
                    )
                }

                else -> {
                    Unit
                }
            }
            if (uiState.tlsPrelude.hasStackedTlsPreludeSteps) {
                add(
                    stringResource(
                        R.string.ripdpi_tls_prelude_badge_stacked,
                        uiState.tlsPrelude.tlsPreludeStepCount,
                    ) to SummaryCapsuleTone.Warning,
                )
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
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
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_marker),
                value = markerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_layout),
                value = layoutSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_scope),
                value = scopeSummary,
            )
        }
    }
}

@Composable
private fun rememberTlsPreludeStatus(uiState: SettingsUiState): TlsPreludeStatusContent =
    when {
        uiState.enableCmdSettings -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_cli_title),
                body = stringResource(R.string.ripdpi_tls_prelude_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.tlsPreludeControlsRelevant && uiState.tlsPrelude.tlsPreludeStepCount > 0 -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_https_disabled_title),
                body = stringResource(R.string.ripdpi_tls_prelude_https_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.tlsPrelude.hasStackedTlsPreludeSteps -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_stacked_title),
                body = stringResource(R.string.ripdpi_tls_prelude_stacked_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.tlsPrelude.tlsPreludeMode == TlsPreludeModeDisabled -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_off_title),
                body = stringResource(R.string.ripdpi_tls_prelude_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.isServiceRunning -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_restart_title),
                body = stringResource(R.string.ripdpi_tls_prelude_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.tlsPrelude.tlsPreludeUsesRandomRecords -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_random_title),
                body = stringResource(R.string.ripdpi_tls_prelude_random_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_single_title),
                body = stringResource(R.string.ripdpi_tls_prelude_single_body),
                tone = StatusIndicatorTone.Active,
            )
        }
    }

@Composable
private fun TlsPreludeModeSelector(
    uiState: SettingsUiState,
    enabled: Boolean,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val presets =
        listOf(
            TlsPreludePresetUiModel(
                value = TlsPreludeModeDisabled,
                title = stringResource(R.string.ripdpi_tls_prelude_mode_off_title),
                body = stringResource(R.string.ripdpi_tls_prelude_mode_off_body),
            ),
            TlsPreludePresetUiModel(
                value = TcpChainStepKind.TlsRec.wireName,
                title = stringResource(R.string.ripdpi_tls_prelude_mode_single_title),
                body = stringResource(R.string.ripdpi_tls_prelude_mode_single_body),
            ),
            TlsPreludePresetUiModel(
                value = TcpChainStepKind.TlsRandRec.wireName,
                title = stringResource(R.string.ripdpi_tls_prelude_mode_random_title),
                body = stringResource(R.string.ripdpi_tls_prelude_mode_random_body),
            ),
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_mode_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_mode_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            TlsPreludePresetCard(
                preset = preset,
                selected = uiState.tlsPrelude.tlsPreludeMode == preset.value,
                enabled = enabled,
                onClick = { onModeSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun TlsPreludePresetCard(
    preset: TlsPreludePresetUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = if (selected) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = preset.title,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = preset.body,
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            if (selected) {
                StatusIndicator(
                    label = stringResource(R.string.quic_fake_preset_selected),
                    tone = StatusIndicatorTone.Active,
                )
            }
        }
    }
}
