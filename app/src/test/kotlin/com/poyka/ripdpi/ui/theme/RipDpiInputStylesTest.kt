@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.EnableKeyboardInput
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSegmentedButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import kotlinx.collections.immutable.persistentListOf
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
class RipDpiInputStylesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `styled switch toggles once and ignores touch after disabling`() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var calls = 0
        composeRule.setContent {
            InputTestTheme {
                RipDpiSwitch(
                    checked = checked,
                    onCheckedChange = {
                        calls++
                        checked = it
                    },
                    enabled = enabled,
                    accessibilityLabel = "Capture traffic",
                    testTag = Control,
                )
            }
        }
        val control = composeRule.onNodeWithTag(Control)
        control.assertIsOff().performTouchInput { click(center) }
        control.assertIsOn()
        composeRule.runOnIdle {
            assertEquals(1, calls)
            enabled = false
        }
        control.assertHasNoClickAction().performTouchInput { click(center) }
        composeRule.runOnIdle {
            assertEquals(1, calls)
            assertEquals(true, checked)
        }
    }

    @Test
    fun `styled segments retain tab selection and commit touch and keyboard once`() {
        var selected by mutableIntStateOf(0)
        var calls = 0
        composeRule.setContent {
            InputTestTheme {
                RipDpiSegmentedButton(
                    options = persistentListOf("Auto", "Manual"),
                    selectedIndex = selected,
                    onSelect = {
                        calls++
                        selected = it
                    },
                    modifier = Modifier.width(280.dp),
                    visualStyle = CommonStyle { rdsBackground(Color.Blue) },
                )
            }
        }
        val auto = composeRule.onNodeWithText("Auto")
        val manual = composeRule.onNodeWithText("Manual")
        auto.assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        manual.assertIsNotSelected().performTouchInput { click(center) }
        manual.assertIsSelected()
        auto.assertIsNotSelected()
        composeRule.runOnIdle { assertEquals(1, calls) }
        auto.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        auto.assertIsSelected()
        manual.assertIsNotSelected()
        composeRule.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun `styled error field keeps its minimum target focus and text input`() {
        var value by mutableStateOf("")
        composeRule.setContent {
            InputTestTheme {
                RipDpiTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.width(280.dp),
                    decoration = RipDpiTextFieldDecoration(errorText = "Invalid port", testTag = Control),
                )
            }
        }
        val field = composeRule.onNodeWithTag(Control)
        field
            .assertHeightIsAtLeast(
                48.dp,
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Invalid port"))
        field.performTouchInput { click(center) }
        field.assertIsFocused().performTextInput("1080")
        field.assertTextEquals("1080")
        composeRule.runOnIdle { assertEquals("1080", value) }
    }

    @Test
    fun `field shows the caller value after rejected edits and programmatic updates`() {
        var value by mutableStateOf("8080")
        composeRule.setContent {
            InputTestTheme {
                RipDpiTextField(
                    value = value,
                    onValueChange = { if (it.all(Char::isDigit)) value = it },
                    modifier = Modifier.width(280.dp),
                    decoration = RipDpiTextFieldDecoration(testTag = Control),
                )
            }
        }
        val field = composeRule.onNodeWithTag(Control)
        field.performTouchInput { click(center) }
        field.assertIsFocused().performTextInputSelection(TextRange(1))
        field.performTextInput("x")
        field.assertTextEquals("8080")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
        field.performTextInput("1")
        field.assertTextEquals("80180")
        composeRule.runOnIdle { assertEquals("80180", value) }
        composeRule.runOnIdle { value = "443" }
        field.assertTextEquals("443")
    }

    @Test
    fun `styled read only field retains focus without allowing edits`() {
        var calls = 0
        composeRule.setContent {
            InputTestTheme {
                RipDpiTextField(
                    value = "1080",
                    onValueChange = { calls++ },
                    modifier = Modifier.width(280.dp),
                    decoration = RipDpiTextFieldDecoration(testTag = Control),
                    behavior = RipDpiTextFieldBehavior(readOnly = true),
                )
            }
        }
        val field = composeRule.onNodeWithTag(Control)
        field.assertIsEnabled().assertHeightIsAtLeast(48.dp)
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        field.performTouchInput { click(center) }
        field.assertIsFocused().performKeyInput { pressKey(Key.A) }
        field.assertTextEquals("1080")
        composeRule.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun `styled disabled field ignores touch and keyboard input`() {
        var calls = 0
        composeRule.setContent {
            InputTestTheme {
                RipDpiTextField(
                    value = "1080",
                    onValueChange = { calls++ },
                    modifier = Modifier.width(280.dp),
                    decoration = RipDpiTextFieldDecoration(testTag = Control),
                    behavior = RipDpiTextFieldBehavior(enabled = false),
                )
            }
        }
        val field = composeRule.onNodeWithTag(Control)
        field.assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        field.performTouchInput { click(center) }
        field.assert(!isFocused()).performKeyInput { pressKey(Key.A) }
        field.assertTextEquals("1080")
        composeRule.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun `styled multiline config field keeps its larger minimum and accepts line breaks`() {
        var value by mutableStateOf("")
        composeRule.setContent {
            InputTestTheme {
                RipDpiConfigTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.width(280.dp),
                    decoration = RipDpiTextFieldDecoration(testTag = Control),
                    multiline = true,
                )
            }
        }
        val field = composeRule.onNodeWithTag(Control)
        field.assertHeightIsAtLeast(96.dp).performTouchInput { click(center) }
        field.assertIsFocused().performTextInput("first\nsecond")
        field.assertTextEquals("first\nsecond")
        composeRule.runOnIdle { assertEquals("first\nsecond", value) }
    }

    @Composable
    private fun InputTestTheme(content: @Composable () -> Unit) {
        EnableKeyboardInput()
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalInspectionMode provides true) {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides RipDpiMotion(animationsEnabled = false, hapticsEnabled = false),
                    content = content,
                )
            }
        }
    }

    private companion object {
        const val Control = "styled-input"
    }
}
