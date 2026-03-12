package com.poyka.ripdpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionItemUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.screens.home.HomeScreen
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreen
import com.poyka.ripdpi.ui.screens.settings.SettingsScreen
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
                    dnsIp = "1.1.1.1",
                    biometricEnabled = true,
                    backupPin = "1234",
                    webrtcProtectionEnabled = true,
                    themedAppIconEnabled = true,
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onShareDebugBundle = {},
            permissionSummary =
                PermissionSummaryUiState(
                    items =
                        listOf(
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
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
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
            uiState = OnboardingUiState(currentPage = 1, totalPages = 3),
            onPageChanged = {},
            onSkip = {},
            onContinue = {},
        )
    }
}
