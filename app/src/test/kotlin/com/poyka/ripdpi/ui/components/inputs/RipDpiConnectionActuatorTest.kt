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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.HomeConnectionActuatorStage
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
    fun `every state exposes one readable button with separate status`() {
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
            composeRule.onAllNodesWithTag(RipDpiTestTags.ConnectionActuatorButton).assertCountEquals(1)
            composeRule
                .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
                .assertHasRole(Role.Button)
                .assertHasClickAction()
                .assertTextEquals("Action $status")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assertIsDisplayed()
            composeRule.onNodeWithText("State $status").assertIsDisplayed()
        }
    }

    @Test
    fun `route information cannot activate or disconnect the connection`() {
        var actions = 0
        composeRule.setActuator(
            state = actuatorState(HomeConnectionActuatorStatus.Locked),
            onActivate = { actions++ },
            onDeactivate = { actions++ },
        )
        listOf(RipDpiTestTags.ConnectionActuatorRouteLabel, RipDpiTestTags.ConnectionActuatorTerminalLabel)
            .forEach { tag ->
                composeRule
                    .onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
                    .performTouchInput { click() }
            }
        composeRule.runOnIdle { assertEquals(0, actions) }
    }

    @Test
    fun `waiting connection does not display completed stage indicators`() {
        composeRule.setActuator(state = actuatorState(HomeConnectionActuatorStatus.Engaging))
        HomeConnectionActuatorStage.entries.forEach { stage ->
            composeRule.onAllNodesWithTag(RipDpiTestTags.homeConnectionStage(stage.stableKey)).assertCountEquals(0)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `action and route wrap at 320dp with large RTL text`() {
        val route = "Локальная VPN через выбранный сетевой маршрут"
        val action = "Подтвердить отключение соединения"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(320.dp)) {
                        RipDpiConnectionActuator(
                            state =
                                actuatorState(HomeConnectionActuatorStatus.Locked).copy(
                                    routeLabel = route,
                                    trailingLabel = "Напрямую",
                                    actionLabel = action,
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
        val buttonBounds =
            composeRule
                .onNodeWithTag(
                    RipDpiTestTags.ConnectionActuatorButton,
                ).fetchSemanticsNode()
                .boundsInRoot
        listOf(route, action, "Напрямую").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            val node =
                composeRule
                    .onNodeWithText(label, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    .fetchSemanticsNode()
            val layout = layouts.single()
            assertFalse(
                "Overflow for $label: ${layout.size}, ${node.boundsInRoot}, " +
                    "paragraph=${layout.multiParagraph.height}, height=${layout.didOverflowHeight}, " +
                    "width=${layout.didOverflowWidth}, lineBottom=${layout.getLineBottom(layout.lineCount - 1)}",
                layout.hasVisualOverflow,
            )
            assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
            assertTrue(node.boundsInRoot.left >= buttonBounds.left)
            assertTrue(node.boundsInRoot.right <= buttonBounds.right)
            if (label == action) {
                assertTrue(node.boundsInRoot.top >= buttonBounds.top)
                assertTrue(node.boundsInRoot.bottom <= buttonBounds.bottom)
            } else {
                assertTrue(node.boundsInRoot.bottom <= buttonBounds.top)
            }
        }
        assertTrue(buttonBounds.height >= 48f)
    }
}
