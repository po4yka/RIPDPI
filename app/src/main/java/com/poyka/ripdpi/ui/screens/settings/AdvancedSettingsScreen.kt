package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.QuicInitialModeDisabled
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import kotlinx.coroutines.flow.collect

private enum class AdvancedToggleSetting {
    UseCommandLine,
    NoDomain,
    TcpFastOpen,
    DropSack,
    DesyncHttp,
    DesyncHttps,
    DesyncUdp,
    HostMixedCase,
    DomainMixedCase,
    HostRemoveSpaces,
    TlsrecEnabled,
    QuicSupportV1,
    QuicSupportV2,
    HostAutolearnEnabled,
}

private enum class AdvancedTextSetting {
    CommandLineArgs,
    ProxyIp,
    ProxyPort,
    MaxConnections,
    BufferSize,
    DefaultTtl,
    ChainDsl,
    SplitMarker,
    FakeTtl,
    FakeSni,
    FakeOffsetMarker,
    OobData,
    TlsrecMarker,
    UdpFakeCount,
    HostAutolearnPenaltyTtlHours,
    HostAutolearnMaxHosts,
    HostsBlacklist,
    HostsWhitelist,
}

private enum class AdvancedOptionSetting {
    DesyncMethod,
    HostsMode,
    QuicInitialMode,
}

private data class AdvancedNotice(
    val title: String,
    val message: String,
    val tone: WarningBannerTone,
)

@Composable
fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<AdvancedNotice?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is SettingsEffect.Notice) {
                notice =
                    AdvancedNotice(
                        title = effect.title,
                        message = effect.message,
                        tone =
                            when (effect.tone) {
                                SettingsNoticeTone.Info -> WarningBannerTone.Info
                                SettingsNoticeTone.Warning -> WarningBannerTone.Warning
                                SettingsNoticeTone.Error -> WarningBannerTone.Error
                            },
                    )
            }
        }
    }

    AdvancedSettingsScreen(
        uiState = uiState,
        notice = notice,
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

                AdvancedToggleSetting.QuicSupportV1 -> {
                    viewModel.updateSetting(
                        key = "quicSupportV1",
                        value = enabled.toString(),
                    ) {
                        setQuicSupportV1(enabled)
                    }
                }

                AdvancedToggleSetting.QuicSupportV2 -> {
                    viewModel.updateSetting(
                        key = "quicSupportV2",
                        value = enabled.toString(),
                    ) {
                        setQuicSupportV2(enabled)
                    }
                }

                AdvancedToggleSetting.HostAutolearnEnabled -> {
                    viewModel.updateSetting(
                        key = "hostAutolearnEnabled",
                        value = enabled.toString(),
                    ) {
                        setHostAutolearnEnabled(enabled)
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

                AdvancedTextSetting.ChainDsl -> {
                    val parsed = parseStrategyChainDsl(value).getOrNull() ?: return@AdvancedSettingsScreen
                    viewModel.updateSetting(
                        key = "chainDsl",
                        value = value,
                    ) {
                        setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
                    }
                }

                AdvancedTextSetting.SplitMarker -> {
                    val marker = normalizeOffsetExpression(value, DefaultSplitMarker)
                    viewModel.updateSetting(
                        key = "splitMarker",
                        value = marker,
                    ) {
                        setSplitMarker(marker)
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

                AdvancedTextSetting.FakeOffsetMarker -> {
                    val marker = normalizeOffsetExpression(value, DefaultFakeOffsetMarker)
                    viewModel.updateSetting(
                        key = "fakeOffsetMarker",
                        value = marker,
                    ) {
                        setFakeOffsetMarker(marker)
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

                AdvancedTextSetting.TlsrecMarker -> {
                    val marker = normalizeOffsetExpression(value, DefaultTlsRecordMarker)
                    viewModel.updateSetting(
                        key = "tlsrecMarker",
                        value = marker,
                    ) {
                        setTlsrecMarker(marker)
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

                AdvancedTextSetting.HostAutolearnPenaltyTtlHours -> {
                    value.toIntOrNull()?.let { ttl ->
                        val normalized = normalizeHostAutolearnPenaltyTtlHours(ttl)
                        viewModel.updateSetting(
                            key = "hostAutolearnPenaltyTtlHours",
                            value = normalized.toString(),
                        ) {
                            setHostAutolearnPenaltyTtlHours(normalized)
                        }
                    }
                }

                AdvancedTextSetting.HostAutolearnMaxHosts -> {
                    value.toIntOrNull()?.let { maxHosts ->
                        val normalized = normalizeHostAutolearnMaxHosts(maxHosts)
                        viewModel.updateSetting(
                            key = "hostAutolearnMaxHosts",
                            value = normalized.toString(),
                        ) {
                            setHostAutolearnMaxHosts(normalized)
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

                AdvancedOptionSetting.QuicInitialMode -> {
                    viewModel.updateSetting(
                        key = "quicInitialMode",
                        value = value,
                    ) {
                        setQuicInitialMode(value)
                    }
                }
            }
        },
        onForgetLearnedHosts = viewModel::forgetLearnedHosts,
        modifier = modifier,
    )
}

@Composable
private fun AdvancedSettingsScreen(
    uiState: SettingsUiState,
    notice: AdvancedNotice?,
    onBack: () -> Unit,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onForgetLearnedHosts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
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
    val quicModeOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.quic_initial_modes,
            valueArrayRes = R.array.quic_initial_modes_entries,
        )

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_advanced_settings),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
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
        notice?.let {
            item(key = "advanced_settings_notice") {
                WarningBanner(
                    title = it.title,
                    message = it.message,
                    tone = it.tone,
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
                    Text(
                        text = stringResource(R.string.config_chain_summary_label, uiState.chainSummary),
                        style = RipDpiThemeTokens.type.caption,
                        color = colors.mutedForeground,
                    )
                    HorizontalDivider(color = colors.divider)
                    AdvancedTextSetting(
                        title = stringResource(R.string.config_chain_editor_label),
                        description = stringResource(R.string.config_chain_editor_helper),
                        value = uiState.chainDsl,
                        placeholder = stringResource(R.string.config_placeholder_chain_dsl),
                        enabled = visualEditorEnabled,
                        multiline = true,
                        validator = { parseStrategyChainDsl(it).isSuccess },
                        invalidMessage = stringResource(R.string.config_error_invalid_chain),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        setting = AdvancedTextSetting.ChainDsl,
                        onConfirm = onTextConfirmed,
                        showDivider = uiState.isFake || uiState.isOob,
                    )
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
                            description = stringResource(R.string.config_fake_offset_marker_helper),
                            value = uiState.fakeOffsetMarker,
                            placeholder = stringResource(R.string.config_placeholder_fake_offset_marker),
                            enabled = visualEditorEnabled,
                            validator = { it.isBlank() || isValidOffsetExpression(it) },
                            invalidMessage = stringResource(R.string.config_error_invalid_marker),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            setting = AdvancedTextSetting.FakeOffsetMarker,
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

        item(key = "advanced_host_autolearn") {
            SettingsSection(title = stringResource(R.string.host_autolearn_section_title)) {
                RipDpiCard {
                    SettingsRow(
                        title = stringResource(R.string.host_autolearn_enabled_title),
                        subtitle = stringResource(R.string.host_autolearn_enabled_body),
                        checked = uiState.hostAutolearnEnabled,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HostAutolearnEnabled, it) },
                        enabled = visualEditorEnabled,
                        showDivider = uiState.hostAutolearnEnabled,
                    )
                    HostAutolearnStatusCard(
                        uiState = uiState,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    if (uiState.hostAutolearnEnabled) {
                        HorizontalDivider(color = colors.divider)
                    }
                    if (uiState.hostAutolearnEnabled) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.host_autolearn_penalty_ttl_title),
                            description = stringResource(R.string.host_autolearn_penalty_ttl_body),
                            value = uiState.hostAutolearnPenaltyTtlHours.toString(),
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
                            value = uiState.hostAutolearnMaxHosts.toString(),
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
                    Text(
                        text = stringResource(R.string.config_https_chain_hint),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                }
            }
        }

        item(key = "advanced_udp") {
            SettingsSection(title = stringResource(R.string.desync_udp_category)) {
                RipDpiCard {
                    Text(
                        text = stringResource(R.string.config_udp_chain_hint),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                }
            }
        }

        item(key = "advanced_quic") {
            SettingsSection(title = stringResource(R.string.quic_initial_section_title)) {
                RipDpiCard {
                    Text(
                        text = stringResource(R.string.quic_initial_section_body),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    HorizontalDivider(color = colors.divider)
                    AdvancedDropdownSetting(
                        title = stringResource(R.string.quic_initial_mode_title),
                        description = stringResource(R.string.quic_initial_mode_body),
                        value = uiState.quicInitialMode,
                        enabled = visualEditorEnabled,
                        options = quicModeOptions,
                        setting = AdvancedOptionSetting.QuicInitialMode,
                        onSelected = onOptionSelected,
                        showDivider = uiState.quicInitialMode != QuicInitialModeDisabled,
                    )
                    if (uiState.quicInitialMode != QuicInitialModeDisabled) {
                        SettingsRow(
                            title = stringResource(R.string.quic_initial_support_v1_title),
                            subtitle = stringResource(R.string.quic_initial_support_v1_body),
                            checked = uiState.quicSupportV1,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicSupportV1, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.quic_initial_support_v2_title),
                            subtitle = stringResource(R.string.quic_initial_support_v2_body),
                            checked = uiState.quicSupportV2,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicSupportV2, it) },
                            enabled = visualEditorEnabled,
                        )
                    }
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
        if (uiState.isServiceRunning && (uiState.hostAutolearnRuntimeEnabled || uiState.hostAutolearnLearnedHostCount > 0)) {
            stringResource(
                R.string.host_autolearn_runtime_summary,
                uiState.hostAutolearnLearnedHostCount,
                uiState.hostAutolearnPenalizedHostCount,
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
                uiState.hostAutolearnPenaltyTtlHours,
                uiState.hostAutolearnMaxHosts,
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
        } else if (uiState.hostAutolearnStorePresent && !uiState.enableCmdSettings) {
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
        uiState.enableCmdSettings && uiState.isServiceRunning && uiState.hostAutolearnRuntimeEnabled ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_live_status_title),
                body = stringResource(R.string.host_autolearn_cli_live_status_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.enableCmdSettings ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_cli_status_title),
                body = stringResource(R.string.host_autolearn_cli_status_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.isServiceRunning && uiState.hostAutolearnEnabled && uiState.hostAutolearnRuntimeEnabled ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_live_status_title),
                body = stringResource(R.string.host_autolearn_live_status_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.isServiceRunning && uiState.hostAutolearnEnabled ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_pending_enable_title),
                body = stringResource(R.string.host_autolearn_pending_enable_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.isServiceRunning && uiState.hostAutolearnRuntimeEnabled ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_pending_disable_title),
                body = stringResource(R.string.host_autolearn_pending_disable_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hostAutolearnEnabled ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_ready_title),
                body = stringResource(R.string.host_autolearn_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.hostAutolearnStorePresent ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_store_title),
                body = stringResource(R.string.host_autolearn_store_body),
                tone = StatusIndicatorTone.Idle,
            )

        else ->
            HostAutolearnStatusContent(
                label = stringResource(R.string.host_autolearn_off_title),
                body = stringResource(R.string.host_autolearn_off_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun hostAutolearnResetHint(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.host_autolearn_reset_hint_cli)
        !uiState.hostAutolearnStorePresent && (uiState.hostAutolearnEnabled || uiState.hostAutolearnRuntimeEnabled) ->
            stringResource(R.string.host_autolearn_reset_hint_waiting)

        !uiState.hostAutolearnStorePresent -> stringResource(R.string.host_autolearn_reset_hint_empty)
        uiState.isServiceRunning -> stringResource(R.string.host_autolearn_reset_hint_running)
        else -> stringResource(R.string.host_autolearn_reset_hint_ready)
    }

@Composable
private fun hostAutolearnLastUpdate(uiState: SettingsUiState): String? {
    val action =
        when (uiState.hostAutolearnLastAction) {
            "host_promoted" -> stringResource(R.string.host_autolearn_action_host_promoted)
            "group_penalized" -> stringResource(R.string.host_autolearn_action_group_penalized)
            "store_reset" -> stringResource(R.string.host_autolearn_action_store_reset)
            else -> null
        } ?: return null

    val host = uiState.hostAutolearnLastHost?.takeIf { it.isNotBlank() }
    val group =
        uiState.hostAutolearnLastGroup?.let {
            stringResource(R.string.host_autolearn_route_group, it)
        }
    return listOfNotNull(action, host, group).joinToString(" · ")
}

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
                    splitMarker = "host+2",
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
                    hostAutolearnEnabled = true,
                    hostAutolearnRuntimeEnabled = true,
                    hostAutolearnStorePresent = true,
                    hostAutolearnLearnedHostCount = 18,
                    hostAutolearnPenalizedHostCount = 2,
                    hostAutolearnLastHost = "video.example.org",
                    hostAutolearnLastGroup = 2,
                    hostAutolearnLastAction = "host_promoted",
                    serviceStatus = com.poyka.ripdpi.data.AppStatus.Running,
                ),
            notice = null,
            onBack = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
            onForgetLearnedHosts = {},
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
                    splitMarker = "host+1",
                    fakeTtl = 12,
                    fakeSni = "www.iana.org",
                    fakeOffsetMarker = "method+2",
                    dropSack = true,
                    desyncHttp = true,
                    desyncHttps = true,
                    desyncUdp = true,
                    tlsrecEnabled = true,
                    tlsrecMarker = "sniext+4",
                    udpFakeCount = 1,
                    hostsMode = "blacklist",
                    hostsBlacklist = "example.com\ncdn.example.net",
                    hostAutolearnStorePresent = true,
                    hostMixedCase = true,
                    domainMixedCase = true,
                    hostRemoveSpaces = false,
                ),
            notice = null,
            onBack = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
            onForgetLearnedHosts = {},
        )
    }
}
