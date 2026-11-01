package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
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
    fun `text action trailing edge sits at the glyph inset of an icon action and keeps a 48dp target`() {
        var glyphInset = 0f
        composeRule.setContent {
            RipDpiTheme {
                glyphInset = ((RipDpiThemeTokens.components.buttons.iconButtonSize - RipDpiIconSizes.Default) / 2).value
                Row(Modifier.width(200.dp)) {
                    Spacer(Modifier.weight(1f))
                    RipDpiTopBarAction("Save", {})
                }
            }
        }
        val density = composeRule.density.density
        // At the end of a bar, an icon glyph sits half its button's spare width from the edge; the text must too.
        val text = composeRule.onNodeWithText("Save", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rowRight = 200f * density
        val action = composeRule.onNode(hasClickAction() and hasText("Save")).fetchSemanticsNode().boundsInRoot
        assertEquals("Text trailing edge to the bar edge", glyphInset * density, rowRight - text.right, 0.5f)
        assertEquals("Target and text share the trailing edge", action.right, text.right, 0.5f)
        assertTrue("Target ${action.height}px is under 48dp", action.height >= 48f * density - 0.5f)
        assertTrue("Target ${action.width}px is under 48dp", action.width >= 48f * density - 0.5f)
    }
}
