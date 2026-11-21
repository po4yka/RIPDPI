package com.poyka.ripdpi.ui.screens.relayeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
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
class RelayValueFieldsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `port field reports a value outside the port range`() {
        composeRule.setContent {
            RipDpiTheme {
                RelayPortField(RelayValueField(label = "Port", testTag = "port"), value = "70000", onValueChange = {})
            }
        }

        composeRule.onNodeWithText("Value is outside the allowed range.").assertExists()
    }

    @Test
    fun `port field shows no range error for a port in range`() {
        composeRule.setContent {
            RipDpiTheme {
                RelayPortField(RelayValueField(label = "Port", testTag = "port"), value = "443", onValueChange = {})
            }
        }

        composeRule.onNodeWithText("Value is outside the allowed range.").assertDoesNotExist()
    }

    @Test
    fun `host field drops the spaces a pasted address carries`() {
        var host by mutableStateOf("")
        composeRule.setContent {
            RipDpiTheme {
                RelayHostField(RelayValueField(label = "Server", testTag = "host"), value = host) { host = it }
            }
        }

        composeRule.onNodeWithTag("host").performTextReplacement(" relay.example \n")

        composeRule.runOnIdle { assertEquals("relay.example", host) }
    }
}
