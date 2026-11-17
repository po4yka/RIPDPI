package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusModeSummaryFacet
import com.poyka.ripdpi.ui.screens.status.compileStrategyPassageScene
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
class RipDpiConnectionStrategyPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `strategy steps stay visible and the labeled settings open on demand above the action`() {
        composeRule.setActuator(
            actuatorState(StatusConnectionActuatorStatus.Locked).copy(
                strategyLabel = "tcp: split(host+1)",
                strategyFacets = persistentListOf(StatusModeSummaryFacet("DNS", "AdGuard DNS DoH")),
            ),
        )
        composeRule.onNodeWithText("tcp: split(host+1)").assertIsDisplayed()
        composeRule.onNodeWithText("AdGuard DNS DoH").assertDoesNotExist()
        // A text toggle, so the panel does not read as a dropdown.
        composeRule.onNodeWithText("Details").assertIsDisplayed()

        composeRule.onNodeWithText("tcp: split(host+1)").performClick()

        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNodeWithText("DNS").assertIsDisplayed()
        composeRule.onNodeWithText("AdGuard DNS DoH").assertIsDisplayed()
        val panel = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorStrategyPanel).fetchSemanticsNode()
        val action = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).fetchSemanticsNode()
        assertTrue(panel.boundsInRoot.bottom <= action.boundsInRoot.top)
    }

    @Test
    fun `a strategy without settings is named but not expandable`() {
        composeRule.setActuator(
            actuatorState(StatusConnectionActuatorStatus.Locked).copy(strategyLabel = "tcp: none"),
        )
        composeRule.onNodeWithText("tcp: none").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun `a passage names the family and keeps the chain and the TalkBack name`() {
        composeRule.setActuator(
            actuatorState(StatusConnectionActuatorStatus.Locked).copy(
                strategyLabel = "tcp: split(host+1)",
                strategyFacets = persistentListOf(StatusModeSummaryFacet("DNS", "AdGuard DNS DoH")),
                strategyPassage =
                    compileStrategyPassageScene(
                        listOf(TcpChainStepModel(TcpChainStepKind.Split, "host+1")),
                        emptyList(),
                    ),
            ),
        )
        // Closed, the panel is one row: the family name. The scene and the chain open on a tap.
        composeRule.onNodeWithText("Packet split").assertIsDisplayed()
        composeRule.onNodeWithText("tcp: split(host+1)").assertDoesNotExist()
        composeRule
            .onNode(hasContentDescription("Bypass strategy: The packet is sent in parts."))
            .assertDoesNotExist()

        composeRule.onNodeWithText("Packet split").performClick()

        composeRule.onNodeWithText("tcp: split(host+1)").assertIsDisplayed()
        composeRule.onNodeWithText("Bypass strategy").assertDoesNotExist()
        composeRule
            .onNode(hasContentDescription("Bypass strategy: The packet is sent in parts."))
            .assertIsDisplayed()
        // The scheme in words: the filter reads two parts of the sample name and finds no match.
        composeRule.onNodeWithText("DPI reads").assertIsDisplayed()
        composeRule.onNodeWithText("ite.test").assertIsDisplayed()
        composeRule.onNodeWithText("no match").assertIsDisplayed()
        composeRule.onNodeWithText("Drawn from settings, not from captured traffic.").assertIsDisplayed()
    }

    @Test
    fun `a passage without settings still opens its scene on a tap`() {
        composeRule.setActuator(
            actuatorState(StatusConnectionActuatorStatus.Locked).copy(
                strategyLabel = "tcp: none",
                strategyPassage = compileStrategyPassageScene(emptyList(), emptyList()),
            ),
        )
        composeRule.onNodeWithText("No changes").performClick()

        composeRule.onNodeWithText("tcp: none").assertIsDisplayed()
        composeRule
            .onNode(hasContentDescription("Bypass strategy: The packet is sent unchanged."))
            .assertIsDisplayed()
        composeRule.onNodeWithText("whole name").assertIsDisplayed()
    }

    @Test
    fun `two families are named together and more become a step count`() {
        val split = TcpChainStepModel(TcpChainStepKind.Split, "host+1")
        val tls = TcpChainStepModel(TcpChainStepKind.TlsRec, "host")
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    Text(strategyPassageName(compileStrategyPassageScene(listOf(tls, split), emptyList())))
                    Text(
                        strategyPassageName(
                            compileStrategyPassageScene(
                                listOf(tls, split),
                                listOf(UdpChainStepModel(1, UdpChainStepKind.FakeBurst)),
                            ),
                        ),
                    )
                    Text(strategyPassageName(compileStrategyPassageScene(emptyList(), emptyList())))
                }
            }
        }
        composeRule.onNodeWithText("TLS records + Packet split").assertIsDisplayed()
        composeRule.onNodeWithText("Steps: 3").assertIsDisplayed()
        composeRule.onNodeWithText("No changes").assertIsDisplayed()
    }

    @Test
    fun `no strategy hides the panel`() {
        composeRule.setActuator(actuatorState(StatusConnectionActuatorStatus.Locked))
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorStrategyPanel).assertDoesNotExist()
    }
}
