package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmScreen
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SubscriptionImportConfirmScreenshotTest {
    @Test
    fun populatedSubscription() {
        capture(
            "populatedSubscription",
            SubscriptionImportConfirmUiState(
                url = "https://sub.example.com/subscribe/abcdef0123456789",
                name = "Example Subscription",
                bootstrap = false,
            ),
        )
    }

    @Test
    fun bootstrapSubscription() {
        capture(
            "bootstrapSubscription",
            SubscriptionImportConfirmUiState(
                url = "https://sub.example.com/bootstrap/one-time-token",
                name = "Bootstrap Profile",
                bootstrap = true,
            ),
        )
    }

    @Test
    fun importedSubscription() {
        capture(
            "importedSubscription",
            SubscriptionImportConfirmUiState(
                url = "https://sub.example.com/subscribe/abcdef0123456789",
                name = "Example Subscription",
                bootstrap = false,
                imported = true,
            ),
        )
    }

    private fun capture(
        name: String,
        state: SubscriptionImportConfirmUiState,
    ) {
        captureScreenBothThemes(
            name = name,
            widthDp = 420,
            heightDp = 900,
            testClassFqn = FQN,
        ) {
            SubscriptionImportConfirmScreen(
                uiState = state,
                onBack = {},
                onConfirm = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.SubscriptionImportConfirmScreenshotTest"
    }
}
