package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Alignment
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
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeDisabled
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeQuicFakeHost
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
    DiagnosticsMonitorEnabled,
    DiagnosticsExportIncludeHistory,
    NoDomain,
    TcpFastOpen,
    DropSack,
    FakeTlsRandomize,
    FakeTlsDupSessionId,
    FakeTlsPadEncap,
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
    DiagnosticsSampleIntervalSeconds,
    DiagnosticsHistoryRetentionDays,
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
    FakeTlsSize,
    QuicFakeHost,
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
    FakeTlsBase,
    FakeTlsSniMode,
    HostsMode,
    QuicInitialMode,
    QuicFakeProfile,
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

                AdvancedToggleSetting.DiagnosticsMonitorEnabled -> {
                    viewModel.updateSetting(
                        key = "diagnosticsMonitorEnabled",
                        value = enabled.toString(),
                    ) {
                        setDiagnosticsMonitorEnabled(enabled)
                    }
                }

                AdvancedToggleSetting.DiagnosticsExportIncludeHistory -> {
                    viewModel.updateSetting(
                        key = "diagnosticsExportIncludeHistory",
                        value = enabled.toString(),
                    ) {
                        setDiagnosticsExportIncludeHistory(enabled)
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

                AdvancedToggleSetting.FakeTlsRandomize -> {
                    viewModel.updateSetting(
                        key = "fakeTlsRandomize",
                        value = enabled.toString(),
                    ) {
                        setFakeTlsRandomize(enabled)
                    }
                }

                AdvancedToggleSetting.FakeTlsDupSessionId -> {
                    viewModel.updateSetting(
                        key = "fakeTlsDupSessionId",
                        value = enabled.toString(),
                    ) {
                        setFakeTlsDupSessionId(enabled)
                    }
                }

                AdvancedToggleSetting.FakeTlsPadEncap -> {
                    viewModel.updateSetting(
                        key = "fakeTlsPadEncap",
                        value = enabled.toString(),
                    ) {
                        setFakeTlsPadEncap(enabled)
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
                AdvancedTextSetting.DiagnosticsSampleIntervalSeconds -> {
                    value.toIntOrNull()?.let { intervalSeconds ->
                        viewModel.updateSetting(
                            key = "diagnosticsSampleIntervalSeconds",
                            value = intervalSeconds.toString(),
                        ) {
                            setDiagnosticsSampleIntervalSeconds(intervalSeconds)
                        }
                    }
                }

                AdvancedTextSetting.DiagnosticsHistoryRetentionDays -> {
                    value.toIntOrNull()?.let { retentionDays ->
                        viewModel.updateSetting(
                            key = "diagnosticsHistoryRetentionDays",
                            value = retentionDays.toString(),
                        ) {
                            setDiagnosticsHistoryRetentionDays(retentionDays)
                        }
                    }
                }

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

                AdvancedTextSetting.FakeTlsSize -> {
                    value.toIntOrNull()?.let { fakeTlsSize ->
                        viewModel.updateSetting(
                            key = "fakeTlsSize",
                            value = fakeTlsSize.toString(),
                        ) {
                            setFakeTlsSize(fakeTlsSize)
                        }
                    }
                }

                AdvancedTextSetting.QuicFakeHost -> {
                    val normalized = normalizeQuicFakeHost(value)
                    viewModel.updateSetting(
                        key = "quicFakeHost",
                        value = normalized,
                    ) {
                        setQuicFakeHost(normalized)
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

                AdvancedOptionSetting.FakeTlsBase -> {
                    val useOriginal = value == "original"
                    viewModel.updateSetting(
                        key = "fakeTlsUseOriginal",
                        value = useOriginal.toString(),
                    ) {
                        setFakeTlsUseOriginal(useOriginal)
                    }
                }

                AdvancedOptionSetting.FakeTlsSniMode -> {
                    viewModel.updateSetting(
                        key = "fakeTlsSniMode",
                        value = value,
                    ) {
                        setFakeTlsSniMode(value)
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

                AdvancedOptionSetting.QuicFakeProfile -> {
                    viewModel.updateSetting(
                        key = "quicFakeProfile",
                        value = value,
                    ) {
                        setQuicFakeProfile(value)
                    }
                }
            }
        },
        onForgetLearnedHosts = viewModel::forgetLearnedHosts,
        onResetFakeTlsProfile = viewModel::resetFakeTlsProfile,
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
    onResetFakeTlsProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val visualEditorEnabled = !uiState.enableCmdSettings
    val showHostFakeSection = uiState.showHostFakeProfile
    val showQuicFakeSection = uiState.showQuicFakeProfile
    val showFakeTlsSection =
        uiState.desyncHttpsEnabled ||
            uiState.isFake ||
            uiState.hasCustomFakeTlsProfile ||
            uiState.enableCmdSettings
    val desyncOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.ripdpi_desync_methods,
            valueArrayRes = R.array.ripdpi_desync_methods_entries,
        )
    val fakeTlsBaseOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.fake_tls_base_modes,
            valueArrayRes = R.array.fake_tls_base_modes_entries,
        )
    val fakeTlsSniModeOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.fake_tls_sni_modes,
            valueArrayRes = R.array.fake_tls_sni_modes_entries,
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

        item(key = "advanced_diagnostics_history") {
            SettingsSection(title = stringResource(R.string.diagnostics_history_section)) {
                RipDpiCard {
                    SettingsRow(
                        title = stringResource(R.string.settings_diagnostics_monitor_title),
                        subtitle = stringResource(R.string.settings_diagnostics_monitor_body),
                        checked = uiState.diagnosticsMonitorEnabled,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DiagnosticsMonitorEnabled, it) },
                        showDivider = true,
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
                    )
                }
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
                        showDivider = showHostFakeSection || uiState.usesFakeTransport || uiState.isOob,
                    )
                    if (showHostFakeSection) {
                        HostFakeProfileCard(
                            uiState = uiState,
                            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                        )
                        if (uiState.usesFakeTransport || showFakeTlsSection || uiState.isOob) {
                            HorizontalDivider(color = colors.divider)
                        }
                    }
                    if (uiState.usesFakeTransport) {
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
                            showDivider = uiState.isFake || showFakeTlsSection || uiState.isOob,
                        )
                        if (uiState.isFake) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_fake_offset_setting),
                                description = stringResource(R.string.config_fake_offset_marker_helper),
                                value = uiState.fakeOffsetMarker,
                                placeholder = stringResource(R.string.config_placeholder_fake_offset_marker),
                                enabled = visualEditorEnabled,
                                validator = { it.isBlank() || isValidOffsetExpression(it) },
                                invalidMessage = stringResource(R.string.config_error_invalid_marker),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                setting = AdvancedTextSetting.FakeOffsetMarker,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                        }
                    }
                    if (showFakeTlsSection) {
                        Text(
                            text = stringResource(R.string.ripdpi_fake_tls_section_title),
                            style = RipDpiThemeTokens.type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text =
                                if (uiState.fakeTlsControlsRelevant) {
                                    stringResource(R.string.ripdpi_fake_tls_section_body)
                                } else {
                                    stringResource(R.string.ripdpi_fake_tls_inactive)
                                },
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                        FakeTlsProfileCard(
                            uiState = uiState,
                            onResetFakeTlsProfile = onResetFakeTlsProfile,
                            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                        )
                        HorizontalDivider(color = colors.divider)
                        AdvancedDropdownSetting(
                            title = stringResource(R.string.ripdpi_fake_tls_base_title),
                            description = stringResource(R.string.ripdpi_fake_tls_base_body),
                            value = if (uiState.fakeTlsUseOriginal) "original" else "default",
                            options = fakeTlsBaseOptions,
                            setting = AdvancedOptionSetting.FakeTlsBase,
                            onSelected = onOptionSelected,
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        AdvancedDropdownSetting(
                            title = stringResource(R.string.ripdpi_fake_tls_sni_mode_title),
                            description = stringResource(R.string.ripdpi_fake_tls_sni_mode_body),
                            value = uiState.fakeTlsSniMode,
                            options = fakeTlsSniModeOptions,
                            setting = AdvancedOptionSetting.FakeTlsSniMode,
                            onSelected = onOptionSelected,
                            enabled = visualEditorEnabled,
                            showDivider = uiState.fakeTlsSniMode == FakeTlsSniModeFixed,
                        )
                        if (uiState.fakeTlsSniMode == FakeTlsSniModeFixed) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.sni_of_fake_packet),
                                value = uiState.fakeSni,
                                enabled = visualEditorEnabled,
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                setting = AdvancedTextSetting.FakeSni,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                        }
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_fake_tls_randomize_title),
                            subtitle = stringResource(R.string.ripdpi_fake_tls_randomize_body),
                            checked = uiState.fakeTlsRandomize,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsRandomize, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_fake_tls_dup_sid_title),
                            subtitle = stringResource(R.string.ripdpi_fake_tls_dup_sid_body),
                            checked = uiState.fakeTlsDupSessionId,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsDupSessionId, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        SettingsRow(
                            title = stringResource(R.string.ripdpi_fake_tls_pad_encap_title),
                            subtitle = stringResource(R.string.ripdpi_fake_tls_pad_encap_body),
                            checked = uiState.fakeTlsPadEncap,
                            onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsPadEncap, it) },
                            enabled = visualEditorEnabled,
                            showDivider = true,
                        )
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_fake_tls_size_title),
                            description = stringResource(R.string.config_fake_tls_size_helper),
                            value = uiState.fakeTlsSize.toString(),
                            placeholder = stringResource(R.string.config_placeholder_fake_tls_size),
                            enabled = visualEditorEnabled,
                            validator = { it.isEmpty() || it.toIntOrNull() != null },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            setting = AdvancedTextSetting.FakeTlsSize,
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
                    if (showQuicFakeSection) {
                        HorizontalDivider(color = colors.divider)
                        QuicFakeProfileCard(uiState = uiState)
                        QuicFakePresetSelector(
                            uiState = uiState,
                            enabled = visualEditorEnabled,
                            onProfileSelected = {
                                onOptionSelected(AdvancedOptionSetting.QuicFakeProfile, it)
                            },
                        )
                        if (uiState.showQuicFakeHostOverride) {
                            QuicFakeHostOverrideCard(
                                uiState = uiState,
                                enabled = visualEditorEnabled,
                                onConfirm = onTextConfirmed,
                            )
                        }
                        Text(
                            text = stringResource(R.string.quic_fake_profile_helper),
                            style = RipDpiThemeTokens.type.caption,
                            color = colors.mutedForeground,
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

private data class FakeTlsStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
private fun FakeTlsProfileCard(
    uiState: SettingsUiState,
    onResetFakeTlsProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakeTlsStatus(uiState)
    val baseSummary =
        stringResource(
            if (uiState.fakeTlsUseOriginal) {
                R.string.ripdpi_fake_tls_summary_base_original
            } else {
                R.string.ripdpi_fake_tls_summary_base_default
            },
        )
    val sniSummary =
        if (uiState.fakeTlsSniMode == FakeTlsSniModeFixed) {
            stringResource(
                R.string.ripdpi_fake_tls_summary_sni_fixed,
                uiState.fakeSni.ifBlank { DefaultFakeSni },
            )
        } else {
            stringResource(R.string.ripdpi_fake_tls_summary_sni_randomized)
        }
    val mutationSummary =
        buildList {
            if (uiState.fakeTlsRandomize) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_randomize))
            if (uiState.fakeTlsDupSessionId) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_dup_sid))
            if (uiState.fakeTlsPadEncap) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_pad_encap))
        }.ifEmpty {
            listOf(stringResource(R.string.ripdpi_fake_tls_summary_mutation_none))
        }.joinToString(", ")
    val sizeSummary =
        when {
            uiState.fakeTlsSize > 0 -> stringResource(R.string.ripdpi_fake_tls_summary_size_exact, uiState.fakeTlsSize)
            uiState.fakeTlsSize < 0 -> stringResource(R.string.ripdpi_fake_tls_summary_size_minus, -uiState.fakeTlsSize)
            else -> stringResource(R.string.ripdpi_fake_tls_summary_size_input)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.ripdpi_fake_tls_scope_cli)
            !uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_fake_tls_scope_https_disabled)
            !uiState.isFake -> stringResource(R.string.ripdpi_fake_tls_scope_needs_fake)
            uiState.isServiceRunning -> stringResource(R.string.ripdpi_fake_tls_scope_restart)
            else -> stringResource(R.string.ripdpi_fake_tls_scope_applies)
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
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_base),
                value = baseSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_sni),
                value = sniSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_mutations),
                value = mutationSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_size),
                value = sizeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_scope),
                value = scopeSummary,
            )
        }
        if (uiState.canResetFakeTlsProfile) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.ripdpi_fake_tls_reset_action),
                    onClick = onResetFakeTlsProfile,
                    variant = RipDpiButtonVariant.Outline,
                    trailingIcon = RipDpiIcons.Close,
                )
            }
            Text(
                text = stringResource(R.string.ripdpi_fake_tls_reset_hint),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun ProfileSummaryLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = label,
            style = type.caption,
            color = colors.mutedForeground,
        )
        Text(
            text = value,
            style = type.secondaryBody,
            color = colors.foreground,
        )
    }
}

private data class HostFakeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class QuicFakeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class QuicFakePresetUiModel(
    val value: String,
    val title: String,
    val body: String,
    val isRecommended: Boolean = false,
)

private enum class SummaryCapsuleTone {
    Neutral,
    Active,
    Info,
    Warning,
}

@Composable
private fun HostFakeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHostFakeStatus(uiState)
    val primaryStep = uiState.primaryHostFakeStep
    val profileSummary =
        when (uiState.hostFakeStepCount) {
            0 -> stringResource(R.string.ripdpi_hostfake_summary_profile_none)
            1 -> stringResource(R.string.ripdpi_hostfake_summary_profile_single)
            else -> stringResource(R.string.ripdpi_hostfake_summary_profile_multiple, uiState.hostFakeStepCount)
        }
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled ->
                stringResource(R.string.ripdpi_hostfake_summary_scope_http_https)
            uiState.desyncHttpEnabled -> stringResource(R.string.ripdpi_hostfake_summary_scope_http)
            uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_hostfake_summary_scope_https)
            else -> stringResource(R.string.ripdpi_hostfake_summary_scope_none)
        }
    val templateSummary =
        primaryStep
            ?.fakeHostTemplate
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_hostfake_summary_template_random)
    val midhostSummary =
        primaryStep
            ?.midhostMarker
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.ripdpi_hostfake_summary_midhost_marker, it) }
            ?: stringResource(R.string.ripdpi_hostfake_summary_midhost_whole)
    val endMarkerSummary =
        primaryStep
            ?.marker
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_hostfake_summary_end_marker_none)

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
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_template),
                value = templateSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_midhost),
                value = midhostSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_end_marker),
                value = endMarkerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_transport),
                value = stringResource(R.string.ripdpi_hostfake_summary_transport, uiState.fakeTtl),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_http_example),
                value = stringResource(R.string.ripdpi_hostfake_example_http),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_tls_example),
                value = stringResource(R.string.ripdpi_hostfake_example_tls),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_hostfake_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun rememberHostFakeStatus(uiState: SettingsUiState): HostFakeStatusContent =
    when {
        uiState.enableCmdSettings ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_cli_title),
                body = stringResource(R.string.ripdpi_hostfake_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.hostFakeControlsRelevant ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_protocols_off_title),
                body = stringResource(R.string.ripdpi_hostfake_protocols_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasHostFake && uiState.isServiceRunning ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_restart_title),
                body = stringResource(R.string.ripdpi_hostfake_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasHostFake ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_ready_title),
                body = stringResource(R.string.ripdpi_hostfake_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_available_title),
                body = stringResource(R.string.ripdpi_hostfake_available_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun QuicFakeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberQuicFakeStatus(uiState)
    val presetSummary =
        stringResource(
            when (uiState.quicFakeProfile) {
                QuicFakeProfileCompatDefault -> R.string.quic_fake_profile_summary_compat
                QuicFakeProfileRealisticInitial -> R.string.quic_fake_profile_summary_realistic
                else -> R.string.quic_fake_profile_summary_off
            },
        )
    val hostSummary =
        when (uiState.quicFakeProfile) {
            QuicFakeProfileRealisticInitial ->
                uiState.quicFakeHost.ifBlank { DefaultQuicFakeHost }
            else -> stringResource(R.string.quic_fake_profile_host_unused)
        }
    val scopeSummary =
        when {
            !uiState.desyncUdpEnabled -> stringResource(R.string.quic_fake_profile_scope_udp_disabled)
            !uiState.hasUdpFakeBurst -> stringResource(R.string.quic_fake_profile_scope_needs_burst)
            else -> stringResource(R.string.quic_fake_profile_scope_active)
        }
    val burstSummary =
        if (uiState.hasUdpFakeBurst) {
            stringResource(R.string.quic_fake_profile_burst_configured, uiState.udpFakeCount)
        } else {
            stringResource(R.string.quic_fake_profile_burst_missing)
        }
    val badges =
        buildList {
            add(
                stringResource(R.string.quic_fake_profile_badge_initial_only) to SummaryCapsuleTone.Info,
            )
            add(
                if (uiState.hasUdpFakeBurst) {
                    stringResource(
                        R.string.quic_fake_profile_badge_burst_ready,
                        uiState.udpFakeCount,
                    ) to SummaryCapsuleTone.Active
                } else {
                    stringResource(R.string.quic_fake_profile_badge_burst_missing) to SummaryCapsuleTone.Warning
                },
            )
            when (uiState.quicFakeProfile) {
                QuicFakeProfileCompatDefault ->
                    add(
                        stringResource(R.string.quic_fake_profile_badge_compat_blob) to SummaryCapsuleTone.Neutral,
                    )

                QuicFakeProfileRealisticInitial ->
                    add(
                        if (uiState.quicFakeUsesCustomHost) {
                            stringResource(R.string.quic_fake_profile_badge_host_custom)
                        } else {
                            stringResource(R.string.quic_fake_profile_badge_host_builtin)
                        } to SummaryCapsuleTone.Active,
                    )

                else -> Unit
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
                label = stringResource(R.string.quic_fake_profile_summary_label_preset),
                value = presetSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.quic_fake_profile_summary_label_host),
                value = hostSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.quic_fake_profile_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.quic_fake_profile_summary_label_burst),
                value = burstSummary,
            )
        }
    }
}

@Composable
private fun QuicFakePresetSelector(
    uiState: SettingsUiState,
    enabled: Boolean,
    onProfileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val presets =
        listOf(
            QuicFakePresetUiModel(
                value = QuicFakeProfileDisabled,
                title = stringResource(R.string.quic_fake_preset_off_title),
                body = stringResource(R.string.quic_fake_preset_off_body),
            ),
            QuicFakePresetUiModel(
                value = QuicFakeProfileCompatDefault,
                title = stringResource(R.string.quic_fake_preset_compat_title),
                body = stringResource(R.string.quic_fake_preset_compat_body),
            ),
            QuicFakePresetUiModel(
                value = QuicFakeProfileRealisticInitial,
                title = stringResource(R.string.quic_fake_preset_realistic_title),
                body = stringResource(R.string.quic_fake_preset_realistic_body),
                isRecommended = true,
            ),
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.quic_fake_profile_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.quic_fake_profile_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            QuicFakePresetCard(
                preset = preset,
                selected = uiState.quicFakeProfile == preset.value,
                enabled = enabled,
                onClick = { onProfileSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun QuicFakePresetCard(
    preset: QuicFakePresetUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val badgeLabel =
        when {
            selected -> stringResource(R.string.quic_fake_preset_selected)
            preset.isRecommended -> stringResource(R.string.quic_fake_preset_recommended)
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
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = badgeTone,
                )
            }
        }
    }
}

@Composable
private fun QuicFakeHostOverrideCard(
    uiState: SettingsUiState,
    enabled: Boolean,
    onConfirm: (AdvancedTextSetting, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val statusLabel =
        if (uiState.quicFakeUsesCustomHost) {
            stringResource(R.string.quic_fake_host_custom_title)
        } else {
            stringResource(R.string.quic_fake_host_builtin_title)
        }
    val statusBody =
        if (uiState.quicFakeUsesCustomHost) {
            stringResource(R.string.quic_fake_host_custom_body)
        } else {
            stringResource(R.string.quic_fake_host_builtin_body)
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = if (uiState.quicFakeUsesCustomHost) StatusIndicatorTone.Active else StatusIndicatorTone.Idle,
        )
        Text(
            text = statusBody,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        AdvancedTextSetting(
            title = stringResource(R.string.quic_fake_host_title),
            description = stringResource(R.string.quic_fake_host_body),
            value = uiState.quicFakeHost,
            enabled = enabled,
            validator = { input ->
                input.isBlank() || normalizeQuicFakeHost(input).isNotEmpty()
            },
            invalidMessage = stringResource(R.string.quic_fake_host_error),
            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
            placeholder = stringResource(R.string.config_placeholder_quic_fake_host),
            setting = AdvancedTextSetting.QuicFakeHost,
            onConfirm = onConfirm,
        )
        ProfileSummaryLine(
            label = stringResource(R.string.quic_fake_host_effective_label),
            value = uiState.quicFakeEffectiveHost,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCapsuleFlow(
    items: List<Pair<String, SummaryCapsuleTone>>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items.forEach { (text, tone) ->
            SummaryCapsule(
                text = text,
                tone = tone,
            )
        }
    }
}

@Composable
private fun SummaryCapsule(
    text: String,
    tone: SummaryCapsuleTone,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val (container, content, border) =
        when (tone) {
            SummaryCapsuleTone.Neutral ->
                Triple(colors.muted, colors.foreground, colors.border)
            SummaryCapsuleTone.Active ->
                Triple(colors.infoContainer, colors.infoContainerForeground, colors.info)
            SummaryCapsuleTone.Info ->
                Triple(colors.card, colors.mutedForeground, colors.border)
            SummaryCapsuleTone.Warning ->
                Triple(colors.warningContainer, colors.warningContainerForeground, colors.warning)
        }

    Text(
        text = text,
        modifier =
            modifier
                .background(container, RipDpiThemeTokens.shapes.lg)
                .border(1.dp, border, RipDpiThemeTokens.shapes.lg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        style = type.caption,
        color = content,
    )
}

@Composable
private fun rememberQuicFakeStatus(uiState: SettingsUiState): QuicFakeStatusContent =
    when {
        uiState.enableCmdSettings ->
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_cli_title),
                body = stringResource(R.string.quic_fake_profile_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.quicFakeControlsRelevant ->
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_udp_disabled_title),
                body = stringResource(R.string.quic_fake_profile_udp_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.quicFakeProfileActive ->
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_off_title),
                body = stringResource(R.string.quic_fake_profile_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.hasUdpFakeBurst ->
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_saved_title),
                body = stringResource(R.string.quic_fake_profile_saved_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.isServiceRunning ->
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_restart_title),
                body = stringResource(R.string.quic_fake_profile_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        else ->
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_ready_title),
                body = stringResource(R.string.quic_fake_profile_ready_body),
                tone = StatusIndicatorTone.Active,
            )
    }

@Composable
private fun rememberFakeTlsStatus(uiState: SettingsUiState): FakeTlsStatusContent =
    when {
        uiState.enableCmdSettings ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_cli_status_title),
                body = stringResource(R.string.ripdpi_fake_tls_cli_status_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.desyncHttpsEnabled ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_https_disabled_title),
                body = stringResource(R.string.ripdpi_fake_tls_https_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.isFake && uiState.hasCustomFakeTlsProfile ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_saved_title),
                body = stringResource(R.string.ripdpi_fake_tls_saved_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.isFake ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_waiting_title),
                body = stringResource(R.string.ripdpi_fake_tls_waiting_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasCustomFakeTlsProfile ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_custom_title),
                body = stringResource(R.string.ripdpi_fake_tls_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_default_title),
                body = stringResource(R.string.ripdpi_fake_tls_default_body),
                tone = StatusIndicatorTone.Active,
            )
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
            onResetFakeTlsProfile = {},
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
                    fakeSni = "alt.example.org",
                    fakeOffsetMarker = "method+2",
                    fakeTlsUseOriginal = true,
                    fakeTlsRandomize = true,
                    fakeTlsDupSessionId = true,
                    fakeTlsPadEncap = true,
                    fakeTlsSize = -24,
                    fakeTlsSniMode = "randomized",
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
            onResetFakeTlsProfile = {},
        )
    }
}
