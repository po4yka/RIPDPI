package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeAnalysisDetectionSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `analysis separates local artifacts from network signs with a neutral verdict`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeAnalysisSheetEvidenceContent(
                    HomeDiagnosticsAnalysisSheetUiState(
                        runId = "run-1",
                        headline = "Analysis complete",
                        summary = "Detection summary",
                        detectionVerdict = "Detected",
                        detectionLocalFindings = persistentListOf("Installed app: com.example.vpn"),
                        detectionNetworkFindings = persistentListOf("Public IP comparison matched"),
                    ),
                )
            }
        }

        val summary =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsDetectionSummary, useUnmergedTree = true)
                .fetchSemanticsNode()
        assertEquals(
            listOf(
                "Detected",
                "Local artifacts",
                "• Installed app: com.example.vpn",
                "Network signs",
                "• Public IP comparison matched",
            ),
            summary.descendantText(),
        )
        composeRule
            .onAllNodesWithText("on this network", substring = true, ignoreCase = true)
            .assertCountEquals(0)
    }

    private fun SemanticsNode.descendantText(): List<String> =
        children.flatMap { child ->
            val text =
                if (child.config.contains(SemanticsProperties.Text)) {
                    child.config[SemanticsProperties.Text].map { it.text }
                } else {
                    emptyList()
                }
            text + child.descendantText()
        }
}
