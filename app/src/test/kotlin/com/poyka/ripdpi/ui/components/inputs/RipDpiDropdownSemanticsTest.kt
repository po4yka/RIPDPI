package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
}
