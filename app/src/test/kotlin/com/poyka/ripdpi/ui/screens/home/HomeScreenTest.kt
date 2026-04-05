package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.AnalysisProgressUiState
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsVerificationSheetUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
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
        composeRule.onNodeWithText("WARP routing").assertExists()
        composeRule.onNodeWithText("2 hosts").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsShareAction).performClick()
        assertTrue(shared)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationSheet).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationOpenDiagnosticsAction).performClick()
        assertTrue(openedDiagnostics)
    }

    @Test
    fun `analysis progress indicator shown when analysis is busy with progress`() {
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
                                            supportingText = "Stage 2 of 3 \u00B7 Testing TCP Desync",
                                            enabled = false,
                                            busy = true,
                                        ),
                                    verifiedVpnAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Start Verified VPN",
                                            supportingText = "Finish analysis first.",
                                            enabled = false,
                                        ),
                                    analysisProgress =
                                        AnalysisProgressUiState(
                                            stages =
                                                listOf(
                                                    AnalysisStageUiState(AnalysisStageStatus.COMPLETED),
                                                    AnalysisStageUiState(AnalysisStageStatus.RUNNING),
                                                    AnalysisStageUiState(AnalysisStageStatus.PENDING),
                                                ),
                                            activeStageIndex = 1,
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

        composeRule.waitForIdle()
        composeRule
            .onNode(hasContentDescription("1 completed, 1 running", substring = true))
            .assertExists()
        composeRule
            .onNode(hasText("Stage 2 of 3", substring = true))
            .assertExists()
    }

    @Test
    fun `plain text shown when analysis is not busy`() {
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
                                            busy = false,
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

        composeRule.waitForIdle()
        composeRule
            .onNode(hasText("Run the audit first."))
            .assertExists()
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
