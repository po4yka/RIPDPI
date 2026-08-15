package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.HomeConnectionActuatorStage
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
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

    /**
     * The control is named by what it does, and the headline and route stay
     * addressable on their own. Merging them into the switch made the route the
     * control's name and split the platform accessibility node into an
     * interactive but nameless half plus a named inert one.
     */
    @Test
    fun `switch is named by its action and leaves headline and route outside`() {
        val status = HomeConnectionActuatorStatus.Open
        composeRule.setActuator(state = actuatorState(status))

        val node =
            composeRule
                .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
                .fetchSemanticsNode()
        assertEquals(
            listOf("Action $status"),
            node.config[SemanticsProperties.ContentDescription],
        )

        composeRule.onNodeWithText("State $status").assertExists()
        composeRule.onNodeWithText("Local VPN").assertExists()
    }

    @Test
    fun `open actuator exposes off switch state`() {
        composeRule.setActuator(state = actuatorState(HomeConnectionActuatorStatus.Open))

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertHasRole(Role.Switch)
            .assertIsOff()
    }

    @Test
    fun `locked actuator exposes on switch state`() {
        composeRule.setActuator(state = actuatorState(HomeConnectionActuatorStatus.Locked))

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertHasRole(Role.Switch)
            .assertIsOn()
    }

    @Test
    fun `engaging actuator exposes indeterminate switch state`() {
        composeRule.setActuator(state = actuatorState(HomeConnectionActuatorStatus.Engaging))

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertHasRole(Role.Switch)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.Indeterminate,
                ),
            )
    }

    @Test
    fun `state action semantics matrix exposes one coherent switch node`() {
        val cases =
            listOf(
                ActuatorSemanticsCase(HomeConnectionActuatorStatus.Open, ToggleableState.Off, clickable = true),
                ActuatorSemanticsCase(
                    HomeConnectionActuatorStatus.Engaging,
                    ToggleableState.Indeterminate,
                    clickable = true,
                ),
                ActuatorSemanticsCase(HomeConnectionActuatorStatus.Locked, ToggleableState.On, clickable = true),
                ActuatorSemanticsCase(HomeConnectionActuatorStatus.Degraded, ToggleableState.On, clickable = true),
                ActuatorSemanticsCase(HomeConnectionActuatorStatus.Fault, ToggleableState.Off, clickable = true),
            )
        var state by mutableStateOf(actuatorState(cases.first().status))
        composeRule.setContent {
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = state,
                    onActivate = {},
                    onDeactivate = {},
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }

        cases.forEach { case ->
            composeRule.runOnIdle { state = actuatorState(case.status) }
            composeRule.waitForIdle()

            composeRule
                .onAllNodesWithTag(RipDpiTestTags.ConnectionActuatorButton, useUnmergedTree = true)
                .assertCountEquals(1)
            val node = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            node
                .assertHasRole(Role.Switch)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ToggleableState,
                        case.toggleableState,
                    ),
                ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            // The status string belongs to the headline, which is its own node and
            // its own live region. Carrying it here as well made TalkBack read the
            // status twice on every landing and every transition.
            composeRule.onNodeWithText("State ${case.status}").assertExists()
            if (case.clickable) {
                node.assertHasClickAction()
                assertEquals(
                    "Action ${case.status}",
                    node.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
                )
            } else {
                node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            }
        }
    }

    @Test
    fun `route label and all stage tags are visible`() {
        composeRule.setActuator(state = actuatorState(HomeConnectionActuatorStatus.Degraded))

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorRouteLabel, useUnmergedTree = true)
            .assertIsDisplayed()
        HomeConnectionActuatorStage.entries.forEach { stage ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.homeConnectionStage(stage.stableKey))
                .assertIsDisplayed()
        }
    }

    @Test
    fun `stable states hide pipeline while transitional and error states show it`() {
        var state by mutableStateOf(actuatorState(HomeConnectionActuatorStatus.Open))
        composeRule.setContent {
            RipDpiTheme {
                RipDpiConnectionActuator(
                    state = state,
                    onActivate = {},
                    onDeactivate = {},
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }

        HomeConnectionActuatorStatus.entries.forEach { status ->
            composeRule.runOnIdle { state = actuatorState(status) }
            composeRule.waitForIdle()
            val expectedCount =
                if (status == HomeConnectionActuatorStatus.Open || status == HomeConnectionActuatorStatus.Locked) {
                    0
                } else {
                    1
                }
            HomeConnectionActuatorStage.entries.forEach { stage ->
                composeRule
                    .onAllNodesWithTag(RipDpiTestTags.homeConnectionStage(stage.stableKey))
                    .assertCountEquals(expectedCount)
            }
        }
    }

    @Test
    fun `stage labels wrap without ellipsis at compact accessibility font scales`() {
        val labels =
            listOf(
                "Network handover",
                "Encrypted DNS resolver",
                "Secure handshake",
                "Protected tunnel",
                "Outbound route",
            )
        var fontScale by mutableStateOf(1.5f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(371.dp)) {
                        RipDpiConnectionActuator(
                            state =
                                actuatorState(HomeConnectionActuatorStatus.Engaging).copy(
                                    stages =
                                        HomeConnectionActuatorStage.entries
                                            .mapIndexed { index, stage ->
                                                HomeConnectionActuatorStageUiState(
                                                    stage = stage,
                                                    label = labels[index],
                                                    state = HomeConnectionActuatorStageState.Active,
                                                )
                                            }.let { persistentListOf(*it.toTypedArray()) },
                                ),
                            onActivate = {},
                            onDeactivate = {},
                            modifier = Modifier.fillMaxWidth(),
                            testTag = RipDpiTestTags.ConnectionActuatorButton,
                        )
                    }
                }
            }
        }

        listOf(1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            labels.forEachIndexed { index, label ->
                val textLayouts = mutableListOf<TextLayoutResult>()
                val textNode =
                    composeRule
                        .onNodeWithText(label, useUnmergedTree = true)
                        .assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                            action(textLayouts)
                        }.fetchSemanticsNode()
                val stageNode =
                    composeRule
                        .onNodeWithTag(
                            RipDpiTestTags.homeConnectionStage(HomeConnectionActuatorStage.entries[index].stableKey),
                        ).fetchSemanticsNode()
                assertTrue(
                    textLayouts.single().let { layout ->
                        (0 until layout.lineCount).none(layout::isLineEllipsized)
                    },
                )
                assertTrue(textNode.boundsInRoot.left >= stageNode.boundsInRoot.left)
                assertTrue(textNode.boundsInRoot.top >= stageNode.boundsInRoot.top)
                assertTrue(textNode.boundsInRoot.right <= stageNode.boundsInRoot.right)
                assertTrue(textNode.boundsInRoot.bottom <= stageNode.boundsInRoot.bottom)
            }
        }
    }

    @Test
    fun `route label stays readable when accessibility layout hides decorative rail`() {
        val routeLabel = "Local VPN through protected outbound route"
        var fontScale by mutableStateOf(1.5f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(371.dp)) {
                        RipDpiConnectionActuator(
                            state =
                                actuatorState(HomeConnectionActuatorStatus.Engaging).copy(
                                    routeLabel = routeLabel,
                                ),
                            onActivate = {},
                            onDeactivate = {},
                            modifier = Modifier.fillMaxWidth(),
                            testTag = RipDpiTestTags.ConnectionActuatorButton,
                        )
                    }
                }
            }
        }

        listOf(1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()

            val textLayouts = mutableListOf<TextLayoutResult>()
            val route =
                composeRule
                    .onNodeWithTag(RipDpiTestTags.ConnectionActuatorRouteLabel, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                        action(textLayouts)
                    }.fetchSemanticsNode()
            assertTrue(
                textLayouts.single().let { layout ->
                    (0 until layout.lineCount).none(layout::isLineEllipsized)
                },
            )
            assertTrue(route.boundsInRoot.width > 0f)
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.ConnectionActuatorRail, useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }

    @Test
    fun `accessibility layout keeps tap action but ignores horizontal drags`() {
        var fontScale by mutableStateOf(1.5f)
        var activations = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(371.dp)) {
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
        }

        listOf(1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            composeRule
                .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
                .assertHasClickAction()
                .performTouchInput { swipeRight() }
            composeRule.runOnIdle { assertEquals(0, activations) }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun `secure terminal label is measured without ellipsis at Pixel 7 default font`() {
        composeRule.setContent {
            RipDpiTheme {
                Box(modifier = Modifier.requiredWidth(411.dp)) {
                    RipDpiConnectionActuator(
                        state = actuatorState(HomeConnectionActuatorStatus.Locked),
                        onActivate = {},
                        onDeactivate = {},
                        modifier = Modifier.fillMaxWidth(),
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }

        val textLayouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorTerminalLabel, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(textLayouts) }

        val layout = textLayouts.single()
        assertEquals(1, layout.lineCount)
        assertFalse(layout.isLineEllipsized(0))
    }

    @Test
    fun `maximum accessibility font uses explicit route instead of decorative terminal`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(371.dp)) {
                        RipDpiConnectionActuator(
                            state =
                                actuatorState(HomeConnectionActuatorStatus.Locked).copy(
                                    trailingLabel = "Direct",
                                ),
                            onActivate = {},
                            onDeactivate = {},
                            modifier = Modifier.fillMaxWidth(),
                            testTag = RipDpiTestTags.ConnectionActuatorButton,
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorRouteLabel, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithTag(RipDpiTestTags.ConnectionActuatorTerminalLabel, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `endpoint labels collapse before overlapping the carriage at narrow maximum font`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(411.dp)) {
                        RipDpiConnectionActuator(
                            state = actuatorState(HomeConnectionActuatorStatus.Engaging),
                            onActivate = {},
                            onDeactivate = {},
                            modifier = Modifier.fillMaxWidth(),
                            testTag = RipDpiTestTags.ConnectionActuatorButton,
                        )
                    }
                }
            }
        }

        composeRule
            .onAllNodesWithTag(RipDpiTestTags.ConnectionActuatorTerminalLabel, useUnmergedTree = true)
            .assertCountEquals(0)
    }
}

private data class ActuatorSemanticsCase(
    val status: HomeConnectionActuatorStatus,
    val toggleableState: ToggleableState,
    val clickable: Boolean,
)
