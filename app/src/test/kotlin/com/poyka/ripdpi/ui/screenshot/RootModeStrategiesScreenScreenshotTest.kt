package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.settings.RootModeStrategiesScreen
import com.poyka.ripdpi.ui.screens.settings.RootModeStrategiesUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RootModeStrategiesScreenScreenshotTest {
    @Test
    fun rootModeEnabledStrategiesScreen() {
        captureScreen(RootModeStrategiesUiState(rootModeEnabled = true))
    }

    @Test
    fun rootModeDisabledStrategiesScreen() {
        captureScreen(RootModeStrategiesUiState(rootModeEnabled = false))
    }

    private fun captureScreen(state: RootModeStrategiesUiState) {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 760) {
            RipDpiTheme(themePreference = "light") {
                RootModeStrategiesScreen(
                    uiState = state,
                    onBack = {},
                    onOpenStrategyConfig = {},
                )
            }
        }
    }
}
