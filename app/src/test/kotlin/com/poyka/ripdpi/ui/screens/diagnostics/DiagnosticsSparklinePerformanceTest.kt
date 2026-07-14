package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel
import com.poyka.ripdpi.ui.debug.RecompositionCounter
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsSparklinePerformanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sparkline animation frames invalidate draw without recomposition`() {
        composeRule.mainClock.autoAdvance = false
        RecompositionCounter.reset()
        composeRule.setContent {
            RipDpiTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    TelemetrySparkline(
                        trend =
                            DiagnosticsSparklineUiModel(
                                label = "TX bytes",
                                values = persistentListOf(10f, 20f, 30f),
                            ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        RecompositionCounter.report()

        composeRule.mainClock.advanceTimeBy(160L)
        composeRule.waitForIdle()

        assertEquals("", RecompositionCounter.report())
    }
}
