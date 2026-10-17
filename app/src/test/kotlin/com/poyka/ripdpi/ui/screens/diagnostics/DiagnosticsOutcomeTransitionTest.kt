package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DiagnosticsOutcomeSource
import com.poyka.ripdpi.activities.DiagnosticsOutcomeUiModel
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w500dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsOutcomeTransitionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `active outcome fades into the actual result without duplicating actions`() {
        val outcome = mutableStateOf(activeOutcome())
        showOutcome(outcome)
        updateOutcome(outcome, terminalOutcome("Network available"))
        composeRule.onNodeWithText("Running probes").assertExists()
        composeRule.onAllNodesWithText("Network available").assertCountEquals(1)
        composeRule.onAllNodesWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction).assertCountEquals(1)

        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
        composeRule.onNodeWithText("Network available").assertExists()
    }

    @Test
    fun `terminal summary updates do not restart or duplicate the completion transition`() {
        val outcome = mutableStateOf(activeOutcome())
        showOutcome(outcome)
        updateOutcome(outcome, terminalOutcome("Partial result"))
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.waitForIdle()
        updateOutcome(outcome, outcome.value.copy(summary = "Updated evidence"))
        composeRule.onNodeWithText("Measured result").assertDoesNotExist()
        composeRule.onNodeWithText("Updated evidence").assertExists()
        composeRule.onAllNodesWithText("Partial result").assertCountEquals(1)
        composeRule.mainClock.advanceTimeBy(176)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
    }

    @Test
    fun `running telemetry replaces text without a completion transition`() {
        val outcome = mutableStateOf(activeOutcome().copy(summary = "First probe"))
        showOutcome(outcome)
        updateOutcome(outcome, outcome.value.copy(summary = "Second probe"))
        composeRule.onNodeWithText("First probe").assertDoesNotExist()
        composeRule.onNodeWithText("Second probe").assertExists()
        composeRule.onAllNodesWithText("Running probes").assertCountEquals(1)
    }

    @Test
    fun `opening saved evidence does not animate its first display`() {
        val outcome = mutableStateOf(terminalOutcome("Saved result"))
        composeRule.mainClock.autoAdvance = false
        showOutcome(outcome)
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        val firstFrame = outcomePixels()
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        assertArrayEquals(firstFrame, outcomePixels())
        composeRule.onAllNodesWithText("Saved result").assertCountEquals(1)
    }

    @Test
    fun `disabled motion shows the terminal result without outgoing content`() {
        assertStaticResult(RipDpiMotion(animationsEnabled = false))
    }

    @Test
    fun `reduced motion shows the terminal result without outgoing content`() {
        assertStaticResult(RipDpiMotion(reducedMotion = true))
    }

    @Test
    fun `disabling motion during completion removes the outgoing result immediately`() {
        val outcome = mutableStateOf(activeOutcome())
        val motion = mutableStateOf(RipDpiMotion())
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides motion.value) {
                    DiagnosticsOutcomeCard(outcome.value, false, HomeDiagnosticsUiState(), {}, {}, {})
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        updateOutcome(outcome, terminalOutcome("Completed scan"))
        composeRule.onNodeWithText("Running probes").assertExists()
        composeRule.runOnUiThread { motion.value = RipDpiMotion(animationsEnabled = false) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
        composeRule.onNodeWithText("Completed scan").assertExists()
    }

    @Test
    fun `failed result retains its actual error during the transition`() {
        assertTerminalResult("Scan failed", "Resolver could not be reached")
    }

    @Test
    fun `cancelled result retains its actual cancellation during the transition`() {
        assertTerminalResult("Scan cancelled", "Stopped by the user")
    }

    @Test
    fun `empty cancellation return has no completion transition`() {
        val outcome = mutableStateOf(activeOutcome())
        showOutcome(outcome)
        updateOutcome(outcome, DiagnosticsOutcomeUiModel(DiagnosticsOutcomeSource.Empty))
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
        composeRule.onNodeWithText("Network available").assertDoesNotExist()
        composeRule.onAllNodesWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction).assertCountEquals(1)
    }

    @Test
    fun `composite audit transitions once to its measured result`() {
        val outcome = mutableStateOf(activeOutcome())
        showOutcome(outcome)
        updateOutcome(
            outcome,
            terminalOutcome("Incomplete audit").copy(source = DiagnosticsOutcomeSource.CompositeAudit),
        )
        composeRule.onNodeWithText("Running probes").assertExists()
        composeRule.onAllNodesWithText("Incomplete audit").assertCountEquals(1)
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
    }

    private fun assertStaticResult(motion: RipDpiMotion) {
        val outcome = mutableStateOf(activeOutcome())
        showOutcome(outcome, motion)
        updateOutcome(outcome, terminalOutcome("Measured static result"))
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
        composeRule.onNodeWithText("Measured static result").assertExists()
    }

    private fun assertTerminalResult(
        headline: String,
        summary: String,
    ) {
        val outcome = mutableStateOf(activeOutcome())
        showOutcome(outcome)
        updateOutcome(outcome, terminalOutcome(headline).copy(summary = summary))
        composeRule.onNodeWithText("Running probes").assertExists()
        composeRule.onAllNodesWithText(headline).assertCountEquals(1)
        composeRule.onNodeWithText(summary).assertExists()
        composeRule.onNodeWithText("Network available").assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Running probes").assertDoesNotExist()
        composeRule.onNodeWithText(headline).assertExists()
    }

    private fun showOutcome(
        outcome: MutableState<DiagnosticsOutcomeUiModel>,
        motion: RipDpiMotion = RipDpiMotion(),
    ) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides motion) {
                    DiagnosticsOutcomeCard(outcome.value, false, HomeDiagnosticsUiState(), {}, {}, {})
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
    }

    private fun updateOutcome(
        outcome: MutableState<DiagnosticsOutcomeUiModel>,
        value: DiagnosticsOutcomeUiModel,
    ) {
        composeRule.runOnUiThread { outcome.value = value }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
    }

    private fun outcomePixels(): IntArray {
        val image = composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsOutcomeTransition).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    private fun activeOutcome() =
        DiagnosticsOutcomeUiModel(source = DiagnosticsOutcomeSource.Active, headline = "Running probes")

    private fun terminalOutcome(headline: String) =
        DiagnosticsOutcomeUiModel(
            source = DiagnosticsOutcomeSource.Session,
            evidenceId = "observed-run",
            headline = headline,
            summary = "Measured result",
        )
}
