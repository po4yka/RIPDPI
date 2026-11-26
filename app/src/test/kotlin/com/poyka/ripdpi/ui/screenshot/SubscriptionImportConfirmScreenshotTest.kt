package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.ui.screens.proxyimport.ImportedServer
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmScreen
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmUiState
import kotlinx.collections.immutable.persistentListOf
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
                importing = true,
            ),
        )
    }

    @Test
    fun importedBundleServers() {
        capture(
            "importedBundleServers",
            SubscriptionImportConfirmUiState(
                name = "RIPDPI",
                localBundle = true,
                servers = persistentListOf(ImportedServer("tokyo", "Tokyo"), ImportedServer("paris", "Paris")),
            ),
        )
    }

    @Test
    fun checkedSubscription() {
        capture(
            "checkedSubscription",
            SubscriptionImportConfirmUiState(
                url = "https://sub.example.com/subscribe/abcdef0123456789",
                fetchedTitle = "Example Fleet",
                serverCount = 12,
                checked = true,
            ),
        )
    }

    @Test
    fun failedCheck() {
        capture(
            "failedCheck",
            SubscriptionImportConfirmUiState(
                url = "https://sub.example.com/subscribe/abcdef0123456789",
                importFailed = true,
                refreshFailure = SubscriptionRefreshFailure.UNREACHABLE,
                httpStatus = 404,
                checked = true,
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
