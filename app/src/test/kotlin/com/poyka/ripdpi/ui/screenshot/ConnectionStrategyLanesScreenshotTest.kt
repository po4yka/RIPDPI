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
import com.poyka.ripdpi.ui.screens.connection.ConnectionDirectSection
import com.poyka.ripdpi.ui.screens.connection.ConnectionUiState
import com.poyka.ripdpi.ui.screens.connection.StrategyLaneOutcome
import com.poyka.ripdpi.ui.screens.connection.StrategyLaneUiState
import com.poyka.ripdpi.ui.screens.connection.StrategyLanesUiState
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
class ConnectionStrategyLanesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun light() = capture()

    @Test fun dark() = capture(themePreference = "dark")

    @Test fun rightToLeft() = capture(layoutDirection = LayoutDirection.Rtl)

    @Test fun maximumFont() = capture(fontScale = 2f)

    @Test fun otherNetwork() = capture(lanes = Lanes.copy(otherNetwork = true))

    private fun capture(
        themePreference: String = "light",
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        lanes: StrategyLanesUiState = Lanes,
    ) {
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
                        ConnectionDirectSection(
                            uiState = ConnectionUiState(strategyLanes = lanes),
                            desyncSummary = "",
                            onOpenDesyncSettings = {},
                            onOpenDnsSettings = {},
                            onRetestStrategies = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(roborazziOptions = CROSS_PLATFORM_OPTIONS)
    }

    private companion object {
        // Noon on the first day of the epoch: old enough to print as a date, the same date in every time zone.
        const val FinishedAt = 43_200_000L

        fun lane(
            label: String,
            outcome: StrategyLaneOutcome,
            succeeded: Int = 0,
            latencyMs: Long? = null,
            recommended: Boolean = false,
        ) = StrategyLaneUiState(
            label = label,
            outcome = outcome,
            succeededTargets = succeeded,
            totalTargets = if (outcome == StrategyLaneOutcome.Skipped) 0 else 6,
            latencyMs = latencyMs,
            recommended = recommended,
        )

        val Passed = StrategyLaneOutcome.Passed

        val Lanes =
            StrategyLanesUiState(
                lanes =
                    persistentListOf(
                        lane("Fake + split", Passed, succeeded = 6, latencyMs = 84, recommended = true),
                        lane("OOB byte", Passed, succeeded = 6, latencyMs = 110),
                        lane("TLS record split at the SNI extension", StrategyLaneOutcome.Partial, 5, latencyMs = 132),
                        lane("Split at SNI", StrategyLaneOutcome.Blocked, succeeded = 1),
                        lane("QUIC drop", StrategyLaneOutcome.Skipped),
                    ),
                finishedAt = FinishedAt,
                hiddenCount = 4,
            )
    }
}
