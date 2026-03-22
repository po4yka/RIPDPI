package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
    fun backgroundGuidanceBannerRendersSeparatelyFromBatteryRecommendation() {
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
                                    backgroundGuidance =
                                        BackgroundGuidanceUiState(
                                            title = "Background activity",
                                            message = "Vendor limits can still stop RIPDPI in the background.",
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

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomePermissionRecommendationBanner)
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeBackgroundGuidanceBanner)
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }
}
