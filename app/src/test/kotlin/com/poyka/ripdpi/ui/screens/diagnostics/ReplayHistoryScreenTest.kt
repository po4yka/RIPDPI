package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReplayHistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsScanCopyAndCta() {
        var scanRequested = false
        var backRequested = false

        composeRule.setContent {
            RipDpiTheme {
                ReplayHistoryScreen(
                    replays = persistentListOf(),
                    onRunScan = { scanRequested = true },
                    onBack = { backRequested = true },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ReplayHistoryEmptyState).assertIsDisplayed()
        composeRule.onNodeWithText("Past replays").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("No past replays yet").assertIsDisplayed()
        composeRule
            .onNodeWithText("Run a raw-path or in-path scan to persist reports and probe details here.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.ReplayHistoryRunScanAction).assertIsDisplayed().performClick()

        assertTrue(scanRequested)
        assertTrue(backRequested)
    }

    @Test
    fun longReplayHistoryScrollsToTheOldestRow() {
        val replays =
            (0..9)
                .map { index ->
                    ReplayProbeResult(
                        request = ReplayProbeRequest("domain-$index.example", "strategy-$index", 1_000L),
                        events = persistentListOf(),
                        verdict = ReplayVerdict.Success,
                        terminalStep = null,
                        recommendationKey = "recommendation-$index",
                    )
                }.toPersistentList()

        composeRule.setContent {
            RipDpiTheme {
                ReplayHistoryScreen(replays = replays, onRunScan = {}, onBack = {})
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("domain-0.example"))
        composeRule.onNodeWithText("domain-0.example").assertIsDisplayed()
    }
}
