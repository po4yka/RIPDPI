package com.poyka.ripdpi.ui.screens.subscription

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.subscription.subscriptionDetailUiState
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SubscriptionStatusScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `subscription link is redacted until reveal is explicitly requested`() {
        var revealRequests = 0
        val uiState = mutableStateOf(state(expiry = Now + EightDaysMillis))
        composeRule.setContent {
            RipDpiTheme {
                SubscriptionStatusScreen(
                    uiState = uiState.value,
                    onBack = {},
                    onToggleSecrets = {
                        revealRequests++
                        uiState.value = state(expiry = Now + EightDaysMillis, revealSecrets = true)
                    },
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(FixtureLink).assertDoesNotExist()
        composeRule.onNodeWithText(FixtureToken, substring = true).assertDoesNotExist()
        composeRule
            .onNodeWithText("https://...", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.subscription_status_reveal_secrets))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, revealRequests) }

        composeRule
            .onNodeWithText(string(R.string.subscription_status_url_format, FixtureLink))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.subscription_status_token_format, FixtureToken))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `expired subscription keeps profiles and offers replacement guidance instead of refresh`() {
        render(uiState = state(expiry = Now))

        composeRule
            .onNodeWithText(string(R.string.subscription_status_replacement_help))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.subscription_status_refresh_action))
            .assertDoesNotExist()
    }

    @Test
    fun `active subscription exposes shared refresh action`() {
        var refreshedGroup = ""
        render(
            uiState = state(expiry = Now + EightDaysMillis),
            onRefresh = { refreshedGroup = it },
        )

        composeRule
            .onNodeWithText(
                string(
                    R.string.subscription_status_refresh_format,
                    string(R.string.subscription_status_refresh_ready),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.subscription_status_refresh_action))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(GroupId, refreshedGroup) }
    }

    private fun render(
        uiState: SubscriptionStatusUiState,
        onToggleSecrets: () -> Unit = {},
        onRefresh: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                SubscriptionStatusScreen(
                    uiState = uiState,
                    onBack = {},
                    onToggleSecrets = onToggleSecrets,
                    onRefresh = onRefresh,
                )
            }
        }
    }

    private fun state(
        expiry: Long,
        revealSecrets: Boolean = false,
    ): SubscriptionStatusUiState {
        val subscription =
            Subscription(
                link = FixtureLink,
                token = FixtureToken,
                tokenExpiresAtEpochMillis = expiry,
            )
        val group =
            ProxyGroup(
                id = GroupId,
                name = "Phone subscription",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = false,
                subscription = subscription,
            )
        val summary = subscriptionExpiryUiState(listOf(group), Now)
        return SubscriptionStatusUiState(
            summary =
                summary.copy(
                    items =
                        summary.items.map { item ->
                            item.copy(
                                details = subscriptionDetailUiState(subscription, revealSecrets),
                            )
                        },
                ),
            secretsRevealed = revealSecrets,
        )
    }

    private fun string(
        id: Int,
        vararg arguments: Any,
    ): String = RuntimeEnvironment.getApplication().getString(id, *arguments)

    private companion object {
        const val GroupId = "subscription-group"
        const val FixtureLink = "https://subscription.example/sub/fixture-token"
        const val FixtureToken = "fixture-token"
        const val Now = 1_800_000_000_000L
        const val EightDaysMillis = 8L * 24L * 60L * 60L * 1_000L
    }
}
