package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.failover.ActiveTransportDescriptor
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class SimpleProtocolLabelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `xhttp descriptor renders localized protocol label`() {
        render(
            ActiveTransportDescriptor(
                protocolKind = RelayKindVless,
                vlessTransport = RelayVlessTransportXhttp,
            ),
        )

        composeRule.onNodeWithText("VLESS+XHTTP").assertIsDisplayed()
    }

    @Test
    fun `generic vless descriptor keeps honest fallback label`() {
        render(
            ActiveTransportDescriptor(
                protocolKind = RelayKindVless,
                vlessTransport = RelayVlessTransportRealityTcp,
            ),
        )

        composeRule.onNodeWithText(RelayKindVless).assertIsDisplayed()
    }

    private fun render(descriptor: ActiveTransportDescriptor) {
        composeRule.setContent {
            RipDpiTheme {
                Text(descriptor.toProtocolLabel())
            }
        }
    }
}
