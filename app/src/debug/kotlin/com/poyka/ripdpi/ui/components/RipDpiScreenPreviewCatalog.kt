package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.permissions.PermissionItemUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreen
import com.poyka.ripdpi.ui.screens.home.HomeScreen
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreen
import com.poyka.ripdpi.ui.screens.settings.SettingsScreen
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Duration.Companion.minutes

@Preview(name = "Home Expanded", showBackground = true, widthDp = 1040, heightDp = 920)
@Composable
private fun HomeExpandedPreview() {
    RipDpiHomeExpandedPreviewScene()
}

@Composable
internal fun RipDpiHomeExpandedPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState =
                MainUiState(
                    appStatus = AppStatus.Running,
                    activeMode = Mode.VPN,
                    configuredMode = Mode.VPN,
                    connectionState = ConnectionState.Connected,
                    connectionDuration = 47.minutes,
                    dataTransferred = 54_321_987L,
                    approachSummary =
                        HomeApproachSummaryUiState(
                            title = "TTL split with fake request",
                            verification = "Verified today",
                            successRate = "84% success rate",
                            supportingText =
                                "Stable on restrictive networks and resilient against plain reset injection.",
                        ),
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Composable
internal fun RipDpiHomeCompactPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState =
                MainUiState(
                    appStatus = AppStatus.Running,
                    activeMode = Mode.VPN,
                    configuredMode = Mode.VPN,
                    connectionState = ConnectionState.Connected,
                    connectionDuration = 47.minutes,
                    dataTransferred = 54_321_987L,
                    approachSummary =
                        HomeApproachSummaryUiState(
                            title = "TTL split with fake request",
                            verification = "Verified today",
                            successRate = "84% success rate",
                            supportingText =
                                "Stable on restrictive networks and resilient against plain reset injection.",
                        ),
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Composable
internal fun RipDpiHomeDarkPreviewScene() {
    RipDpiTheme(themePreference = "dark") {
        HomeScreen(
            uiState =
                MainUiState(
                    appStatus = AppStatus.Running,
                    activeMode = Mode.VPN,
                    configuredMode = Mode.VPN,
                    connectionState = ConnectionState.Connected,
                    connectionDuration = 47.minutes,
                    dataTransferred = 54_321_987L,
                    approachSummary =
                        HomeApproachSummaryUiState(
                            title = "TTL split with fake request",
                            verification = "Verified today",
                            successRate = "84% success rate",
                            supportingText =
                                "Stable on restrictive networks and resilient against plain reset injection.",
                        ),
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Composable
internal fun RipDpiHomeConnectingPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState =
                MainUiState(
                    appStatus = AppStatus.Running,
                    activeMode = Mode.VPN,
                    configuredMode = Mode.VPN,
                    connectionState = ConnectionState.Connecting,
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(name = "Settings Medium", showBackground = true, widthDp = 720, heightDp = 1100)
@Composable
private fun SettingsMediumPreview() {
    RipDpiSettingsMediumPreviewScene()
}

@Composable
internal fun RipDpiSettingsMediumPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    appTheme = "system",
                    dns = DnsUiState(dnsIp = "1.1.1.1"),
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                    webrtcProtectionEnabled = true,
                    themedAppIconEnabled = true,
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onShareDebugBundle = {},
            permissionSummary =
                PermissionSummaryUiState(
                    items =
                        persistentListOf(
                            PermissionItemUiState(
                                kind = PermissionKind.VpnConsent,
                                title = "VPN permission",
                                subtitle = "Required before traffic can be tunneled.",
                                statusLabel = "Granted",
                            ),
                            PermissionItemUiState(
                                kind = PermissionKind.BatteryOptimization,
                                title = "Battery optimization",
                                subtitle = "Allow background runtime to keep the tunnel alive.",
                                statusLabel = "Attention",
                                actionLabel = "Open settings",
                            ),
                        ),
                ),
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
    }
}

@Composable
internal fun RipDpiSettingsDarkPreviewScene() {
    RipDpiTheme(themePreference = "dark") {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    appTheme = "system",
                    dns = DnsUiState(dnsIp = "1.1.1.1"),
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                    webrtcProtectionEnabled = true,
                    themedAppIconEnabled = true,
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onShareDebugBundle = {},
            permissionSummary =
                PermissionSummaryUiState(
                    items =
                        persistentListOf(
                            PermissionItemUiState(
                                kind = PermissionKind.VpnConsent,
                                title = "VPN permission",
                                subtitle = "Required before traffic can be tunneled.",
                                statusLabel = "Granted",
                            ),
                            PermissionItemUiState(
                                kind = PermissionKind.BatteryOptimization,
                                title = "Battery optimization",
                                subtitle = "Allow background runtime to keep the tunnel alive.",
                                statusLabel = "Attention",
                                actionLabel = "Open settings",
                            ),
                        ),
                ),
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
    }
}

@Preview(name = "Intro Large Font", showBackground = true, widthDp = 420, heightDp = 900, fontScale = 1.3f)
@Composable
private fun IntroLargeFontPreview() {
    RipDpiIntroLargeFontPreviewScene()
}

@Composable
internal fun RipDpiIntroLargeFontPreviewScene() {
    RipDpiTheme(themePreference = "dark") {
        OnboardingScreen(
            uiState = OnboardingUiState(currentPage = 1, totalPages = 9),
            onPageChanged = {},
            onSkip = {},
            onContinue = {},
            onModeSelected = {},
            onDnsSelected = {},
            onRunTest = {},
        )
    }
}

// ── Diagnostics preview scenes ──────────────────────────────────────────────

private val diagnosticsScanProfiles =
    listOf(
        DiagnosticsProfileOptionUiModel(
            id = "general",
            name = "General",
            source = "builtin",
            family = DiagnosticProfileFamily.GENERAL,
        ),
        DiagnosticsProfileOptionUiModel(
            id = "web_connectivity",
            name = "Web Connectivity",
            source = "builtin",
            family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
        ),
        DiagnosticsProfileOptionUiModel(
            id = "dpi_full",
            name = "DPI Full",
            source = "builtin",
            family = DiagnosticProfileFamily.DPI_FULL,
        ),
        DiagnosticsProfileOptionUiModel(
            id = "quick_strategy",
            name = "Quick Strategy Probe v1",
            source = "builtin",
            kind = ScanKind.STRATEGY_PROBE,
            strategyProbeSuiteId = "quick_v1",
        ),
    )

private val diagnosticsScanState =
    DiagnosticsScanUiModel(
        profiles = diagnosticsScanProfiles,
        selectedProfileId = "dpi_full",
        selectedProfile = diagnosticsScanProfiles[2],
        selectedProfileScopeLabel = "DNS + HTTP + TLS + TCP + QUIC + Services + Throughput",
        runRawEnabled = true,
        runInPathEnabled = true,
    )

private val diagnosticsShareState =
    DiagnosticsShareUiModel(
        previewTitle = "RIPDPI diagnostics",
        previewBody = "DPI Full scan completed. 42 probes, 8 diagnoses.",
        metrics =
            listOf(
                DiagnosticsMetricUiModel("Probes", "42", DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("Blocked", "12", DiagnosticsTone.Negative),
                DiagnosticsMetricUiModel("DNS poisoned", "3", DiagnosticsTone.Warning),
            ),
    )

private val noopDiagnosticsCallbacks: DiagnosticsNoopCallbacks
    get() = DiagnosticsNoopCallbacks

private object DiagnosticsNoopCallbacks

@Composable
private fun DiagnosticsPreviewSceneImpl(
    uiState: DiagnosticsUiState,
    initialPage: Int = 0,
) {
    val pagerState =
        rememberPagerState(initialPage = initialPage) {
            DiagnosticsSection.entries.size
        }
    DiagnosticsScreen(
        uiState = uiState,
        pagerState = pagerState,
        snackbarHostState = remember { SnackbarHostState() },
        onSelectSection = {},
        onSelectProfile = {},
        onRunRawScan = {},
        onRunInPathScan = {},
        onCancelScan = {},
        onKeepResolverRecommendation = {},
        onSaveResolverRecommendation = {},
        onSelectSession = {},
        onDismissSessionDetail = {},
        onSelectStrategyProbeCandidate = {},
        onDismissStrategyProbeCandidate = {},
        onSelectApproachMode = {},
        onSelectApproach = {},
        onDismissApproachDetail = {},
        onSelectEvent = {},
        onDismissEventDetail = {},
        onSelectProbe = {},
        onDismissProbeDetail = {},
        onToggleSensitiveSessionDetails = {},
        onSessionPathFilter = {},
        onSessionStatusFilter = {},
        onSessionSearch = {},
        onToggleEventFilter = { _, _ -> },
        onEventSearch = {},
        onEventAutoScroll = {},
        onShareSummary = {},
        onShareArchive = {},
        onSaveArchive = {},
        onSaveLogs = {},
        onOpenHistory = {},
    )
}

@Preview(name = "Diagnostics Scan", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun DiagnosticsScanPreview() {
    RipDpiDiagnosticsScanPreviewScene()
}

@Composable
internal fun RipDpiDiagnosticsScanPreviewScene() {
    RipDpiTheme(themePreference = "light") {
        DiagnosticsPreviewSceneImpl(
            uiState =
                DiagnosticsUiState(
                    selectedSection = DiagnosticsSection.Scan,
                    scan = diagnosticsScanState,
                ),
            initialPage = DiagnosticsSection.Scan.ordinal,
        )
    }
}

@Preview(name = "Diagnostics Share", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun DiagnosticsSharePreview() {
    RipDpiDiagnosticsSharePreviewScene()
}

@Composable
internal fun RipDpiDiagnosticsSharePreviewScene() {
    RipDpiTheme(themePreference = "light") {
        DiagnosticsPreviewSceneImpl(
            uiState =
                DiagnosticsUiState(
                    selectedSection = DiagnosticsSection.Tools,
                    share = diagnosticsShareState,
                ),
            initialPage = DiagnosticsSection.Tools.ordinal,
        )
    }
}
