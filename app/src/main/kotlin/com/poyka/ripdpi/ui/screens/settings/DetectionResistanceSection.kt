package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.EntropyModeDisabled
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun LazyListScope.detectionResistanceSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    entropyModeOptions: List<RipDpiDropdownOption<String>>,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_detection_resistance") {
        val spacing = RipDpiThemeTokens.spacing
        AdvancedSettingsSection(
            title = stringResource(R.string.detection_resistance_section_title),
            testTag = RipDpiTestTags.advancedSection("detection_resistance"),
        ) {
            DetectionResistanceSummaryCard(
                uiState = uiState,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            RipDpiCard(variant = RipDpiCardVariant.Outlined) {
                AdvancedDropdownSetting(
                    title = stringResource(R.string.detection_resistance_entropy_mode_title),
                    description = stringResource(R.string.detection_resistance_entropy_mode_body),
                    value = uiState.detectionResistance.entropyMode,
                    enabled = visualEditorEnabled,
                    options = entropyModeOptions,
                    setting = AdvancedOptionSetting.EntropyMode,
                    onSelected = onOptionSelected,
                    showDivider = true,
                )
                DetectionResistanceMorphingFields(
                    uiState = uiState,
                    visualEditorEnabled = visualEditorEnabled,
                    onTextConfirmed = onTextConfirmed,
                )
                HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
                SettingsRow(
                    title = stringResource(R.string.detection_resistance_strategy_evolution_title),
                    subtitle = stringResource(R.string.detection_resistance_strategy_evolution_body),
                    checked = uiState.detectionResistance.strategyEvolution,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.StrategyEvolution, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.StrategyEvolution),
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.detection_resistance_evolution_epsilon_title),
                    value = uiState.detectionResistance.evolutionEpsilon.toString(),
                    setting = AdvancedTextSetting.EvolutionEpsilon,
                    onConfirm = onTextConfirmed,
                    description = stringResource(R.string.detection_resistance_evolution_epsilon_body),
                    enabled = visualEditorEnabled && uiState.detectionResistance.strategyEvolution,
                    keyboardOptions = DecimalKeyboard,
                )
                HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
                SettingsRow(
                    title = stringResource(R.string.detection_resistance_quic_low_port_title),
                    subtitle = stringResource(R.string.detection_resistance_quic_low_port_body),
                    checked = uiState.detectionResistance.quicBindLowPort,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicBindLowPort, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.QuicBindLowPort),
                )
                SettingsRow(
                    title = stringResource(R.string.detection_resistance_quic_migrate_title),
                    subtitle =
                        if (uiState.isVpn) {
                            stringResource(R.string.detection_resistance_quic_migrate_body)
                        } else {
                            stringResource(R.string.detection_resistance_quic_migrate_vpn_only)
                        },
                    checked = uiState.detectionResistance.quicMigrateAfterHandshake,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicMigrateAfterHandshake, it) },
                    enabled = visualEditorEnabled && uiState.isVpn,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.QuicMigrateAfterHandshake),
                )
            }
        }
    }
}

private val NumericKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
    )

private val DecimalKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )

@Composable
private fun DetectionResistanceSummaryCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val resistance = uiState.detectionResistance
    val tone =
        when {
            uiState.enableCmdSettings -> StatusIndicatorTone.Warning
            resistance.entropyEnabled || resistance.strategyEvolution || resistance.quicMigrateAfterHandshake -> {
                StatusIndicatorTone.Active
            }
            else -> StatusIndicatorTone.Idle
        }
    val label =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.detection_resistance_cli_title)
            resistance.entropyEnabled || resistance.strategyEvolution || resistance.quicMigrateAfterHandshake -> {
                stringResource(R.string.detection_resistance_active_title)
            }
            else -> stringResource(R.string.detection_resistance_idle_title)
        }

    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Elevated) {
        StatusIndicator(label = label, tone = tone)
        Text(
            text = stringResource(R.string.detection_resistance_section_body),
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(
            items =
                buildList {
                    if (resistance.entropyEnabled) {
                        add(resistance.entropyMode to SummaryCapsuleTone.Active)
                    }
                    if (resistance.strategyEvolution) {
                        add(stringResource(R.string.detection_resistance_badge_evolution) to SummaryCapsuleTone.Info)
                    }
                    if (resistance.quicBindLowPort) {
                        add(stringResource(R.string.detection_resistance_badge_low_port) to SummaryCapsuleTone.Info)
                    }
                    if (resistance.quicMigrateAfterHandshake) {
                        add(stringResource(R.string.detection_resistance_badge_quic_migrate) to SummaryCapsuleTone.Active)
                    }
                    if (isEmpty()) {
                        add(stringResource(R.string.detection_resistance_badge_default) to SummaryCapsuleTone.Neutral)
                    }
                },
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_entropy),
                value =
                    if (resistance.entropyEnabled) {
                        stringResource(
                            R.string.detection_resistance_summary_entropy_value,
                            resistance.entropyPaddingTargetPermil,
                            resistance.shannonEntropyTargetPermil,
                        )
                    } else {
                        stringResource(R.string.detection_resistance_summary_off)
                    },
            )
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_exploration),
                value =
                    if (resistance.strategyEvolution) {
                        stringResource(
                            R.string.detection_resistance_summary_epsilon_value,
                            resistance.evolutionEpsilon,
                        )
                    } else {
                        stringResource(R.string.detection_resistance_summary_off)
                    },
            )
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_quic),
                value =
                    when {
                        resistance.quicMigrateAfterHandshake -> {
                            stringResource(R.string.detection_resistance_summary_quic_migrate)
                        }
                        resistance.quicBindLowPort -> {
                            stringResource(R.string.detection_resistance_summary_quic_low_port)
                        }
                        else -> stringResource(R.string.detection_resistance_summary_off)
                    },
            )
        }
        Text(
            text = stringResource(R.string.detection_resistance_restart_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun DetectionResistanceMorphingFields(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    val enabled = visualEditorEnabled && uiState.detectionResistance.entropyMode != EntropyModeDisabled
    AdvancedTextSetting(
        title = stringResource(R.string.detection_resistance_entropy_padding_title),
        value = uiState.detectionResistance.entropyPaddingTargetPermil.toString(),
        setting = AdvancedTextSetting.EntropyPaddingTargetPermil,
        onConfirm = onTextConfirmed,
        description = stringResource(R.string.detection_resistance_entropy_padding_body),
        enabled = enabled,
        keyboardOptions = NumericKeyboard,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.detection_resistance_entropy_padding_max_title),
        value = uiState.detectionResistance.entropyPaddingMax.toString(),
        setting = AdvancedTextSetting.EntropyPaddingMax,
        onConfirm = onTextConfirmed,
        description = stringResource(R.string.detection_resistance_entropy_padding_max_body),
        enabled = enabled,
        keyboardOptions = NumericKeyboard,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.detection_resistance_shannon_title),
        value = uiState.detectionResistance.shannonEntropyTargetPermil.toString(),
        setting = AdvancedTextSetting.ShannonEntropyTargetPermil,
        onConfirm = onTextConfirmed,
        description = stringResource(R.string.detection_resistance_shannon_body),
        enabled = enabled,
        keyboardOptions = NumericKeyboard,
    )
}
