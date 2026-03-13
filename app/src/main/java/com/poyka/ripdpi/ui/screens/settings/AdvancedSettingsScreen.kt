package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeCustom
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.AdaptiveSplitPresetCustom
import com.poyka.ripdpi.activities.AdaptiveSplitPresetManual
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerSniExt
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.HostPackApplyModeMerge
import com.poyka.ripdpi.data.HostPackApplyModeReplace
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.HostPackTargetBlacklist
import com.poyka.ripdpi.data.HostPackTargetWhitelist
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeDisabled
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.formatOffsetExpressionLabel
import com.poyka.ripdpi.data.formatNumericRange
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
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

internal const val TlsPreludeModeDisabled = "disabled"
internal const val HostPackApplyDialogDefaultMode = HostPackApplyModeMerge

internal enum class AdvancedToggleSetting {
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
    HttpMethodEol,
    HttpUnixEol,
    TlsrecEnabled,
    QuicSupportV1,
    QuicSupportV2,
    HostAutolearnEnabled,
    NetworkStrategyMemoryEnabled,
}

internal enum class AdvancedTextSetting {
    DiagnosticsSampleIntervalSeconds,
    DiagnosticsHistoryRetentionDays,
    CommandLineArgs,
    ProxyIp,
    ProxyPort,
    MaxConnections,
    BufferSize,
    DefaultTtl,
    ChainDsl,
    ActivationRoundFrom,
    ActivationRoundTo,
    ActivationPayloadSizeFrom,
    ActivationPayloadSizeTo,
    ActivationStreamBytesFrom,
    ActivationStreamBytesTo,
    SplitMarker,
    FakeTtl,
    AdaptiveFakeTtlMin,
    AdaptiveFakeTtlMax,
    AdaptiveFakeTtlFallback,
    FakeSni,
    FakeOffsetMarker,
    FakeTlsSize,
    QuicFakeHost,
    OobData,
    TlsrecMarker,
    TlsRandRecFragmentCount,
    TlsRandRecMinFragmentSize,
    TlsRandRecMaxFragmentSize,
    UdpFakeCount,
    HostAutolearnPenaltyTtlHours,
    HostAutolearnMaxHosts,
    HostsBlacklist,
    HostsWhitelist,
}

internal enum class AdvancedOptionSetting {
    DesyncMethod,
    AdaptiveSplitPreset,
    AdaptiveFakeTtlMode,
    TlsPreludeMode,
    HttpFakeProfile,
    FakeTlsBase,
    FakeTlsSniMode,
    TlsFakeProfile,
    HostsMode,
    QuicInitialMode,
    UdpFakeProfile,
    QuicFakeProfile,
}

internal enum class ActivationWindowDimension {
    Round,
    PayloadSize,
    StreamBytes,
}

internal data class AdaptiveSplitPresetUiModel(
    val value: String,
    val title: String,
    val body: String,
    val isRecommended: Boolean = false,
)

internal data class AdaptiveFakeTtlModeUiModel(
    val value: String,
    val title: String,
    val body: String,
    val badgeLabel: String? = null,
    val badgeTone: StatusIndicatorTone = StatusIndicatorTone.Active,
)

internal data class AdvancedNotice(
    val title: String,
    val message: String,
    val tone: WarningBannerTone,
)



@Composable
private fun rememberAdaptiveSplitPresetOptions(
    uiState: SettingsUiState,
    includeCustom: Boolean = uiState.hasCustomAdaptiveSplitPreset,
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
                            formatOffsetExpressionLabel(uiState.splitMarker),
                        ),
                ),
            )
        }
    }

@Composable
private fun rememberAdaptiveFakeTtlModeOptions(
    uiState: SettingsUiState,
    includeCustom: Boolean = uiState.hasCustomAdaptiveFakeTtl,
): List<AdaptiveFakeTtlModeUiModel> =
    buildList {
        add(
            AdaptiveFakeTtlModeUiModel(
                value = AdaptiveFakeTtlModeFixed,
                title = stringResource(R.string.adaptive_fake_ttl_mode_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_mode_fixed_body),
            ),
        )
        add(
            AdaptiveFakeTtlModeUiModel(
                value = AdaptiveFakeTtlModeAdaptive,
                title = stringResource(R.string.adaptive_fake_ttl_mode_adaptive_title),
                body = stringResource(R.string.adaptive_fake_ttl_mode_adaptive_body),
                badgeLabel = stringResource(R.string.adaptive_fake_ttl_mode_recommended),
            ),
        )
        if (includeCustom) {
            add(
                1,
                AdaptiveFakeTtlModeUiModel(
                    value = AdaptiveFakeTtlModeCustom,
                    title = stringResource(R.string.adaptive_fake_ttl_mode_custom_title),
                    body =
                        stringResource(
                            R.string.adaptive_fake_ttl_mode_custom_body,
                            uiState.adaptiveFakeTtlDelta,
                        ),
                    badgeLabel = stringResource(R.string.adaptive_fake_ttl_mode_custom_badge),
                    badgeTone = StatusIndicatorTone.Warning,
                ),
            )
        }
    }


@Composable
internal fun AdvancedSettingsScreen(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    notice: AdvancedNotice?,
    onBack: () -> Unit,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onApplyHostPackPreset: (HostPackPreset, String, String) -> Unit,
    onRefreshHostPackCatalog: () -> Unit,
    onForgetLearnedHosts: () -> Unit,
    onClearRememberedNetworks: () -> Unit,
    onSaveActivationRange: (ActivationWindowDimension, Long?, Long?) -> Unit,
    onResetAdaptiveSplit: () -> Unit,
    onResetAdaptiveFakeTtlProfile: () -> Unit,
    onResetActivationWindow: () -> Unit,
    onResetHttpParserEvasions: () -> Unit,
    onResetFakePayloadLibrary: () -> Unit,
    onResetFakeTlsProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val visualEditorEnabled = !uiState.enableCmdSettings
    val hostPackApplyControlsEnabled = hostPackApplyEnabled(uiState)
    val showHostFakeSection = uiState.showHostFakeProfile
    val showFakeApproxSection = uiState.showFakeApproximationProfile
    val showQuicFakeSection = uiState.showQuicFakeProfile
    val showFakePayloadLibrary = uiState.showFakePayloadLibrary
    val showAdaptiveFakeTtlSection = uiState.showAdaptiveFakeTtlProfile
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
    val httpFakeProfileOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.http_fake_profiles,
            valueArrayRes = R.array.http_fake_profiles_entries,
        )
    val fakeTlsSniModeOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.fake_tls_sni_modes,
            valueArrayRes = R.array.fake_tls_sni_modes_entries,
        )
    val tlsFakeProfileOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.tls_fake_profiles,
            valueArrayRes = R.array.tls_fake_profiles_entries,
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
    val udpFakeProfileOptions =
        rememberSettingsOptions(
            labelArrayRes = R.array.udp_fake_profiles,
            valueArrayRes = R.array.udp_fake_profiles_entries,
        )
    val adaptiveSplitPresetOptions = rememberAdaptiveSplitPresetOptions(uiState)
    val adaptiveFakeTtlModeOptions = rememberAdaptiveFakeTtlModeOptions(uiState)
    var pendingHostPack by remember { mutableStateOf<HostPackPreset?>(null) }
    var selectedHostPackTargetMode by rememberSaveable { mutableStateOf(defaultHostPackTargetMode(uiState)) }
    var selectedHostPackApplyMode by rememberSaveable { mutableStateOf(HostPackApplyDialogDefaultMode) }

    pendingHostPack?.let { preset ->
        HostPackApplyDialog(
            preset = preset,
            targetMode = selectedHostPackTargetMode,
            applyMode = selectedHostPackApplyMode,
            onTargetModeChanged = { selectedHostPackTargetMode = it },
            onApplyModeChanged = { selectedHostPackApplyMode = it },
            onDismiss = { pendingHostPack = null },
            onApply = {
                onApplyHostPackPreset(
                    preset,
                    selectedHostPackTargetMode,
                    selectedHostPackApplyMode,
                )
                pendingHostPack = null
            },
        )
    }

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
                    AdaptiveSplitProfileCard(
                        uiState = uiState,
                        onResetAdaptiveSplit = onResetAdaptiveSplit,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    HorizontalDivider(color = colors.divider)
                    AdaptiveSplitPresetSelector(
                        uiState = uiState,
                        presets = adaptiveSplitPresetOptions,
                        enabled = visualEditorEnabled && uiState.adaptiveSplitVisualEditorSupported,
                        onPresetSelected = { onOptionSelected(AdvancedOptionSetting.AdaptiveSplitPreset, it) },
                    )
                    if (!uiState.hasAdaptiveSplitPreset) {
                        HorizontalDivider(color = colors.divider)
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_split_marker_setting),
                            description = stringResource(R.string.config_split_marker_helper),
                            value = uiState.splitMarker,
                            placeholder = stringResource(R.string.config_placeholder_split_marker),
                            enabled = visualEditorEnabled && uiState.adaptiveSplitVisualEditorSupported,
                            validator = { it.isBlank() || (isValidOffsetExpression(it) && !isAdaptiveOffsetExpression(it)) },
                            invalidMessage = stringResource(R.string.config_error_invalid_marker),
                            disabledMessage =
                                if (uiState.adaptiveSplitVisualEditorSupported) {
                                    stringResource(R.string.advanced_settings_visual_controls_disabled)
                                } else {
                                    stringResource(R.string.adaptive_split_hostfake_disabled)
                                },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            setting = AdvancedTextSetting.SplitMarker,
                            onConfirm = onTextConfirmed,
                            showDivider = true,
                        )
                    } else {
                        HorizontalDivider(color = colors.divider)
                    }
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
                        showDivider =
                            showHostFakeSection ||
                                showFakeApproxSection ||
                                showAdaptiveFakeTtlSection ||
                                showFakeTlsSection ||
                                uiState.isOob,
                    )
                    if (showHostFakeSection) {
                        HostFakeProfileCard(
                            uiState = uiState,
                            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                        )
                        if (showFakeApproxSection || showAdaptiveFakeTtlSection || showFakeTlsSection || uiState.isOob) {
                            HorizontalDivider(color = colors.divider)
                        }
                    }
                    if (showFakeApproxSection) {
                        FakeApproximationProfileCard(
                            uiState = uiState,
                            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                        )
                        if (showAdaptiveFakeTtlSection || showFakeTlsSection || uiState.isOob) {
                            HorizontalDivider(color = colors.divider)
                        }
                    }
                    if (showAdaptiveFakeTtlSection) {
                        AdaptiveFakeTtlProfileCard(
                            uiState = uiState,
                            onResetAdaptiveFakeTtlProfile = onResetAdaptiveFakeTtlProfile,
                            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                        )
                        HorizontalDivider(color = colors.divider)
                        AdaptiveFakeTtlModeSelector(
                            uiState = uiState,
                            presets = adaptiveFakeTtlModeOptions,
                            enabled = visualEditorEnabled,
                            onModeSelected = { onOptionSelected(AdvancedOptionSetting.AdaptiveFakeTtlMode, it) },
                        )
                        HorizontalDivider(color = colors.divider)
                        if (uiState.adaptiveFakeTtlMode == AdaptiveFakeTtlModeFixed) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_fake_ttl_setting),
                                description = stringResource(R.string.adaptive_fake_ttl_fixed_body),
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
                        } else {
                            AdvancedTextSetting(
                                title = stringResource(R.string.adaptive_fake_ttl_min_title),
                                description = stringResource(R.string.adaptive_fake_ttl_min_body),
                                value = uiState.adaptiveFakeTtlMin.toString(),
                                enabled = visualEditorEnabled,
                                validator = { validateIntRange(it, 1, 255) },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.AdaptiveFakeTtlMin,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                            AdvancedTextSetting(
                                title = stringResource(R.string.adaptive_fake_ttl_max_title),
                                description = stringResource(R.string.adaptive_fake_ttl_max_body),
                                value = uiState.adaptiveFakeTtlMax.toString(),
                                enabled = visualEditorEnabled,
                                validator = { validateIntRange(it, uiState.adaptiveFakeTtlMin.coerceIn(1, 255), 255) },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.AdaptiveFakeTtlMax,
                                onConfirm = onTextConfirmed,
                                showDivider = true,
                            )
                            AdvancedTextSetting(
                                title = stringResource(R.string.adaptive_fake_ttl_fallback_title),
                                description = stringResource(R.string.adaptive_fake_ttl_fallback_body),
                                value = uiState.adaptiveFakeTtlFallback.toString(),
                                enabled = visualEditorEnabled,
                                validator = { validateIntRange(it, 1, 255) },
                                invalidMessage = stringResource(R.string.config_error_out_of_range),
                                disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                setting = AdvancedTextSetting.AdaptiveFakeTtlFallback,
                                onConfirm = onTextConfirmed,
                                showDivider = uiState.isFake || showFakeTlsSection || uiState.isOob,
                            )
                        }
                        if (uiState.isFake) {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_fake_offset_setting),
                                description = stringResource(R.string.config_fake_offset_marker_helper),
                                value = uiState.fakeOffsetMarker,
                                placeholder = stringResource(R.string.config_placeholder_fake_offset_marker),
                                enabled = visualEditorEnabled,
                                validator = {
                                    it.isBlank() || (isValidOffsetExpression(it) && !isAdaptiveOffsetExpression(it))
                                },
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
                    if (showFakePayloadLibrary) {
                        Text(
                            text = stringResource(R.string.fake_payload_library_section_title),
                            style = RipDpiThemeTokens.type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text =
                                if (uiState.fakePayloadLibraryControlsRelevant) {
                                    stringResource(R.string.fake_payload_library_section_body)
                                } else {
                                    stringResource(R.string.fake_payload_library_inactive)
                                },
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                        FakePayloadLibraryCard(
                            uiState = uiState,
                            onResetFakePayloadLibrary = onResetFakePayloadLibrary,
                            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                        )
                        FakePayloadProfileCard(
                            title = stringResource(R.string.http_fake_profile_title),
                            description = stringResource(R.string.http_fake_profile_body),
                            profileLabel = formatHttpFakeProfileLabel(uiState.httpFakeProfile),
                            statusLabel =
                                when {
                                    uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
                                    !uiState.desyncHttpEnabled -> stringResource(R.string.fake_payload_profile_status_off)
                                    uiState.httpFakeProfileActiveInStrategy ->
                                        stringResource(R.string.fake_payload_profile_status_live)
                                    uiState.hasHostFake -> stringResource(R.string.fake_payload_profile_status_separate)
                                    else -> stringResource(R.string.fake_payload_profile_status_ready)
                                },
                            statusTone =
                                when {
                                    uiState.enableCmdSettings -> StatusIndicatorTone.Warning
                                    !uiState.desyncHttpEnabled -> StatusIndicatorTone.Idle
                                    uiState.httpFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
                                    uiState.hasHostFake -> StatusIndicatorTone.Idle
                                    else -> StatusIndicatorTone.Idle
                                },
                            badges =
                                buildList {
                                    add(
                                        (
                                            if (uiState.desyncHttpEnabled) {
                                                stringResource(R.string.fake_payload_badge_http_on)
                                            } else {
                                                stringResource(R.string.fake_payload_badge_http_off)
                                            }
                                        ) to SummaryCapsuleTone.Neutral,
                                    )
                                    add(
                                        (
                                            if (uiState.httpFakeProfileActiveInStrategy) {
                                                stringResource(R.string.fake_payload_badge_fake_step_live)
                                            } else if (uiState.hasHostFake) {
                                                stringResource(R.string.fake_payload_badge_hostfake_separate)
                                            } else {
                                                stringResource(R.string.fake_payload_badge_fake_step_needed)
                                            }
                                        ) to
                                            when {
                                                uiState.httpFakeProfileActiveInStrategy -> SummaryCapsuleTone.Active
                                                uiState.hasHostFake -> SummaryCapsuleTone.Info
                                                else -> SummaryCapsuleTone.Warning
                                            },
                                    )
                                },
                            appliesSummary =
                                when {
                                    !uiState.desyncHttpEnabled ->
                                        stringResource(R.string.http_fake_profile_scope_off)
                                    uiState.httpFakeProfileActiveInStrategy ->
                                        stringResource(R.string.http_fake_profile_scope_live)
                                    uiState.hasHostFake ->
                                        stringResource(R.string.http_fake_profile_scope_hostfake)
                                    else -> stringResource(R.string.http_fake_profile_scope_ready)
                                },
                            interactionSummary = stringResource(R.string.http_fake_profile_interaction),
                            value = uiState.httpFakeProfile,
                            options = httpFakeProfileOptions,
                            setting = AdvancedOptionSetting.HttpFakeProfile,
                            onSelected = onOptionSelected,
                            enabled = visualEditorEnabled,
                            modifier = Modifier.padding(bottom = spacing.sm),
                        )
                        FakePayloadProfileCard(
                            title = stringResource(R.string.tls_fake_profile_title),
                            description = stringResource(R.string.tls_fake_profile_body),
                            profileLabel = formatTlsFakeProfileLabel(uiState.tlsFakeProfile),
                            statusLabel =
                                when {
                                    uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
                                    !uiState.desyncHttpsEnabled -> stringResource(R.string.fake_payload_profile_status_off)
                                    uiState.tlsFakeProfileActiveInStrategy ->
                                        stringResource(R.string.fake_payload_profile_status_live)
                                    uiState.hasHostFake -> stringResource(R.string.fake_payload_profile_status_separate)
                                    else -> stringResource(R.string.fake_payload_profile_status_ready)
                                },
                            statusTone =
                                when {
                                    uiState.enableCmdSettings -> StatusIndicatorTone.Warning
                                    !uiState.desyncHttpsEnabled -> StatusIndicatorTone.Idle
                                    uiState.tlsFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
                                    uiState.hasHostFake -> StatusIndicatorTone.Idle
                                    else -> StatusIndicatorTone.Idle
                                },
                            badges =
                                buildList {
                                    add(
                                        (
                                            if (uiState.desyncHttpsEnabled) {
                                                stringResource(R.string.fake_payload_badge_https_on)
                                            } else {
                                                stringResource(R.string.fake_payload_badge_https_off)
                                            }
                                        ) to SummaryCapsuleTone.Neutral,
                                    )
                                    add(
                                        (
                                            if (uiState.tlsFakeProfileActiveInStrategy) {
                                                stringResource(R.string.fake_payload_badge_fake_step_live)
                                            } else if (uiState.hasHostFake) {
                                                stringResource(R.string.fake_payload_badge_hostfake_separate)
                                            } else {
                                                stringResource(R.string.fake_payload_badge_fake_step_needed)
                                            }
                                        ) to
                                            when {
                                                uiState.tlsFakeProfileActiveInStrategy -> SummaryCapsuleTone.Active
                                                uiState.hasHostFake -> SummaryCapsuleTone.Info
                                                else -> SummaryCapsuleTone.Warning
                                            },
                                    )
                                    add(
                                        stringResource(R.string.fake_payload_badge_fake_tls_layers) to SummaryCapsuleTone.Info,
                                    )
                                },
                            appliesSummary =
                                when {
                                    !uiState.desyncHttpsEnabled ->
                                        stringResource(R.string.tls_fake_profile_scope_off)
                                    uiState.tlsFakeProfileActiveInStrategy ->
                                        stringResource(R.string.tls_fake_profile_scope_live)
                                    uiState.hasHostFake ->
                                        stringResource(R.string.tls_fake_profile_scope_hostfake)
                                    else -> stringResource(R.string.tls_fake_profile_scope_ready)
                                },
                            interactionSummary = stringResource(R.string.tls_fake_profile_interaction),
                            value = uiState.tlsFakeProfile,
                            options = tlsFakeProfileOptions,
                            setting = AdvancedOptionSetting.TlsFakeProfile,
                            onSelected = onOptionSelected,
                            enabled = visualEditorEnabled,
                            modifier = Modifier.padding(bottom = spacing.sm),
                        )
                        FakePayloadProfileCard(
                            title = stringResource(R.string.udp_fake_profile_title),
                            description = stringResource(R.string.udp_fake_profile_body),
                            profileLabel = formatUdpFakeProfileLabel(uiState.udpFakeProfile),
                            statusLabel =
                                when {
                                    uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
                                    !uiState.desyncUdpEnabled -> stringResource(R.string.fake_payload_profile_status_off)
                                    uiState.udpFakeProfileActiveInStrategy ->
                                        stringResource(R.string.fake_payload_profile_status_live)
                                    else -> stringResource(R.string.fake_payload_profile_status_ready)
                                },
                            statusTone =
                                when {
                                    uiState.enableCmdSettings -> StatusIndicatorTone.Warning
                                    !uiState.desyncUdpEnabled -> StatusIndicatorTone.Idle
                                    uiState.udpFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
                                    else -> StatusIndicatorTone.Idle
                                },
                            badges =
                                buildList {
                                    add(
                                        (
                                            if (uiState.desyncUdpEnabled) {
                                                stringResource(R.string.fake_payload_badge_udp_on)
                                            } else {
                                                stringResource(R.string.fake_payload_badge_udp_off)
                                            }
                                        ) to SummaryCapsuleTone.Neutral,
                                    )
                                    add(
                                        (
                                            if (uiState.udpFakeProfileActiveInStrategy) {
                                                stringResource(
                                                    R.string.fake_payload_badge_burst_ready,
                                                    uiState.udpFakeCount,
                                                )
                                            } else {
                                                stringResource(R.string.fake_payload_badge_burst_needed)
                                            }
                                        ) to
                                            if (uiState.udpFakeProfileActiveInStrategy) {
                                                SummaryCapsuleTone.Active
                                            } else {
                                                SummaryCapsuleTone.Warning
                                            },
                                    )
                                    if (uiState.quicFakeProfileActive) {
                                        add(
                                            stringResource(R.string.fake_payload_badge_quic_separate) to SummaryCapsuleTone.Info,
                                        )
                                    }
                                },
                            appliesSummary =
                                when {
                                    !uiState.desyncUdpEnabled ->
                                        stringResource(R.string.udp_fake_profile_scope_off)
                                    uiState.udpFakeProfileActiveInStrategy ->
                                        stringResource(
                                            R.string.udp_fake_profile_scope_live,
                                            uiState.udpFakeCount,
                                        )
                                    else -> stringResource(R.string.udp_fake_profile_scope_ready)
                                },
                            interactionSummary =
                                if (uiState.quicFakeProfileActive) {
                                    stringResource(R.string.udp_fake_profile_interaction_quic_override)
                                } else {
                                    stringResource(R.string.udp_fake_profile_interaction)
                                },
                            value = uiState.udpFakeProfile,
                            options = udpFakeProfileOptions,
                            setting = AdvancedOptionSetting.UdpFakeProfile,
                            onSelected = onOptionSelected,
                            enabled = visualEditorEnabled,
                            modifier = Modifier.padding(bottom = spacing.sm),
                        )
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

        if (uiState.showActivationWindowProfile) {
            item(key = "advanced_activation_window") {
                SettingsSection(title = stringResource(R.string.activation_window_section_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        ActivationWindowProfileCard(
                            uiState = uiState,
                            onResetActivationWindow = onResetActivationWindow,
                        )
                        ActivationRangeEditorCard(
                            title = stringResource(R.string.activation_window_round_card_title),
                            description = stringResource(R.string.activation_window_round_body),
                            currentRange = uiState.groupActivationFilter.round,
                            emptySummary = stringResource(R.string.activation_window_range_unbounded),
                            effectSummary = stringResource(R.string.activation_window_round_effect),
                            enabled = visualEditorEnabled,
                            minValue = 1L,
                            onSave = { start, end -> onSaveActivationRange(ActivationWindowDimension.Round, start, end) },
                        )
                        ActivationRangeEditorCard(
                            title = stringResource(R.string.activation_window_payload_card_title),
                            description = stringResource(R.string.activation_window_payload_body),
                            currentRange = uiState.groupActivationFilter.payloadSize,
                            emptySummary = stringResource(R.string.activation_window_range_unbounded),
                            effectSummary = stringResource(R.string.activation_window_payload_effect),
                            enabled = visualEditorEnabled,
                            minValue = 0L,
                            onSave = { start, end -> onSaveActivationRange(ActivationWindowDimension.PayloadSize, start, end) },
                        )
                        ActivationRangeEditorCard(
                            title = stringResource(R.string.activation_window_stream_card_title),
                            description = stringResource(R.string.activation_window_stream_body),
                            currentRange = uiState.groupActivationFilter.streamBytes,
                            emptySummary = stringResource(R.string.activation_window_range_unbounded),
                            effectSummary = stringResource(R.string.activation_window_stream_effect),
                            enabled = visualEditorEnabled,
                            minValue = 0L,
                            onSave = { start, end -> onSaveActivationRange(ActivationWindowDimension.StreamBytes, start, end) },
                        )
                    }
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

        item(key = "advanced_network_strategy_memory") {
            SettingsSection(title = stringResource(R.string.network_strategy_memory_section_title)) {
                RipDpiCard {
                    SettingsRow(
                        title = stringResource(R.string.network_strategy_memory_enabled_title),
                        subtitle = stringResource(R.string.network_strategy_memory_enabled_body),
                        checked = uiState.networkStrategyMemoryEnabled,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.NetworkStrategyMemoryEnabled, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.network_strategy_memory_count_summary,
                                uiState.rememberedNetworkCount,
                            ),
                        style = RipDpiThemeTokens.type.caption,
                        color = colors.mutedForeground,
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
                        color = colors.mutedForeground,
                    )
                }
            }
        }

        item(key = "advanced_http") {
            SettingsSection(title = stringResource(R.string.desync_http_category)) {
                HttpParserEvasionsProfileCard(
                    uiState = uiState,
                    onResetHttpParserEvasions = onResetHttpParserEvasions,
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
                HttpParserToggleGroupCard(
                    title = stringResource(R.string.ripdpi_http_parser_safe_group_title),
                    description = stringResource(R.string.ripdpi_http_parser_safe_group_body),
                    summary = formatHttpParserSafeSummary(uiState),
                    statusLabel =
                        stringResource(
                            if (uiState.hasSafeHttpParserTweaks) {
                                R.string.ripdpi_http_parser_group_status_active
                            } else {
                                R.string.ripdpi_http_parser_group_status_off
                            },
                        ),
                    statusTone =
                        if (uiState.hasSafeHttpParserTweaks) {
                            StatusIndicatorTone.Active
                        } else {
                            StatusIndicatorTone.Idle
                        },
                    badges =
                        buildList {
                            add(stringResource(R.string.ripdpi_http_parser_badge_http_only) to SummaryCapsuleTone.Info)
                            if (uiState.httpParserSafeCount > 0) {
                                add(
                                    stringResource(
                                        R.string.ripdpi_http_parser_badge_safe_count,
                                        uiState.httpParserSafeCount,
                                    ) to SummaryCapsuleTone.Active,
                                )
                            }
                        },
                ) {
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
                HttpParserToggleGroupCard(
                    modifier = Modifier.padding(top = spacing.sm),
                    title = stringResource(R.string.ripdpi_http_parser_aggressive_group_title),
                    description = stringResource(R.string.ripdpi_http_parser_aggressive_group_body),
                    summary = formatHttpParserAggressiveSummary(uiState),
                    statusLabel =
                        stringResource(
                            if (uiState.hasAggressiveHttpParserEvasions) {
                                R.string.ripdpi_http_parser_group_status_warning
                            } else {
                                R.string.ripdpi_http_parser_group_status_off
                            },
                        ),
                    statusTone =
                        if (uiState.hasAggressiveHttpParserEvasions) {
                            StatusIndicatorTone.Warning
                        } else {
                            StatusIndicatorTone.Idle
                        },
                    badges =
                        buildList {
                            add(stringResource(R.string.ripdpi_http_parser_badge_http_only) to SummaryCapsuleTone.Info)
                            add(stringResource(R.string.ripdpi_http_parser_badge_nginx_biased) to SummaryCapsuleTone.Warning)
                            if (uiState.httpParserAggressiveCount > 0) {
                                add(
                                    stringResource(
                                        R.string.ripdpi_http_parser_badge_aggressive_count,
                                        uiState.httpParserAggressiveCount,
                                    ) to SummaryCapsuleTone.Warning,
                                )
                            }
                        },
                ) {
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_http_method_eol_setting),
                        checked = uiState.httpMethodEol,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HttpMethodEol, it) },
                        enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_http_unix_eol_setting),
                        checked = uiState.httpUnixEol,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HttpUnixEol, it) },
                        enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    )
                }
            }
        }

        item(key = "advanced_https") {
            SettingsSection(title = stringResource(R.string.desync_https_category)) {
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
                    if (uiState.hasStackedTlsPreludeSteps) {
                        Text(
                            text = stringResource(R.string.ripdpi_tls_prelude_multiple_note),
                            style = RipDpiThemeTokens.type.caption,
                            color = colors.warning,
                        )
                    }
                    if (uiState.tlsPreludeMode != TlsPreludeModeDisabled) {
                        HorizontalDivider(color = colors.divider)
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_tlsrec_position_setting),
                            description = stringResource(R.string.config_tls_record_marker_helper),
                            value = uiState.tlsrecMarker,
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
                            showDivider = uiState.tlsPreludeUsesRandomRecords,
                        )
                    }
                    if (uiState.tlsPreludeUsesRandomRecords) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_tlsrandrec_count_title),
                            description = stringResource(R.string.ripdpi_tlsrandrec_count_body),
                            value = uiState.tlsRandRecFragmentCount.toString(),
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
                            value = uiState.tlsRandRecMinFragmentSize.toString(),
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
                            value = uiState.tlsRandRecMaxFragmentSize.toString(),
                            enabled = visualEditorEnabled,
                            validator = { input ->
                                input.toIntOrNull()?.let { value ->
                                    value in uiState.tlsRandRecMinFragmentSize..4096
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
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    HostPackCatalogStatusCard(
                        hostPackCatalog = hostPackCatalog,
                        onRefreshCatalog = onRefreshHostPackCatalog,
                    )
                    RipDpiCard {
                        if (hostPackCatalog.presets.isNotEmpty()) {
                            HostPackPresetSelector(
                                presets = hostPackCatalog.presets,
                                enabled = hostPackApplyControlsEnabled,
                                selectedPresetId = pendingHostPack?.id,
                                onPresetSelected = { preset ->
                                    selectedHostPackTargetMode = defaultHostPackTargetMode(uiState)
                                    selectedHostPackApplyMode = HostPackApplyDialogDefaultMode
                                    pendingHostPack = preset
                                },
                            )
                            if (!hostPackApplyControlsEnabled) {
                                Text(
                                    text = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                    style = RipDpiThemeTokens.type.caption,
                                    color = colors.mutedForeground,
                                )
                            }
                            Text(
                                text = stringResource(R.string.host_pack_semantics_note),
                                style = RipDpiThemeTokens.type.caption,
                                color = colors.mutedForeground,
                            )
                            HorizontalDivider(color = colors.divider)
                        }
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

private data class HostPackCatalogStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
private fun HostPackCatalogStatusCard(
    hostPackCatalog: HostPackCatalogUiState,
    onRefreshCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHostPackCatalogStatus(hostPackCatalog)
    val generatedAt =
        remember(hostPackCatalog.snapshot.catalog.generatedAt) {
            formatHostPackGeneratedAt(hostPackCatalog.snapshot.catalog.generatedAt)
        }
    val lastFetchedAt =
        remember(hostPackCatalog.snapshot.lastFetchedAtEpochMillis) {
            hostPackCatalog.snapshot.lastFetchedAtEpochMillis?.let(::formatHostPackFetchedAt)
        }
    val downloadedBadge = stringResource(R.string.host_pack_badge_downloaded)
    val bundledBadge = stringResource(R.string.host_pack_badge_bundled)
    val packCountBadge = stringResource(R.string.host_pack_packs_badge, hostPackCatalog.snapshot.packs.size)
    val verifiedBadge = stringResource(R.string.host_pack_badge_checksum_verified)
    val offlineBadge = stringResource(R.string.host_pack_badge_offline_snapshot)
    val badges =
        remember(
            hostPackCatalog.snapshot.source,
            hostPackCatalog.snapshot.packs.size,
            hostPackCatalog.snapshot.lastFetchedAtEpochMillis,
            downloadedBadge,
            bundledBadge,
            packCountBadge,
            verifiedBadge,
            offlineBadge,
        ) {
            buildList {
                add(
                    if (hostPackCatalog.snapshot.source == HostPackCatalogSourceDownloaded) {
                        downloadedBadge to SummaryCapsuleTone.Active
                    } else {
                        bundledBadge to SummaryCapsuleTone.Info
                    },
                )
                add(packCountBadge to SummaryCapsuleTone.Neutral)
                add(
                    if (hostPackCatalog.snapshot.lastFetchedAtEpochMillis != null) {
                        verifiedBadge to SummaryCapsuleTone.Active
                    } else {
                        offlineBadge to SummaryCapsuleTone.Neutral
                    },
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
        generatedAt?.let {
            ProfileSummaryLine(
                label = stringResource(R.string.host_pack_snapshot_built_label),
                value = it,
            )
        }
        ProfileSummaryLine(
            label = stringResource(R.string.host_pack_last_fetch_label),
            value = lastFetchedAt ?: stringResource(R.string.host_pack_last_fetch_never),
        )
        Text(
            text = stringResource(R.string.host_pack_refresh_source_hint),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            RipDpiButton(
                text =
                    if (hostPackCatalog.isRefreshing) {
                        stringResource(R.string.host_pack_refresh_in_progress)
                    } else {
                        stringResource(R.string.host_pack_refresh_action)
                    },
                onClick = onRefreshCatalog,
                enabled = hostPackRefreshEnabled(hostPackCatalog),
                loading = hostPackCatalog.isRefreshing,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
private fun rememberHostPackCatalogStatus(hostPackCatalog: HostPackCatalogUiState): HostPackCatalogStatusContent =
    when {
        hostPackCatalog.isRefreshing ->
            HostPackCatalogStatusContent(
                label = stringResource(R.string.host_pack_refresh_status_title),
                body = stringResource(R.string.host_pack_refresh_status_body),
                tone = StatusIndicatorTone.Active,
            )

        hostPackCatalog.snapshot.source == HostPackCatalogSourceDownloaded ->
            HostPackCatalogStatusContent(
                label = stringResource(R.string.host_pack_downloaded_status_title),
                body = stringResource(R.string.host_pack_downloaded_status_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            HostPackCatalogStatusContent(
                label = stringResource(R.string.host_pack_bundled_status_title),
                body = stringResource(R.string.host_pack_bundled_status_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun HostPackPresetSelector(
    presets: List<HostPackPreset>,
    enabled: Boolean,
    selectedPresetId: String?,
    onPresetSelected: (HostPackPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.host_pack_presets_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.host_pack_presets_body),
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        presets.forEach { preset ->
            HostPackPresetCard(
                preset = preset,
                enabled = enabled,
                selected = selectedPresetId == preset.id,
                onClick = { onPresetSelected(preset) },
            )
        }
    }
}

@Composable
private fun HostPackPresetCard(
    preset: HostPackPreset,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceSummary = hostPackSourceSummary(preset)
    val description =
        if (sourceSummary.isBlank()) {
            preset.description
        } else {
            stringResource(R.string.host_pack_preset_body, preset.description, sourceSummary)
        }

    PresetCard(
        title = preset.title,
        description = description,
        modifier = modifier,
        badgeText = stringResource(R.string.host_pack_hosts_badge, preset.hostCount.takeIf { it > 0 } ?: preset.hosts.size),
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun HostPackApplyDialog(
    preset: HostPackPreset,
    targetMode: String,
    applyMode: String,
    onTargetModeChanged: (String) -> Unit,
    onApplyModeChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val targetOptions =
        listOf(
            RipDpiDropdownOption(
                value = HostPackTargetBlacklist,
                label = stringResource(R.string.host_pack_target_blacklist),
            ),
            RipDpiDropdownOption(
                value = HostPackTargetWhitelist,
                label = stringResource(R.string.host_pack_target_whitelist),
            ),
        )
    val applyModeOptions =
        listOf(
            RipDpiDropdownOption(
                value = HostPackApplyModeMerge,
                label = stringResource(R.string.host_pack_apply_merge),
            ),
            RipDpiDropdownOption(
                value = HostPackApplyModeReplace,
                label = stringResource(R.string.host_pack_apply_replace),
            ),
        )
    val sourceSummary = hostPackSourceSummary(preset)
    val summary =
        if (sourceSummary.isBlank()) {
            stringResource(R.string.host_pack_apply_summary_hosts_only, preset.hostCount.takeIf { it > 0 } ?: preset.hosts.size)
        } else {
            stringResource(
                R.string.host_pack_apply_summary,
                preset.hostCount.takeIf { it > 0 } ?: preset.hosts.size,
                sourceSummary,
            )
        }

    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.host_pack_apply_dialog_title, preset.title),
        message = stringResource(R.string.host_pack_apply_dialog_message),
        dismissLabel = stringResource(R.string.host_pack_apply_dismiss),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.host_pack_apply_confirm),
        onConfirm = onApply,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            Text(
                text = summary,
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            HostPackDialogDropdown(
                title = stringResource(R.string.host_pack_target_title),
                value = targetMode,
                options = targetOptions,
                onSelected = onTargetModeChanged,
            )
            HostPackDialogDropdown(
                title = stringResource(R.string.host_pack_action_title),
                value = applyMode,
                options = applyModeOptions,
                onSelected = onApplyModeChanged,
            )
        }
    }
}

@Composable
private fun HostPackDialogDropdown(
    title: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = onSelected,
        )
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

private data class ActivationWindowStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class AdaptiveSplitStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class AdaptiveFakeTtlStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class HttpParserEvasionStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
private fun ActivationWindowProfileCard(
    uiState: SettingsUiState,
    onResetActivationWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberActivationWindowStatus(uiState)
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.activation_window_scope_cli)
            !uiState.activationWindowControlsRelevant ->
                stringResource(R.string.activation_window_scope_inactive)
            uiState.hasCustomActivationWindow ->
                stringResource(R.string.activation_window_scope_filtered)
            else -> stringResource(R.string.activation_window_scope_open)
        }
    val stepFilterSummary =
        if (uiState.hasStepActivationFilters) {
            stringResource(
                R.string.activation_window_step_filters_present,
                uiState.stepActivationFilterCount,
            )
        } else {
            stringResource(R.string.activation_window_step_filters_none)
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasCustomActivationWindow) {
                        stringResource(R.string.activation_window_badge_custom)
                    } else {
                        stringResource(R.string.activation_window_badge_default)
                    }
                ) to
                    if (uiState.hasCustomActivationWindow) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            if (!uiState.groupActivationFilter.round.isEmpty) {
                add(stringResource(R.string.activation_window_badge_round) to SummaryCapsuleTone.Active)
            }
            if (!uiState.groupActivationFilter.payloadSize.isEmpty) {
                add(stringResource(R.string.activation_window_badge_payload) to SummaryCapsuleTone.Active)
            }
            if (!uiState.groupActivationFilter.streamBytes.isEmpty) {
                add(stringResource(R.string.activation_window_badge_stream) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasStepActivationFilters) {
                add(
                    stringResource(
                        R.string.activation_window_badge_step_filters,
                        uiState.stepActivationFilterCount,
                    ) to SummaryCapsuleTone.Info,
                )
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
                label = stringResource(R.string.activation_window_summary_label),
                value = uiState.activationWindowSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_scope_label),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_step_filters_label),
                value = stepFilterSummary,
            )
        }
        Text(
            text = stringResource(R.string.activation_window_section_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetActivationWindow) {
            RipDpiButton(
                text = stringResource(R.string.activation_window_reset_action),
                onClick = onResetActivationWindow,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberActivationWindowStatus(uiState: SettingsUiState): ActivationWindowStatusContent =
    when {
        uiState.enableCmdSettings ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_cli_title),
                body = stringResource(R.string.activation_window_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.activationWindowControlsRelevant && uiState.hasCustomActivationWindow ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_group_disabled_title),
                body = stringResource(R.string.activation_window_group_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.activationWindowControlsRelevant ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_group_off_title),
                body = stringResource(R.string.activation_window_group_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasCustomActivationWindow ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_restart_title),
                body = stringResource(R.string.activation_window_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomActivationWindow ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_custom_title),
                body = stringResource(R.string.activation_window_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.hasStepActivationFilters ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_step_only_title),
                body = stringResource(R.string.activation_window_step_only_body),
                tone = StatusIndicatorTone.Idle,
            )

        else ->
            ActivationWindowStatusContent(
                label = stringResource(R.string.activation_window_default_title),
                body = stringResource(R.string.activation_window_default_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun HttpParserEvasionsProfileCard(
    uiState: SettingsUiState,
    onResetHttpParserEvasions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHttpParserEvasionStatus(uiState)
    val profileSummary =
        when {
            uiState.hasSafeHttpParserTweaks && uiState.hasAggressiveHttpParserEvasions ->
                stringResource(R.string.ripdpi_http_parser_profile_safe_and_aggressive)

            uiState.hasAggressiveHttpParserEvasions ->
                stringResource(R.string.ripdpi_http_parser_profile_aggressive_only)

            uiState.hasSafeHttpParserTweaks ->
                stringResource(R.string.ripdpi_http_parser_profile_safe)

            else -> stringResource(R.string.ripdpi_http_parser_profile_default)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.ripdpi_http_parser_scope_cli)
            !uiState.httpParserControlsRelevant -> stringResource(R.string.ripdpi_http_parser_scope_http_off)
            uiState.isServiceRunning && uiState.hasCustomHttpParserEvasions ->
                stringResource(R.string.ripdpi_http_parser_scope_restart)

            else -> stringResource(R.string.ripdpi_http_parser_scope_active)
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasCustomHttpParserEvasions) {
                        stringResource(R.string.ripdpi_http_parser_badge_custom)
                    } else {
                        stringResource(R.string.ripdpi_http_parser_badge_default)
                    }
                ) to
                    if (uiState.hasCustomHttpParserEvasions) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.ripdpi_http_parser_badge_http_only) to SummaryCapsuleTone.Info)
            if (uiState.httpParserSafeCount > 0) {
                add(
                    stringResource(
                        R.string.ripdpi_http_parser_badge_safe_count,
                        uiState.httpParserSafeCount,
                    ) to SummaryCapsuleTone.Active,
                )
            }
            if (uiState.httpParserAggressiveCount > 0) {
                add(
                    stringResource(
                        R.string.ripdpi_http_parser_badge_aggressive_count,
                        uiState.httpParserAggressiveCount,
                    ) to SummaryCapsuleTone.Warning,
                )
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
                label = stringResource(R.string.ripdpi_http_parser_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_safe),
                value = formatHttpParserSafeSummary(uiState),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_aggressive),
                value = formatHttpParserAggressiveSummary(uiState),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_probing),
                value = stringResource(R.string.ripdpi_http_parser_probing_summary),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_http_parser_section_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetHttpParserEvasions) {
            RipDpiButton(
                text = stringResource(R.string.ripdpi_http_parser_reset_action),
                onClick = onResetHttpParserEvasions,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberHttpParserEvasionStatus(uiState: SettingsUiState): HttpParserEvasionStatusContent =
    when {
        uiState.enableCmdSettings ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_cli_title),
                body = stringResource(R.string.ripdpi_http_parser_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.httpParserControlsRelevant && uiState.hasCustomHttpParserEvasions ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_saved_title),
                body = stringResource(R.string.ripdpi_http_parser_saved_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.httpParserControlsRelevant ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_http_off_title),
                body = stringResource(R.string.ripdpi_http_parser_http_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasCustomHttpParserEvasions ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_restart_title),
                body = stringResource(R.string.ripdpi_http_parser_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasAggressiveHttpParserEvasions ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_aggressive_title),
                body = stringResource(R.string.ripdpi_http_parser_aggressive_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasSafeHttpParserTweaks ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_safe_title),
                body = stringResource(R.string.ripdpi_http_parser_safe_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_default_title),
                body = stringResource(R.string.ripdpi_http_parser_default_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun HttpParserToggleGroupCard(
    title: String,
    description: String,
    summary: String,
    statusLabel: String,
    statusTone: StatusIndicatorTone,
    badges: List<Pair<String, SummaryCapsuleTone>>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = statusTone,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        SummaryCapsuleFlow(items = badges)
        ProfileSummaryLine(
            label = stringResource(R.string.ripdpi_http_parser_group_summary_label_current),
            value = summary,
        )
        HorizontalDivider(color = colors.divider)
        content()
    }
}

@Composable
private fun formatHttpParserSafeSummary(uiState: SettingsUiState): String =
    buildList {
        if (uiState.hostMixedCase) {
            add(stringResource(R.string.ripdpi_host_mixed_case_setting))
        }
        if (uiState.domainMixedCase) {
            add(stringResource(R.string.ripdpi_domain_mixed_case_setting))
        }
        if (uiState.hostRemoveSpaces) {
            add(stringResource(R.string.ripdpi_host_remove_spaces_setting))
        }
    }.joinToString(separator = " · ")
        .ifBlank { stringResource(R.string.ripdpi_http_parser_safe_none) }

@Composable
private fun formatHttpParserAggressiveSummary(uiState: SettingsUiState): String =
    buildList {
        if (uiState.httpMethodEol) {
            add(stringResource(R.string.ripdpi_http_method_eol_setting))
        }
        if (uiState.httpUnixEol) {
            add(stringResource(R.string.ripdpi_http_unix_eol_setting))
        }
    }.joinToString(separator = " · ")
        .ifBlank { stringResource(R.string.ripdpi_http_parser_aggressive_none) }

@Composable
private fun AdaptiveSplitPresetSelector(
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
                selected = uiState.adaptiveSplitPreset == preset.value,
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

@Composable
private fun AdaptiveSplitProfileCard(
    uiState: SettingsUiState,
    onResetAdaptiveSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberAdaptiveSplitStatus(uiState)
    val profileSummary =
        when (uiState.adaptiveSplitPreset) {
            AdaptiveSplitPresetManual -> stringResource(R.string.adaptive_split_profile_manual)
            AdaptiveSplitPresetCustom ->
                stringResource(
                    R.string.adaptive_split_profile_custom,
                    formatOffsetExpressionLabel(uiState.splitMarker),
                )
            else -> formatOffsetExpressionLabel(uiState.splitMarker)
        }
    val targetSummary =
        if (uiState.settings.tcpChainStepsCount > 0 && primaryTcpChainStep(uiState.tcpChainSteps) != null) {
            stringResource(R.string.adaptive_split_target_chain_step)
        } else {
            stringResource(R.string.adaptive_split_target_legacy)
        }
    val focusSummary =
        when (uiState.adaptiveSplitPreset) {
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
            !uiState.adaptiveSplitVisualEditorSupported -> stringResource(R.string.adaptive_split_scope_hostfake)
            uiState.hasAdaptiveSplitPreset -> stringResource(R.string.adaptive_split_scope_active)
            else -> stringResource(R.string.adaptive_split_scope_manual)
        }
    val protocolSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled ->
                stringResource(R.string.adaptive_split_protocol_http_https)
            uiState.desyncHttpEnabled -> stringResource(R.string.adaptive_split_protocol_http)
            uiState.desyncHttpsEnabled -> stringResource(R.string.adaptive_split_protocol_https)
            else -> stringResource(R.string.adaptive_split_protocol_none)
        }
    val dslSummary = stringResource(R.string.adaptive_split_dsl_only_summary)
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasAdaptiveSplitPreset) {
                        stringResource(R.string.adaptive_split_badge_adaptive)
                    } else {
                        stringResource(R.string.adaptive_split_badge_manual)
                    }
                ) to
                    if (uiState.hasAdaptiveSplitPreset) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            if (uiState.hasCustomAdaptiveSplitPreset) {
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
            RipDpiButton(
                text = stringResource(R.string.adaptive_split_reset_action),
                onClick = onResetAdaptiveSplit,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberAdaptiveSplitStatus(uiState: SettingsUiState): AdaptiveSplitStatusContent =
    when {
        uiState.enableCmdSettings ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_cli_title),
                body = stringResource(R.string.adaptive_split_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.adaptiveSplitVisualEditorSupported ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_hostfake_title),
                body = stringResource(R.string.adaptive_split_hostfake_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.desyncEnabled && uiState.hasAdaptiveSplitPreset ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_saved_title),
                body = stringResource(R.string.adaptive_split_saved_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.desyncEnabled ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_off_title),
                body = stringResource(R.string.adaptive_split_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasAdaptiveSplitPreset ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_restart_title),
                body = stringResource(R.string.adaptive_split_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasAdaptiveSplitPreset ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_ready_title),
                body = stringResource(R.string.adaptive_split_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_manual_title),
                body = stringResource(R.string.adaptive_split_manual_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun AdaptiveFakeTtlProfileCard(
    uiState: SettingsUiState,
    onResetAdaptiveFakeTtlProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberAdaptiveFakeTtlStatus(uiState)
    val modeSummary =
        when (uiState.adaptiveFakeTtlMode) {
            AdaptiveFakeTtlModeAdaptive -> stringResource(R.string.adaptive_fake_ttl_summary_mode_adaptive)
            AdaptiveFakeTtlModeCustom -> stringResource(R.string.adaptive_fake_ttl_summary_mode_custom, uiState.adaptiveFakeTtlDelta)
            else -> stringResource(R.string.adaptive_fake_ttl_summary_mode_fixed)
        }
    val windowSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(
                R.string.adaptive_fake_ttl_summary_window_value,
                uiState.adaptiveFakeTtlMin,
                uiState.adaptiveFakeTtlMax,
            )
        } else {
            stringResource(R.string.adaptive_fake_ttl_summary_window_fixed)
        }
    val fallbackSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(R.string.adaptive_fake_ttl_summary_fallback_value, uiState.adaptiveFakeTtlFallback)
        } else {
            stringResource(R.string.adaptive_fake_ttl_summary_fallback_fixed, uiState.fakeTtl)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.adaptive_fake_ttl_scope_cli)
            !uiState.fakeTtlControlsRelevant -> stringResource(R.string.adaptive_fake_ttl_scope_idle)
            uiState.hasAdaptiveFakeTtl -> stringResource(R.string.adaptive_fake_ttl_scope_adaptive)
            else -> stringResource(R.string.adaptive_fake_ttl_scope_fixed)
        }
    val targetLabels =
        buildList {
            if (uiState.isFake) add(stringResource(R.string.adaptive_fake_ttl_targets_fake))
            if (uiState.hasHostFake) add(stringResource(R.string.adaptive_fake_ttl_targets_hostfake))
            if (uiState.hasDisoob) add(stringResource(R.string.adaptive_fake_ttl_targets_disoob))
        }
    val targetSummary =
        if (targetLabels.isEmpty()) {
            stringResource(R.string.adaptive_fake_ttl_targets_none)
        } else {
            targetLabels.joinToString()
        }
    val learningSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(R.string.adaptive_fake_ttl_learning_runtime)
        } else {
            stringResource(R.string.adaptive_fake_ttl_learning_fixed)
        }
    val badges =
        buildList {
            add(
                (
                    when (uiState.adaptiveFakeTtlMode) {
                        AdaptiveFakeTtlModeAdaptive -> stringResource(R.string.adaptive_fake_ttl_badge_adaptive)
                        AdaptiveFakeTtlModeCustom -> stringResource(R.string.adaptive_fake_ttl_badge_custom)
                        else -> stringResource(R.string.adaptive_fake_ttl_badge_fixed)
                    }
                ) to
                    when (uiState.adaptiveFakeTtlMode) {
                        AdaptiveFakeTtlModeAdaptive -> SummaryCapsuleTone.Active
                        AdaptiveFakeTtlModeCustom -> SummaryCapsuleTone.Info
                        else -> SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.adaptive_fake_ttl_badge_tcp_only) to SummaryCapsuleTone.Info)
            if (uiState.hasAdaptiveFakeTtl) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_runtime_learned) to SummaryCapsuleTone.Active)
            }
            if (uiState.isFake) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_fake) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasHostFake) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_hostfake) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasDisoob) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_disoob) to SummaryCapsuleTone.Warning)
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
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_window),
                value = windowSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_fallback),
                value = fallbackSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_targets),
                value = targetSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_learning),
                value = learningSummary,
            )
        }
        if (uiState.canResetAdaptiveFakeTtlProfile) {
            RipDpiButton(
                text = stringResource(R.string.adaptive_fake_ttl_reset_action),
                onClick = onResetAdaptiveFakeTtlProfile,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberAdaptiveFakeTtlStatus(uiState: SettingsUiState): AdaptiveFakeTtlStatusContent =
    when {
        uiState.enableCmdSettings ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_cli_title),
                body = stringResource(R.string.adaptive_fake_ttl_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakeTtlControlsRelevant && uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_saved_title),
                body = stringResource(R.string.adaptive_fake_ttl_saved_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.fakeTtlControlsRelevant ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_restart_title),
                body = stringResource(R.string.adaptive_fake_ttl_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_custom_title),
                body = stringResource(R.string.adaptive_fake_ttl_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_adaptive_title),
                body = stringResource(R.string.adaptive_fake_ttl_adaptive_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun AdaptiveFakeTtlModeSelector(
    uiState: SettingsUiState,
    presets: List<AdaptiveFakeTtlModeUiModel>,
    enabled: Boolean,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.adaptive_fake_ttl_selector_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.adaptive_fake_ttl_selector_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            AdaptiveFakeTtlModeCard(
                preset = preset,
                selected = uiState.adaptiveFakeTtlMode == preset.value,
                enabled = enabled && preset.value != AdaptiveFakeTtlModeCustom,
                onClick = { onModeSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun AdaptiveFakeTtlModeCard(
    preset: AdaptiveFakeTtlModeUiModel,
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
            val badgeLabel =
                when {
                    selected -> stringResource(R.string.adaptive_fake_ttl_mode_selected)
                    preset.badgeLabel != null -> preset.badgeLabel
                    else -> null
                }
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = if (selected) StatusIndicatorTone.Active else preset.badgeTone,
                )
            }
        }
    }
}

@Composable
private fun ActivationRangeEditorCard(
    title: String,
    description: String,
    currentRange: NumericRangeModel,
    emptySummary: String,
    effectSummary: String,
    enabled: Boolean,
    minValue: Long,
    onSave: (Long?, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var startInput by rememberSaveable(currentRange.start, currentRange.end) {
        mutableStateOf(currentRange.start?.toString().orEmpty())
    }
    var endInput by rememberSaveable(currentRange.start, currentRange.end) {
        mutableStateOf(currentRange.end?.toString().orEmpty())
    }
    val currentStart = currentRange.start?.toString().orEmpty()
    val currentEnd = currentRange.end?.toString().orEmpty()
    val isDirty = startInput != currentStart || endInput != currentEnd
    val startValid = isActivationBoundaryValid(startInput, minValue)
    val endValid = isActivationBoundaryValid(endInput, minValue)
    val isValid = startValid && endValid
    val currentSummary =
        formatNumericRange(currentRange)
            ?: emptySummary
    val statusLabel =
        if (currentRange.isEmpty) {
            stringResource(R.string.activation_window_range_status_open)
        } else {
            stringResource(R.string.activation_window_range_status_scoped)
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = if (currentRange.isEmpty) StatusIndicatorTone.Idle else StatusIndicatorTone.Active,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_range_summary_label),
                value = currentSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_range_effect_label),
                value = effectSummary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ActivationBoundaryField(
                title = stringResource(R.string.activation_window_field_from),
                value = startInput,
                enabled = enabled,
                minValue = minValue,
                onValueChange = { startInput = it },
                modifier = Modifier.weight(1f),
            )
            ActivationBoundaryField(
                title = stringResource(R.string.activation_window_field_to),
                value = endInput,
                enabled = enabled,
                minValue = minValue,
                onValueChange = { endInput = it },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = {
                    onSave(
                        parseOptionalRangeValue(startInput),
                        parseOptionalRangeValue(endInput),
                    )
                },
                enabled = enabled && isDirty && isValid,
                variant = RipDpiButtonVariant.Outline,
                trailingIcon = RipDpiIcons.Check,
            )
        }
    }
}

@Composable
private fun ActivationBoundaryField(
    title: String,
    value: String,
    enabled: Boolean,
    minValue: Long,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isValid = isActivationBoundaryValid(value, minValue)
    val helperText =
        if (!enabled && isValid) {
            stringResource(R.string.advanced_settings_visual_controls_disabled)
        } else {
            null
        }
    val errorText =
        if (!isValid) {
            stringResource(R.string.config_error_out_of_range)
        } else {
            null
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiConfigTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            helperText = helperText,
            errorText = errorText,
            density = RipDpiControlDensity.Compact,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
    }
}


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
            when (uiState.tlsPreludeMode) {
                TcpChainStepKind.TlsRec.wireName -> R.string.ripdpi_tls_prelude_summary_mode_single
                TcpChainStepKind.TlsRandRec.wireName -> R.string.ripdpi_tls_prelude_summary_mode_random
                else -> R.string.ripdpi_tls_prelude_summary_mode_off
            },
        )
    val markerSummary =
        if (uiState.tlsPreludeMode == TlsPreludeModeDisabled) {
            stringResource(R.string.ripdpi_tls_prelude_marker_unused)
        } else {
            uiState.tlsrecMarker
        }
    val layoutSummary =
        when (uiState.tlsPreludeMode) {
            TcpChainStepKind.TlsRandRec.wireName ->
                stringResource(
                    R.string.ripdpi_tls_prelude_summary_layout_random,
                    uiState.tlsRandRecFragmentCount,
                    uiState.tlsRandRecMinFragmentSize,
                    uiState.tlsRandRecMaxFragmentSize,
                )

            TcpChainStepKind.TlsRec.wireName ->
                stringResource(R.string.ripdpi_tls_prelude_summary_layout_single)

            else -> stringResource(R.string.ripdpi_tls_prelude_summary_layout_off)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.ripdpi_tls_prelude_scope_cli)
            !uiState.tlsPreludeControlsRelevant -> stringResource(R.string.ripdpi_tls_prelude_scope_https_disabled)
            uiState.hasStackedTlsPreludeSteps -> stringResource(R.string.ripdpi_tls_prelude_scope_stacked)
            else -> stringResource(R.string.ripdpi_tls_prelude_scope_active)
        }
    val badges =
        buildList {
            add(stringResource(R.string.ripdpi_tls_prelude_badge_https_only) to SummaryCapsuleTone.Info)
            when (uiState.tlsPreludeMode) {
                TcpChainStepKind.TlsRec.wireName ->
                    add(stringResource(R.string.ripdpi_tls_prelude_badge_single) to SummaryCapsuleTone.Active)

                TcpChainStepKind.TlsRandRec.wireName ->
                    add(
                        stringResource(
                            R.string.ripdpi_tls_prelude_badge_random,
                            uiState.tlsRandRecFragmentCount,
                        ) to SummaryCapsuleTone.Active,
                    )

                else -> Unit
            }
            if (uiState.hasStackedTlsPreludeSteps) {
                add(
                    stringResource(
                        R.string.ripdpi_tls_prelude_badge_stacked,
                        uiState.tlsPreludeStepCount,
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
        uiState.enableCmdSettings ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_cli_title),
                body = stringResource(R.string.ripdpi_tls_prelude_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.tlsPreludeControlsRelevant && uiState.tlsPreludeStepCount > 0 ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_https_disabled_title),
                body = stringResource(R.string.ripdpi_tls_prelude_https_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasStackedTlsPreludeSteps ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_stacked_title),
                body = stringResource(R.string.ripdpi_tls_prelude_stacked_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.tlsPreludeMode == TlsPreludeModeDisabled ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_off_title),
                body = stringResource(R.string.ripdpi_tls_prelude_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_restart_title),
                body = stringResource(R.string.ripdpi_tls_prelude_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.tlsPreludeUsesRandomRecords ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_random_title),
                body = stringResource(R.string.ripdpi_tls_prelude_random_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_single_title),
                body = stringResource(R.string.ripdpi_tls_prelude_single_body),
                tone = StatusIndicatorTone.Active,
            )
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
                selected = uiState.tlsPreludeMode == preset.value,
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

private data class HostFakeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class FakeApproximationStatusContent(
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
private fun FakeApproximationProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakeApproximationStatus(uiState)
    val primaryStep = uiState.primaryFakeApproximationStep
    val profileSummary =
        when (uiState.fakeApproximationStepCount) {
            0 -> stringResource(R.string.ripdpi_fake_approx_summary_profile_none)
            1 -> stringResource(R.string.ripdpi_fake_approx_summary_profile_single)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_profile_multiple, uiState.fakeApproximationStepCount)
        }
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled ->
                stringResource(R.string.ripdpi_fake_approx_summary_scope_http_https)
            uiState.desyncHttpEnabled -> stringResource(R.string.ripdpi_fake_approx_summary_scope_http)
            uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_fake_approx_summary_scope_https)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_scope_none)
        }
    val modeSummary =
        when (primaryStep?.kind) {
            TcpChainStepKind.FakeSplit -> stringResource(R.string.ripdpi_fake_approx_summary_mode_fakedsplit)
            TcpChainStepKind.FakeDisorder -> stringResource(R.string.ripdpi_fake_approx_summary_mode_fakeddisorder)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_mode_none)
        }
    val markerSummary =
        primaryStep
            ?.marker
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_fake_approx_summary_marker_none)
    val transportSummary =
        when (primaryStep?.kind) {
            TcpChainStepKind.FakeSplit -> stringResource(R.string.ripdpi_fake_approx_summary_transport_fakedsplit)
            TcpChainStepKind.FakeDisorder -> stringResource(R.string.ripdpi_fake_approx_summary_transport_fakeddisorder)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_transport_none)
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasFakeApproximation) {
                        stringResource(R.string.ripdpi_fake_approx_badge_configured)
                    } else {
                        stringResource(R.string.ripdpi_fake_approx_badge_available)
                    }
                ) to
                    if (uiState.hasFakeApproximation) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.ripdpi_fake_approx_badge_linux_android) to SummaryCapsuleTone.Info)
            if (uiState.desyncHttpEnabled) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_http) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpsEnabled) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_https) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasFakeSplitApproximation) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_fakedsplit) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasFakeDisorderApproximation) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_fakeddisorder) to SummaryCapsuleTone.Warning)
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
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_marker),
                value = markerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_shared),
                value = stringResource(R.string.ripdpi_fake_approx_summary_shared),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_transport),
                value = transportSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_http_example),
                value = stringResource(R.string.ripdpi_fake_approx_example_http),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_tls_example),
                value = stringResource(R.string.ripdpi_fake_approx_example_tls),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_fake_approx_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun rememberFakeApproximationStatus(uiState: SettingsUiState): FakeApproximationStatusContent =
    when {
        uiState.enableCmdSettings ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_cli_title),
                body = stringResource(R.string.ripdpi_fake_approx_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakeApproximationControlsRelevant ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_protocols_off_title),
                body = stringResource(R.string.ripdpi_fake_approx_protocols_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasFakeApproximation && uiState.isServiceRunning ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_restart_title),
                body = stringResource(R.string.ripdpi_fake_approx_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasFakeApproximation ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_ready_title),
                body = stringResource(R.string.ripdpi_fake_approx_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_available_title),
                body = stringResource(R.string.ripdpi_fake_approx_available_body),
                tone = StatusIndicatorTone.Idle,
            )
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

private data class FakePayloadLibraryStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
private fun FakePayloadLibraryCard(
    uiState: SettingsUiState,
    onResetFakePayloadLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakePayloadLibraryStatus(uiState)
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_scope_cli)
            !uiState.fakePayloadLibraryControlsRelevant -> stringResource(R.string.fake_payload_library_scope_protocols_disabled)
            uiState.httpFakeProfileActiveInStrategy && uiState.udpFakeProfileActiveInStrategy ->
                stringResource(R.string.fake_payload_library_scope_tcp_and_udp_live)
            uiState.httpFakeProfileActiveInStrategy || uiState.tlsFakeProfileActiveInStrategy ->
                stringResource(R.string.fake_payload_library_scope_tcp_live)
            uiState.udpFakeProfileActiveInStrategy -> stringResource(R.string.fake_payload_library_scope_udp_live)
            uiState.hasHostFake -> stringResource(R.string.fake_payload_library_scope_hostfake_only)
            else -> stringResource(R.string.fake_payload_library_scope_active)
        }
    val badges =
        buildList {
            if (uiState.hasCustomFakePayloadProfiles) {
                add(stringResource(R.string.fake_payload_library_badge_custom) to SummaryCapsuleTone.Active)
            } else {
                add(stringResource(R.string.fake_payload_library_badge_default) to SummaryCapsuleTone.Neutral)
            }
            if (uiState.isFake) {
                add(stringResource(R.string.fake_payload_library_badge_tcp_fake_live) to SummaryCapsuleTone.Active)
            } else if (uiState.hasHostFake) {
                add(stringResource(R.string.fake_payload_library_badge_hostfake_only) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasUdpFakeBurst) {
                add(
                    stringResource(R.string.fake_payload_library_badge_udp_burst, uiState.udpFakeCount) to
                        SummaryCapsuleTone.Active,
                )
            }
            if (uiState.quicFakeProfileActive) {
                add(stringResource(R.string.fake_payload_library_badge_quic_separate) to SummaryCapsuleTone.Info)
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
                label = stringResource(R.string.fake_payload_library_summary_label_http),
                value = formatHttpFakeProfileLabel(uiState.httpFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_tls),
                value = formatTlsFakeProfileLabel(uiState.tlsFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_udp),
                value = formatUdpFakeProfileLabel(uiState.udpFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_scope),
                value = scopeSummary,
            )
        }
        if (uiState.canResetFakePayloadLibrary) {
            RipDpiButton(
                text = stringResource(R.string.fake_payload_library_reset_action),
                onClick = onResetFakePayloadLibrary,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun FakePayloadProfileCard(
    title: String,
    description: String,
    profileLabel: String,
    statusLabel: String,
    statusTone: StatusIndicatorTone,
    badges: List<Pair<String, SummaryCapsuleTone>>,
    appliesSummary: String,
    interactionSummary: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    setting: AdvancedOptionSetting,
    onSelected: (AdvancedOptionSetting, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = statusTone,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_current),
                value = profileLabel,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_when_used),
                value = appliesSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_interaction),
                value = interactionSummary,
            )
        }
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = { selectedValue -> onSelected(setting, selectedValue) },
            enabled = enabled,
        )
    }
}

@Composable
private fun rememberFakePayloadLibraryStatus(uiState: SettingsUiState): FakePayloadLibraryStatusContent =
    when {
        uiState.enableCmdSettings ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_cli_title),
                body = stringResource(R.string.fake_payload_library_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakePayloadLibraryControlsRelevant ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_protocols_disabled_title),
                body = stringResource(R.string.fake_payload_library_protocols_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasCustomFakePayloadProfiles ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_restart_title),
                body = stringResource(R.string.fake_payload_library_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomFakePayloadProfiles ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_custom_title),
                body = stringResource(R.string.fake_payload_library_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_default_title),
                body = stringResource(R.string.fake_payload_library_default_body),
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
                    tlsPreludeMode = TlsPreludeModeDisabled,
                    tlsPreludeStepCount = 0,
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
            hostPackCatalog = previewHostPackCatalog(source = "bundled"),
            notice = null,
            onBack = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
            onApplyHostPackPreset = { _, _, _ -> },
            onRefreshHostPackCatalog = {},
            onForgetLearnedHosts = {},
            onClearRememberedNetworks = {},
            onSaveActivationRange = { _, _, _ -> },
            onResetAdaptiveSplit = {},
            onResetAdaptiveFakeTtlProfile = {},
            onResetActivationWindow = {},
            onResetHttpParserEvasions = {},
            onResetFakePayloadLibrary = {},
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
                    tlsPreludeMode = TcpChainStepKind.TlsRandRec.wireName,
                    tlsPreludeStepCount = 1,
                    tlsRandRecFragmentCount = 5,
                    tlsRandRecMinFragmentSize = 24,
                    tlsRandRecMaxFragmentSize = 48,
                    udpFakeCount = 1,
                    hostsMode = "blacklist",
                    hostsBlacklist = "example.com\ncdn.example.net",
                    hostAutolearnStorePresent = true,
                    hostMixedCase = true,
                    domainMixedCase = true,
                    hostRemoveSpaces = false,
                ),
            hostPackCatalog =
                previewHostPackCatalog(
                    source = HostPackCatalogSourceDownloaded,
                    lastFetchedAtEpochMillis = 1_741_765_600_000,
                ),
            notice = null,
            onBack = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
            onApplyHostPackPreset = { _, _, _ -> },
            onRefreshHostPackCatalog = {},
            onForgetLearnedHosts = {},
            onClearRememberedNetworks = {},
            onSaveActivationRange = { _, _, _ -> },
            onResetAdaptiveSplit = {},
            onResetAdaptiveFakeTtlProfile = {},
            onResetActivationWindow = {},
            onResetHttpParserEvasions = {},
            onResetFakePayloadLibrary = {},
            onResetFakeTlsProfile = {},
        )
    }
}
