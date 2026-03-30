package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsVerificationSheetUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
        val dismissNodes =
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(RipDpiTestTags.WarningBannerDismiss),
                ).fetchSemanticsNodes()
        assertTrue("Expected at least one dismiss button", dismissNodes.isNotEmpty())
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
    fun `home diagnostics card renders alongside connection and history sections`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        val connectionTop =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeConnectionButton)
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val diagnosticsTop =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsCard)
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val historyTop =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeHistoryCard)
                .fetchSemanticsNode()
                .boundsInRoot
                .top

        assertTrue(diagnosticsTop > connectionTop)
    }

    @Test
    fun `verified vpn action stays disabled until analysis produces actionable result`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Run the audit first.",
                                            enabled = true,
                                        ),
                                    verifiedVpnAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Start Verified VPN",
                                            supportingText = "Run analysis first.",
                                            enabled = false,
                                        ),
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

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis).assertHasClickAction()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerifiedVpn).assertIsNotEnabled()
    }

    @Test
    fun `analysis and verification sheets expose actions`() {
        var shared = false
        var openedDiagnostics = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
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
                                            sessionId = "audit-session",
                                            headline = "Analysis complete",
                                            summary = "Settings were applied.",
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
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsShareAction).performClick()
        assertTrue(shared)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationSheet).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationOpenDiagnosticsAction).performClick()
        assertTrue(openedDiagnostics)
    }

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
}
