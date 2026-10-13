package com.poyka.ripdpi.ui.screens.history

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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU")
class HistoryFilterLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun localizedCompletedChipSelectsTheOriginalStatusKey() {
        var selected: String? = null
        val empty = HistoryFilterChipsConfig(emptyList(), null, {}, { it })
        composeRule.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Фильтры",
                    searchValue = "",
                    searchPlaceholder = "Поиск",
                    onSearch = {},
                    searchTestTag = "search",
                    primaryFilter = empty,
                    secondaryFilter =
                        HistoryFilterChipsConfig(
                            options = listOf("completed"),
                            selected = null,
                            onSelect = { selected = it },
                            tagForOption = { "status-$it" },
                        ),
                )
            }
        }
        composeRule.onNodeWithText("Сканирование завершено").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("completed", selected) }
    }
}
