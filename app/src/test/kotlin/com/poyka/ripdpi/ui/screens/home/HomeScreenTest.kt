package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsVerificationSheetUiState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backgroundGuidanceMergedIntoBatteryRecommendationBanner() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = uiStateWithBothBanners(),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomePermissionRecommendationBanner)
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeBackgroundGuidanceBanner)
            .assertDoesNotExist()
    }

    @Test
    fun `battery recommendation banner shows dismiss button`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = uiStateWithBothBanners(),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onDismissBatteryBanner = {},
                    onDismissBackgroundGuidance = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomePermissionRecommendationBanner)
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithTag(RipDpiTestTags.WarningBannerDismiss)
            .assertCountEquals(1)
    }

    @Test
    fun `clicking dismiss on battery banner invokes callback`() {
        var dismissed = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            permissionSummary =
                                PermissionSummaryUiState(
                                    recommendedIssue =
                                        PermissionIssueUiState(
                                            kind = PermissionKind.BatteryOptimization,
                                            title = "Battery optimization",
                                            message = "Review the Doze exemption.",
                                            recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
                                            actionLabel = "Review",
                                            blocking = false,
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onDismissBatteryBanner = { dismissed = true },
                    onDismissBackgroundGuidance = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.WarningBannerDismiss)
            .performClick()
        assertTrue("Expected battery dismiss callback to fire", dismissed)
    }

    @Test
    fun `home renders the three mode cards in order and removes legacy body sections`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        HomeMode.entries.forEach { mode ->
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.homeModeCard(mode.name))
                .assertCountEquals(1)
        }

        val bypassTop = cardTop(HomeMode.LocalDpiBypass)
        val vpnTop = cardTop(HomeMode.RemoteVpn)
        val diagnosticTop = cardTop(HomeMode.Diagnostic)
        assertTrue(vpnTop > bypassTop)
        assertTrue(diagnosticTop > vpnTop)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeConnectionButton).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsCard).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeHistoryCard).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeStatsGrid).assertDoesNotExist()
    }

    @Test
    fun `permission warning remains above mode cards`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = uiStateWithBothBanners().copy(modeCards = modeCards()),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        val warningBottom =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomePermissionRecommendationBanner)
                .fetchSemanticsNode()
                .boundsInRoot
                .bottom
        assertTrue(cardTop(HomeMode.LocalDpiBypass) > warningBottom)
    }

    @Test
    fun `mode cards route primary and card actions through screen callbacks`() {
        var bypassEnabled: Boolean? = null
        var vpnEnabled: Boolean? = null
        var bypassOpened = false
        var vpnOpened = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(modeCards = modeCards(activeMode = HomeMode.LocalDpiBypass)),
                    onToggleConnection = {},
                    onBypassToggle = { bypassEnabled = it },
                    onVpnToggle = { vpnEnabled = it },
                    onBypassCardClick = { bypassOpened = true },
                    onVpnCardClick = { vpnOpened = true },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.LocalDpiBypass.name)).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.RemoteVpn.name)).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.homeModeConfigureAction(HomeMode.LocalDpiBypass.name)).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.homeModeCardBody(HomeMode.RemoteVpn.name)).performClick()

        assertEquals(false, bypassEnabled)
        assertEquals(true, vpnEnabled)
        assertTrue(bypassOpened)
        assertTrue(vpnOpened)
    }

    @Test
    fun `diagnostic card runs analysis and opens diagnostics`() {
        var ranAnalysis = false
        var openedDiagnostics = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    onToggleConnection = {},
                    onDiagnosticRun = { ranAnalysis = true },
                    onDiagnosticCardClick = { openedDiagnostics = true },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.Diagnostic.name))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCardBody(HomeMode.Diagnostic.name))
            .performScrollTo()
            .performClick()

        assertTrue(ranAnalysis)
        assertTrue(openedDiagnostics)
    }

    @Test
    fun `analysis and verification sheets stay functional with mode card body`() {
        var shared = false
        var openedDiagnostics = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            modeCards = modeCards(),
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                    verifiedVpnAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Start Verified VPN",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                    analysisSheet =
                                        HomeDiagnosticsAnalysisSheetUiState(
                                            runId = "home-run",
                                            headline = "Analysis complete",
                                            summary = "Settings were applied.",
                                            appliedSettings =
                                                listOf(
                                                    DiagnosticsAppliedSetting("WARP routing", "Rules"),
                                                    DiagnosticsAppliedSetting("WARP hostlist", "2 hosts"),
                                                ),
                                        ),
                                    verificationSheet =
                                        HomeDiagnosticsVerificationSheetUiState(
                                            sessionId = "verify-session",
                                            success = true,
                                            headline = "VPN access confirmed",
                                            summary = "Connectivity is working.",
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = { openedDiagnostics = true },
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onShareAnalysis = { shared = true },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsAnalysisSheet).assertIsDisplayed()
        composeRule.onNodeWithText("WARP routing").assertIsDisplayed()
        composeRule.onNodeWithText("2 hosts").assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsShareAction).performClick()
        assertTrue(shared)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationSheet).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationOpenDiagnosticsAction).performClick()
        assertTrue(openedDiagnostics)
    }

    private fun cardTop(mode: HomeMode): Float =
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCard(mode.name))
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    private fun uiStateWithBothBanners(): MainUiState =
        MainUiState(
            permissionSummary =
                PermissionSummaryUiState(
                    recommendedIssue =
                        PermissionIssueUiState(
                            kind = PermissionKind.BatteryOptimization,
                            title = "Battery optimization",
                            message = "Review the Doze exemption.",
                            recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
                            actionLabel = "Review",
                            blocking = false,
                        ),
                    backgroundGuidance =
                        BackgroundGuidanceUiState(
                            title = "Background activity",
                            message = "Vendor limits can still stop RIPDPI in the background.",
                        ),
                ),
        )

    private fun modeCards(activeMode: HomeMode? = null) =
        persistentListOf(
            card(HomeMode.LocalDpiBypass, activeMode = activeMode),
            card(HomeMode.RemoteVpn, activeMode = activeMode),
            card(HomeMode.Diagnostic, activeMode = activeMode),
        )

    private fun card(
        mode: HomeMode,
        activeMode: HomeMode?,
    ): HomeModeCardUiState {
        val active = mode == activeMode
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
                    HomeMode.Diagnostic -> "No analysis yet"
                },
            statusLine = if (active) "Connected 00:01:00" else "Inactive",
            primaryActionLabel =
                when {
                    mode == HomeMode.Diagnostic -> "Run Scan"
                    active -> "Disable"
                    else -> "Enable"
                },
            configureLabel = "Configure",
            primaryActionEnabled = true,
            isActive = active,
        )
    }
}
