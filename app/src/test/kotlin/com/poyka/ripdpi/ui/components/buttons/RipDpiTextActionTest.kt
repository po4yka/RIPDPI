package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiTextActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders its label as a clickable action`() {
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme {
                RipDpiTextAction(text = "Skip", onClick = { clicks++ })
            }
        }

        composeRule
            .onNodeWithText("Skip")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `a disabled action does not fire`() {
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme {
                RipDpiTextAction(text = "Finish anyway", onClick = { clicks++ }, enabled = false)
            }
        }

        composeRule.onNodeWithText("Finish anyway").performClick()

        composeRule.runOnIdle { assertEquals(0, clicks) }
    }
}
