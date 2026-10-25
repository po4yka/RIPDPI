package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class RipDpiConnectionActuatorFeedbackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `connection result fades out progress while status and action update immediately`() {
        var state by mutableStateOf(actuatorState(HomeConnectionActuatorStatus.Engaging))
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides DefaultRipDpiMotion) {
                    RipDpiConnectionActuator(
                        state = state,
                        onActivate = {},
                        onDeactivate = {},
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }
        val progress =
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        composeRule.onNode(progress, useUnmergedTree = true).assertExists()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { state = actuatorState(HomeConnectionActuatorStatus.Locked) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("State Locked").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).assertTextEquals("Action Locked")
        composeRule.onNode(progress, useUnmergedTree = true).assertExists()

        composeRule.mainClock.advanceTimeBy(DefaultRipDpiMotion.quickDurationMillis.toLong())
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `disabled motion replaces progress without an outgoing symbol`() {
        val state = showFeedback(motion = mutableStateOf(DefaultRipDpiMotion.copy(animationsEnabled = false)))

        updateState(state, state.value.copy(status = HomeConnectionActuatorStatus.Locked))

        composeRule.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `reduced motion fades progress without moving the headline or action`() {
        val reducedMotion = DefaultRipDpiMotion.copy(reducedMotion = true)
        val state = showFeedback(motion = mutableStateOf(reducedMotion))
        val progressBounds = composeRule.onNode(progress, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val headlineBounds = composeRule.onNodeWithText("State Engaging").fetchSemanticsNode().boundsInRoot
        val buttonBounds =
            composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).fetchSemanticsNode().boundsInRoot

        updateState(state, actuatorState(HomeConnectionActuatorStatus.Locked))

        assertEquals(
            progressBounds,
            composeRule.onNode(progress, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(
            headlineBounds.left,
            composeRule
                .onNodeWithText("State Locked")
                .fetchSemanticsNode()
                .boundsInRoot.left,
        )
        assertEquals(
            buttonBounds,
            composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).fetchSemanticsNode().boundsInRoot,
        )
        composeRule.mainClock.advanceTimeBy(reducedMotion.duration(reducedMotion.quickDurationMillis).toLong())
        composeRule.waitForIdle()
        composeRule.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `telemetry updates do not extend the result transition`() {
        val state = showFeedback()
        updateState(state, actuatorState(HomeConnectionActuatorStatus.Locked))
        composeRule.onNode(progress, useUnmergedTree = true).assertExists()
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.waitForIdle()

        updateState(state, state.value.copy(routeLabel = "Updated route"))
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Updated route").assertExists()
        composeRule.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `disabling motion during feedback removes outgoing progress immediately`() {
        val motion = mutableStateOf(DefaultRipDpiMotion)
        val state = showFeedback(motion = motion)
        updateState(state, actuatorState(HomeConnectionActuatorStatus.Locked))
        composeRule.onNode(progress, useUnmergedTree = true).assertExists()

        composeRule.runOnUiThread { motion.value = DefaultRipDpiMotion.copy(animationsEnabled = false) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()

        composeRule.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `connecting can be cancelled while its symbol enters`() {
        var cancellations = 0
        val state =
            showFeedback(
                initialStatus = HomeConnectionActuatorStatus.Open,
                onDeactivate = { cancellations++ },
            )
        updateState(state, actuatorState(HomeConnectionActuatorStatus.Engaging))

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).assertIsEnabled().performClick()

        assertEquals(1, cancellations)
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorDisconnectConfirm).assertDoesNotExist()
    }

    @Test
    fun `fault retry acts on the current state while progress fades`() {
        var retries = 0
        var cancellations = 0
        val state = showFeedback(onActivate = { retries++ }, onDeactivate = { cancellations++ })
        updateState(state, actuatorState(HomeConnectionActuatorStatus.Fault))
        composeRule.onNode(progress, useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).assertIsEnabled().performClick()

        assertEquals(1, retries)
        assertEquals(0, cancellations)
        composeRule.onNodeWithText("State Fault").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        composeRule.onNode(progress).assertDoesNotExist()
    }

    private fun showFeedback(
        initialStatus: HomeConnectionActuatorStatus = HomeConnectionActuatorStatus.Engaging,
        motion: MutableState<RipDpiMotion> = mutableStateOf(DefaultRipDpiMotion),
        onActivate: () -> Unit = {},
        onDeactivate: () -> Unit = {},
    ): MutableState<HomeConnectionActuatorUiState> {
        val state = mutableStateOf(actuatorState(initialStatus))
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides motion.value) {
                    RipDpiConnectionActuator(
                        state = state.value,
                        onActivate = onActivate,
                        onDeactivate = onDeactivate,
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        return state
    }

    private fun updateState(
        state: MutableState<HomeConnectionActuatorUiState>,
        value: HomeConnectionActuatorUiState,
    ) {
        composeRule.runOnUiThread { state.value = value }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
    }

    private val progress =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate,
        )
}
