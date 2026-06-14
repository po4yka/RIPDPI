package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationScreen
import com.poyka.ripdpi.ui.state.SettingsUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AppCustomizationScreenshotTest {
    @Test
    fun defaultIcon() {
        capture(
            name = "defaultIcon",
            state =
                SettingsUiState(
                    appIconVariant = LauncherIconManager.DefaultIconKey,
                    themedAppIconEnabled = true,
                ),
        )
    }

    @Test
    fun customizedIcon() {
        capture(
            name = "customizedIcon",
            state =
                SettingsUiState(
                    appIconVariant = LauncherIconManager.CrackedIconKey,
                    themedAppIconEnabled = false,
                ),
        )
    }

    private fun capture(
        name: String,
        state: SettingsUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1100, testClassFqn = FQN) {
            AppCustomizationScreen(
                uiState = state,
                onBack = {},
                onIconSelected = {},
                onThemedIconChanged = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.AppCustomizationScreenshotTest"
    }
}
