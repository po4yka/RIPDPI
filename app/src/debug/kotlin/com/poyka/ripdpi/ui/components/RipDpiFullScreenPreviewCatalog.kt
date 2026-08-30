package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.HistoryConnectionFiltersUiModel
import com.poyka.ripdpi.activities.HistoryConnectionRowUiModel
import com.poyka.ripdpi.activities.HistoryConnectionsUiModel
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.HomeModeSummaryFacet
import com.poyka.ripdpi.activities.HostAutolearnUiState
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.HttpParserUiState
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogsUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.ProxyNetworkUiState
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.activities.TlsPreludeUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.screens.config.ConfigScreen
import com.poyka.ripdpi.ui.screens.config.ModeEditorChainBlockEditor
import com.poyka.ripdpi.ui.screens.config.NoOpModeEditorActions
import com.poyka.ripdpi.ui.screens.customization.AboutScreen
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationScreen
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsScreen
import com.poyka.ripdpi.ui.screens.history.HistoryScreen
import com.poyka.ripdpi.ui.screens.home.HomeScreen
import com.poyka.ripdpi.ui.screens.logs.LogsScreen
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptScreen
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptStage
import com.poyka.ripdpi.ui.screens.permissions.VpnPermissionDialog
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsActions
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsScreen
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyScreen
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigScreen
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigScreenState
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigSource
import com.poyka.ripdpi.ui.screens.settings.TlsPreludeModeDisabled
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

// -- Home state variants --------------------------------------------------------

@Composable
internal fun RipDpiHomeDisconnectedPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState = MainUiState(modeCards = homePreviewModeCards()),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Composable
internal fun RipDpiHomeErrorPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState =
                MainUiState(
                    connectionState = ConnectionState.Error,
                    connectionActuator = homePreviewActuatorState(HomeConnectionActuatorStatus.Fault),
                    errorMessage = "Failed to start VPN",
                    configuredMode = Mode.Proxy,
                    proxyIp = "127.0.0.1",
                    proxyPort = "1080",
                    modeCards = homePreviewModeCards(),
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

// -- Config screen --------------------------------------------------------------

@Composable
internal fun RipDpiConfigPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        ConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = Mode.VPN,
                    presets = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
                    draft = AppSettingsSerializer.defaultValue.toConfigDraft(),
                ),
            onModeSelected = {},
            onPresetSelected = {},
            onEditCurrent = {},
            onOpenConfigEditor = {},
            onRetestStrategies = {},
            onPasteServerLink = {},
            onScanServer = {},
        )
    }
}

// -- Advanced Settings ----------------------------------------------------------

@Composable
internal fun RipDpiAdvancedSettingsPreviewScene() {
    RipDpiTheme(themePreference = "light") {
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
                    serviceStatus = AppStatus.Running,
                ),
            hostPackCatalog = HostPackCatalogUiState(snapshot = HostPackCatalogSnapshot()),
            strategyPackCatalog = StrategyPackCatalogUiState(),
            notice = null,
            actions = noopAdvancedSettingsActions(),
        )
    }
}

@Composable
internal fun RipDpiStrategyConfigPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        StrategyConfigScreen(
            state =
                StrategyConfigScreenState(
                    source = StrategyConfigSource.CustomYaml,
                    configText = "",
                    luaPath = "",
                    luaFunction = "",
                    activePath = "Imported YAML",
                    banner = null,
                ),
            onBack = {},
            onSourceChanged = {},
            onConfigTextChanged = {},
            onLuaPathChanged = {},
            onLuaFunctionChanged = {},
            onImport = {},
            onExport = {},
            onSave = {},
            onReload = {},
            onValidateLua = {},
        )
    }
}

private fun noopAdvancedSettingsActions(): AdvancedSettingsActions =
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

// -- Logs -----------------------------------------------------------------------

private val previewLogEntries =
    persistentListOf(
        LogEntry(
            id = "service-started",
            createdAtMs = 1_711_452_264_000,
            timestamp = "12:31:04",
            subsystem = LogSubsystem.Service,
            severity = LogSeverity.Info,
            message = "VPN service started",
            source = "service",
            runtimeId = "runtime-preview",
            isActiveSession = true,
        ),
        LogEntry(
            id = "proxy-dns",
            createdAtMs = 1_711_452_268_000,
            timestamp = "12:31:08",
            subsystem = LogSubsystem.Proxy,
            severity = LogSeverity.Info,
            message = "DNS resolver switched to 1.1.1.1",
            source = "proxy",
            runtimeId = "runtime-preview",
            isActiveSession = true,
        ),
        LogEntry(
            id = "tunnel-fallback",
            createdAtMs = 1_711_452_276_000,
            timestamp = "12:31:16",
            subsystem = LogSubsystem.Tunnel,
            severity = LogSeverity.Warn,
            message = "Fallback resolver is active",
            source = "tunnel",
            runtimeId = "runtime-preview",
            isActiveSession = true,
        ),
        LogEntry(
            id = "diagnostics-failure",
            createdAtMs = 1_711_452_282_000,
            timestamp = "12:31:22",
            subsystem = LogSubsystem.Diagnostics,
            severity = LogSeverity.Error,
            message = "Proxy service failed to start",
            source = "diagnostics",
            diagnosticsSessionId = "scan-preview",
            isActiveSession = false,
        ),
    )

@Composable
internal fun RipDpiLogsPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        LogsScreen(
            uiState = LogsUiState(logs = previewLogEntries),
            onRefresh = {},
            onToggleSubsystemFilter = {},
            onToggleSeverityFilter = {},
            onAutoScrollChanged = {},
            onActiveSessionOnlyChanged = {},
            onClearLogs = {},
            onSaveLogs = {},
            onShareSupportBundle = {},
        )
    }
}

@Composable
internal fun RipDpiLogsEmptyDarkPreviewScene() {
    RipDpiTheme(themePreference = "dark") {
        LogsScreen(
            uiState = LogsUiState(),
            onRefresh = {},
            onToggleSubsystemFilter = {},
            onToggleSeverityFilter = {},
            onAutoScrollChanged = {},
            onActiveSessionOnlyChanged = {},
            onClearLogs = {},
            onSaveLogs = {},
            onShareSupportBundle = {},
        )
    }
}

// -- DNS Settings ---------------------------------------------------------------

@Composable
internal fun RipDpiDnsSettingsPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        DnsSettingsScreen(
            uiState =
                SettingsUiState(
                    ripdpiMode = Mode.VPN.preferenceValue,
                    dns =
                        DnsUiState(
                            dnsIp = "1.1.1.1",
                            dnsSummary = "Plain DNS - 1.1.1.1",
                        ),
                    isVpn = true,
                ),
            onBack = {},
            onModeSelected = {},
            onProtocolSelected = {},
            onResolverSelected = {},
            onSaveCustomDoh = { _, _ -> },
            onSaveCustomDot = { _, _, _, _ -> },
            onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
            onSavePlainDns = {},
            onIpv6Changed = {},
        )
    }
}

// -- About ----------------------------------------------------------------------

@Composable
internal fun RipDpiAboutPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        AboutScreen(onBack = {})
    }
}

// -- Data Transparency ----------------------------------------------------------

@Composable
internal fun RipDpiDataTransparencyPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        DataTransparencyScreen(onBack = {})
    }
}

// -- App Customization ----------------------------------------------------------

@Composable
internal fun RipDpiCustomizationPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        AppCustomizationScreen(
            uiState =
                SettingsUiState(
                    appIconVariant = LauncherIconManager.DefaultIconKey,
                    themedAppIconEnabled = true,
                ),
            onBack = {},
            onIconSelected = {},
            onThemedIconChanged = {},
        )
    }
}

// -- History (connections tab) --------------------------------------------------

@Composable
internal fun RipDpiHistoryPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        HistoryScreen(
            uiState =
                HistoryUiState(
                    connections =
                        HistoryConnectionsUiModel(
                            filters = HistoryConnectionFiltersUiModel(),
                            sessions =
                                listOf(
                                    HistoryConnectionRowUiModel(
                                        id = "1",
                                        title = "VPN session",
                                        subtitle = "Wi-Fi - Home network",
                                        serviceMode = "vpn",
                                        connectionState = "connected",
                                        networkType = "wifi",
                                        startedAtLabel = "Today 14:02",
                                        summary = "18 min, 12 MB transferred",
                                        metrics = persistentListOf(),
                                        tone = DiagnosticsTone.Positive,
                                    ),
                                    HistoryConnectionRowUiModel(
                                        id = "2",
                                        title = "Proxy session",
                                        subtitle = "Mobile - Carrier",
                                        serviceMode = "proxy",
                                        connectionState = "error",
                                        networkType = "mobile",
                                        startedAtLabel = "Yesterday 09:15",
                                        summary = "2 min, connection lost",
                                        metrics = persistentListOf(),
                                        tone = DiagnosticsTone.Negative,
                                    ),
                                ).toImmutableList(),
                        ),
                ),
            onBack = {},
            onRefresh = {},
            onSelectSection = {},
            onConnectionModeFilter = {},
            onConnectionStatusFilter = {},
            onConnectionSearch = {},
            onClearConnectionFilters = {},
            onSelectConnection = {},
            onDismissConnectionDetail = {},
            onDiagnosticsPathFilter = {},
            onDiagnosticsStatusFilter = {},
            onDiagnosticsSearch = {},
            onClearDiagnosticsFilters = {},
            onSelectDiagnosticsSession = {},
            onDismissDiagnosticsDetail = {},
            onToggleEventFilter = { _, _ -> },
            onEventSearch = {},
            onClearEventFilters = {},
            onEventAutoScroll = {},
            onSelectEvent = {},
            onDismissEventDetail = {},
        )
    }
}

// -- Biometric prompt -----------------------------------------------------------

@Composable
internal fun RipDpiBiometricPromptPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        BiometricPromptScreen(
            uiState =
                SettingsUiState(
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                ),
            stage = BiometricPromptStage.Biometric,
            pin = "",
            pinError = null,
            onAuthenticate = {},
            onUseBackupPin = {},
            onBackToBiometric = {},
            onPinChanged = {},
            onSubmitPin = {},
        )
    }
}

@Composable
internal fun RipDpiBiometricPinPreviewScene() {
    RipDpiTheme(themePreference = "dark") {
        BiometricPromptScreen(
            uiState =
                SettingsUiState(
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                ),
            stage = BiometricPromptStage.Pin,
            pin = "0000",
            pinError = "The backup PIN does not match.",
            onAuthenticate = {},
            onUseBackupPin = {},
            onBackToBiometric = {},
            onPinChanged = {},
            onSubmitPin = {},
        )
    }
}

// -- VPN Permission dialog ------------------------------------------------------

@Composable
internal fun RipDpiVpnPermissionDialogPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        VpnPermissionDialog(
            uiState = MainUiState(),
            onDismiss = {},
            onContinue = {},
        )
    }
}

// -- Detection Check ------------------------------------------------------------

@Composable
internal fun RipDpiDetectionCheckPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        com.poyka.ripdpi.ui.screens.detection.DetectionCheckScreen(
            uiState =
                com.poyka.ripdpi.ui.screens.detection.DetectionCheckUiState(
                    stealthScore = 78,
                    stealthLabel = "Good",
                ),
            onStart = {},
            onStop = {},
            onBack = {},
            onDismissOnboarding = {},
            onApplyFixes = {},
            onPrivacyModeChange = {},
            onReloadCommunityStats = {},
            onRequestPermissions = {},
        )
    }
}

@Composable
internal fun RipDpiDetectionCheckDarkPreviewScene() {
    RipDpiTheme(themePreference = "dark") {
        com.poyka.ripdpi.ui.screens.detection.DetectionCheckScreen(
            uiState =
                com.poyka.ripdpi.ui.screens.detection.DetectionCheckUiState(
                    stealthScore = 42,
                    stealthLabel = "Moderate",
                ),
            onStart = {},
            onStop = {},
            onBack = {},
            onDismissOnboarding = {},
            onApplyFixes = {},
            onPrivacyModeChange = {},
            onReloadCommunityStats = {},
            onRequestPermissions = {},
        )
    }
}

// -- Mode Editor ----------------------------------------------------------------

@Composable
internal fun RipDpiModeEditorPreviewScene() {
    val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
    com.poyka.ripdpi.ui.screens.config.ModeEditorScreenWithNoOpCallbacks(
        uiState =
            ConfigUiState(
                activeMode = Mode.VPN,
                presets = buildConfigPresets(draft),
                draft = draft,
            ),
    )
}

@Composable
internal fun RipDpiModeEditorInvalidChainPreviewScene() {
    val draft = AppSettingsSerializer.defaultValue.toConfigDraft().copy(chainDsl = "[tcp]\nsplit host\ntlsrec sniext+1")
    RipDpiTheme(themePreference = "light") {
        Column(
            modifier =
                androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(RipDpiThemeTokens.layout.horizontalPadding),
        ) {
            ModeEditorChainBlockEditor(
                draft = draft,
                uiState = ConfigUiState(activeMode = Mode.VPN, presets = buildConfigPresets(draft), draft = draft),
                actions = NoOpModeEditorActions,
            )
        }
    }
}

// -- Home high contrast ---------------------------------------------------------

@Composable
internal fun RipDpiHomeHighContrastPreviewScene() {
    RipDpiTheme(
        themePreference = "light",
        contrastLevel = com.poyka.ripdpi.ui.theme.RipDpiContrastLevel.High,
    ) {
        HomeScreen(
            uiState =
                MainUiState(
                    appStatus = AppStatus.Running,
                    connectionState = ConnectionState.Connected,
                    connectionActuator = homePreviewActuatorState(HomeConnectionActuatorStatus.Locked),
                    modeCards = homePreviewModeCards(activeMode = HomeMode.RemoteVpn),
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

internal fun homePreviewModeCards(
    activeMode: HomeMode? = null,
    loadingMode: HomeMode? = null,
) = persistentListOf(
    homePreviewModeCard(
        mode = HomeMode.LocalDpiBypass,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
    homePreviewModeCard(
        mode = HomeMode.RemoteVpn,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
    homePreviewModeCard(
        mode = HomeMode.Diagnostic,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
)

private fun homePreviewModeCard(
    mode: HomeMode,
    activeMode: HomeMode?,
    loadingMode: HomeMode?,
): HomeModeCardUiState {
    val active = mode == activeMode
    val loading = mode == loadingMode
    return HomeModeCardUiState(
        mode = mode,
        title =
            when (mode) {
                HomeMode.LocalDpiBypass -> "Local DPI Bypass"
                HomeMode.RemoteVpn -> "VPN with Remote Server"
                HomeMode.Diagnostic -> "Network Diagnostic"
            },
        primaryLabel =
            when (mode) {
                HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                HomeMode.RemoteVpn -> "relay.example"
                HomeMode.Diagnostic -> if (loading) "Stage 2 of 4 - Testing TCP" else "No analysis yet"
            },
        summaryFacets =
            if (mode == HomeMode.LocalDpiBypass) {
                persistentListOf(
                    HomeModeSummaryFacet(label = "Strategy", value = "tcp: split(host+1)"),
                    HomeModeSummaryFacet(label = "DNS", value = "Encrypted DNS · AdGuard DNS (DoH)"),
                )
            } else {
                persistentListOf()
            },
        secondaryLabel = if (active) "Connected 00:47:00" else null,
        statusLine =
            when {
                loading -> "Running"
                active -> "Connected 00:47:00"
                else -> "Inactive"
            },
        primaryActionLabel =
            when {
                mode == HomeMode.Diagnostic -> "Run Scan"
                active -> "Disable"
                else -> "Enable"
            },
        configureLabel = "Configure",
        primaryActionEnabled = !loading,
        isActive = active,
        isLoading = loading,
    )
}
