package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.preflight.ProfilePreflightRunState
import com.poyka.ripdpi.ui.screens.preflight.ProfilePreflightScreen
import com.poyka.ripdpi.ui.screens.preflight.ProfilePreflightUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfilePreflightScreenScreenshotTest {
    @Test
    fun profilePreflightIdleState() {
        captureProfilePreflightScreen(ProfilePreflightUiState())
    }

    @Test
    fun profilePreflightRunningState() {
        captureProfilePreflightScreen(
            ProfilePreflightUiState(
                runState = ProfilePreflightRunState.Running,
                activeProfileId = "automatic-audit",
                summary = "Starting profile preflight",
            ),
        )
    }

    @Test
    fun profilePreflightPassedState() {
        captureProfilePreflightScreen(
            ProfilePreflightUiState(
                runState = ProfilePreflightRunState.Passed,
                activeProfileId = "quick_v1",
                sessionId = "session-12345678",
                summary = "Profile reached the target",
                shareTitle = "RIPDPI diagnostics session-12345678",
                shareBody = "RIPDPI profile preflight\nverdict=pass",
            ),
        )
    }
}

private fun captureProfilePreflightScreen(state: ProfilePreflightUiState) {
    captureRipDpiScreenshot(widthDp = 420, heightDp = 760) {
        RipDpiTheme(themePreference = "light") {
            ProfilePreflightScreen(
                state = state,
                onBack = {},
                onRun = {},
                onShare = {},
            )
        }
    }
}
