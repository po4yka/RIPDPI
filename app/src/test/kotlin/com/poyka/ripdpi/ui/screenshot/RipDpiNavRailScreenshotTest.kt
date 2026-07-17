package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.components.RipDpiHomeExpandedPreviewScene
import com.poyka.ripdpi.ui.navigation.RipDpiNavRail
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiNavRailScreenshotTest {
    @Test
    fun navRailAt600DpAndDefaultFont() {
        captureNavigationShell(widthDp = 600, fontScale = 1f)
    }

    @Test
    fun navRailAt650DpAndLargeFont() {
        captureNavigationShell(widthDp = 650, fontScale = 1.3f)
    }

    @Test
    fun navRailAt700DpAndMaximumAccessibilityFont() {
        captureNavigationShell(widthDp = 700, fontScale = 2f)
    }

    private fun captureNavigationShell(
        widthDp: Int,
        fontScale: Float,
    ) {
        captureRipDpiScreenshot(
            widthDp = widthDp,
            heightDp = 400,
            fontScale = fontScale,
        ) {
            RipDpiTheme(themePreference = "light") {
                Row(modifier = Modifier.fillMaxSize()) {
                    RipDpiNavRail(
                        selectedRoute = Route.Home,
                        onNavigate = {},
                    )
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        RipDpiHomeExpandedPreviewScene()
                    }
                }
            }
        }
    }
}
