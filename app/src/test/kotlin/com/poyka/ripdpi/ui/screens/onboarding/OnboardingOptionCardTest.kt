package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
class OnboardingOptionCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `short title stays complete and badge fits at narrow widths and large font`() {
        var width by mutableStateOf(320)
        var fontScale by mutableStateOf(1f)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var clicks = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalLayoutDirection provides direction,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(width.dp, 600.dp).padding(horizontal = 16.dp)) {
                        OnboardingOptionCard(
                            title = "Guided",
                            description = "Everyday controls stay visible.",
                            selected = true,
                            onClick = { clicks++ },
                            badgeText = "Recommended",
                            modifier = Modifier.testTag("option"),
                        )
                    }
                }
            }
        }

        listOf(320, 420).forEach { targetWidth ->
            listOf(1f, 2f).forEach { targetScale ->
                listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { targetDirection ->
                    composeRule.runOnIdle {
                        width = targetWidth
                        fontScale = targetScale
                        direction = targetDirection
                    }
                    composeRule.waitForIdle()
                    val context = "$targetWidth dp, $targetScale font, $targetDirection"
                    val title = composeRule.onNodeWithText("Guided", useUnmergedTree = true)
                    val badge = composeRule.onNodeWithText("Recommended", useUnmergedTree = true)
                    val layouts = mutableListOf<TextLayoutResult>()
                    title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals(context, 1, layouts.single().lineCount)
                    val cardBounds = composeRule.onNodeWithTag("option").fetchSemanticsNode().boundsInRoot
                    val titleBounds = title.fetchSemanticsNode().boundsInRoot
                    val badgeBounds = badge.fetchSemanticsNode().boundsInRoot
                    assertTrue(context, badgeBounds.left >= cardBounds.left)
                    assertTrue(context, badgeBounds.right <= cardBounds.right)
                    assertTrue(context, badgeBounds.bottom <= cardBounds.bottom)
                    if (targetScale == 1f) {
                        assertTrue(context, badgeBounds.top < titleBounds.bottom)
                    } else if (targetWidth == 320) {
                        assertTrue(context, badgeBounds.top >= titleBounds.bottom)
                    }
                    composeRule.onNodeWithTag("option").assertIsSelected().performClick()
                }
            }
        }
        assertEquals(8, clicks)
    }
}
