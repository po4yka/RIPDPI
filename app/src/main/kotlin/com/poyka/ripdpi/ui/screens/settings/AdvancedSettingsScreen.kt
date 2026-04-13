package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.FakeTransportUiState
import com.poyka.ripdpi.activities.HostAutolearnUiState
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.HttpParserUiState
import com.poyka.ripdpi.activities.ProxyNetworkUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.TlsPreludeUiState
import com.poyka.ripdpi.activities.WarpUiState
import com.poyka.ripdpi.data.FakeOrderAllFakesFirst
import com.poyka.ripdpi.data.FakeOrderAllRealsFirst
import com.poyka.ripdpi.data.FakeOrderDefault
import com.poyka.ripdpi.data.FakeOrderInterleaveRealFirst
import com.poyka.ripdpi.data.FakeSeqModeDuplicate
import com.poyka.ripdpi.data.FakeSeqModeSequential
import com.poyka.ripdpi.data.HostPackApplyModeMerge
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    HttpHostPad,
    HttpMethodEol,
    HttpUnixEol,
    HttpMethodSpace,
    HttpHostExtraSpace,
    HttpHostTab,
    TlsrecEnabled,
    QuicSupportV1,
    QuicSupportV2,
    QuicBindLowPort,
    QuicMigrateAfterHandshake,
    StrategyEvolution,
    WarpEnabled,
    WarpBuiltInRulesEnabled,
    WarpScannerEnabled,
    WarpAmneziaEnabled,
    HostAutolearnEnabled,
    NetworkStrategyMemoryEnabled,
    AdaptiveFallbackEnabled,
    AdaptiveFallbackTorst,
    AdaptiveFallbackTlsErr,
    AdaptiveFallbackHttpRedirect,
    AdaptiveFallbackConnectFailure,
    AdaptiveFallbackAutoSort,
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
    AdaptiveFallbackCacheTtlSeconds,
    AdaptiveFallbackCachePrefixV4,
    EvolutionEpsilon,
    EntropyPaddingTargetPermil,
    EntropyPaddingMax,
    ShannonEntropyTargetPermil,
    HostsBlacklist,
    HostsWhitelist,
    WarpRouteHosts,
    WarpManualEndpointHost,
    WarpManualEndpointIpv4,
    WarpManualEndpointIpv6,
    WarpManualEndpointPort,
    WarpScannerParallelism,
    WarpScannerMaxRttMs,
    WarpAmneziaJc,
    WarpAmneziaJmin,
    WarpAmneziaJmax,
    WarpAmneziaH1,
    WarpAmneziaH2,
    WarpAmneziaH3,
    WarpAmneziaH4,
    WarpAmneziaS1,
    WarpAmneziaS2,
    WarpAmneziaS3,
    WarpAmneziaS4,
}

internal enum class AdvancedOptionSetting {
    DesyncMethod,
    AdaptiveSplitPreset,
    AdaptiveFakeTtlMode,
    TlsPreludeMode,
    FakeOrder,
    FakeSeqMode,
    TcpFlagsSet,
    TcpFlagsUnset,
    TcpFlagsOrigSet,
    TcpFlagsOrigUnset,
    IpIdMode,
    HttpFakeProfile,
    FakeTlsBase,
    FakeTlsSniMode,
    TlsFakeProfile,
    HostsMode,
    WarpRouteMode,
    WarpEndpointSelectionMode,
    WarpAmneziaPreset,
    QuicInitialMode,
    TlsFingerprintProfile,
    EntropyMode,
    UdpFakeProfile,
    QuicFakeProfile,
    AppRoutingPolicyMode,
    DhtMitigationMode,
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

internal data class AdvancedSettingsActions(
    val onBack: () -> Unit,
    val onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    val onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    val onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    val onApplyHostPackPreset: (HostPackPreset, String, String) -> Unit,
    val onRefreshHostPackCatalog: () -> Unit,
    val onForgetLearnedHosts: () -> Unit,
    val onClearRememberedNetworks: () -> Unit,
    val onWsTunnelModeChanged: (String) -> Unit,
    val onRotateTelemetrySalt: () -> Unit,
    val onSaveActivationRange: (ActivationWindowDimension, Long?, Long?) -> Unit,
    val onResetAdaptiveSplit: () -> Unit,
    val onResetAdaptiveFakeTtlProfile: () -> Unit,
    val onResetActivationWindow: () -> Unit,
    val onResetHttpParserEvasions: () -> Unit,
    val onResetFakePayloadLibrary: () -> Unit,
    val onResetFakeTlsProfile: () -> Unit,
    val onRoutingPolicyModeSelected: (String) -> Unit,
    val onDhtMitigationModeSelected: (String) -> Unit,
    val onAntiCorrelationEnabledChanged: (Boolean) -> Unit,
    val onAppRoutingPresetEnabledChanged: (String, Boolean) -> Unit,
)

private data class AdvancedSettingsContentState(
    val visualEditorEnabled: Boolean,
    val hostPackApplyControlsEnabled: Boolean,
    val showHostFakeSection: Boolean,
    val showSeqOverlapSection: Boolean,
    val showFakeApproxSection: Boolean,
    val showFakeOrderingSection: Boolean,
    val showQuicFakeSection: Boolean,
    val showFakePayloadLibrary: Boolean,
    val showAdaptiveFakeTtlSection: Boolean,
    val showFakeTlsSection: Boolean,
    val fakeTlsBaseOptions: List<RipDpiDropdownOption<String>>,
    val httpFakeProfileOptions: List<RipDpiDropdownOption<String>>,
    val fakeTlsSniModeOptions: List<RipDpiDropdownOption<String>>,
    val tlsFakeProfileOptions: List<RipDpiDropdownOption<String>>,
    val fakeOrderOptions: List<RipDpiDropdownOption<String>>,
    val fakeSeqModeOptions: List<RipDpiDropdownOption<String>>,
    val ipIdModeOptions: List<RipDpiDropdownOption<String>>,
    val hostsOptions: List<RipDpiDropdownOption<String>>,
    val warpRouteModeOptions: List<RipDpiDropdownOption<String>>,
    val warpEndpointSelectionOptions: List<RipDpiDropdownOption<String>>,
    val warpAmneziaPresetOptions: List<RipDpiDropdownOption<String>>,
    val quicModeOptions: List<RipDpiDropdownOption<String>>,
    val tlsFingerprintOptions: List<RipDpiDropdownOption<String>>,
    val entropyModeOptions: List<RipDpiDropdownOption<String>>,
    val udpFakeProfileOptions: List<RipDpiDropdownOption<String>>,
    val adaptiveSplitPresetOptions: List<AdaptiveSplitPresetUiModel>,
    val adaptiveFakeTtlModeOptions: List<AdaptiveFakeTtlModeUiModel>,
)

@Composable
internal fun AdvancedSettingsScreen(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    notice: AdvancedNotice?,
    actions: AdvancedSettingsActions,
    modifier: Modifier = Modifier,
) {
    val contentState = rememberAdvancedSettingsContentState(uiState)
    var pendingHostPackId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHostPackTargetMode by rememberSaveable { mutableStateOf(defaultHostPackTargetMode(uiState)) }
    var selectedHostPackApplyMode by rememberSaveable { mutableStateOf(HostPackApplyDialogDefaultMode) }

    val pendingHostPack = pendingHostPackId?.let { id -> hostPackCatalog.presets.find { it.id == id } }

    pendingHostPack?.let { preset ->
        HostPackApplyDialog(
            preset = preset,
            targetMode = selectedHostPackTargetMode,
            applyMode = selectedHostPackApplyMode,
            onTargetModeChanged = { selectedHostPackTargetMode = it },
            onApplyModeChanged = { selectedHostPackApplyMode = it },
            onDismiss = { pendingHostPackId = null },
            onApply = {
                actions.onApplyHostPackPreset(
                    preset,
                    selectedHostPackTargetMode,
                    selectedHostPackApplyMode,
                )
                pendingHostPackId = null
            },
        )
    }

    AdvancedSettingsContent(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        notice = notice,
        actions = actions,
        contentState = contentState,
        pendingHostPack = pendingHostPack,
        onPresetSelected = { preset ->
            selectedHostPackTargetMode = defaultHostPackTargetMode(uiState)
            selectedHostPackApplyMode = HostPackApplyDialogDefaultMode
            pendingHostPackId = preset.id
        },
        modifier = modifier,
    )
}

@Composable
private fun rememberAdvancedSettingsContentState(uiState: SettingsUiState): AdvancedSettingsContentState =
    AdvancedSettingsContentState(
        visualEditorEnabled = !uiState.enableCmdSettings,
        hostPackApplyControlsEnabled = hostPackApplyEnabled(uiState),
        showHostFakeSection = uiState.showHostFakeProfile,
        showSeqOverlapSection = uiState.showSeqOverlapProfile,
        showFakeApproxSection = uiState.showFakeApproximationProfile,
        showFakeOrderingSection = uiState.showFakeOrderingProfile,
        showQuicFakeSection = uiState.showQuicFakeProfile,
        showFakePayloadLibrary = uiState.showFakePayloadLibrary,
        showAdaptiveFakeTtlSection = uiState.showAdaptiveFakeTtlProfile,
        showFakeTlsSection =
            uiState.desyncHttpsEnabled ||
                uiState.isFake ||
                uiState.usesSeqOverlapFakeProfile ||
                uiState.fake.hasCustomFakeTlsProfile ||
                uiState.enableCmdSettings,
        fakeTlsBaseOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.fake_tls_base_modes,
                valueArrayRes = R.array.fake_tls_base_modes_entries,
            ),
        httpFakeProfileOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.http_fake_profiles,
                valueArrayRes = R.array.http_fake_profiles_entries,
            ),
        fakeTlsSniModeOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.fake_tls_sni_modes,
                valueArrayRes = R.array.fake_tls_sni_modes_entries,
            ),
        tlsFakeProfileOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.tls_fake_profiles,
                valueArrayRes = R.array.tls_fake_profiles_entries,
            ),
        fakeOrderOptions =
            listOf(
                RipDpiDropdownOption(FakeOrderDefault, "Altorder 0"),
                RipDpiDropdownOption(FakeOrderAllFakesFirst, "Altorder 1"),
                RipDpiDropdownOption(FakeOrderInterleaveRealFirst, "Altorder 2"),
                RipDpiDropdownOption(FakeOrderAllRealsFirst, "Altorder 3"),
            ),
        fakeSeqModeOptions =
            listOf(
                RipDpiDropdownOption(FakeSeqModeDuplicate, "Duplicate"),
                RipDpiDropdownOption(FakeSeqModeSequential, "Sequential"),
            ),
        ipIdModeOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.ip_id_modes,
                valueArrayRes = R.array.ip_id_modes_entries,
            ),
        hostsOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.ripdpi_hosts_modes,
                valueArrayRes = R.array.ripdpi_hosts_modes_entries,
            ),
        warpRouteModeOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.warp_route_modes,
                valueArrayRes = R.array.warp_route_modes_entries,
            ),
        warpEndpointSelectionOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.warp_endpoint_selection_modes,
                valueArrayRes = R.array.warp_endpoint_selection_modes_entries,
            ),
        warpAmneziaPresetOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.warp_amnezia_presets,
                valueArrayRes = R.array.warp_amnezia_presets_entries,
            ),
        quicModeOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.quic_initial_modes,
                valueArrayRes = R.array.quic_initial_modes_entries,
            ),
        tlsFingerprintOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.tls_fingerprint_profiles,
                valueArrayRes = R.array.tls_fingerprint_profiles_entries,
            ),
        entropyModeOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.entropy_modes,
                valueArrayRes = R.array.entropy_modes_entries,
            ),
        udpFakeProfileOptions =
            rememberSettingsOptions(
                labelArrayRes = R.array.udp_fake_profiles,
                valueArrayRes = R.array.udp_fake_profiles_entries,
            ),
        adaptiveSplitPresetOptions = rememberAdaptiveSplitPresetOptions(uiState),
        adaptiveFakeTtlModeOptions = rememberAdaptiveFakeTtlModeOptions(uiState),
    )

@Composable
private fun AdvancedSettingsContent(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    notice: AdvancedNotice?,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.AdvancedSettings))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_advanced_settings),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = actions.onBack,
    ) {
        advancedSettingsBannerItems(uiState, notice)
        advancedSettingsPrimarySections(uiState, actions, contentState)
        advancedSettingsSecondarySections(
            uiState = uiState,
            hostPackCatalog = hostPackCatalog,
            actions = actions,
            contentState = contentState,
            pendingHostPack = pendingHostPack,
            onPresetSelected = onPresetSelected,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsBannerItems(
    uiState: SettingsUiState,
    notice: AdvancedNotice?,
) {
    if (uiState.enableCmdSettings) {
        item(key = "advanced_settings_warning") {
            WarningBanner(
                title = stringResource(R.string.config_cli_banner_title),
                message = stringResource(R.string.config_cli_banner_body),
                tone = WarningBannerTone.Restricted,
                testTag = RipDpiTestTags.AdvancedCommandLineWarning,
            )
        }
    }
    notice?.let {
        item(key = "advanced_settings_notice") {
            WarningBanner(
                title = it.title,
                message = it.message,
                tone = it.tone,
                testTag = RipDpiTestTags.AdvancedNoticeBanner,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsPrimarySections(
    uiState: SettingsUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
) {
    diagnosticsHistorySection(
        uiState = uiState,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
        onRotateTelemetrySalt = actions.onRotateTelemetrySalt,
    )
    commandLineOverridesSection(
        uiState = uiState,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
    )
    proxySection(uiState, contentState.visualEditorEnabled, actions.onToggleChanged, actions.onTextConfirmed)
    desyncSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        showHostFakeSection = contentState.showHostFakeSection,
        showSeqOverlapSection = contentState.showSeqOverlapSection,
        showFakeApproxSection = contentState.showFakeApproxSection,
        showFakeOrderingSection = contentState.showFakeOrderingSection,
        showAdaptiveFakeTtlSection = contentState.showAdaptiveFakeTtlSection,
        showFakePayloadLibrary = contentState.showFakePayloadLibrary,
        showFakeTlsSection = contentState.showFakeTlsSection,
        adaptiveSplitPresetOptions = contentState.adaptiveSplitPresetOptions,
        adaptiveFakeTtlModeOptions = contentState.adaptiveFakeTtlModeOptions,
        httpFakeProfileOptions = contentState.httpFakeProfileOptions,
        fakeTlsBaseOptions = contentState.fakeTlsBaseOptions,
        fakeTlsSniModeOptions = contentState.fakeTlsSniModeOptions,
        tlsFakeProfileOptions = contentState.tlsFakeProfileOptions,
        fakeOrderOptions = contentState.fakeOrderOptions,
        fakeSeqModeOptions = contentState.fakeSeqModeOptions,
        ipIdModeOptions = contentState.ipIdModeOptions,
        udpFakeProfileOptions = contentState.udpFakeProfileOptions,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
        onOptionSelected = actions.onOptionSelected,
        onResetAdaptiveSplit = actions.onResetAdaptiveSplit,
        onResetAdaptiveFakeTtlProfile = actions.onResetAdaptiveFakeTtlProfile,
        onResetFakePayloadLibrary = actions.onResetFakePayloadLibrary,
        onResetFakeTlsProfile = actions.onResetFakeTlsProfile,
    )
    activationWindowSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onResetActivationWindow = actions.onResetActivationWindow,
        onSaveActivationRange = actions.onSaveActivationRange,
    )
    protocolsSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsSecondarySections(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
) {
    advancedSettingsProtectionSections(uiState, actions, contentState)
    advancedSettingsProtocolSections(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        actions = actions,
        contentState = contentState,
        pendingHostPack = pendingHostPack,
        onPresetSelected = onPresetSelected,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsProtectionSections(
    uiState: SettingsUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
) {
    hostAutolearnSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
        onForgetLearnedHosts = actions.onForgetLearnedHosts,
    )
    networkStrategyMemorySection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onClearRememberedNetworks = actions.onClearRememberedNetworks,
    )
    wsTunnelSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onWsTunnelModeChanged = actions.onWsTunnelModeChanged,
    )
    adaptiveFallbackSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onTextConfirmed = actions.onTextConfirmed,
    )
    routingProtectionSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onRoutingPolicyModeSelected = actions.onRoutingPolicyModeSelected,
        onDhtMitigationModeSelected = actions.onDhtMitigationModeSelected,
        onAntiCorrelationEnabledChanged = actions.onAntiCorrelationEnabledChanged,
        onAppRoutingPresetEnabledChanged = actions.onAppRoutingPresetEnabledChanged,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedSettingsProtocolSections(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    actions: AdvancedSettingsActions,
    contentState: AdvancedSettingsContentState,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
) {
    httpParserSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onToggleChanged = actions.onToggleChanged,
        onResetHttpParserEvasions = actions.onResetHttpParserEvasions,
    )
    httpsSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        onTextConfirmed = actions.onTextConfirmed,
        onOptionSelected = actions.onOptionSelected,
    )
    udpSection()
    quicSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        showQuicFakeSection = contentState.showQuicFakeSection,
        quicModeOptions = contentState.quicModeOptions,
        onToggleChanged = actions.onToggleChanged,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    detectionResistanceSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        tlsFingerprintOptions = contentState.tlsFingerprintOptions,
        entropyModeOptions = contentState.entropyModeOptions,
        onToggleChanged = actions.onToggleChanged,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    warpSection(
        uiState = uiState,
        visualEditorEnabled = contentState.visualEditorEnabled,
        routeModeOptions = contentState.warpRouteModeOptions,
        endpointSelectionOptions = contentState.warpEndpointSelectionOptions,
        amneziaPresetOptions = contentState.warpAmneziaPresetOptions,
        onToggleChanged = actions.onToggleChanged,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
    hostsSection(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        visualEditorEnabled = contentState.visualEditorEnabled,
        hostPackApplyControlsEnabled = contentState.hostPackApplyControlsEnabled,
        hostsOptions = contentState.hostsOptions,
        pendingHostPack = pendingHostPack,
        onPresetSelected = onPresetSelected,
        onRefreshHostPackCatalog = actions.onRefreshHostPackCatalog,
        onOptionSelected = actions.onOptionSelected,
        onTextConfirmed = actions.onTextConfirmed,
    )
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun AdvancedSettingsScreenPreview() {
    RipDpiTheme {
        AdvancedSettingsScreen(
            uiState =
                SettingsUiState(
                    enableCmdSettings = false,
                    cmdArgs = "",
                    proxy =
                        ProxyNetworkUiState(
                            proxyIp = "127.0.0.1",
                            proxyPort = 1080,
                            maxConnections = 512,
                            bufferSize = 16_384,
                            noDomain = false,
                            tcpFastOpen = true,
                        ),
                    desync =
                        DesyncCoreUiState(
                            desyncMethod = "disorder",
                            splitMarker = "host+2",
                            defaultTtl = 8,
                            customTtl = true,
                            udpFakeCount = 0,
                        ),
                    fake = FakeTransportUiState(dropSack = false),
                    desyncHttp = true,
                    desyncHttps = true,
                    desyncUdp = false,
                    httpParser =
                        HttpParserUiState(
                            hostMixedCase = true,
                        ),
                    tlsPrelude =
                        TlsPreludeUiState(
                            tlsrecEnabled = false,
                            tlsPreludeMode = TlsPreludeModeDisabled,
                            tlsPreludeStepCount = 0,
                        ),
                    warp =
                        WarpUiState(
                            enabled = true,
                            routeMode = "rules",
                            routeHosts = "chat.openai.com\nclaude.ai",
                            scannerEnabled = true,
                            scannerParallelism = 8,
                            scannerMaxRttMs = 900,
                        ),
                    hostsMode = "disable",
                    autolearn =
                        HostAutolearnUiState(
                            hostAutolearnEnabled = true,
                            hostAutolearnRuntimeEnabled = true,
                            hostAutolearnStorePresent = true,
                            hostAutolearnLearnedHostCount = 18,
                            hostAutolearnPenalizedHostCount = 2,
                            hostAutolearnLastHost = "video.example.org",
                            hostAutolearnLastGroup = 2,
                            hostAutolearnLastAction = "host_promoted",
                        ),
                    serviceStatus = com.poyka.ripdpi.data.AppStatus.Running,
                ),
            hostPackCatalog = previewHostPackCatalog(source = "bundled"),
            notice = null,
            actions = previewAdvancedSettingsActions(),
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun AdvancedSettingsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        AdvancedSettingsScreen(
            uiState = previewAdvancedSettingsDarkUiState(),
            hostPackCatalog =
                previewHostPackCatalog(
                    source = HostPackCatalogSourceDownloaded,
                    lastFetchedAtEpochMillis = 1_741_765_600_000,
                ),
            notice = null,
            actions = previewAdvancedSettingsActions(),
        )
    }
}

private fun previewAdvancedSettingsDarkUiState(): SettingsUiState =
    SettingsUiState(
        enableCmdSettings = true,
        cmdArgs = "--fake --split 2",
        proxy =
            ProxyNetworkUiState(
                proxyIp = "127.0.0.1",
                proxyPort = 1080,
                maxConnections = 256,
                bufferSize = 8192,
            ),
        desync =
            DesyncCoreUiState(
                desyncMethod = "fake",
                splitMarker = "host+1",
                defaultTtl = 8,
                customTtl = true,
                udpFakeCount = 1,
            ),
        fake =
            FakeTransportUiState(
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
            ),
        desyncHttp = true,
        desyncHttps = true,
        desyncUdp = true,
        tlsPrelude =
            TlsPreludeUiState(
                tlsrecEnabled = true,
                tlsrecMarker = "sniext+4",
                tlsPreludeMode = TcpChainStepKind.TlsRandRec.wireName,
                tlsPreludeStepCount = 1,
                tlsRandRecFragmentCount = 5,
                tlsRandRecMinFragmentSize = 24,
                tlsRandRecMaxFragmentSize = 48,
            ),
        hostsMode = "blacklist",
        hostsBlacklist = "example.com\ncdn.example.net",
        autolearn = HostAutolearnUiState(hostAutolearnStorePresent = true),
        httpParser =
            HttpParserUiState(
                hostMixedCase = true,
                domainMixedCase = true,
            ),
    )

private fun previewAdvancedSettingsActions(): AdvancedSettingsActions =
    AdvancedSettingsActions(
        onBack = {},
        onToggleChanged = { _, _ -> },
        onTextConfirmed = { _, _ -> },
        onOptionSelected = { _, _ -> },
        onApplyHostPackPreset = { _, _, _ -> },
        onRefreshHostPackCatalog = {},
        onForgetLearnedHosts = {},
        onClearRememberedNetworks = {},
        onWsTunnelModeChanged = {},
        onRotateTelemetrySalt = {},
        onSaveActivationRange = { _, _, _ -> },
        onResetAdaptiveSplit = {},
        onResetAdaptiveFakeTtlProfile = {},
        onResetActivationWindow = {},
        onResetHttpParserEvasions = {},
        onResetFakePayloadLibrary = {},
        onResetFakeTlsProfile = {},
        onRoutingPolicyModeSelected = {},
        onDhtMitigationModeSelected = {},
        onAntiCorrelationEnabledChanged = {},
        onAppRoutingPresetEnabledChanged = { _, _ -> },
    )
