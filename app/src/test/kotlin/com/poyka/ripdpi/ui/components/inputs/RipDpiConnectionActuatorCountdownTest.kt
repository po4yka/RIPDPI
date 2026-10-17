package com.poyka.ripdpi.ui.components.inputs

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiConnectionActuatorCountdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `disconnect first press shows remaining time even when motion is disabled`() {
        var deactivations = 0
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(animationsEnabled = false),
                ) {
                    RipDpiConnectionActuator(
                        state = actuatorState(HomeConnectionActuatorStatus.Locked),
                        onActivate = {},
                        onDeactivate = { deactivations++ },
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performClick()

        val remaining =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getString(R.string.home_connection_actuator_confirmation_remaining, 4)
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining)
            .assertIsDisplayed()
            .assertTextEquals(remaining)
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationProgress).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }

    @Test
    fun `confirmation clears the countdown after the second press`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertIsDisplayed()

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performClick()

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationProgress).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    @Test
    fun `elapsed confirmation expires without another press`() {
        var deactivations = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onDeactivate = { deactivations++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertIsDisplayed()

        composeRule.runOnIdle {
            ShadowSystemClock.advanceBy(Duration.ofMillis(4_001L))
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationProgress).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, deactivations) }
    }
}
