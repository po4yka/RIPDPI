package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.services.OwnedStackBrowserBackend
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import com.poyka.ripdpi.services.OwnedStackExecutionTrace
import com.poyka.ripdpi.services.SecureHttpEchMode
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserPageUiModel
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserScreen
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OwnedStackBrowserScreenshotTest {
    @Test
    fun ownedStackBrowserEmptyState() {
        capture(
            "ownedStackBrowserEmptyState",
            OwnedStackBrowserUiState(
                support =
                    OwnedStackBrowserSupport(
                        platformHttpEngineAvailable = true,
                        android17EchEligible = false,
                    ),
                inputUrl = "",
            ),
        )
    }

    @Test
    fun ownedStackBrowserPopulatedState() {
        capture(
            "ownedStackBrowserPopulatedState",
            OwnedStackBrowserUiState(
                support =
                    OwnedStackBrowserSupport(
                        platformHttpEngineAvailable = true,
                        android17EchEligible = true,
                    ),
                inputUrl = "https://example.org/",
                page =
                    OwnedStackBrowserPageUiModel(
                        requestedUrl = "https://example.org/",
                        finalUrl = "https://example.org/",
                        statusCode = 200,
                        bodyText =
                            "Example Domain. This domain is for use in illustrative examples in documents.",
                        backend = OwnedStackBrowserBackend.HTTP_ENGINE,
                        android17EchEligible = true,
                        tlsProfileId = "chrome-131-stable",
                        executionTrace =
                            OwnedStackExecutionTrace(
                                authority = "example.org",
                                confirmedEchCapableAuthority = true,
                                echEnforcedDomain = true,
                                effectiveEchMode = SecureHttpEchMode.REQUIRE_CONFIRMED,
                                platformAttempted = true,
                            ),
                    ),
            ),
        )
    }

    @Test
    fun ownedStackBrowserErrorState() {
        capture(
            "ownedStackBrowserErrorState",
            OwnedStackBrowserUiState(
                support =
                    OwnedStackBrowserSupport(
                        platformHttpEngineAvailable = false,
                        android17EchEligible = false,
                    ),
                inputUrl = "https://blocked.example/",
                errorMessage = "Connection reset by peer during TLS handshake.",
            ),
        )
    }

    private fun capture(
        name: String,
        state: OwnedStackBrowserUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1200, testClassFqn = FQN) {
            OwnedStackBrowserScreen(
                uiState = state,
                onBack = {},
                onUrlChanged = {},
                onOpen = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.OwnedStackBrowserScreenshotTest"
    }
}
