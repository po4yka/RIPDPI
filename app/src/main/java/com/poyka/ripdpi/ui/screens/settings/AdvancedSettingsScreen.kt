package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.HostPackApplyModeMerge
import com.poyka.ripdpi.data.HostPackApplyModeReplace
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.HostPackTargetBlacklist
import com.poyka.ripdpi.data.HostPackTargetWhitelist
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeDisabled
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange

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

        diagnosticsHistorySection(
            uiState = uiState,
            onToggleChanged = onToggleChanged,
            onTextConfirmed = onTextConfirmed,
        )

        commandLineOverridesSection(
            uiState = uiState,
            onToggleChanged = onToggleChanged,
            onTextConfirmed = onTextConfirmed,
        )

        proxySection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
            onTextConfirmed = onTextConfirmed,
        )

        desyncSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            showHostFakeSection = showHostFakeSection,
            showFakeApproxSection = showFakeApproxSection,
            showAdaptiveFakeTtlSection = showAdaptiveFakeTtlSection,
            showFakePayloadLibrary = showFakePayloadLibrary,
            showFakeTlsSection = showFakeTlsSection,
            adaptiveSplitPresetOptions = adaptiveSplitPresetOptions,
            adaptiveFakeTtlModeOptions = adaptiveFakeTtlModeOptions,
            httpFakeProfileOptions = httpFakeProfileOptions,
            fakeTlsBaseOptions = fakeTlsBaseOptions,
            fakeTlsSniModeOptions = fakeTlsSniModeOptions,
            tlsFakeProfileOptions = tlsFakeProfileOptions,
            udpFakeProfileOptions = udpFakeProfileOptions,
            onToggleChanged = onToggleChanged,
            onTextConfirmed = onTextConfirmed,
            onOptionSelected = onOptionSelected,
            onResetAdaptiveSplit = onResetAdaptiveSplit,
            onResetAdaptiveFakeTtlProfile = onResetAdaptiveFakeTtlProfile,
            onResetFakePayloadLibrary = onResetFakePayloadLibrary,
            onResetFakeTlsProfile = onResetFakeTlsProfile,
        )


        activationWindowSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onResetActivationWindow = onResetActivationWindow,
            onSaveActivationRange = onSaveActivationRange,
        )

        protocolsSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
        )

        item(key = "advanced_host_autolearn") {
            AdvancedSettingsSection(title = stringResource(R.string.host_autolearn_section_title)) {
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

        networkStrategyMemorySection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
            onClearRememberedNetworks = onClearRememberedNetworks,
        )

        httpParserSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
            onResetHttpParserEvasions = onResetHttpParserEvasions,
        )

        httpsSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onTextConfirmed = onTextConfirmed,
            onOptionSelected = onOptionSelected,
        )

        item(key = "advanced_udp") {
            AdvancedSettingsSection(title = stringResource(R.string.desync_udp_category)) {
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
            AdvancedSettingsSection(title = stringResource(R.string.quic_initial_section_title)) {
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
            AdvancedSettingsSection(title = stringResource(R.string.ripdpi_hosts_mode_setting)) {
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
