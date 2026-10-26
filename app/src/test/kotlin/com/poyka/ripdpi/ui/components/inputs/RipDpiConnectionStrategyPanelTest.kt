package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeModeSummaryFacet
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiConnectionStrategyPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val explainer =
        RuntimeEnvironment.getApplication().getString(R.string.home_connection_strategy_explainer)

    @Test
    fun `strategy steps stay visible and the labeled settings open on demand above the action`() {
        composeRule.setActuator(
            actuatorState(HomeConnectionActuatorStatus.Locked).copy(
                strategyLabel = "tcp: split(host+1)",
                strategyFacets = persistentListOf(HomeModeSummaryFacet("DNS", "AdGuard DNS DoH")),
            ),
        )
        composeRule.onNodeWithText("tcp: split(host+1)").assertIsDisplayed()
        composeRule.onNodeWithText(explainer).assertDoesNotExist()
        composeRule.onNodeWithText("AdGuard DNS DoH").assertDoesNotExist()
        // A text toggle, so the panel does not read as a dropdown.
        composeRule.onNodeWithText("Details").assertIsDisplayed()

        composeRule.onNodeWithText("tcp: split(host+1)").performClick()

        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNodeWithText(explainer).assertIsDisplayed()
        composeRule.onNodeWithText("DNS").assertIsDisplayed()
        composeRule.onNodeWithText("AdGuard DNS DoH").assertIsDisplayed()
        val panel = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorStrategyPanel).fetchSemanticsNode()
        val action = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).fetchSemanticsNode()
        assertTrue(panel.boundsInRoot.bottom <= action.boundsInRoot.top)
    }

    @Test
    fun `a profile without settings is named but not expandable`() {
        composeRule.setActuator(
            actuatorState(HomeConnectionActuatorStatus.Locked).copy(strategyLabel = "Home Wi-Fi profile"),
        )
        composeRule.onNodeWithText("Home Wi-Fi profile").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun `no strategy hides the panel`() {
        composeRule.setActuator(actuatorState(HomeConnectionActuatorStatus.Locked))
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorStrategyPanel).assertDoesNotExist()
    }
}
