package com.poyka.ripdpi.ui.components.inputs

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiConnectionActuatorInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val HalfRailSwipeFraction = 0.5f

        /**
         * A pull that clears the old 28% release threshold and falls short of
         * the shared 72% one, whatever the terminal slot measures out to.
         */
        const val ShortPullFraction = 0.30f
    }

    @Test
    fun `physical tap activates open actuator`() {
        var activations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Open),
            onActivate = { activations++ },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertHasClickAction()
            .performTouchInput { click() }

        composeRule.runOnIdle {
            assertEquals(1, activations)
        }
    }

    @Test
    fun `right drag past threshold activates open actuator`() {
        var activated = false
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Open),
            onActivate = { activated = true },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { swipeRight() }

        composeRule.runOnIdle {
            assertTrue(activated)
        }
    }

    /**
     * The commit threshold is measured against carriage travel, not rail width.
     * When it was measured against rail width the carriage pinned at the end of
     * its run while the gesture was still short of firing, so a drag that
     * visually completed did nothing. A half-width swipe clears 72% of travel
     * but not 72% of the rail, which is exactly the window that used to be dead.
     */
    @Test
    fun `drag covering carriage travel activates while short of rail width`() {
        var activated = false
        composeRule.setContent {
            RipDpiTheme {
                Box(modifier = Modifier.requiredWidth(411.dp)) {
                    RipDpiConnectionActuator(
                        state = actuatorState(HomeConnectionActuatorStatus.Open),
                        onActivate = { activated = true },
                        onDeactivate = {},
                        modifier = Modifier.fillMaxWidth(),
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput {
            swipe(
                start = Offset(left + 1f, centerY),
                end = Offset(left + width * HalfRailSwipeFraction, centerY),
            )
        }

        composeRule.runOnIdle { assertTrue(activated) }
    }

    @Test
    fun `left drag past threshold deactivates locked actuator`() {
        var deactivated = false
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivated = true },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle {
            assertTrue(deactivated)
        }
    }

    /**
     * A single stray touch still must not drop a live line, but the tap has to
     * land: WCAG 2.2 SC 2.5.7 wants the release reachable with one pointer that
     * never drags, and the earlier withheld tap simply did nothing at all.
     */
    @Test
    fun `first release tap arms and the second commits`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, deactivations) }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    /** Arming renames the action wherever that name is read. */
    @Test
    fun `arming a release renames the action on the lane and on the switch`() {
        val state = actuatorState(HomeConnectionActuatorStatus.Locked)
        composeRule.setActuator(state = state)
        val confirmLabel =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getString(R.string.home_connection_actuator_action_confirm_release)

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorActionLabel, useUnmergedTree = true)
            .assertTextEquals(state.actionLabel)

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { click() }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorActionLabel, useUnmergedTree = true)
            .assertTextEquals(confirmLabel)
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(confirmLabel),
                ),
            )
    }

    /**
     * Releasing used to commit at 28% of travel while engaging took 72%, so the
     * destructive direction was the cheaper gesture by a factor of two and a
     * half. This pull clears the old release threshold and falls short of the
     * shared one.
     */
    @Test
    fun `short pull no longer releases a locked line`() {
        var deactivations = 0
        composeRule.setContent {
            RipDpiTheme {
                Box(modifier = Modifier.requiredWidth(411.dp)) {
                    RipDpiConnectionActuator(
                        state = actuatorState(HomeConnectionActuatorStatus.Locked),
                        onActivate = {},
                        onDeactivate = { deactivations++ },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput {
            swipe(
                start = Offset(right - 1f, centerY),
                end = Offset(right - width * ShortPullFraction, centerY),
            )
        }

        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }

    /**
     * The commit threshold used to announce itself only once the finger came
     * off, so a gesture that had already done enough looked and felt exactly
     * like one that had not, at the one moment the user could still back out.
     */
    @Test
    fun `crossing the commit threshold ticks while the finger is still down`() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            RipDpiTheme {
                Box(modifier = Modifier.requiredWidth(411.dp)) {
                    RipDpiConnectionActuator(
                        state = actuatorState(HomeConnectionActuatorStatus.Open),
                        onActivate = {},
                        onDeactivate = {},
                        modifier = Modifier.fillMaxWidth(),
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }
        val shadowView = shadowOf(view)

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput {
            down(Offset(left + 1f, centerY))
            moveTo(Offset(left + width * ShortPullFraction, centerY))
        }
        composeRule.runOnIdle {
            assertNotEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                shadowView.lastHapticFeedbackPerformed(),
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput {
            moveTo(Offset(left + width * HalfRailSwipeFraction, centerY))
        }
        composeRule.runOnIdle {
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                shadowView.lastHapticFeedbackPerformed(),
            )
        }
    }

    /**
     * The live threshold reading and the release read the same function, so a
     * gesture that crosses and retreats must not latch on the crossing.
     */
    @Test
    fun `drag that retreats below the threshold does not commit`() {
        var activations = 0
        composeRule.setContent {
            RipDpiTheme {
                Box(modifier = Modifier.requiredWidth(411.dp)) {
                    RipDpiConnectionActuator(
                        state = actuatorState(HomeConnectionActuatorStatus.Open),
                        onActivate = { activations++ },
                        onDeactivate = {},
                        modifier = Modifier.fillMaxWidth(),
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performTouchInput {
            down(Offset(left + 1f, centerY))
            moveTo(Offset(left + width * HalfRailSwipeFraction, centerY))
            moveTo(Offset(left + width * ShortPullFraction, centerY))
            up()
        }

        composeRule.runOnIdle { assertEquals(0, activations) }
    }

    /** A drag is the same action by another route, so it takes the control back. */
    @Test
    fun `drag past threshold releases without a second tap`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    /** Assistive technology takes the same two steps, and is told about the first. */
    @Test
    fun `accessibility action arms then releases a locked line`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(0, deactivations) }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    /** A hardware keyboard or D-pad rides the same toggleable, not a rival handler. */
    @Test
    fun `keyboard commit still releases a locked line`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        // One keypress arms and no more: two handlers racing the same key would
        // land here as a release nobody confirmed.
        composeRule.runOnIdle { assertEquals(0, deactivations) }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    @Test
    fun `connecting actuator accepts deactivation but not activation gestures`() {
        var activated = false
        var deactivated = false
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Engaging),
            onActivate = { activated = true },
            onDeactivate = { deactivated = true },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { swipeRight() }
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle {
            assertFalse(activated)
            assertTrue(deactivated)
        }
    }
}
