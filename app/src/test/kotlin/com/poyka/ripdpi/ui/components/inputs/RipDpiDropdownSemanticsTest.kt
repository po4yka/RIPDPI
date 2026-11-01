package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiDropdownSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val options = persistentListOf(RipDpiDropdownOption("a", "Alpha"), RipDpiDropdownOption("b", "Beta"))

    @Test
    fun `dropdown is one node carrying label, value, role and error`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiDropdown(
                    options = options,
                    selectedValue = "b",
                    onValueSelected = {},
                    labels = RipDpiDropdownLabels(label = "Mode", errorText = "Pick one"),
                    testTag = "dropdown",
                )
            }
        }
        composeRule
            .onNodeWithTag("dropdown")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Mode")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Beta"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Pick one"))
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `empty selection announces the placeholder as the state and disabled dropdown is not enabled`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiDropdown(
                    options = options,
                    selectedValue = null,
                    onValueSelected = {},
                    labels = RipDpiDropdownLabels(label = "Mode", placeholder = "Choose"),
                    enabled = false,
                    testTag = "dropdown",
                )
            }
        }
        composeRule
            .onNodeWithTag("dropdown")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Choose"))
            .assertIsNotEnabled()
    }

    @Test
    fun `dropdown states its selected value and offers click and expand, then collapse once open`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiDropdown(
                    options = options,
                    selectedValue = "a",
                    onValueSelected = {},
                    labels = RipDpiDropdownLabels(label = "Theme"),
                    testTag = "dropdown",
                )
            }
        }
        composeRule
            .onNodeWithTag("dropdown")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Alpha"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Collapse))
            .performSemanticsAction(SemanticsActions.Expand)
        composeRule
            .onNodeWithTag("dropdown")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Expand))
    }

    @Test
    fun `dropdown and text field with the same text are equally tall at default and double font scale`() {
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            RipDpiTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                    Column {
                        RipDpiDropdown(
                            options = options,
                            selectedValue = "a",
                            onValueSelected = {},
                            labels = RipDpiDropdownLabels(label = "Mode"),
                            testTag = "dropdown",
                        )
                        RipDpiTextField(
                            value = "Gamma",
                            onValueChange = {},
                            decoration = RipDpiTextFieldDecoration(label = "Mode", testTag = "field"),
                        )
                    }
                }
            }
        }
        listOf(1f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            val dropdown =
                composeRule
                    .onNodeWithTag("dropdown")
                    .fetchSemanticsNode()
                    .size.height
            val field =
                composeRule
                    .onNodeWithTag("field")
                    .fetchSemanticsNode()
                    .size.height
            assertEquals("font scale $scale", field, dropdown)
            if (scale == 2f) {
                val minGap = 8f * composeRule.density.density
                val dropdownBounds = composeRule.onNodeWithTag("dropdown").fetchSemanticsNode().boundsInRoot
                val dropdownText =
                    composeRule.onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "dropdown text ${dropdownText.top - dropdownBounds.top}px from the border",
                    dropdownText.top - dropdownBounds.top >= minGap &&
                        dropdownBounds.bottom - dropdownText.bottom >= minGap,
                )
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule
                    .onNodeWithTag("field")
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val fieldGap = (field - layouts.single().size.height) / 2f
                assertTrue("field text ${fieldGap}px from the border", fieldGap >= minGap)
            }
        }
    }

    @Test
    fun `a wrapped dropdown value keeps 8dp from the border at default and double font scale`() {
        var fontScale by mutableFloatStateOf(1f)
        val wrappedOptions =
            persistentListOf(
                RipDpiDropdownOption("long", "A rather long option label that cannot fit on a single line here"),
            )
        composeRule.setContent {
            RipDpiTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                    Box(Modifier.width(220.dp)) {
                        RipDpiDropdown(
                            options = wrappedOptions,
                            selectedValue = "long",
                            onValueSelected = {},
                            labels = RipDpiDropdownLabels(label = "Mode"),
                            testTag = "dropdown",
                        )
                    }
                }
            }
        }
        listOf(1f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            val dropdown = composeRule.onNodeWithTag("dropdown").fetchSemanticsNode().boundsInRoot
            val text =
                composeRule
                    .onNodeWithText(wrappedOptions.single().label, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val minGap = 8f * composeRule.density.density
            assertTrue("font scale $scale: top gap ${text.top - dropdown.top}", text.top - dropdown.top >= minGap)
            assertTrue(
                "font scale $scale: bottom gap ${dropdown.bottom - text.bottom}",
                dropdown.bottom - text.bottom >= minGap,
            )
        }
    }
}
