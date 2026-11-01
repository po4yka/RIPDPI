package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import com.poyka.ripdpi.ui.components.contrastRatio
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiTextFieldContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `field colors follow the design contract and keep a 3 to 1 boundary`() {
        var theme by mutableStateOf("light")
        lateinit var fieldColors: TextFieldColors
        lateinit var tokens: RipDpiExtendedColors
        composeRule.setContent {
            RipDpiTheme(themePreference = theme) {
                fieldColors = ripDpiOutlinedTextFieldColors()
                tokens = RipDpiThemeTokens.colors
            }
        }
        listOf("light", "dark").forEach { name ->
            composeRule.runOnIdle { theme = name }
            composeRule.runOnIdle {
                assertEquals(tokens.foreground, fieldColors.unfocusedTextColor)
                assertEquals(tokens.destructive, fieldColors.errorTextColor)
                val boundary = fieldColors.unfocusedIndicatorColor
                assertTrue("$name boundary vs page", contrastRatio(boundary, tokens.background) >= 3f)
                assertTrue("$name boundary vs fill", contrastRatio(boundary, tokens.inputBackground) >= 3f)
            }
        }
    }

    @Test
    fun `visible secret is readable text`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiTextField(
                    value = "secret",
                    onValueChange = {},
                    behavior = RipDpiTextFieldBehavior(textObfuscationMode = TextObfuscationMode.Visible),
                )
            }
        }
        composeRule
            .onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("secret")))
    }

    @Test
    fun `read only secure field rejects edits and an editable one forwards them`() {
        var readOnly by mutableStateOf(true)
        val changes = mutableListOf<String>()
        composeRule.setContent {
            RipDpiTheme {
                RipDpiTextField(
                    value = "secret",
                    onValueChange = { changes += it },
                    behavior =
                        RipDpiTextFieldBehavior(
                            readOnly = readOnly,
                            textObfuscationMode = TextObfuscationMode.Hidden,
                        ),
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("x")
        composeRule.runOnIdle { assertTrue("Read-only field reported $changes", changes.isEmpty()) }
        composeRule.runOnIdle { readOnly = false }
        composeRule.onNode(hasSetTextAction()).performTextInput("x")
        composeRule.runOnIdle { assertEquals(listOf("secretx"), changes) }
    }
}
