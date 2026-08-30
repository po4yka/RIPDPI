package com.poyka.ripdpi.ui.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.FakeTransportUiState
import com.poyka.ripdpi.activities.HostAutolearnUiState
import com.poyka.ripdpi.activities.HttpParserUiState
import com.poyka.ripdpi.activities.ProxyNetworkUiState
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.activities.TlsPreludeUiState
import com.poyka.ripdpi.activities.WarpUiState
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsActions
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsScreen
import com.poyka.ripdpi.ui.screens.settings.TlsPreludeModeDisabled
import com.poyka.ripdpi.ui.screens.settings.previewHostPackCatalog
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme

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
                    httpParser = HttpParserUiState(hostMixedCase = true),
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
            strategyPackCatalog = StrategyPackCatalogUiState(),
            notice = null,
            actions = previewAdvancedSettingsActions(),
        )
    }
}

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
            strategyPackCatalog = StrategyPackCatalogUiState(),
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
        onOpenStrategyConfig = {},
        onOpenBlockcheck = {},
        onOpenAssetProvider = {},
        onOpenRememberedNetworks = {},
        onToggleChanged = { _, _ -> },
        onTextConfirmed = { _, _ -> },
        onOptionSelected = { _, _ -> },
        onApplyHostPackPreset = { _, _, _ -> },
        onRefreshHostPackCatalog = {},
        onRefreshStrategyPackCatalog = {},
        onForgetLearnedHosts = {},
        onClearRememberedNetworks = {},
        onWsTunnelModeChanged = {},
        onSaveWsTunnelWorkerTransport = { _, _, _ -> },
        onClearWsTunnelWorkerTransport = {},
        onRotateSalt = {},
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
