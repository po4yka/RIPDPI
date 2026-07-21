package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsScanPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsMediumPreviewScene
import com.poyka.ripdpi.ui.navigation.BottomNavBar
import com.poyka.ripdpi.ui.navigation.Route
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MaximumFontNavigationShellScreenshotTest {
    @Test
    fun settingsAtPixel7MaximumFont() {
        captureShell(name = "settingsAtPixel7MaximumFont", selectedRoute = Route.Settings) {
            RipDpiSettingsMediumPreviewScene()
        }
    }

    @Test
    fun diagnosticsAtPixel7MaximumFont() {
        captureShell(name = "diagnosticsAtPixel7MaximumFont", selectedRoute = Route.Diagnostics()) {
            RipDpiDiagnosticsScanPreviewScene()
        }
    }

    private fun captureShell(
        name: String,
        selectedRoute: Route,
        scene: @Composable () -> Unit,
    ) {
        assumeFullExperienceScreenshot()
        captureExperienceRipDpiScreenshot(
            name = name,
            testClassFqn = javaClass.name,
            widthDp = 411,
            heightDp = 891,
            fontScale = 2f,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { scene() }
                BottomNavBar(
                    selectedRoute = selectedRoute,
                    onNavigate = {},
                )
            }
        }
    }
}
