package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
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
class RipDpiTopBarActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `text action sits at the glyph inset of an icon action and keeps a 48dp target`() {
        var iconButtonSize = 0f
        var glyphSize = 0f
        composeRule.setContent {
            RipDpiTheme {
                iconButtonSize = RipDpiThemeTokens.components.buttons.iconButtonSize.value
                glyphSize = RipDpiIconSizes.Default.value
                Row { RipDpiTopBarAction("Save", {}, Modifier.ripDpiTestTag("save")) }
            }
        }
        val density = composeRule.density.density
        val bounds = composeRule.onNodeWithTag("save").fetchSemanticsNode().boundsInRoot
        val text = composeRule.onNodeWithText("Save", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals((iconButtonSize - glyphSize) / 2 * density, text.left, 0.5f)
        assertTrue("Target ${bounds.height}px is under 48dp", bounds.height >= 48f * density - 0.5f)
        assertTrue("Target ${bounds.width}px is under 48dp", bounds.width >= 48f * density - 0.5f)
    }
}
