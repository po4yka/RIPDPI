package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.SupportedTcpFlagNames
import com.poyka.ripdpi.data.supportsFakeTcpFlags
import com.poyka.ripdpi.data.supportsOriginalTcpFlags
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun TcpFlagProfileCard(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = uiState.desync.primaryTcpFlagStep ?: return
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val supportsVisualEditing = uiState.desync.tcpFlagVisualEditorSupported
    val enabled = visualEditorEnabled && supportsVisualEditing

    RipDpiCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.tcp_flag_card_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text =
                if (supportsVisualEditing) {
                    stringResource(R.string.tcp_flag_card_body)
                } else {
                    stringResource(R.string.tcp_flag_card_dsl_only)
                },
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.tcp_flag_card_step_label),
                value = step.kind.wireName,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.tcp_flag_card_runtime_label),
                value =
                    if (uiState.desync.hasTcpFlagOverrides) {
                        stringResource(R.string.tcp_flag_card_runtime_custom)
                    } else {
                        stringResource(R.string.tcp_flag_card_runtime_default)
                    },
            )
        }
        if (step.kind.supportsFakeTcpFlags) {
            TcpFlagChipGroup(
                title = stringResource(R.string.tcp_flag_fake_set_title),
                currentValue = step.tcpFlagsSet,
                enabled = enabled,
                onUpdated = { onOptionSelected(AdvancedOptionSetting.TcpFlagsSet, it) },
            )
            TcpFlagChipGroup(
                title = stringResource(R.string.tcp_flag_fake_clear_title),
                currentValue = step.tcpFlagsUnset,
                enabled = enabled,
                onUpdated = { onOptionSelected(AdvancedOptionSetting.TcpFlagsUnset, it) },
            )
        }
        if (step.kind.supportsOriginalTcpFlags) {
            TcpFlagChipGroup(
                title = stringResource(R.string.tcp_flag_orig_set_title),
                currentValue = step.tcpFlagsOrigSet,
                enabled = enabled,
                onUpdated = { onOptionSelected(AdvancedOptionSetting.TcpFlagsOrigSet, it) },
            )
            TcpFlagChipGroup(
                title = stringResource(R.string.tcp_flag_orig_clear_title),
                currentValue = step.tcpFlagsOrigUnset,
                enabled = enabled,
                onUpdated = { onOptionSelected(AdvancedOptionSetting.TcpFlagsOrigUnset, it) },
            )
        }
    }
}

@Composable
private fun TcpFlagChipGroup(
    title: String,
    currentValue: String,
    enabled: Boolean,
    onUpdated: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val selected =
        currentValue
            .split('|')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = title,
            style = type.caption,
            color = colors.mutedForeground,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SupportedTcpFlagNames.forEach { flag ->
                RipDpiChip(
                    text = flag,
                    selected = flag in selected,
                    enabled = enabled,
                    onClick = {
                        val updated = selected.toMutableSet()
                        if (!updated.add(flag)) {
                            updated.remove(flag)
                        }
                        onUpdated(updated.sorted().joinToString(separator = "|"))
                    },
                )
            }
        }
    }
}
