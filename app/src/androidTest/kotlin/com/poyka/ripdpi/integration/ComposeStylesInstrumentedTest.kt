@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.poyka.ripdpi.integration

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownLabels
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Run on a leased device with the software IME enabled, including with a hardware keyboard. */
@RunWith(AndroidJUnit4::class)
class ComposeStylesInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun textInputAndDoneActionKeepTheValueAndReleaseFocus() {
        // Match MainActivity so IME insets are observable on API 27.
        composeRule.runOnUiThread {
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var value by mutableStateOf("")
        val imeVisible = AtomicBoolean()
        var commits = 0
        composeRule.setContent {
            RipDpiTheme {
                val keyboard = LocalSoftwareKeyboardController.current
                val focus = LocalFocusManager.current
                imeVisible.set(WindowInsets.isImeVisible)
                Column {
                    RipDpiTextField(
                        value = value,
                        onValueChange = { value = it },
                        decoration = RipDpiTextFieldDecoration(label = "Host", testTag = Field),
                        behavior =
                            RipDpiTextFieldBehavior(
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions =
                                    KeyboardActions(onDone = {
                                        commits++
                                        keyboard?.hide()
                                        focus.clearFocus()
                                    }),
                            ),
                    )
                }
            }
        }
        val field = composeRule.onNodeWithTag(Field)
        field.performClick().assertIsFocused().performTextInput("resolver.example")
        composeRule.waitUntil(timeoutMillis = 5_000L) { imeVisible.get() }
        field.performImeAction()
        composeRule.waitUntil(timeoutMillis = 5_000L) { !imeVisible.get() }
        field.assertTextContains("resolver.example").assertIsNotFocused()
        composeRule.runOnIdle { assertEquals(1, commits) }
    }

    @Test
    fun dropdownSelectsOnceClosesAndKeepsDisabledBehavior() {
        var selected by mutableStateOf("a")
        var enabled by mutableStateOf(true)
        var selections = 0
        composeRule.setContent {
            RipDpiTheme {
                RipDpiDropdown(
                    options =
                        persistentListOf(
                            RipDpiDropdownOption("a", "Alpha"),
                            RipDpiDropdownOption("b", "Beta"),
                        ),
                    selectedValue = selected,
                    onValueSelected = {
                        selections++
                        selected = it
                    },
                    labels = RipDpiDropdownLabels(label = "Mode"),
                    enabled = enabled,
                    testTag = Dropdown,
                    optionTagForValue = { "style-option-$it" },
                )
            }
        }
        val trigger = composeRule.onNodeWithTag(Dropdown)
        trigger.performClick()
        composeRule.onNodeWithTag("style-option-b").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("style-option-b").assertDoesNotExist()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("b", selected)
            assertEquals(1, selections)
            enabled = false
        }
        trigger.assertIsNotEnabled().performTouchInput { click() }
        composeRule.onNodeWithTag("style-option-b").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, selections) }
    }

    @Test
    fun disconnectNeedsASecondActionBeforeItDispatches() {
        var deactivations = 0
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(animationsEnabled = false),
                ) {
                    RipDpiConnectionActuator(
                        state = HomeConnectionActuatorUiState(status = HomeConnectionActuatorStatus.Locked),
                        onActivate = { error("An active connection must not start again") },
                        onDeactivate = { deactivations++ },
                        testTag = Action,
                    )
                }
            }
        }
        val action = composeRule.onNodeWithTag(Action)
        action.performClick()
        action.assertTextContains(
            composeRule.activity.getString(R.string.home_connection_actuator_action_confirm_release),
        )
        composeRule.runOnIdle { assertEquals(0, deactivations) }
        action.performClick()
        composeRule.runOnIdle { assertEquals(1, deactivations) }
    }

    private companion object {
        const val Field = "style-device-field"
        const val Dropdown = "style-device-dropdown"
        const val Action = "style-device-disconnect"
    }
}
