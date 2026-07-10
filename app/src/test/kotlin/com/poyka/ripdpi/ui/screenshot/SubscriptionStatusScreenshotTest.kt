package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusScreen
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SubscriptionStatusScreenshotTest {
    @Test
    fun expiringSubscription() = capture("expiring", expiry = Now + DayMillis)

    @Test
    fun expiredSubscription() = capture("expired", expiry = Now)

    private fun capture(
        name: String,
        expiry: Long,
    ) {
        val group =
            ProxyGroup(
                id = "phone",
                name = "Phone subscription",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = false,
                subscription =
                    Subscription(
                        link = "https://subscription.example/sub/secret-token",
                        lastUpdated = Now - DayMillis,
                        expiryDate = 1_900_000_000L,
                        tokenExpiresAtEpochMillis = expiry,
                    ),
            )
        captureScreenBothThemes(
            name = name,
            widthDp = 420,
            heightDp = 900,
            testClassFqn = FQN,
        ) {
            SubscriptionStatusScreen(
                uiState = SubscriptionStatusUiState(summary = subscriptionExpiryUiState(listOf(group), Now)),
                onBack = {},
                onToggleSecrets = {},
                onRefresh = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.SubscriptionStatusScreenshotTest"
        const val Now = 1_800_000_000_000L
        const val DayMillis = 24L * 60L * 60L * 1_000L
    }
}
