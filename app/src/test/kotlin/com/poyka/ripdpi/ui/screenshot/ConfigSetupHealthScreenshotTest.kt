package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.ui.screens.connection.ConnectionDraft
import com.poyka.ripdpi.ui.screens.connection.ConnectionRelaySection
import com.poyka.ripdpi.ui.screens.connection.ConnectionUiState
import com.poyka.ripdpi.ui.screens.connection.RelayProfileUiState
import com.poyka.ripdpi.ui.screens.connection.buildConfigPresets
import com.poyka.ripdpi.ui.screens.status.HardKillSwitchUiState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorUiState
import com.poyka.ripdpi.ui.screens.status.StatusMode
import com.poyka.ripdpi.ui.screens.status.StatusModeCardUiState
import com.poyka.ripdpi.ui.screens.status.StatusScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConfigSetupHealthScreenshotTest {
    @Test
    fun vpnConfigPopulatedAdvanced() {
        captureScreenBothThemes(
            name = "vpnConfigPopulatedAdvanced",
            widthDp = PhoneWidthDp,
            heightDp = TallScreenHeightDp,
            testClassFqn = FQN,
        ) {
            ConnectionRelaySection(
                uiState = populatedVpnConfigState(),
                onOpenRelaySettings = {},
                onOpenDnsSettings = {},
            )
        }
    }

    @Test
    fun setupHealthPermissionRecovery() {
        captureScreenBothThemes(
            name = "setupHealthPermissionRecovery",
            widthDp = PhoneWidthDp,
            heightDp = HomeScreenHeightDp,
            testClassFqn = FQN,
            isolateByExperience = true,
        ) {
            StatusScreen(
                uiState = setupHealthPermissionState(),
                onToggleConnection = {},
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    @Test
    fun setupHealthBatteryRecommendation() {
        captureScreenBothThemes(
            name = "setupHealthBatteryRecommendation",
            widthDp = PhoneWidthDp,
            heightDp = HomeScreenHeightDp,
            testClassFqn = FQN,
            isolateByExperience = true,
        ) {
            StatusScreen(
                uiState = setupHealthBatteryRecommendationState(),
                onToggleConnection = {},
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    @Test
    fun homeSetupAtPixel7LargeFont() {
        captureScreenBothThemes(
            name = "homeSetupAtPixel7LargeFont",
            widthDp = Pixel7WidthDp,
            heightDp = Pixel7HeightDp,
            testClassFqn = FQN,
            fontScale = 1.5f,
            isolateByExperience = true,
        ) {
            StatusScreen(
                uiState = setupHealthPermissionState(),
                onToggleConnection = {},
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    @Test
    fun homeTransitionAtPixel7MaximumFont() {
        captureScreenBothThemes(
            name = "homeTransitionAtPixel7MaximumFont",
            widthDp = Pixel7WidthDp,
            heightDp = Pixel7HeightDp,
            testClassFqn = FQN,
            fontScale = 2f,
            isolateByExperience = true,
        ) {
            StatusScreen(
                uiState = setupHealthPermissionState().copy(connectionActuator = engagingActuatorState()),
                onToggleConnection = {},
                onOpenDiagnostics = {},
                onOpenHistory = {},
                onRepairPermission = {},
                onOpenVpnPermissionDialog = {},
            )
        }
    }

    private fun populatedVpnConfigState(): ConnectionUiState {
        val draft =
            ConnectionDraft(
                mode = Mode.VPN,
                dnsProviderId = DnsProviderAdGuard,
                relayEnabled = true,
                relayKind = RelayKindVlessReality,
                relayProfileId = "tokyo-reality",
                relayServer = "relay.example.net",
                relayServerPort = "443",
                relayServerName = "cdn.example.net",
                relayRealityPublicKey = "pub-demo-key",
                relayRealityShortId = "a1b2c3d4",
                relayVlessUuid = "00000000-0000-4000-8000-000000000001",
            )
        val profiles =
            persistentListOf(
                RelayProfileUiState(
                    id = "tokyo-reality",
                    kind = RelayKindVlessReality,
                    kindLabel = "VLESS + Reality",
                    jurisdiction = "JP",
                    operatorName = "Edge Labs",
                ),
                RelayProfileUiState(
                    id = "berlin-hysteria",
                    kind = RelayKindHysteria2,
                    kindLabel = "Hysteria2",
                    jurisdiction = "DE",
                    operatorName = "Transit Ops",
                ),
                RelayProfileUiState(
                    id = "nyc-tuic",
                    kind = RelayKindTuicV5,
                    kindLabel = "TUIC v5",
                    jurisdiction = "US",
                    operatorName = "Backup Net",
                ),
                RelayProfileUiState(
                    id = "amsterdam-reality",
                    kind = RelayKindVlessReality,
                    kindLabel = "VLESS + Reality",
                    jurisdiction = "NL",
                    operatorName = "Spare Path",
                ),
            )
        return ConnectionUiState(
            activeMode = Mode.VPN,
            runningMode = Mode.VPN,
            uiPersona = "advanced",
            presets = buildConfigPresets(draft),
            draft = draft,
            relayProfiles = profiles,
            vpnProfiles = profiles,
        )
    }

    private fun setupHealthPermissionState(): MainUiState =
        MainUiState(
            permissionSummary =
                PermissionSummaryUiState(
                    issue =
                        PermissionIssueUiState(
                            kind = PermissionKind.VpnConsent,
                            title = "VPN permission needed",
                            message = "Allow RIPDPI to create the local VPN before connecting.",
                            recovery = PermissionRecovery.ShowVpnPermissionDialog,
                            actionLabel = "Allow VPN",
                            blocking = true,
                        ),
                ),
            hardKillSwitch =
                HardKillSwitchUiState(
                    status = AndroidHardKillSwitchStatus.NOT_ENABLED,
                    label = "System lockdown not enabled",
                    summary = "Android is not blocking traffic outside the VPN.",
                    actionLabel = "Open VPN settings",
                    visible = true,
                ),
            modeCards = populatedHomeModeCards(activeMode = StatusMode.RemoteVpn),
        )

    private fun setupHealthBatteryRecommendationState(): MainUiState =
        MainUiState(
            permissionSummary =
                PermissionSummaryUiState(
                    recommendedIssue =
                        PermissionIssueUiState(
                            kind = PermissionKind.BatteryOptimization,
                            title = "Battery optimization",
                            message = "Allow unrestricted battery use so Android keeps the tunnel alive.",
                            recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
                            actionLabel = "Review",
                            blocking = false,
                        ),
                ),
            modeCards = populatedHomeModeCards(activeMode = StatusMode.RemoteVpn),
        )

    private fun populatedHomeModeCards(activeMode: StatusMode) =
        listOf(
            StatusModeCardUiState(
                mode = StatusMode.LocalDpiBypass,
                title = "Local DPI bypass",
                primaryLabel = "tlsrec_split_host · AdGuard DoH",
                statusLine = "Ready",
                primaryActionLabel = "Turn on",
                configureLabel = "Configure",
                isActive = activeMode == StatusMode.LocalDpiBypass,
            ),
            StatusModeCardUiState(
                mode = StatusMode.RemoteVpn,
                title = "VPN",
                primaryLabel = "tokyo-reality · VLESS + Reality",
                statusLine = "Connected",
                primaryActionLabel = "Turn off",
                configureLabel = "Servers",
                isActive = activeMode == StatusMode.RemoteVpn,
            ),
            StatusModeCardUiState(
                mode = StatusMode.Diagnostic,
                title = "Diagnostics",
                primaryLabel = "Last recommendation is still valid",
                statusLine = "Ready",
                primaryActionLabel = "Run",
                configureLabel = "History",
                isActive = activeMode == StatusMode.Diagnostic,
            ),
        ).toImmutableList()

    private fun engagingActuatorState() =
        StatusConnectionActuatorUiState(
            status = StatusConnectionActuatorStatus.Engaging,
            trailingLabel = "Relay",
            routeLabel = "VLESS Reality via local VPN",
            statusDescription = "Connecting…",
            actionLabel = "Cancel connection",
        )

    companion object {
        private const val FQN = "com.poyka.ripdpi.ui.screenshot.ConfigSetupHealthScreenshotTest"
        private const val PhoneWidthDp = 420
        private const val Pixel7WidthDp = 411
        private const val Pixel7HeightDp = 891
        private const val HomeScreenHeightDp = 1160
        private const val TallScreenHeightDp = 1480
    }
}
