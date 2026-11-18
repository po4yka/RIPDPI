package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.screens.connection.ConnectionPath
import com.poyka.ripdpi.ui.screens.connection.ConnectionRouteDiagram
import com.poyka.ripdpi.ui.screens.connection.RouteDiagramUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w420dp-h1200dp")
class ConnectionRouteDiagramScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun directOff() = capture()

    @Test fun directOnDark() = capture(RouteDiagramUiState(powered = true), themePreference = "dark")

    @Test fun directBlocked() = capture(RouteDiagramUiState(powered = true, directBlocked = true))

    @Test fun relayOn() = capture(Relay.copy(powered = true, relayReady = true))

    @Test fun relayWithoutServer() = capture(Relay)

    @Test fun reconnecting() = capture(RouteDiagramUiState(powered = true, busy = true))

    @Test fun rightToLeft() =
        capture(
            Relay.copy(powered = true, relayReady = true),
            layoutDirection = LayoutDirection.Rtl,
        )

    @Test fun maximumFont() = capture(RouteDiagramUiState(scope = Mode.Proxy), fontScale = 2f)

    private fun capture(
        state: RouteDiagramUiState = RouteDiagramUiState(),
        themePreference: String = "light",
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) = withStaticMotion {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                RipDpiTheme(themePreference = themePreference) {
                    Box(
                        Modifier
                            .requiredWidth(400.dp)
                            .background(RipDpiThemeTokens.colors.background)
                            .padding(16.dp),
                    ) {
                        ConnectionRouteDiagram(state = state, onPathSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(roborazziOptions = CROSS_PLATFORM_OPTIONS)
    }

    private companion object {
        val Relay = RouteDiagramUiState(path = ConnectionPath.Relay)
    }
}
