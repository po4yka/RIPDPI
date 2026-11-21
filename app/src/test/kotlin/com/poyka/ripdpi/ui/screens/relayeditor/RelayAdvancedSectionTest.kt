package com.poyka.ripdpi.ui.screens.relayeditor

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RelayAdvancedSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `advanced fields stay collapsed until opened`() {
        composeRule.setContent { RipDpiTheme { RelayAdvancedSection(hasError = false) { Text("inside") } } }

        composeRule.onNodeWithText("inside").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.RelayEditorAdvanced).performClick()
        composeRule.onNodeWithText("inside").assertExists()
    }

    @Test
    fun `an invalid field inside keeps advanced open`() {
        composeRule.setContent { RipDpiTheme { RelayAdvancedSection(hasError = true) { Text("inside") } } }

        composeRule.onNodeWithText("inside").assertExists()
    }

    @Test
    fun `fixing the invalid field keeps advanced open`() {
        var hasError by mutableStateOf(true)
        composeRule.setContent { RipDpiTheme { RelayAdvancedSection(hasError = hasError) { Text("inside") } } }

        hasError = false

        composeRule.onNodeWithText("inside").assertExists()
    }
}
