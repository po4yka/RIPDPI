package com.poyka.ripdpi.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LanguagePickerSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun deviceLanguageChoiceClearsAnExplicitOverride() {
        var applied by mutableStateOf(LocaleListCompat.forLanguageTags("fr"))
        composeRule.setContent {
            RipDpiTheme {
                LanguagePickerOptions(tags = listOf("en", "fr"), applied = applied) { applied = it }
            }
        }

        composeRule.onNodeWithText("Français").assertIsSelected()
        composeRule.onNodeWithText("System language").performClick()

        assertTrue(applied.isEmpty)
        composeRule.onNodeWithText("System language").assertIsSelected()
        composeRule.onNodeWithText("Français").assertIsNotSelected()
    }

    @Test
    fun explicitLanguageChoiceRemainsAvailableFromSystemLanguage() {
        var applied by mutableStateOf(LocaleListCompat.getEmptyLocaleList())
        composeRule.setContent {
            RipDpiTheme {
                LanguagePickerOptions(tags = listOf("en", "fr"), applied = applied) { applied = it }
            }
        }

        composeRule.onNodeWithText("System language").assertIsSelected()
        composeRule.onNodeWithText("Français").performClick()

        assertEquals("fr", applied.get(0)?.toLanguageTag())
        composeRule.onNodeWithText("System language").assertIsNotSelected()
        composeRule.onNodeWithText("Français").assertIsSelected()
    }

    @Test
    fun explicitLanguageWithDeviceRegionSelectsTheSupportedLanguage() {
        val applied = LocaleListCompat.forLanguageTags("fr-FR")
        composeRule.setContent {
            RipDpiTheme {
                LanguagePickerOptions(tags = listOf("en", "fr"), applied = applied) {}
            }
        }

        composeRule.onNodeWithText("System language").assertIsNotSelected()
        composeRule.onNodeWithText("Français").assertIsSelected()
    }
}
