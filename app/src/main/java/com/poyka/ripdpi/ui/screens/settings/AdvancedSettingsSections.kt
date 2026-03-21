package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort

internal fun LazyListScope.diagnosticsHistorySection(
    uiState: SettingsUiState,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onRotateTelemetrySalt: () -> Unit = {},
) {
    item(key = "advanced_diagnostics_history") {
        AdvancedSettingsSection(title = stringResource(R.string.diagnostics_history_section)) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.settings_diagnostics_monitor_title),
                    subtitle = stringResource(R.string.settings_diagnostics_monitor_body),
                    checked = uiState.diagnosticsMonitorEnabled,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DiagnosticsMonitorEnabled, it) },
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled),
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.settings_diagnostics_sample_title),
                    description = stringResource(R.string.settings_diagnostics_sample_body),
                    value = uiState.diagnosticsSampleIntervalSeconds.toString(),
                    enabled = uiState.diagnosticsMonitorEnabled,
                    validator = { validateIntRange(it, 5, 300) },
                    invalidMessage = stringResource(R.string.config_error_out_of_range),
                    disabledMessage = stringResource(R.string.settings_diagnostics_monitor_disabled),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    setting = AdvancedTextSetting.DiagnosticsSampleIntervalSeconds,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.settings_diagnostics_retention_title),
                    description = stringResource(R.string.settings_diagnostics_retention_body),
                    value = uiState.diagnosticsHistoryRetentionDays.toString(),
                    validator = { validateIntRange(it, 1, 365) },
                    invalidMessage = stringResource(R.string.config_error_out_of_range),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    setting = AdvancedTextSetting.DiagnosticsHistoryRetentionDays,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_diagnostics_export_history_title),
                    subtitle = stringResource(R.string.settings_diagnostics_export_history_body),
                    checked = uiState.diagnosticsExportIncludeHistory,
                    onCheckedChange = {
                        onToggleChanged(
                            AdvancedToggleSetting.DiagnosticsExportIncludeHistory,
                            it,
                        )
                    },
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsExportIncludeHistory),
                )
                Text(
                    text = stringResource(R.string.settings_telemetry_salt_reset_body),
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.settings_telemetry_salt_reset_action),
                        onClick = onRotateTelemetrySalt,
                        variant = RipDpiButtonVariant.Outline,
                        trailingIcon = RipDpiIcons.Close,
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.commandLineOverridesSection(
    uiState: SettingsUiState,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_overrides") {
        AdvancedSettingsSection(title = stringResource(R.string.config_overrides_section)) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.use_command_line_settings),
                    subtitle = stringResource(R.string.config_command_line_caption),
                    checked = uiState.enableCmdSettings,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.UseCommandLine, it) },
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.UseCommandLine),
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.command_line_arguments),
                    description = stringResource(R.string.config_command_line_helper),
                    value = uiState.cmdArgs,
                    placeholder = stringResource(R.string.config_placeholder_command_line),
                    enabled = uiState.enableCmdSettings,
                    multiline = true,
                    disabledMessage = stringResource(R.string.advanced_settings_command_line_disabled),
                    setting = AdvancedTextSetting.CommandLineArgs,
                    onConfirm = onTextConfirmed,
                )
            }
        }
    }
}

internal fun LazyListScope.proxySection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_proxy") {
        AdvancedSettingsSection(title = stringResource(R.string.ripdpi_proxy)) {
            RipDpiCard {
                AdvancedTextSetting(
                    title = stringResource(R.string.bye_dpi_proxy_ip_setting),
                    description = stringResource(R.string.config_proxy_helper),
                    value = uiState.proxy.proxyIp,
                    placeholder = stringResource(R.string.config_placeholder_proxy_ip),
                    enabled = visualEditorEnabled,
                    validator = ::checkIp,
                    invalidMessage = stringResource(R.string.config_error_invalid_proxy_ip),
                    disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                    setting = AdvancedTextSetting.ProxyIp,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.ripdpi_proxy_port_setting),
                    description = stringResource(R.string.config_port_helper),
                    value = uiState.proxy.proxyPort.toString(),
                    placeholder = stringResource(R.string.config_placeholder_proxy_port),
                    enabled = visualEditorEnabled,
                    validator = ::validatePort,
                    invalidMessage = stringResource(R.string.config_error_invalid_port),
                    disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    setting = AdvancedTextSetting.ProxyPort,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.ripdpi_max_connections_setting),
                    description = stringResource(R.string.config_max_connections_helper),
                    value = uiState.proxy.maxConnections.toString(),
                    enabled = visualEditorEnabled,
                    validator = { validateIntRange(it, 1, Short.MAX_VALUE.toInt()) },
                    invalidMessage = stringResource(R.string.config_error_out_of_range),
                    disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    setting = AdvancedTextSetting.MaxConnections,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                AdvancedTextSetting(
                    title = stringResource(R.string.ripdpi_buffer_size_setting),
                    description = stringResource(R.string.config_buffer_helper),
                    value = uiState.proxy.bufferSize.toString(),
                    enabled = visualEditorEnabled,
                    validator = { validateIntRange(it, 1, Int.MAX_VALUE / 4) },
                    invalidMessage = stringResource(R.string.config_error_out_of_range),
                    disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    setting = AdvancedTextSetting.BufferSize,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_no_domain_setting),
                    checked = uiState.proxy.noDomain,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.NoDomain, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.NoDomain),
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_tcp_fast_open_setting),
                    checked = uiState.proxy.tcpFastOpen,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.TcpFastOpen, it) },
                    enabled = visualEditorEnabled,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.TcpFastOpen),
                )
            }
        }
    }
}

internal fun LazyListScope.protocolsSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    item(key = "advanced_protocols") {
        AdvancedSettingsSection(title = stringResource(R.string.ripdpi_protocols_category)) {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.ripdpi_protocols_hint),
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
                SettingsRow(
                    title = stringResource(R.string.desync_http),
                    checked = uiState.desyncHttp,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DesyncHttp, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp),
                )
                SettingsRow(
                    title = stringResource(R.string.desync_https),
                    checked = uiState.desyncHttps,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DesyncHttps, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttps),
                )
                SettingsRow(
                    title = stringResource(R.string.desync_udp),
                    checked = uiState.desyncUdp,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DesyncUdp, it) },
                    enabled = visualEditorEnabled,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncUdp),
                )
            }
        }
    }
}

internal fun LazyListScope.networkStrategyMemorySection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onClearRememberedNetworks: () -> Unit,
) {
    item(key = "advanced_network_strategy_memory") {
        AdvancedSettingsSection(title = stringResource(R.string.network_strategy_memory_section_title)) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.network_strategy_memory_enabled_title),
                    subtitle = stringResource(R.string.network_strategy_memory_enabled_body),
                    checked = uiState.autolearn.networkStrategyMemoryEnabled,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.NetworkStrategyMemoryEnabled, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.NetworkStrategyMemoryEnabled),
                )
                Text(
                    text =
                        stringResource(
                            R.string.network_strategy_memory_count_summary,
                            uiState.autolearn.rememberedNetworkCount,
                        ),
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.network_strategy_memory_clear_action),
                        onClick = onClearRememberedNetworks,
                        enabled = uiState.canClearRememberedNetworks,
                        variant = RipDpiButtonVariant.Outline,
                        trailingIcon = RipDpiIcons.Close,
                    )
                }
                Text(
                    text = stringResource(R.string.network_strategy_memory_helper),
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
internal fun AdvancedSettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = title)
        content()
    }
}
