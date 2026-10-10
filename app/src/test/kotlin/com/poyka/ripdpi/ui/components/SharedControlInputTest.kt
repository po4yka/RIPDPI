package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownLabels
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class SharedControlInputTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `loading icon action retains its name and busy state`() {
        var loading by mutableStateOf(false)
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                RipDpiIconButton(
                    RipDpiIcons.Copy,
                    "Copy entry",
                    {},
                    Modifier.ripDpiTestTag("copy"),
                    loading = loading,
                )
            }
        }
        val action = composeRule.onNodeWithTag("copy")
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Copy entry")))
        composeRule.runOnIdle { loading = true }
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Copy entry")))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading"))
        action.assertIsNotEnabled()
    }

    @Test
    fun `checkbox chips expose checked state and toggle once`() {
        var checked by mutableStateOf(false)
        var calls = 0
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                RipDpiChip(
                    "Errors",
                    {
                        calls++
                        checked = !checked
                    },
                    Modifier.ripDpiTestTag("filter"),
                    selected = checked,
                )
            }
        }
        val chip = composeRule.onNodeWithTag("filter")
        chip.assertIsOff().performClick().assertIsOn()
        chip.requestFocus().performKeyInput { pressKey(Key.Enter) }
        chip.assertIsOff()
        composeRule.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun `radio chips keep selection semantics`() {
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                RipDpiChip("Automatic", {}, Modifier.ripDpiTestTag("radio"), selected = true, role = Role.RadioButton)
            }
        }
        composeRule
            .onNodeWithTag("radio")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test
    fun `focused text button commits once with enter`() = assertEnterCommit(0)

    @Test
    fun `focused icon button commits once with enter`() = assertEnterCommit(1)

    @Test
    fun `focused checkbox chip commits once with enter`() = assertEnterCommit(2)

    @Test
    fun `focused dropdown opens with enter and commits the option once`() = assertEnterCommit(3)

    @Test
    fun `focused action commits once with space`() {
        var calls = 0
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme { RipDpiButton("Action", { calls++ }, Modifier.ripDpiTestTag("space")) }
        }
        composeRule.onNodeWithTag("space").requestFocus().performKeyInput { pressKey(Key.Spacebar) }
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun `loading action ignores keyboard and semantic activation`() {
        var calls = 0
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme { RipDpiButton("Action", { calls++ }, Modifier.ripDpiTestTag("busy"), loading = true) }
        }
        val action = composeRule.onNodeWithTag("busy")
        action.assertIsNotEnabled().performClick()
        action.performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.Spacebar)
        }
        composeRule.runOnIdle { assertEquals(0, calls) }
    }

    private fun assertEnterCommit(kind: Int) {
        var calls = 0
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                Box(Modifier.requiredWidth(320.dp)) {
                    val modifier = Modifier.ripDpiTestTag("control")
                    when (kind) {
                        0 -> {
                            RipDpiButton("Action", { calls++ }, modifier)
                        }

                        1 -> {
                            RipDpiIconButton(RipDpiIcons.Copy, "Copy", { calls++ }, modifier)
                        }

                        2 -> {
                            RipDpiChip("Filter", { calls++ }, modifier)
                        }

                        else -> {
                            RipDpiDropdown(
                                options = persistentListOf(RipDpiDropdownOption("a", "Option")),
                                selectedValue = "a",
                                onValueSelected = { calls++ },
                                testTag = "control",
                                optionTagForValue = { "option" },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("control").requestFocus().performKeyInput { pressKey(Key.Enter) }
        if (kind == 3) {
            composeRule
                .onNodeWithTag("option")
                .requestFocus()
                .assertIsFocused()
                .performKeyInput { pressKey(Key.Enter) }
        }
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun `dropdown trigger can have a name without a duplicate visible label`() {
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                RipDpiDropdown(
                    options = persistentListOf(RipDpiDropdownOption("auto", "Automatic")),
                    selectedValue = "auto",
                    onValueSelected = {},
                    labels = RipDpiDropdownLabels(accessibilityLabel = "Network mode"),
                    testTag = "mode",
                )
            }
        }
        composeRule
            .onNodeWithTag("mode")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Network mode")))
        composeRule.onNodeWithText("Network mode", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `legacy positional dropdown keeps labels placeholder helper and selection callback`() {
        var calls = 0
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                RipDpiDropdown(
                    persistentListOf(RipDpiDropdownOption("auto", "Automatic")),
                    null,
                    { calls++ },
                    Modifier,
                    "Network mode",
                    "Choose mode",
                    "Choose automatically",
                    null,
                    true,
                    false,
                    RipDpiControlDensity.Default,
                    null,
                    "legacy",
                    { "legacy-option" },
                )
            }
        }
        composeRule.onNodeWithText("Network mode").assertIsDisplayed()
        composeRule.onNodeWithText("Choose mode").assertIsDisplayed()
        composeRule.onNodeWithText("Choose automatically").assertIsDisplayed()
        composeRule
            .onNodeWithTag("legacy")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Network mode")))
            .performClick()
        composeRule.onNodeWithTag("legacy-option").performClick()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun `tab moves directly to the next action`() {
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                Column {
                    RipDpiButton("First", {}, Modifier.ripDpiTestTag("first"))
                    RipDpiIconButton(RipDpiIcons.Copy, "Second", {}, Modifier.ripDpiTestTag("second"))
                }
            }
        }
        composeRule.onNodeWithTag("first").requestFocus().performKeyInput { pressKey(Key.Tab) }
        composeRule.onNodeWithTag("second").assertIsFocused()
    }

    @Test
    fun `disabled action cancels a held keyboard activation`() {
        var enabled by mutableStateOf(true)
        var calls = 0
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme {
                RipDpiButton("Action", { calls++ }, Modifier.ripDpiTestTag("action"), enabled = enabled)
            }
        }
        val action = composeRule.onNodeWithTag("action")
        action.requestFocus().performKeyInput { keyDown(Key.Enter) }
        composeRule.runOnIdle { enabled = false }
        action.performKeyInput { keyUp(Key.Enter) }
        action.assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, calls) }
    }
}
