package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeChromeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `simple app name remains complete at Pixel 7 maximum font`() {
        val title = "RIPDPI Quick Connect"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(411.dp)) {
                        HomeTopBar(title = title)
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(title, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val layout = layouts.single()
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
    }
}
