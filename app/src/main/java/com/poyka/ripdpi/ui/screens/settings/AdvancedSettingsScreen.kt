package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort

private enum class AdvancedToggleSetting {
    UseCommandLine,
    NoDomain,
    TcpFastOpen,
    SplitAtHost,
    DropSack,
    DesyncHttp,
    DesyncHttps,
    DesyncUdp,
    HostMixedCase,
    DomainMixedCase,
    HostRemoveSpaces,
    TlsrecEnabled,
    TlsrecAtSni,
}

private enum class AdvancedTextSetting {
    CommandLineArgs,
    ProxyIp,
    ProxyPort,
    MaxConnections,
    BufferSize,
    DefaultTtl,
    SplitPosition,
    FakeTtl,
    FakeSni,
    FakeOffset,
    OobData,
    TlsrecPosition,
    UdpFakeCount,
    HostsBlacklist,
    HostsWhitelist,
}

private enum class AdvancedOptionSetting {
    DesyncMethod,
    HostsMode,
}

@Composable
fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AdvancedSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onToggleChanged = { setting, enabled ->
            when (setting) {
                AdvancedToggleSetting.UseCommandLine -> {
                    viewModel.updateSetting(
                        key = "enableCmdSettings",
                        value = enabled.toString(),
                    ) {
                        setEnableCmdSettings(enabled)
                    }
                }

                AdvancedToggleSetting.NoDomain -> {
                    viewModel.updateSetting(
                        key = "noDomain",
                        value = enabled.toString(),
                    ) {
                        setNoDomain(enabled)
                    }
                }

                AdvancedToggleSetting.TcpFastOpen -> {
                    viewModel.updateSetting(
                        key = "tcpFastOpen",
                        value = enabled.toString(),
                    ) {
                        setTcpFastOpen(enabled)
                    }
                }

                AdvancedToggleSetting.SplitAtHost -> {
                    viewModel.updateSetting(
                        key = "splitAtHost",
                        value = enabled.toString(),
                    ) {
                        setSplitAtHost(enabled)
                    }
                }

                AdvancedToggleSetting.DropSack -> {
                    viewModel.updateSetting(
                        key = "dropSack",
                        value = enabled.toString(),
                    ) {
                        setDropSack(enabled)
                    }
                }

                AdvancedToggleSetting.DesyncHttp -> {
                    viewModel.updateSetting(
                        key = "desyncHttp",
                        value = enabled.toString(),
                    ) {
                        setDesyncHttp(enabled)
                    }
                }

                AdvancedToggleSetting.DesyncHttps -> {
                    viewModel.updateSetting(
                        key = "desyncHttps",
                        value = enabled.toString(),
                    ) {
                        setDesyncHttps(enabled)
                    }
                }

                AdvancedToggleSetting.DesyncUdp -> {
                    viewModel.updateSetting(
                        key = "desyncUdp",
                        value = enabled.toString(),
                    ) {
                        setDesyncUdp(enabled)
                    }
                }

                AdvancedToggleSetting.HostMixedCase -> {
                    viewModel.updateSetting(
                        key = "hostMixedCase",
                        value = enabled.toString(),
                    ) {
                        setHostMixedCase(enabled)
                    }
                }

                AdvancedToggleSetting.DomainMixedCase -> {
                    viewModel.updateSetting(
                        key = "domainMixedCase",
                        value = enabled.toString(),
                    ) {
                        setDomainMixedCase(enabled)
                    }
                }

                AdvancedToggleSetting.HostRemoveSpaces -> {
                    viewModel.updateSetting(
                        key = "hostRemoveSpaces",
                        value = enabled.toString(),
                    ) {
                        setHostRemoveSpaces(enabled)
                    }
                }

                AdvancedToggleSetting.TlsrecEnabled -> {
                    viewModel.updateSetting(
                        key = "tlsrecEnabled",
                        value = enabled.toString(),
                    ) {
                        setTlsrecEnabled(enabled)
                    }
                }

                AdvancedToggleSetting.TlsrecAtSni -> {
                    viewModel.updateSetting(
                        key = "tlsrecAtSni",
                        value = enabled.toString(),
                    ) {
                        setTlsrecAtSni(enabled)
                    }
                }
            }
        },
        onTextConfirmed = { setting, value ->
            when (setting) {
                AdvancedTextSetting.CommandLineArgs -> {
                    viewModel.updateSetting(
                        key = "cmdArgs",
                        value = value,
                    ) {
                        setCmdArgs(value)
                    }
                }

                AdvancedTextSetting.ProxyIp -> {
                    viewModel.updateSetting(
                        key = "proxyIp",
                        value = value,
                    ) {
                        setProxyIp(value)
                    }
                }

                AdvancedTextSetting.ProxyPort -> {
                    value.toIntOrNull()?.let { port ->
                        viewModel.updateSetting(
                            key = "proxyPort",
                            value = value,
                        ) {
                            setProxyPort(port)
                        }
                    }
                }

                AdvancedTextSetting.MaxConnections -> {
                    value.toIntOrNull()?.let { maxConnections ->
                        viewModel.updateSetting(
                            key = "maxConnections",
                            value = value,
                        ) {
                            setMaxConnections(maxConnections)
                        }
                    }
                }

                AdvancedTextSetting.BufferSize -> {
                    value.toIntOrNull()?.let { bufferSize ->
                        viewModel.updateSetting(
                            key = "bufferSize",
                            value = value,
                        ) {
                            setBufferSize(bufferSize)
                        }
                    }
                }

                AdvancedTextSetting.DefaultTtl -> {
                    if (value.isBlank()) {
                        viewModel.updateSetting(
                            key = "defaultTtl",
                            value = "0",
                        ) {
                            setCustomTtl(false)
                            setDefaultTtl(0)
                        }
                    } else {
                        value.toIntOrNull()?.let { ttl ->
                            viewModel.updateSetting(
                                key = "defaultTtl",
                                value = value,
                            ) {
                                setCustomTtl(true)
                                setDefaultTtl(ttl)
                            }
                        }
                    }
                }

                AdvancedTextSetting.SplitPosition -> {
                    value.toIntOrNull()?.let { splitPosition ->
                        viewModel.updateSetting(
                            key = "splitPosition",
                            value = value,
                        ) {
                            setSplitPosition(splitPosition)
                        }
                    }
                }

                AdvancedTextSetting.FakeTtl -> {
                    value.toIntOrNull()?.let { fakeTtl ->
                        viewModel.updateSetting(
                            key = "fakeTtl",
                            value = value,
                        ) {
                            setFakeTtl(fakeTtl)
                        }
                    }
                }

                AdvancedTextSetting.FakeSni -> {
                    viewModel.updateSetting(
                        key = "fakeSni",
                        value = value,
                    ) {
                        setFakeSni(value)
                    }
                }

                AdvancedTextSetting.FakeOffset -> {
                    value.toIntOrNull()?.let { fakeOffset ->
                        viewModel.updateSetting(
                            key = "fakeOffset",
                            value = value,
                        ) {
                            setFakeOffset(fakeOffset)
                        }
                    }
                }

                AdvancedTextSetting.OobData -> {
                    if (value.length <= 1) {
                        viewModel.updateSetting(
                            key = "oobData",
                            value = value,
                        ) {
                            setOobData(value)
                        }
                    }
                }

                AdvancedTextSetting.TlsrecPosition -> {
                    value.toIntOrNull()?.let { tlsrecPosition ->
                        viewModel.updateSetting(
                            key = "tlsrecPosition",
                            value = value,
                        ) {
                            setTlsrecPosition(tlsrecPosition)
                        }
                    }
                }

                AdvancedTextSetting.UdpFakeCount -> {
                    value.toIntOrNull()?.let { udpFakeCount ->
                        viewModel.updateSetting(
                            key = "udpFakeCount",
                            value = value,
                        ) {
                            setUdpFakeCount(udpFakeCount)
                        }
                    }
                }

                AdvancedTextSetting.HostsBlacklist -> {
                    viewModel.updateSetting(
                        key = "hostsBlacklist",
                        value = value,
                    ) {
                        setHostsBlacklist(value)
                    }
                }

                AdvancedTextSetting.HostsWhitelist -> {
                    viewModel.updateSetting(
                        key = "hostsWhitelist",
                        value = value,
                    ) {
                        setHostsWhitelist(value)
                    }
                }
            }
        },
        onOptionSelected = { setting, value ->
            when (setting) {
                AdvancedOptionSetting.DesyncMethod -> {
                    viewModel.updateSetting(
                        key = "desyncMethod",
                        value = value,
                    ) {
                        setDesyncMethod(value)
                    }
                }

                AdvancedOptionSetting.HostsMode -> {
                    viewModel.updateSetting(
                        key = "hostsMode",
                        value = value,
                    ) {
                        setHostsMode(value)
                    }
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun AdvancedSettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val visualEditorEnabled = !uiState.enableCmdSettings
    val desyncOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.ripdpi_desync_methods,
            valueArrayRes = R.array.ripdpi_desync_methods_entries,
        )
    val hostsOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.ripdpi_hosts_modes,
            valueArrayRes = R.array.ripdpi_hosts_modes_entries,
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
    ) {
        RipDpiTopAppBar(
            title = stringResource(R.string.title_advanced_settings),
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = layout.horizontalPadding,
                    top = spacing.sm,
                    end = layout.horizontalPadding,
                    bottom = spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
        ) {
            if (uiState.enableCmdSettings) {
                item(key = "advanced_settings_warning") {
                    WarningBanner(
                        title = stringResource(R.string.config_cli_banner_title),
                        message = stringResource(R.string.config_cli_banner_body),
                        tone = WarningBannerTone.Restricted,
                    )
                }
            }

            item(key = "advanced_overrides") {
                SettingsSection(title = stringResource(R.string.config_overrides_section)) {
                    RipDpiCard {
                        SettingsRow(
                            title = stringResource(R.string.use_command_line_settings),
                            subtitle = stringResource(R.string.config_command_line_caption),
                            checked = uiState.enableCmdSettings,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.UseCommandLine, it) },
                            showDivider = true,
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

            item(key = "advanced_proxy") {
                SettingsSection(title = stringResource(R.string.ripdpi_proxy)) {
                    RipDpiCard {
                        AdvancedTextSetting(
                            title = stringResource(R.string.bye_dpi_proxy_ip_setting),
                            description = stringResource(R.string.config_proxy_helper),
                            value = uiState.proxyIp,
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
                            value = uiState.proxyPort.toString(),
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
                            value = uiState.maxConnections.toString(),
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
                            value = uiState.bufferSize.toString(),
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
                            checked = uiState.noDomain,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.NoDomain, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_tcp_fast_open_setting),
                            checked = uiState.tcpFastOpen,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.TcpFastOpen, it) },
                            enabled = visualEditorEnabled,
                        )
                    }
                }
            }

            item(key = "advanced_desync") {
                SettingsSection(title = stringResource(R.string.ripdpi_desync)) {
                    RipDpiCard {
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_default_ttl_setting),
                            description = stringResource(R.string.config_default_ttl_helper),
                            value = uiState.defaultTtlValue,
                            placeholder = stringResource(R.string.config_placeholder_default_ttl),
                            enabled = visualEditorEnabled,
                            validator = { it.isEmpty() || validateIntRange(it, 0, 255) },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            setting = AdvancedTextSetting.DefaultTtl,
                            onConfirm = onTextConfirmed,
                            showDivider = true,
                        )
                        AdvancedDropdownSetting(
                            title = stringResource(R.string.ripdpi_desync_method_setting),
                            description = stringResource(R.string.config_desync_helper),
                            value = uiState.desyncMethod,
                            enabled = visualEditorEnabled,
                            options = desyncOptions,
                            setting = AdvancedOptionSetting.DesyncMethod,
                            onSelected = onOptionSelected,
                            showDivider = true,
                        )
                        if (uiState.desyncEnabled) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_split_position_setting),
                                value = uiState.splitPosition.toString(),
                                enabled = visualEditorEnabled,
                                validator = { it.toIntOrNull() != null },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.SplitPosition,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                            SettingsRow(
                                title = stringResource(R.string.ripdpi_split_at_host_setting),
                                checked = uiState.splitAtHost,
                                onCheckedChange = { onToggleChanged(AdvancedToggleSetting.SplitAtHost, it) },
                                enabled = visualEditorEnabled,
                                showDivider = true,
                            )
                        }
                        if (uiState.isFake) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_fake_ttl_setting),
                                value = uiState.fakeTtl.toString(),
                                enabled = visualEditorEnabled,
                                validator = { validateIntRange(it, 1, 255) },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.FakeTtl,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                            AdvancedTextSetting(
                                title = stringResource(R.string.sni_of_fake_packet),
                                value = uiState.fakeSni,
                                enabled = visualEditorEnabled,
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                setting = AdvancedTextSetting.FakeSni,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_fake_offset_setting),
                                value = uiState.fakeOffset.toString(),
                                enabled = visualEditorEnabled,
                                validator = { it.toIntOrNull() != null },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.FakeOffset,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                        }
                        if (uiState.isOob) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.oob_data),
                                value = uiState.oobData,
                                enabled = visualEditorEnabled,
                                validator = { it.length <= 1 },
                                invalidMessage = stringResource(R.string.advanced_settings_error_oob_data),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.OobData,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                        }
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_drop_sack_setting),
                            checked = uiState.dropSack,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DropSack, it) },
                            enabled = visualEditorEnabled,
                        )
                    }
                }
            }

            item(key = "advanced_protocols") {
                SettingsSection(title = stringResource(R.string.ripdpi_protocols_category)) {
                    RipDpiCard {
                        Text(
                            text = stringResource(R.string.ripdpi_protocols_hint),
                            style = RipDpiThemeTokens.type.caption,
                            color = colors.mutedForeground,
                        )
                        HorizontalDivider(color = colors.divider)
                        SettingsRow(
                            title = stringResource(R.string.desync_http),
                            checked = uiState.desyncHttp,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DesyncHttp, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.desync_https),
                            checked = uiState.desyncHttps,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DesyncHttps, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.desync_udp),
                            checked = uiState.desyncUdp,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DesyncUdp, it) },
                            enabled = visualEditorEnabled,
                        )
                    }
                }
            }

            item(key = "advanced_http") {
                SettingsSection(title = stringResource(R.string.desync_http_category)) {
                    RipDpiCard {
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_host_mixed_case_setting),
                            checked = uiState.hostMixedCase,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HostMixedCase, it) },
                            enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_domain_mixed_case_setting),
                            checked = uiState.domainMixedCase,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DomainMixedCase, it) },
                            enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_host_remove_spaces_setting),
                            checked = uiState.hostRemoveSpaces,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HostRemoveSpaces, it) },
                            enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                        )
                    }
                }
            }

            item(key = "advanced_https") {
                SettingsSection(title = stringResource(R.string.desync_https_category)) {
                    RipDpiCard {
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_tlsrec_enabled_setting),
                            checked = uiState.tlsrecEnabled,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.TlsrecEnabled, it) },
                            enabled = visualEditorEnabled && uiState.desyncHttpsEnabled,
                            showDivider = uiState.tlsRecEnabled,
                        )
                        if (uiState.tlsRecEnabled) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_tlsrec_position_setting),
                                value = uiState.tlsrecPosition.toString(),
                                enabled = visualEditorEnabled,
                                validator = { validateIntRange(it, 2 * Short.MIN_VALUE, 2 * Short.MAX_VALUE) },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage =
                                    if (!visualEditorEnabled) {
                                        stringResource(R.string.advanced_settings_visual_controls_disabled)
                                    } else {
                                        null
                                    },
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.TlsrecPosition,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                            SettingsRow(
                                title = stringResource(R.string.ripdpi_tlsrec_at_sni_setting),
                                checked = uiState.tlsrecAtSni,
                                onCheckedChange = { onToggleChanged(AdvancedToggleSetting.TlsrecAtSni, it) },
                                enabled = visualEditorEnabled,
                            )
                        }
                    }
                }
            }

            item(key = "advanced_udp") {
                SettingsSection(title = stringResource(R.string.desync_udp_category)) {
                    RipDpiCard {
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_udp_fake_count),
                            value = uiState.udpFakeCount.toString(),
                            enabled = visualEditorEnabled && uiState.desyncUdpEnabled,
                            validator = { validateIntRange(it, 0, Int.MAX_VALUE) },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage =
                                if (!visualEditorEnabled) {
                                    stringResource(R.string.advanced_settings_visual_controls_disabled)
                                } else {
                                    null
                                },
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            setting = AdvancedTextSetting.UdpFakeCount,
                            onConfirm = onTextConfirmed,
                        )
                    }
                }
            }

            item(key = "advanced_hosts") {
                SettingsSection(title = stringResource(R.string.ripdpi_hosts_mode_setting)) {
                    RipDpiCard {
                        AdvancedDropdownSetting(
                            title = stringResource(R.string.ripdpi_hosts_mode_setting),
                            value = uiState.hostsMode,
                            enabled = visualEditorEnabled,
                            options = hostsOptions,
                            setting = AdvancedOptionSetting.HostsMode,
                            onSelected = onOptionSelected,
                            showDivider = uiState.hostsMode != "disable",
                        )
                        when (uiState.hostsMode) {
                            "blacklist" -> {
                                AdvancedTextSetting(
                                    title = stringResource(R.string.ripdpi_hosts_blacklist_setting),
                                    value = uiState.hostsBlacklist,
                                    enabled = visualEditorEnabled,
                                    multiline = true,
                                    disabledMessage =
                                        stringResource(
                                            R.string.advanced_settings_visual_controls_disabled,
                                        ),
                                    setting = AdvancedTextSetting.HostsBlacklist,
                                    onConfirm = onTextConfirmed,
                                )
                            }

                            "whitelist" -> {
                                AdvancedTextSetting(
                                    title = stringResource(R.string.ripdpi_hosts_whitelist_setting),
                                    value = uiState.hostsWhitelist,
                                    enabled = visualEditorEnabled,
                                    multiline = true,
                                    disabledMessage =
                                        stringResource(
                                            R.string.advanced_settings_visual_controls_disabled,
                                        ),
                                    setting = AdvancedTextSetting.HostsWhitelist,
                                    onConfirm = onTextConfirmed,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = title)
        content()
    }
}

@Composable
private fun rememberSettingsOptions(
    labelArrayRes: Int,
    valueArrayRes: Int,
): List<RipDpiDropdownOption<String>> {
    val labels = stringArrayResource(labelArrayRes)
    val values = stringArrayResource(valueArrayRes)

    return remember(labels, values) {
        labels.zip(values) { label, value ->
            RipDpiDropdownOption(value = value, label = label)
        }
    }
}

@Composable
private fun AdvancedDropdownSetting(
    title: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    setting: AdvancedOptionSetting,
    onSelected: (AdvancedOptionSetting, String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        description?.let {
            Text(
                text = it,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = { selectedValue -> onSelected(setting, selectedValue) },
            enabled = enabled,
        )
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
private fun AdvancedTextSetting(
    title: String,
    value: String,
    setting: AdvancedTextSetting,
    onConfirm: (AdvancedTextSetting, String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    multiline: Boolean = false,
    validator: (String) -> Boolean = { true },
    invalidMessage: String? = null,
    disabledMessage: String? = null,
    keyboardOptions: KeyboardOptions =
        KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
    showDivider: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var input by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedInput = input.trim()
    val isValid = remember(normalizedInput) { validator(normalizedInput) }
    val isDirty = normalizedInput != value
    val errorText = if (normalizedInput.isNotEmpty() && !isValid) invalidMessage else null
    val helperText =
        if (errorText == null && !enabled) {
            disabledMessage
        } else {
            null
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        description?.let {
            Text(
                text = it,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        RipDpiConfigTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = placeholder,
            enabled = enabled,
            helperText = helperText,
            errorText = errorText,
            multiline = multiline,
            keyboardOptions = keyboardOptions,
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (enabled && isDirty && isValid) {
                            onConfirm(setting, normalizedInput)
                        }
                    },
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = { onConfirm(setting, normalizedInput) },
                enabled = enabled && isDirty && isValid,
                variant = RipDpiButtonVariant.Outline,
                trailingIcon = RipDpiIcons.Check,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

private val SettingsUiState.defaultTtlValue: String
    get() = if (customTtl) defaultTtl.toString() else ""

@Preview(showBackground = true)
@Composable
private fun AdvancedSettingsScreenPreview() {
    RipDpiTheme {
        AdvancedSettingsScreen(
            uiState =
                SettingsUiState(
                    enableCmdSettings = false,
                    cmdArgs = "",
                    proxyIp = "127.0.0.1",
                    proxyPort = 1080,
                    maxConnections = 512,
                    bufferSize = 16_384,
                    noDomain = false,
                    tcpFastOpen = true,
                    customTtl = true,
                    defaultTtl = 8,
                    desyncMethod = "disorder",
                    splitPosition = 2,
                    splitAtHost = true,
                    dropSack = false,
                    desyncHttp = true,
                    desyncHttps = true,
                    desyncUdp = false,
                    hostMixedCase = true,
                    domainMixedCase = false,
                    hostRemoveSpaces = false,
                    tlsrecEnabled = false,
                    udpFakeCount = 0,
                    hostsMode = "disable",
                ),
            onBack = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdvancedSettingsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        AdvancedSettingsScreen(
            uiState =
                SettingsUiState(
                    enableCmdSettings = true,
                    cmdArgs = "--fake --split 2",
                    proxyIp = "127.0.0.1",
                    proxyPort = 1080,
                    maxConnections = 256,
                    bufferSize = 8192,
                    customTtl = true,
                    defaultTtl = 8,
                    desyncMethod = "fake",
                    splitPosition = 1,
                    splitAtHost = true,
                    fakeTtl = 12,
                    fakeSni = "www.iana.org",
                    fakeOffset = 2,
                    dropSack = true,
                    desyncHttp = true,
                    desyncHttps = true,
                    desyncUdp = true,
                    tlsrecEnabled = true,
                    tlsrecPosition = 4,
                    tlsrecAtSni = true,
                    udpFakeCount = 1,
                    hostsMode = "blacklist",
                    hostsBlacklist = "example.com\ncdn.example.net",
                    hostMixedCase = true,
                    domainMixedCase = true,
                    hostRemoveSpaces = false,
                ),
            onBack = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
        )
    }
}
