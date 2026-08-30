package com.poyka.ripdpi.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort

private const val diagnosticsSampleIntervalMin = 5
private const val diagnosticsSampleIntervalMax = 300
private const val diagnosticsRetentionMaxDays = 365
private const val bufferSizeDiv = 4

internal fun LazyListScope.diagnosticsHistorySection(
    uiState: SettingsUiState,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onRotateSalt: () -> Unit = {},
) {
    item(key = "advanced_diagnostics_history") {
        AdvancedSettingsSection(
            title = stringResource(R.string.diagnostics_history_section),
            testTag = RipDpiTestTags.advancedSection("diagnostics_history"),
        ) {
            RipDpiCard {
                DiagnosticsHistorySettingsContent(
                    uiState = uiState,
                    onToggleChanged = onToggleChanged,
                    onTextConfirmed = onTextConfirmed,
                    onRotateSalt = onRotateSalt,
                )
            }
        }
    }
}

internal fun LazyListScope.strategyConfigSection(onOpenStrategyConfig: () -> Unit) {
    item(key = "advanced_strategy_config") {
        AdvancedSettingsSection(
            title = stringResource(R.string.strategy_config_section_title),
            testTag = RipDpiTestTags.advancedSection("strategy_config"),
        ) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.strategy_config_entry_title),
                    subtitle = stringResource(R.string.strategy_config_entry_body),
                    onClick = onOpenStrategyConfig,
                    leadingIcon = RipDpiIcons.Advanced,
                    showChevron = true,
                    testTag = RipDpiTestTags.SettingsStrategyConfig,
                )
            }
        }
    }
}

internal fun LazyListScope.assetProviderSection(onOpenAssetProvider: () -> Unit) {
    item(key = "advanced_asset_provider") {
        AdvancedSettingsSection(
            title = stringResource(R.string.asset_provider_section_title),
            testTag = RipDpiTestTags.advancedSection("asset_provider"),
        ) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.asset_provider_entry_title),
                    subtitle = stringResource(R.string.asset_provider_entry_body),
                    onClick = onOpenAssetProvider,
                    leadingIcon = RipDpiIcons.Public,
                    showChevron = true,
                    testTag = RipDpiTestTags.SettingsAssetProvider,
                )
            }
        }
    }
}

internal fun LazyListScope.blockcheckSection(onOpenBlockcheck: () -> Unit) {
    item(key = "advanced_blockcheck") {
        AdvancedSettingsSection(
            title = stringResource(R.string.blockcheck_section_title),
            testTag = RipDpiTestTags.advancedSection("blockcheck"),
        ) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.blockcheck_entry_title),
                    subtitle = stringResource(R.string.blockcheck_entry_body),
                    onClick = onOpenBlockcheck,
                    leadingIcon = RipDpiIcons.Logs,
                    showChevron = true,
                    testTag = RipDpiTestTags.SettingsBlockcheck,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsHistorySettingsContent(
    uiState: SettingsUiState,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onRotateSalt: () -> Unit,
) {
    DiagnosticsMonitorToggle(
        enabled = uiState.diagnosticsMonitorEnabled,
        onToggleChanged = onToggleChanged,
    )
    DiagnosticsRetentionFields(
        uiState = uiState,
        onTextConfirmed = onTextConfirmed,
    )
    DiagnosticsExportHistoryToggle(
        enabled = uiState.diagnosticsExportIncludeHistory,
        onToggleChanged = onToggleChanged,
    )
    if (BuildConfig.DEBUG) {
        StrategyPackRollbackOverrideToggle(
            enabled = uiState.strategyPackAllowRollbackOverride,
            onToggleChanged = onToggleChanged,
        )
    }
    TelemetrySaltResetAction(onRotateSalt = onRotateSalt)
}

@Composable
private fun DiagnosticsMonitorToggle(
    enabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.settings_diagnostics_monitor_title),
        subtitle = stringResource(R.string.settings_diagnostics_monitor_body),
        checked = enabled,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DiagnosticsMonitorEnabled, it) },
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled),
    )
}

@Composable
private fun DiagnosticsRetentionFields(
    uiState: SettingsUiState,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    val numericKeyboardOptions =
        KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )
    AdvancedTextSetting(
        title = stringResource(R.string.settings_diagnostics_sample_title),
        description = stringResource(R.string.settings_diagnostics_sample_body),
        value = uiState.diagnosticsSampleIntervalSeconds.toString(),
        enabled = uiState.diagnosticsMonitorEnabled,
        validator = { validateIntRange(it, diagnosticsSampleIntervalMin, diagnosticsSampleIntervalMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.settings_diagnostics_monitor_disabled),
        keyboardOptions = numericKeyboardOptions,
        setting = AdvancedTextSetting.DiagnosticsSampleIntervalSeconds,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.settings_diagnostics_retention_title),
        description = stringResource(R.string.settings_diagnostics_retention_body),
        value = uiState.diagnosticsHistoryRetentionDays.toString(),
        validator = { validateIntRange(it, 1, diagnosticsRetentionMaxDays) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        keyboardOptions = numericKeyboardOptions,
        setting = AdvancedTextSetting.DiagnosticsHistoryRetentionDays,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
}

@Composable
private fun DiagnosticsExportHistoryToggle(
    enabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.settings_diagnostics_export_history_title),
        subtitle = stringResource(R.string.settings_diagnostics_export_history_body),
        checked = enabled,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DiagnosticsExportIncludeHistory, it) },
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsExportIncludeHistory),
    )
}

@Composable
private fun StrategyPackRollbackOverrideToggle(
    enabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.settings_strategy_pack_allow_rollback_override_title),
        subtitle = stringResource(R.string.settings_strategy_pack_allow_rollback_override_body),
        checked = enabled,
        onCheckedChange = {
            onToggleChanged(AdvancedToggleSetting.StrategyPackAllowRollbackOverride, it)
        },
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.StrategyPackAllowRollbackOverride),
    )
}

@Composable
private fun TelemetrySaltResetAction(onRotateSalt: () -> Unit) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        RipDpiDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = stringResource(R.string.confirm_rotate_telemetry_salt_title),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.confirm_rotate_telemetry_salt_dismiss),
                    onClick = { showConfirmDialog = false },
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.confirm_rotate_telemetry_salt_confirm),
                    onClick = {
                        showConfirmDialog = false
                        onRotateSalt()
                    },
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.confirm_rotate_telemetry_salt_body),
                    tone = RipDpiDialogTone.Destructive,
                ),
        )
    }

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
            onClick = { showConfirmDialog = true },
            variant = RipDpiButtonVariant.Outline,
            trailingIcon = RipDpiIcons.Close,
        )
    }
}

internal fun LazyListScope.commandLineOverridesSection(
    uiState: SettingsUiState,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_overrides") {
        AdvancedSettingsSection(
            title = stringResource(R.string.config_overrides_section),
            testTag = RipDpiTestTags.advancedSection("overrides"),
        ) {
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
        AdvancedSettingsSection(
            title = stringResource(R.string.ripdpi_proxy),
            testTag = RipDpiTestTags.advancedSection("proxy"),
        ) {
            ProxySectionCard(
                uiState = uiState,
                visualEditorEnabled = visualEditorEnabled,
                onToggleChanged = onToggleChanged,
                onTextConfirmed = onTextConfirmed,
            )
        }
    }
}

@Composable
private fun ProxySectionCard(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
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
        ProxyPortSetting(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onTextConfirmed = onTextConfirmed,
        )
        ProxyCapacitySettings(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onTextConfirmed = onTextConfirmed,
        )
        ProxyToggleSettings(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
        )
    }
}

@Composable
private fun ProxyPortSetting(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
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
}

@Composable
private fun ProxyCapacitySettings(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_max_connections_setting),
        description = stringResource(R.string.config_max_connections_helper),
        value = uiState.proxy.maxConnections.toString(),
        enabled = visualEditorEnabled,
        validator = { validateIntRange(it, 1, Short.MAX_VALUE.toInt()) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = numberKeyboardOptions(),
        setting = AdvancedTextSetting.MaxConnections,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_buffer_size_setting),
        description = stringResource(R.string.config_buffer_helper),
        value = uiState.proxy.bufferSize.toString(),
        enabled = visualEditorEnabled,
        validator = { validateIntRange(it, 1, Int.MAX_VALUE / bufferSizeDiv) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = numberKeyboardOptions(),
        setting = AdvancedTextSetting.BufferSize,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
}

@Composable
private fun ProxyToggleSettings(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    var showAllowLanWarning by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lanAuthTokenLabel = stringResource(R.string.clipboard_label_lan_auth_token)

    if (showAllowLanWarning) {
        ProxyAllowLanWarningDialog(
            onDismiss = { showAllowLanWarning = false },
            onConfirm = {
                showAllowLanWarning = false
                onToggleChanged(AdvancedToggleSetting.ProxyAllowLan, true)
            },
        )
    }

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
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.TcpFastOpen),
    )
    SettingsRow(
        title = stringResource(R.string.settings_mixed_inbound_title),
        subtitle = stringResource(R.string.settings_mixed_inbound_body),
        checked = uiState.proxy.mixedInboundEnabled,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.MixedInbound, it) },
        enabled = visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.MixedInbound),
    )
    SettingsRow(
        title = stringResource(R.string.settings_append_http_proxy_title),
        subtitle = stringResource(R.string.settings_append_http_proxy_body),
        checked = uiState.proxy.appendHttpProxy,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AppendHttpProxy, it) },
        enabled = visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AppendHttpProxy),
    )
    SettingsRow(
        title = stringResource(R.string.settings_proxy_allow_lan_title),
        subtitle = stringResource(R.string.settings_proxy_allow_lan_body),
        checked = uiState.proxy.allowLan,
        onCheckedChange = { newValue ->
            if (newValue && !uiState.proxy.allowLan) {
                showAllowLanWarning = true
            } else {
                onToggleChanged(AdvancedToggleSetting.ProxyAllowLan, newValue)
            }
        },
        enabled = visualEditorEnabled,
        showDivider = uiState.proxy.allowLan && uiState.proxy.lanAuthToken.isNotEmpty(),
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.ProxyAllowLan),
    )
    if (uiState.proxy.allowLan && uiState.proxy.lanAuthToken.isNotEmpty()) {
        val lanAuthTokenLabel = stringResource(R.string.clipboard_label_lan_auth_token)
        ProxyLanTokenRow(
            token = uiState.proxy.lanAuthToken,
            enabled = visualEditorEnabled,
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        lanAuthTokenLabel,
                        uiState.proxy.lanAuthToken,
                    ),
                )
            },
        )
    }
}

@Composable
private fun ProxyAllowLanWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_proxy_allow_lan_warn_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.settings_proxy_allow_lan_warn_dismiss),
                onClick = onDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.settings_proxy_allow_lan_warn_confirm),
                onClick = onConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.settings_proxy_allow_lan_warn_message),
                tone = RipDpiDialogTone.Info,
            ),
    )
}

@Composable
private fun ProxyLanTokenRow(
    token: String,
    enabled: Boolean,
    onCopy: () -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.settings_proxy_lan_token_label),
        value = token,
        monospaceValue = true,
        onClick = onCopy,
        enabled = enabled,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.ProxyAllowLan) + "_token",
    )
}

private fun numberKeyboardOptions(): KeyboardOptions =
    KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
    )

internal fun LazyListScope.protocolsSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    item(key = "advanced_protocols") {
        AdvancedSettingsSection(
            title = stringResource(R.string.ripdpi_protocols_category),
            testTag = RipDpiTestTags.advancedSection("protocols"),
        ) {
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
    onOpenRememberedNetworks: () -> Unit,
) {
    item(key = "advanced_network_strategy_memory") {
        var showClearDialog by remember { mutableStateOf(false) }

        if (showClearDialog) {
            ClearRememberedNetworksDialog(
                onDismiss = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onClearRememberedNetworks()
                },
            )
        }

        AdvancedSettingsSection(
            title = stringResource(R.string.network_strategy_memory_section_title),
            testTag = RipDpiTestTags.advancedSection("network_strategy_memory"),
        ) {
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
                SettingsRow(
                    title = stringResource(R.string.network_strategy_memory_inspect_title),
                    subtitle = stringResource(R.string.network_strategy_memory_inspect_body),
                    value = uiState.autolearn.rememberedNetworkCount.toString(),
                    onClick = onOpenRememberedNetworks,
                    enabled = uiState.autolearn.rememberedNetworkCount > 0,
                    showDivider = true,
                    testTag = RipDpiTestTags.AdvancedInspectRememberedNetworks,
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
                        onClick = { showClearDialog = true },
                        enabled = uiState.canClearRememberedNetworks,
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.AdvancedClearRememberedNetworks),
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
private fun ClearRememberedNetworksDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.confirm_clear_remembered_networks_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.confirm_clear_remembered_networks_dismiss),
                onClick = onDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.confirm_clear_remembered_networks_confirm),
                onClick = onConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.confirm_clear_remembered_networks_body),
                tone = RipDpiDialogTone.Destructive,
            ),
    )
}

internal fun LazyListScope.wsTunnelSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onWsTunnelModeChanged: (String) -> Unit,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onSaveWorkerTransport: (String, String, String) -> Unit,
    onClearWorkerTransport: () -> Unit,
) {
    item(key = "advanced_ws_tunnel") {
        AdvancedSettingsSection(
            title = stringResource(R.string.ws_tunnel_section_title),
            testTag = RipDpiTestTags.advancedSection("ws_tunnel"),
        ) {
            RipDpiCard {
                val offLabel = stringResource(R.string.ws_tunnel_mode_off)
                val alwaysLabel = stringResource(R.string.ws_tunnel_mode_always)
                val fallbackLabel = stringResource(R.string.ws_tunnel_mode_fallback)
                val options =
                    remember(offLabel, alwaysLabel, fallbackLabel) {
                        kotlinx.collections.immutable.persistentListOf(
                            RipDpiDropdownOption("off", offLabel),
                            RipDpiDropdownOption("always", alwaysLabel),
                            RipDpiDropdownOption("fallback", fallbackLabel),
                        )
                    }
                RipDpiDropdown(
                    options = options,
                    selectedValue = uiState.autolearn.wsTunnelMode,
                    onValueSelected = onWsTunnelModeChanged,
                    label = stringResource(R.string.ws_tunnel_mode_label),
                    helperText = stringResource(R.string.ws_tunnel_mode_helper),
                    enabled = visualEditorEnabled,
                    testTag = RipDpiTestTags.advancedSection("ws_tunnel_mode"),
                    optionTagForValue = { selectedValue ->
                        RipDpiTestTags.dropdownOption(
                            RipDpiTestTags.advancedSection("ws_tunnel_mode"),
                            selectedValue,
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.ws_tunnel_allow_insecure_sni_label),
                    subtitle = stringResource(R.string.ws_tunnel_allow_insecure_sni_helper),
                    checked = uiState.autolearn.wsTunnelAllowInsecureSni,
                    onCheckedChange = {
                        onToggleChanged(AdvancedToggleSetting.WsTunnelAllowInsecureSni, it)
                    },
                    enabled = visualEditorEnabled,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.WsTunnelAllowInsecureSni),
                )
                WsTunnelWorkerSettingsUi(
                    workerUrl = uiState.autolearn.wsTunnelWorkerUrl,
                    credentialRef = uiState.autolearn.wsTunnelWorkerCredentialRef,
                    enabled = visualEditorEnabled,
                    onSave = onSaveWorkerTransport,
                    onClear = onClearWorkerTransport,
                )
            }
        }
    }
}

internal fun LazyListScope.pcapCaptureSection(
    pcapCaptureEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    item(key = "advanced_pcap_capture") {
        AdvancedSettingsSection(
            title = stringResource(R.string.vpn_dev_pcap_section_title),
            testTag = RipDpiTestTags.advancedSection("pcap_capture"),
        ) {
            RipDpiCard {
                PcapCaptureToggle(
                    enabled = pcapCaptureEnabled,
                    onToggleChanged = onToggleChanged,
                )
            }
        }
    }
}

@Composable
private fun PcapCaptureToggle(
    enabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    var showConsent by remember { mutableStateOf(false) }

    if (showConsent) {
        RipDpiDialog(
            onDismissRequest = { showConsent = false },
            title = stringResource(R.string.vpn_dev_pcap_consent_title),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.settings_biometric_confirm_cancel),
                    onClick = { showConsent = false },
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.onboarding_continue),
                    onClick = {
                        showConsent = false
                        onToggleChanged(AdvancedToggleSetting.PcapCaptureEnabled, true)
                    },
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.vpn_dev_pcap_consent_warning),
                    tone = RipDpiDialogTone.Info,
                ),
        )
    }

    SettingsRow(
        title = stringResource(R.string.vpn_dev_pcap_toggle_label),
        subtitle = stringResource(R.string.vpn_dev_pcap_toggle_desc),
        checked = enabled,
        onCheckedChange = { newValue ->
            if (newValue && !enabled) {
                showConsent = true
            } else {
                onToggleChanged(AdvancedToggleSetting.PcapCaptureEnabled, newValue)
            }
        },
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.PcapCaptureEnabled),
    )
}

@Composable
internal fun AdvancedSettingsSection(
    title: String,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = Modifier.ripDpiTestTag(testTag),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingsCategoryHeader(title = title)
        content()
    }
}
