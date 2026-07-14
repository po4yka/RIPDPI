package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.activities.RememberedNetworksUiState
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworksScreen
import com.poyka.ripdpi.ui.screens.settings.rememberedNetworksPreviewEntries
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RememberedNetworksScreenScreenshotTest {
    @Test
    fun populatedRememberedNetworksScreen() {
        captureScreen(
            RememberedNetworksUiState(loading = false, entries = rememberedNetworksPreviewEntries().toImmutableList()),
        )
    }

    @Test
    fun emptyRememberedNetworksScreen() {
        captureScreen(RememberedNetworksUiState(loading = false, entries = persistentListOf()))
    }

    private fun captureScreen(state: RememberedNetworksUiState) {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 980) {
            RipDpiTheme(themePreference = "light") {
                RememberedNetworksScreen(
                    uiState = state,
                    onBack = {},
                    onDeleteEntry = {},
                    onClearAll = {},
                )
            }
        }
    }
}
