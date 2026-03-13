package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.HostPackApplyModeMerge
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
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

        hostAutolearnSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
            onTextConfirmed = onTextConfirmed,
            onForgetLearnedHosts = onForgetLearnedHosts,
        )

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

        udpSection()

        quicSection(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            showQuicFakeSection = showQuicFakeSection,
            quicModeOptions = quicModeOptions,
            onToggleChanged = onToggleChanged,
            onOptionSelected = onOptionSelected,
            onTextConfirmed = onTextConfirmed,
        )

        hostsSection(
            uiState = uiState,
            hostPackCatalog = hostPackCatalog,
            visualEditorEnabled = visualEditorEnabled,
            hostPackApplyControlsEnabled = hostPackApplyControlsEnabled,
            hostsOptions = hostsOptions,
            pendingHostPack = pendingHostPack,
            onPresetSelected = { preset ->
                selectedHostPackTargetMode = defaultHostPackTargetMode(uiState)
                selectedHostPackApplyMode = HostPackApplyDialogDefaultMode
                pendingHostPack = preset
            },
            onRefreshHostPackCatalog = onRefreshHostPackCatalog,
            onOptionSelected = onOptionSelected,
            onTextConfirmed = onTextConfirmed,
        )
    }
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
