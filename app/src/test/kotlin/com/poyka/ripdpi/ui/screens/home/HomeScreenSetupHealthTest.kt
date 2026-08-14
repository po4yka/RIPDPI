package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.HardKillSwitchUiState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
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
class HomeScreenSetupHealthTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `lockdown setup health only appears when vpn is active and opens vpn settings`() {
        var repairedKind: PermissionKind? = null
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            modeCards = modeCards(activeMode = HomeMode.RemoteVpn),
                            hardKillSwitch = hardKillSwitchWarning(),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = { repairedKind = it },
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeHardKillSwitchBanner).assertCountEquals(0)
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeSetupHealthAction)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertEquals(PermissionKind.VpnLockdown, repairedKind)
    }

    /**
     * An inactive VPN is when this advisory is most actionable: the user can
     * still turn lockdown on before bringing the tunnel up. Gating it on an
     * active VPN card withheld it there, and in the post-failure state too.
     */
    @Test
    fun `lockdown setup health stays reachable while vpn is inactive`() {
        var repairedKind: PermissionKind? = null
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(hardKillSwitch = hardKillSwitchWarning()),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = { repairedKind = it },
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeHardKillSwitchBanner).assertCountEquals(0)
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeSetupHealthAction)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertEquals(PermissionKind.VpnLockdown, repairedKind)
    }

    @Test
    fun backgroundGuidanceAndBatteryRecommendationUseSetupHealthRow() {
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

        composeRule.onAllNodesWithTag(RipDpiTestTags.HomePermissionRecommendationBanner).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeBackgroundGuidanceBanner).assertCountEquals(0)
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeSetupHealthDetails).assertIsDisplayed()
        composeRule.onNodeWithText("Review →").assertIsDisplayed()
        composeRule.onAllNodesWithText("Review the Doze exemption.").assertCountEquals(0)
    }

    @Test
    fun `setup health has no warning banner dismiss button`() {
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

        composeRule.onAllNodesWithTag(RipDpiTestTags.HomePermissionRecommendationBanner).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.WarningBannerDismiss).assertCountEquals(0)
        composeRule.onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow).assertIsDisplayed()
    }

    @Test
    fun `clicking battery setup health action invokes callback`() {
        var dismissed = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            permissionSummary =
                                PermissionSummaryUiState(
                                    recommendedIssue = batteryOptimizationIssue(),
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

        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeSetupHealthRow).assertCountEquals(0)
        composeRule.onNodeWithText("Battery optimization").assertIsDisplayed()
        composeRule.onNodeWithText("Review →").assertIsDisplayed()
        composeRule.onAllNodesWithText("Review the Doze exemption.").assertCountEquals(0)
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeSetupHealthAction)
            .assertIsDisplayed()
            .performClick()
        assertTrue("Expected battery dismiss callback to fire", dismissed)
    }

    @Test
    fun `primary actuator remains above setup health`() {
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

        val warningTop =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow)
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val actuatorBottom =
            composeRule
                .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
                .fetchSemanticsNode()
                .boundsInRoot
                .bottom
        assertTrue(actuatorBottom <= warningTop)
    }

    private fun hardKillSwitchWarning(): HardKillSwitchUiState =
        HardKillSwitchUiState(
            status = AndroidHardKillSwitchStatus.NOT_ENABLED,
            label = "System lockdown not enabled",
            summary = "Android is not blocking traffic outside the VPN.",
            actionLabel = "Open VPN settings",
            visible = true,
        )

    private fun uiStateWithBothBanners(): MainUiState =
        MainUiState(
            permissionSummary =
                PermissionSummaryUiState(
                    recommendedIssue = batteryOptimizationIssue(),
                    backgroundGuidance =
                        BackgroundGuidanceUiState(
                            title = "Background activity restricted",
                            message = "Allow background service restarts.",
                        ),
                ),
        )

    private fun batteryOptimizationIssue(): PermissionIssueUiState =
        PermissionIssueUiState(
            kind = PermissionKind.BatteryOptimization,
            title = "Battery optimization",
            message = "Review the Doze exemption.",
            recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
            actionLabel = "Review",
            blocking = false,
        )

    private fun modeCards(activeMode: HomeMode? = null) =
        HomeMode.entries
            .map { mode ->
                HomeModeCardUiState(mode = mode, isActive = mode == activeMode)
            }.toImmutableList()
}
