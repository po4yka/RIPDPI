package com.poyka.ripdpi.ui.screens.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
@Config(sdk = [35], qualifiers = "en")
class HistoryFiltersTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `search-only filter exposes clear all and clears it`() {
        var clearClicks = 0
        composeRule.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Filters",
                    searchValue = "handshake",
                    searchPlaceholder = "Search",
                    onSearch = {},
                    searchTestTag = "history-search",
                    primaryFilter = emptyFilterConfig(),
                    secondaryFilter = emptyFilterConfig(),
                    onClearFilters = { clearClicks += 1 },
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.HistoryFilterClearAll)
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, clearClicks) }
    }

    private fun emptyFilterConfig() =
        HistoryFilterChipsConfig(
            options = emptyList(),
            selected = null,
            onSelect = {},
            tagForOption = { "history-filter-$it" },
        )
}
