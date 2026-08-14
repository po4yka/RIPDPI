package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.HardKillSwitchUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Setup health, the Xray provider stage, the network condition and subscription
 * expiry each used to render themselves, so a bad enough moment stacked four
 * full-width surfaces between the primary control and the screen's content, in
 * declaration order rather than by how much any of them mattered.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeAdvisorySlotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `two advisories share one card headed by the more severe of them`() {
        setHome()

        // The lapsed subscription is an error and the lockdown hint a warning,
        // so the subscription names the row even though setup health is built
        // first. Neither keeps a surface of its own.
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeSubscriptionExpiryBanner).assertCountEquals(0)
        composeRule.onNodeWithText(EXPIRED_TITLE).assertIsDisplayed()
        composeRule.onAllNodesWithText(LOCKDOWN_LABEL).assertCountEquals(0)
    }

    @Test
    fun `expanding the card reaches every advisory it holds`() {
        setHome()

        composeRule.onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow).performClick()

        composeRule.onNodeWithTag(RipDpiTestTags.HomeSetupHealthDetails).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(LOCKDOWN_LABEL).performScrollTo().assertIsDisplayed()
    }

    private fun setHome() {
        val group =
            ProxyGroup(
                id = "phone",
                name = "Phone",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = false,
                subscription = Subscription(tokenExpiresAtEpochMillis = NOW - 1L),
            )
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            hardKillSwitch =
                                HardKillSwitchUiState(
                                    status = AndroidHardKillSwitchStatus.NOT_ENABLED,
                                    label = LOCKDOWN_LABEL,
                                    summary = "Android is not blocking traffic outside the VPN.",
                                    actionLabel = "Open VPN settings",
                                    visible = true,
                                ),
                        ),
                    subscriptionExpiry = subscriptionExpiryUiState(listOf(group), NOW),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }
    }

    private companion object {
        const val NOW = 1_000L
        const val LOCKDOWN_LABEL = "System lockdown not enabled"
        const val EXPIRED_TITLE = "Subscription expired"
    }
}
