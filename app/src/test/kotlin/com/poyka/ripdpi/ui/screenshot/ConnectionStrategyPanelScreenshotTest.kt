package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.statusPreviewActuatorState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusModeSummaryFacet
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w420dp-h1200dp")
class ConnectionStrategyPanelScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun collapsedLight() = capture(expanded = false)

    @Test fun expandedLight() = capture(expanded = true)

    @Test fun expandedDark() = capture(expanded = true, themePreference = "dark")

    @Test fun expandedMaximumFont() = capture(expanded = true, fontScale = 2f)

    private fun capture(
        expanded: Boolean,
        themePreference: String = "light",
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RipDpiTheme(themePreference = themePreference) {
                    Box(
                        Modifier
                            .requiredWidth(400.dp)
                            .background(RipDpiThemeTokens.colors.background)
                            .padding(16.dp),
                    ) {
                        RipDpiConnectionActuator(
                            state =
                                statusPreviewActuatorState(StatusConnectionActuatorStatus.Locked).copy(
                                    strategyLabel = StrategyLabel,
                                    strategyFacets = Facets,
                                ),
                            onActivate = {},
                            onDeactivate = {},
                        )
                    }
                }
            }
        }
        if (expanded) composeRule.onNodeWithText(StrategyLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(roborazziOptions = CROSS_PLATFORM_OPTIONS)
    }

    private companion object {
        const val StrategyLabel = "tcp: split(host+1)"
        val Facets =
            persistentListOf(
                StatusModeSummaryFacet("Protocols", "HTTP, HTTPS, UDP"),
                StatusModeSummaryFacet("Host learning", "On"),
                StatusModeSummaryFacet("QUIC", "Disabled"),
                StatusModeSummaryFacet("DNS", "AdGuard DNS DoH"),
            )
    }
}
