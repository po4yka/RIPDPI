package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiTextFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `input transformation filters mixed replacement before state commit`() {
        val state = TextFieldState()
        setField(
            state = state,
            inputTransformation =
                InputTransformation.byValue { _, proposed -> proposed.filter(Char::isDigit) },
        )

        composeRule.onNodeWithTag(TestTag).performTextReplacement("3a4")

        composeRule.runOnIdle { assertEquals("34", state.text.toString()) }
        composeRule.onNodeWithTag(TestTag).assertTextEquals("34")
    }

    @Test
    fun `programmatic state replacement is rendered`() {
        val state = TextFieldState(initialText = "before")
        setField(state = state)

        composeRule.runOnIdle { state.setTextAndPlaceCursorAtEnd("after") }

        composeRule.onNodeWithTag(TestTag).assertTextEquals("after")
    }

    @Test
    fun `screen state bridge forwards edits and applies authoritative replacements`() {
        var externalText by mutableStateOf("initial")
        var replacementRevision by mutableStateOf(0)
        lateinit var fieldState: TextFieldState
        composeRule.setContent {
            RipDpiTheme {
                fieldState =
                    rememberRipDpiTextFieldState(
                        value = externalText,
                        onValueChange = { externalText = it },
                        replacementKey = replacementRevision,
                    )
                RipDpiTextField(
                    state = fieldState,
                    decoration = RipDpiTextFieldDecoration(testTag = TestTag),
                )
            }
        }

        composeRule.onNodeWithTag(TestTag).performTextReplacement("edited")
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("edited", externalText) }

        composeRule.runOnIdle {
            externalText = "replaced"
            replacementRevision++
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals("replaced", fieldState.text.toString()) }
        composeRule.onNodeWithTag(TestTag).assertTextEquals("replaced")
    }

    @Test
    fun `delayed edit echoes do not overwrite newer text or selection`() {
        var externalText by mutableStateOf("initial")
        val edits = mutableListOf<String>()
        lateinit var fieldState: TextFieldState
        composeRule.setContent {
            RipDpiTheme {
                fieldState =
                    rememberRipDpiTextFieldState(
                        value = externalText,
                        onValueChange = edits::add,
                    )
                RipDpiTextField(
                    state = fieldState,
                    decoration = RipDpiTextFieldDecoration(testTag = TestTag),
                )
            }
        }

        composeRule.onNodeWithTag(TestTag).performTextReplacement("first")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTag).performTextReplacement("second")
        composeRule.waitForIdle()

        composeRule.runOnIdle { externalText = "second" }
        composeRule.waitForIdle()
        composeRule.runOnIdle { externalText = "first" }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("second", fieldState.text.toString())
            assertEquals(TextRange("second".length), fieldState.selection)
        }
    }

    @Test
    fun `replacement key forces an intentional reset to a pending edit value`() {
        var externalText by mutableStateOf("initial")
        var replacementRevision by mutableStateOf(0)
        lateinit var fieldState: TextFieldState
        composeRule.setContent {
            RipDpiTheme {
                fieldState =
                    rememberRipDpiTextFieldState(
                        value = externalText,
                        onValueChange = {},
                        replacementKey = replacementRevision,
                    )
                RipDpiTextField(
                    state = fieldState,
                    decoration = RipDpiTextFieldDecoration(testTag = TestTag),
                )
            }
        }

        composeRule.onNodeWithTag(TestTag).performTextReplacement("first")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTag).performTextReplacement("second")
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            externalText = "first"
            replacementRevision++
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals("first", fieldState.text.toString()) }
    }

    @Test
    fun `secure field exposes password semantics`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiSecureTextField(
                    state = TextFieldState(initialText = "secret"),
                    autofillPolicy = RipDpiAutofillPolicy.Disabled,
                    decoration = RipDpiTextFieldDecoration(testTag = TestTag),
                )
            }
        }

        val semantics = composeRule.onNodeWithTag(TestTag).fetchSemanticsNode().config
        assertTrue(semantics.contains(SemanticsProperties.Password))
    }

    @Test
    fun `autofill policy exposes only opted in credential hints`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    RipDpiTextField(
                        state = TextFieldState(),
                        decoration = RipDpiTextFieldDecoration(testTag = "disabled"),
                        autofillPolicy = RipDpiAutofillPolicy.Disabled,
                    )
                    RipDpiTextField(
                        state = TextFieldState(),
                        decoration = RipDpiTextFieldDecoration(testTag = "username"),
                        autofillPolicy = RipDpiAutofillPolicy.Username,
                    )
                    RipDpiSecureTextField(
                        state = TextFieldState(),
                        decoration = RipDpiTextFieldDecoration(testTag = "password"),
                        autofillPolicy = RipDpiAutofillPolicy.Password,
                    )
                }
            }
        }

        val actual =
            listOf("disabled", "username", "password").map { testTag ->
                val semantics = composeRule.onNodeWithTag(testTag).fetchSemanticsNode().config
                semantics[SemanticsProperties.ContentDataType] to
                    semantics.getOrElseNullable(SemanticsProperties.ContentType) { null }
            }

        assertEquals(
            listOf(
                ContentDataType.None to null,
                ContentDataType.Text to ContentType.Username,
                ContentDataType.Text to ContentType.Password,
            ),
            actual,
        )
    }

    private fun setField(
        state: TextFieldState,
        inputTransformation: InputTransformation? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiTextField(
                    state = state,
                    decoration = RipDpiTextFieldDecoration(testTag = TestTag),
                    inputTransformation = inputTransformation,
                )
            }
        }
    }

    private companion object {
        const val TestTag = "ripdpi-text-field"
    }
}
