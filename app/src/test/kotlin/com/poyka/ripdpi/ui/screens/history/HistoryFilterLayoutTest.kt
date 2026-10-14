package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOn
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
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
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
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h2000dp")
class HistoryFilterLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun statusLabelsRemainCompleteWithinTheCardWhenSelected() {
        val width = mutableStateOf(411)
        val scale = mutableStateOf(2f)
        val theme = mutableStateOf("light")
        val selected = mutableStateOf<String?>(null)
        val selections = mutableListOf<String?>()
        var fullChipContentBudget = 0f
        val labels =
            linkedMapOf(
                "completed" to "Сканирование завершено",
                "running" to "Выполняется",
                "failed" to "Ошибка",
                "cancelled" to "Отменено",
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                RipDpiTheme(themePreference = theme.value) {
                    val inputs = RipDpiThemeTokens.components.inputs
                    fullChipContentBudget =
                        width.value - 40f - 2f * RipDpiThemeTokens.layout.cardPadding.value -
                        2f * inputs.chipHorizontalPadding.value - inputs.chipIconSize.value - 6f
                    Box(Modifier.requiredWidth(width.value.dp).padding(horizontal = 20.dp)) {
                        FilterCard(
                            modifier = Modifier.ripDpiTestTag("history-filter-card"),
                            title = "Фильтры",
                            searchValue = "",
                            searchPlaceholder = "Поиск",
                            onSearch = {},
                            searchTestTag = "search",
                            primaryFilter = HistoryFilterChipsConfig(emptyList(), null, {}, { it }),
                            secondaryFilter =
                                HistoryFilterChipsConfig(
                                    options = labels.keys.toList(),
                                    selected = selected.value,
                                    onSelect = {
                                        selected.value = it
                                        selections += it
                                    },
                                    tagForOption = { "status-$it" },
                                ),
                        )
                    }
                }
            }
        }
        listOf("light", "dark").forEach { preference ->
            listOf(411 to 2f, 320 to 2f, 420 to 1f).forEach { (viewport, fontScale) ->
                composeRule.runOnIdle {
                    width.value = viewport
                    scale.value = fontScale
                    theme.value = preference
                }
                labels.forEach { (key, label) ->
                    composeRule.onNodeWithTag("status-$key").performClick().assertIsOn()
                    assertCompleteLabel(label, fullChipContentBudget)
                }
            }
        }
        assertEquals(List(6) { labels.keys.toList() }.flatten(), selections)
    }

    private fun assertCompleteLabel(
        label: String,
        fullChipContentBudget: Float,
    ) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val input = layout.layoutInput
        val wordWidth =
            TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
                .measure(AnnotatedString(label), style = input.style.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth
        val card = composeRule.onNodeWithTag("history-filter-card").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Label must retain its whole-word budget: $label",
            layout.size.width + 1f >= minOf(wordWidth, fullChipContentBudget),
        )
        assertTrue("Label must fit the card: $label", layout.size.width <= card.width)
        assertFalse("Label has visual overflow: $label", layout.hasVisualOverflow)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
    }
}
