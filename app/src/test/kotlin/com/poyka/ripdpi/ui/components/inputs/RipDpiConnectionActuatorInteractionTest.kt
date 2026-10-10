package com.poyka.ripdpi.ui.components.inputs

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.ui.components.EnableKeyboardInput
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import android.view.KeyEvent as AndroidKeyEvent

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiConnectionActuatorInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tap connects and retries without confirmation`() {
        var activations = 0
        var state by mutableStateOf(actuatorState(HomeConnectionActuatorStatus.Open))
        composeRule.setContent {
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = state,
                    onActivate = { activations++ },
                    onDeactivate = {},
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(1, activations)
            state = actuatorState(HomeConnectionActuatorStatus.Fault)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(2, activations) }
    }

    @Test
    fun `connecting action cancels with one tap`() {
        var activations = 0
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Engaging),
            onActivate = { activations++ },
            onDeactivate = { deactivations++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(0, activations)
            assertEquals(1, deactivations)
        }
    }

    @Test
    fun `live connection needs confirmation and names the next action`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Degraded),
            onDeactivate = { deactivations++ },
        )
        val confirm =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getString(R.string.home_connection_actuator_action_confirm_release)
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, deactivations) }
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertTextEquals(confirm)
            .performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    @Test
    fun `accessibility action confirms before disconnection`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.ConnectionActuatorButton,
            ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(0, deactivations) }
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.ConnectionActuatorButton,
            ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    @Test
    fun `keyboard confirms before disconnection`() {
        var deactivations = 0
        composeRule.setActuator(
            keyboardInput = true,
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(0, deactivations) }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    @Test
    fun `key release after a state change cannot arm the new action`() {
        var deactivations = 0
        lateinit var view: View
        var state by mutableStateOf(actuatorState(HomeConnectionActuatorStatus.Locked))
        composeRule.setContent {
            EnableKeyboardInput()
            view = LocalView.current
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = state,
                    onActivate = {},
                    onDeactivate = { deactivations++ },
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).requestFocus()
        composeRule.runOnIdle {
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER))
        }
        composeRule.runOnIdle { state = actuatorState(HomeConnectionActuatorStatus.Degraded) }
        composeRule.runOnIdle {
            view.dispatchKeyEvent(
                AndroidKeyEvent(0L, 1_000L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER, 1),
            )
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_ENTER))
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }

    @Test
    fun `held enter repeat commits the connection callback once`() {
        var activations = 0
        lateinit var view: View
        composeRule.setContent {
            EnableKeyboardInput()
            view = LocalView.current
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = actuatorState(HomeConnectionActuatorStatus.Open),
                    onActivate = { activations++ },
                    onDeactivate = {},
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).requestFocus()
        composeRule.runOnIdle {
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER))
            view.dispatchKeyEvent(
                AndroidKeyEvent(0L, 100L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER, 1),
            )
            view.dispatchKeyEvent(
                AndroidKeyEvent(0L, 200L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER, 2),
            )
            assertEquals(0, activations)
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_ENTER))
            assertEquals(1, activations)
        }
    }

    @Test
    fun `canceled key release cannot connect`() {
        var activations = 0
        lateinit var view: View
        composeRule.setContent {
            EnableKeyboardInput()
            view = LocalView.current
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = actuatorState(HomeConnectionActuatorStatus.Open),
                    onActivate = { activations++ },
                    onDeactivate = {},
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).requestFocus()
        composeRule.runOnIdle {
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER))
            view.dispatchKeyEvent(
                AndroidKeyEvent.changeFlags(
                    AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_ENTER),
                    AndroidKeyEvent.FLAG_CANCELED,
                ),
            )
            assertEquals(0, activations)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun `expired confirmation cannot disconnect on the next tap`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { ShadowSystemClock.advanceBy(Duration.ofMillis(4_001L)) }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }

    @Test
    fun `state transition clears a pending disconnect confirmation`() {
        var deactivations = 0
        var state by mutableStateOf(actuatorState(HomeConnectionActuatorStatus.Locked))
        composeRule.setContent {
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = state,
                    onActivate = {},
                    onDeactivate = { deactivations++ },
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { state = actuatorState(HomeConnectionActuatorStatus.Degraded) }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }

    @Test
    fun `Android managed action is disabled even for direct semantics activation`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked).copy(deactivationEnabled = false),
            onDeactivate = { deactivations++ },
        )
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertIsNotEnabled()
            .performTouchInput { click() }
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }

    @Test
    fun `swipe cannot bypass connection actions or the disconnect guard`() {
        var actions = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onActivate = { actions++ },
            onDeactivate = { actions++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { swipeRight() }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, actions) }
    }
}
