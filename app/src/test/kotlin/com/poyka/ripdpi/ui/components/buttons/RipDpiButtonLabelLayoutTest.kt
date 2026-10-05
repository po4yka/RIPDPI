package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RipDpiButtonLabelLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val label = "Reconnect using saved configuration"

    @Test fun `default label keeps its single line presentation`() {
        composeRule.setContent {
            RipDpiTheme { Box(Modifier.width(220.dp)) { RipDpiButton(label, {}) } }
        }
        val layout = labelLayout()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
    }

    @Test fun `wrapped label remains complete with icons at maximum font and dispatches once`() {
        var clicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.width(300.dp)) {
                        RipDpiButton(
                            label,
                            { clicks++ },
                            leadingIcon = RipDpiIcons.Connected,
                            trailingIcon = RipDpiIcons.Offline,
                            wrapLabel = true,
                        )
                    }
                }
            }
        }
        val layout = labelLayout()
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        composeRule.onNodeWithText(label).performClick()
        assertEquals(1, clicks)
    }

    private fun labelLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
