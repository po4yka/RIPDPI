package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsSparklineAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sparkline exposes a 48dp accessibility target and navigation actions`() {
        setSparklineContent()

        val node = composeRule.onNodeWithContentDescription("TX bytes trend, 3 samples")
        node.assertHeightIsAtLeast(48.dp)

        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Previous sample", "Next sample"), actions.map { it.label })
    }

    @Test
    fun `sparkline custom action selects a sample and updates state description`() {
        setSparklineContent()
        val node = composeRule.onNodeWithContentDescription("TX bytes trend, 3 samples")
        val nextAction =
            node
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Next sample" }

        composeRule.runOnIdle { assertTrue(nextAction.action()) }
        composeRule.waitForIdle()

        assertEquals(
            "Sample 1 of 3: 10 B",
            node.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    private fun setSparklineContent() {
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
    }
}
