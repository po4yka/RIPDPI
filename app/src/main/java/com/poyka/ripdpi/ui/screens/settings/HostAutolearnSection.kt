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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange

internal fun LazyListScope.hostAutolearnSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onForgetLearnedHosts: () -> Unit,
) {
    item(key = "advanced_host_autolearn") {
        val colors = RipDpiThemeTokens.colors
        val spacing = RipDpiThemeTokens.spacing

        AdvancedSettingsSection(title = stringResource(R.string.host_autolearn_section_title)) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.host_autolearn_enabled_title),
                    subtitle = stringResource(R.string.host_autolearn_enabled_body),
                    checked = uiState.autolearn.hostAutolearnEnabled,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HostAutolearnEnabled, it) },
                    enabled = visualEditorEnabled,
                    showDivider = uiState.autolearn.hostAutolearnEnabled,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HostAutolearnEnabled),
                )
                HostAutolearnStatusCard(
                    uiState = uiState,
                    modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                )
                if (uiState.autolearn.hostAutolearnEnabled) {
                    HorizontalDivider(color = colors.divider)
                }
                if (uiState.autolearn.hostAutolearnEnabled) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.host_autolearn_penalty_ttl_title),
                        description = stringResource(R.string.host_autolearn_penalty_ttl_body),
                        value = uiState.autolearn.hostAutolearnPenaltyTtlHours.toString(),
                        enabled = visualEditorEnabled,
                        validator = { validateIntRange(it, 1, 24 * 30) },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.HostAutolearnPenaltyTtlHours,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.host_autolearn_max_hosts_title),
                        description = stringResource(R.string.host_autolearn_max_hosts_body),
                        value = uiState.autolearn.hostAutolearnMaxHosts.toString(),
                        enabled = visualEditorEnabled,
                        validator = { validateIntRange(it, 1, 50_000) },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.HostAutolearnMaxHosts,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                Text(
                    text = stringResource(R.string.host_autolearn_helper),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.host_autolearn_forget_action),
                        onClick = onForgetLearnedHosts,
                        enabled = uiState.canForgetLearnedHosts,
                        variant = RipDpiButtonVariant.Outline,
                        trailingIcon = RipDpiIcons.Close,
                    )
                }
                Text(
                    text = hostAutolearnResetHint(uiState),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

private data class HostAutolearnStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
private fun HostAutolearnStatusCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHostAutolearnStatus(uiState)
    val runtimeSummary =
        if (uiState.isServiceRunning &&
            (uiState.autolearn.hostAutolearnRuntimeEnabled || uiState.autolearn.hostAutolearnLearnedHostCount > 0)
        ) {
            stringResource(
                R.string.host_autolearn_runtime_summary,
                uiState.autolearn.hostAutolearnLearnedHostCount,
                uiState.autolearn.hostAutolearnPenalizedHostCount,
            )
        } else {
            null
        }
    val limitsSummary =
        if (uiState.enableCmdSettings) {
            null
        } else {
            stringResource(
                R.string.host_autolearn_limits_summary,
                uiState.autolearn.hostAutolearnPenaltyTtlHours,
                uiState.autolearn.hostAutolearnMaxHosts,
            )
        }
    val lastUpdate = hostAutolearnLastUpdate(uiState)

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
        limitsSummary?.let {
            Text(
                text = it,
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
        if (runtimeSummary != null) {
            Text(
                text = runtimeSummary,
                style = type.caption,
                color = colors.foreground,
            )
        } else if (uiState.autolearn.hostAutolearnStorePresent && !uiState.enableCmdSettings) {
            Text(
                text = stringResource(R.string.host_autolearn_store_present_summary),
                style = type.caption,
                color = colors.foreground,
            )
        }
        lastUpdate?.let {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(R.string.host_autolearn_last_update_label),
                    style = type.sectionTitle,
                    color = colors.mutedForeground,
                )
                Text(
                    text = it,
                    style = type.secondaryBody,
                    color = colors.foreground,
                )
            }
        }
    }
}

@Composable
private fun rememberHostAutolearnStatus(uiState: SettingsUiState): HostAutolearnStatusContent =
    when {
        uiState.enableCmdSettings && uiState.isServiceRunning && uiState.autolearn.hostAutolearnRuntimeEnabled -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_live_status_title),
                body = stringResource(R.string.host_autolearn_cli_live_status_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        uiState.enableCmdSettings -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_cli_status_title),
                body = stringResource(R.string.host_autolearn_cli_status_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.isServiceRunning && uiState.autolearn.hostAutolearnEnabled &&
            uiState.autolearn.hostAutolearnRuntimeEnabled -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_live_status_title),
                body = stringResource(R.string.host_autolearn_live_status_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        uiState.isServiceRunning && uiState.autolearn.hostAutolearnEnabled -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_pending_enable_title),
                body = stringResource(R.string.host_autolearn_pending_enable_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.isServiceRunning && uiState.autolearn.hostAutolearnRuntimeEnabled -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_pending_disable_title),
                body = stringResource(R.string.host_autolearn_pending_disable_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.autolearn.hostAutolearnEnabled -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_ready_title),
                body = stringResource(R.string.host_autolearn_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        uiState.autolearn.hostAutolearnStorePresent -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_store_title),
                body = stringResource(R.string.host_autolearn_store_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        else -> {
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_off_title),
                body = stringResource(R.string.host_autolearn_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }

@Composable
private fun hostAutolearnResetHint(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> {
            stringResource(R.string.host_autolearn_reset_hint_cli)
        }

        !uiState.autolearn.hostAutolearnStorePresent &&
            (uiState.autolearn.hostAutolearnEnabled || uiState.autolearn.hostAutolearnRuntimeEnabled) -> {
            stringResource(R.string.host_autolearn_reset_hint_waiting)
        }

        !uiState.autolearn.hostAutolearnStorePresent -> {
            stringResource(R.string.host_autolearn_reset_hint_empty)
        }

        uiState.isServiceRunning -> {
            stringResource(R.string.host_autolearn_reset_hint_running)
        }

        else -> {
            stringResource(R.string.host_autolearn_reset_hint_ready)
        }
    }

@Composable
private fun hostAutolearnLastUpdate(uiState: SettingsUiState): String? {
    val action =
        when (uiState.autolearn.hostAutolearnLastAction) {
            "host_promoted" -> stringResource(R.string.host_autolearn_action_host_promoted)
            "group_penalized" -> stringResource(R.string.host_autolearn_action_group_penalized)
            "store_reset" -> stringResource(R.string.host_autolearn_action_store_reset)
            else -> null
        } ?: return null

    val host = uiState.autolearn.hostAutolearnLastHost?.takeIf { it.isNotBlank() }
    val group =
        uiState.autolearn.hostAutolearnLastGroup?.let {
            stringResource(R.string.host_autolearn_route_group, it)
        }
    return listOfNotNull(action, host, group).joinToString(" · ")
}
