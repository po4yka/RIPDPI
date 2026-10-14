package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.data.Mode
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
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h2000dp")
class ConfigModeChipsLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val width = mutableStateOf(411)
    private val scale = mutableStateOf(2f)
    private val theme = mutableStateOf("light")

    @Test
    fun modesRetainWholeLabelsSelectionTagsAndCallbacks() {
        val mode = mutableStateOf(Mode.VPN)
        val selections = mutableListOf<Mode>()
        composeRule.setContent {
            Container {
                ConfigModeChips(mode.value, {
                    mode.value = it
                    selections += it
                })
            }
        }
        configurations {
            listOf(Mode.VPN, Mode.Proxy).forEach { value ->
                val label = RuntimeEnvironment.getApplication().getString(modeLabelRes(value))
                assertWordsFit(label)
                composeRule.onNodeWithTag(RipDpiTestTags.configMode(value.name)).performClick().assertIsSelected()
            }
        }
        assertEquals(List(6) { listOf(Mode.VPN, Mode.Proxy) }.flatten(), selections)
    }

    @Composable
    private fun Container(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            RipDpiTheme(themePreference = theme.value) {
                Box(Modifier.requiredWidth(width.value.dp).padding(horizontal = 20.dp)) { content() }
            }
        }
    }

    private fun configurations(assertion: () -> Unit) {
        listOf("light", "dark").forEach { preference ->
            listOf(411 to 2f, 320 to 2f, 420 to 1f).forEach { (viewport, fontScale) ->
                composeRule.runOnIdle {
                    width.value = viewport
                    scale.value = fontScale
                    theme.value = preference
                }
                composeRule.waitForIdle()
                assertion()
            }
        }
    }

    private fun assertWordsFit(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val input = layout.layoutInput
        val wordWidth =
            TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
                .measure(AnnotatedString(text), style = input.style.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth
        assertTrue(
            "Word needs $wordWidth px but receives ${layout.size.width}: $text",
            layout.size.width + 1f >= wordWidth,
        )
        assertFalse("Text remains complete: $text", layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
    }
}
