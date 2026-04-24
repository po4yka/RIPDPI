package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.poyka.ripdpi.activities.HomeConnectionActuatorStage
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RipDpiConnectionActuatorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `click fallback activates open actuator`() {
        var activations = 0
        setActuator(state = actuatorState(HomeConnectionActuatorStatus.Open), onActivate = { activations++ })

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeConnectionButton)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, activations)
        }
    }

    @Test
    fun `right drag past threshold activates open actuator`() {
        var activated = false
        setActuator(state = actuatorState(HomeConnectionActuatorStatus.Open), onActivate = { activated = true })

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeConnectionButton)
            .performTouchInput { swipeRight() }

        composeRule.runOnIdle {
            assertTrue(activated)
        }
    }

    @Test
    fun `left drag past threshold deactivates locked actuator`() {
        var deactivated = false
        setActuator(state = actuatorState(HomeConnectionActuatorStatus.Locked), onDeactivate = { deactivated = true })

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeConnectionButton)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle {
            assertTrue(deactivated)
        }
    }

    @Test
    fun `connecting actuator ignores activation and deactivation gestures`() {
        var activated = false
        var deactivated = false
        setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Engaging),
            onActivate = { activated = true },
            onDeactivate = { deactivated = true },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeConnectionButton)
            .performTouchInput { swipeRight() }
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeConnectionButton)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle {
            assertFalse(activated)
            assertFalse(deactivated)
        }
    }

    @Test
    fun `route label and all stage tags are visible`() {
        setActuator(state = actuatorState(HomeConnectionActuatorStatus.Degraded))

        composeRule.onNodeWithTag(RipDpiTestTags.HomeConnectionRouteLabel).assertIsDisplayed()
        HomeConnectionActuatorStage.entries.forEach { stage ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.homeConnectionStage(stage.stableKey))
                .assertIsDisplayed()
        }
    }

    private fun setActuator(
        state: HomeConnectionActuatorUiState,
        onActivate: () -> Unit = {},
        onDeactivate: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = state,
                    onActivate = onActivate,
                    onDeactivate = onDeactivate,
                    testTag = RipDpiTestTags.HomeConnectionButton,
                )
            }
        }
    }

    private fun actuatorState(status: HomeConnectionActuatorStatus): HomeConnectionActuatorUiState =
        HomeConnectionActuatorUiState(
            status = status,
            leadingLabel = "Open",
            trailingLabel = "Secure",
            routeLabel = "Local VPN",
            statusDescription = "Secure line",
            actionLabel = "Toggle",
            carriageFraction =
                when (status) {
                    HomeConnectionActuatorStatus.Open -> {
                        0f
                    }

                    HomeConnectionActuatorStatus.Engaging -> {
                        0.48f
                    }

                    HomeConnectionActuatorStatus.Locked,
                    HomeConnectionActuatorStatus.Degraded,
                    -> {
                        1f
                    }

                    HomeConnectionActuatorStatus.Fault -> {
                        0.68f
                    }
                },
            stages =
                persistentListOf(
                    stage(HomeConnectionActuatorStage.Network, HomeConnectionActuatorStageState.Complete),
                    stage(HomeConnectionActuatorStage.Dns, stageStateForDns(status)),
                    stage(HomeConnectionActuatorStage.Handshake, HomeConnectionActuatorStageState.Complete),
                    stage(HomeConnectionActuatorStage.Tunnel, stageStateForTunnel(status)),
                    stage(HomeConnectionActuatorStage.Route, HomeConnectionActuatorStageState.Complete),
                ),
        )

    private fun stageStateForDns(status: HomeConnectionActuatorStatus): HomeConnectionActuatorStageState =
        if (status == HomeConnectionActuatorStatus.Degraded) {
            HomeConnectionActuatorStageState.Warning
        } else {
            HomeConnectionActuatorStageState.Complete
        }

    private fun stageStateForTunnel(status: HomeConnectionActuatorStatus): HomeConnectionActuatorStageState =
        if (status == HomeConnectionActuatorStatus.Fault) {
            HomeConnectionActuatorStageState.Failed
        } else {
            HomeConnectionActuatorStageState.Complete
        }

    private fun stage(
        stage: HomeConnectionActuatorStage,
        state: HomeConnectionActuatorStageState,
    ): HomeConnectionActuatorStageUiState =
        HomeConnectionActuatorStageUiState(
            stage = stage,
            label =
                when (stage) {
                    HomeConnectionActuatorStage.Network -> "Network"
                    HomeConnectionActuatorStage.Dns -> "DNS"
                    HomeConnectionActuatorStage.Handshake -> "Handshake"
                    HomeConnectionActuatorStage.Tunnel -> "Tunnel"
                    HomeConnectionActuatorStage.Route -> "Route"
                },
            state = state,
        )
}
