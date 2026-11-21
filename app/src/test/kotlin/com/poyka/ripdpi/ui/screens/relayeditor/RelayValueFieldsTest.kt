package com.poyka.ripdpi.ui.screens.relayeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
    fun `port outside the range names the range in place of the general error`() {
        composeRule.setContent {
            RipDpiTheme {
                RelayPortField(
                    RelayValueField(label = "Port", errorText = "General error", testTag = "port"),
                    value = "70000",
                    onValueChange = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(
                "port",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "From 1 to 65535"))
        composeRule.onNodeWithText("General error").assertDoesNotExist()
    }

    @Test
    fun `port field shows no error for a port in range`() {
        composeRule.setContent {
            RipDpiTheme {
                RelayPortField(RelayValueField(label = "Port", testTag = "port"), value = "443", onValueChange = {})
            }
        }

        composeRule.onNodeWithTag("port").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun `an empty field the editor rejects reads as required`() {
        val texts = mutableListOf<String?>()
        composeRule.setContent {
            RipDpiTheme {
                texts += fieldErrorText(value = " ", hasError = true)
                texts += fieldErrorText(value = "not-a-key", hasError = true)
                texts += fieldErrorText(value = "", hasError = false)
            }
        }

        composeRule.runOnIdle {
            assertEquals(listOf("This field is required.", "Not a valid value for this field.", null), texts.take(3))
        }
    }

    @Test
    fun `host field drops the spaces a pasted address carries`() {
        var host by mutableStateOf("")
        composeRule.setContent {
            RipDpiTheme {
                RelayHostField(RelayValueField(label = "Server", testTag = "host"), value = host, onValueChange = {
                    host =
                        it
                })
            }
        }

        composeRule.onNodeWithTag("host").performTextReplacement(" relay.example \n")

        composeRule.runOnIdle { assertEquals("relay.example", host) }
    }
}
