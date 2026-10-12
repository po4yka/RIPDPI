package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h1600dp")
class FeedbackWordLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val width = mutableStateOf(320)
    private val fontScale = mutableStateOf(2f)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    @Test
    fun bannerKeepsWholeLocalizedWordsAndSeparateClickAndDismissActions() {
        val title = text(R.string.home_quality_measurements_title)
        val message = text(R.string.home_quality_waiting)
        var clicks = 0
        var dismissals = 0
        composeRule.setContent {
            Container {
                WarningBanner(title, message, testTag = "banner", onClick = { clicks++ }, onDismiss = { dismissals++ })
            }
        }
        configurations {
            assertWholeWords(title)
            assertWholeWords(message)
            composeRule.onNodeWithTag("banner").assertHasClickAction().performClick()
            composeRule.onNodeWithTag(RipDpiTestTags.WarningBannerDismiss).assertHasClickAction().performClick()
        }
        assertEquals(8, clicks)
        assertEquals(8, dismissals)
    }

    @Test
    fun accordionKeepsWholeLocalizedTitleAndExpansionCallbacks() {
        val title = text(R.string.ripdpi_http_host_extra_space_setting)
        val body = text(R.string.home_modes_diagnostics_title)
        var expanded by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            Container {
                RipDpiAccordion(title, expanded, {
                    expanded = it
                    changes += it
                }, headerTestTag = "header") {
                    Text(body)
                }
            }
        }
        configurations {
            assertWholeWords(title)
            val header = composeRule.onNodeWithTag("header").assertHasClickAction()
            header.performClick()
            composeRule.onNodeWithText(body).assertIsDisplayed()
            header.performClick()
            composeRule.onNodeWithText(body).assertDoesNotExist()
        }
        assertEquals(List(8) { listOf(true, false) }.flatten(), changes)
    }

    @androidx.compose.runtime.Composable
    private fun Container(content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale.value),
            LocalLayoutDirection provides direction.value,
        ) {
            RipDpiTheme {
                // Match the 20dp gutters in the real component preview at a 320dp viewport.
                Box(Modifier.requiredWidth(width.value.dp).padding(horizontal = 20.dp)) { content() }
            }
        }
    }

    private fun configurations(assertion: () -> Unit) {
        val cases =
            listOf(320, 420).flatMap { viewport ->
                listOf(1f, 2f).flatMap { scale -> LayoutDirection.entries.map { Triple(viewport, scale, it) } }
            }
        cases.forEach { (viewport, scale, layoutDirection) ->
            composeRule.runOnIdle {
                width.value = viewport
                fontScale.value = scale
                direction.value = layoutDirection
            }
            composeRule.waitForIdle()
            assertion()
        }
    }

    private fun assertWholeWords(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val input = layout.layoutInput
        val wholeWordWidth =
            TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
                .measure(AnnotatedString(text), style = input.style.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth
        assertTrue(
            "Word needs $wholeWordWidth px but receives ${layout.size.width}: $text",
            layout.size.width + 1f >= wholeWordWidth,
        )
        assertFalse("Text remains complete: $text", layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    private fun text(key: Int): String = RuntimeEnvironment.getApplication().getString(key)
}
