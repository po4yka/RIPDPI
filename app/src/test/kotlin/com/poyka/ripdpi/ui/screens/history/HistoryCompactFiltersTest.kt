package com.poyka.ripdpi.ui.screens.history

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
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
class HistoryCompactFiltersTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `categorical options stay collapsed until filters are opened`() {
        composeRule.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Events",
                    searchValue = "",
                    searchPlaceholder = "Search",
                    onSearch = {},
                    searchTestTag = "history-search",
                    primaryFilter =
                        HistoryFilterChipsConfig(
                            options = listOf("info", "warn"),
                            selected = null,
                            onSelect = {},
                            tagForOption = { "severity-$it" },
                        ),
                    secondaryFilter = HistoryFilterChipsConfig(emptyList(), null, {}, { it }),
                )
            }
        }

        composeRule.onNodeWithTag("history-search").assertIsDisplayed()
        composeRule.onNodeWithTag("severity-warn").assertDoesNotExist()
        composeRule
            .onNodeWithTag(RipDpiTestTags.historyFilterDisclosure("history-search"))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("severity-warn").assertIsDisplayed()
    }

    @Test
    fun `active filters can be removed one at a time while collapsed`() {
        val query = mutableStateOf("handshake")
        val severity = mutableStateOf<String?>("warn")
        val source = mutableStateOf<String?>("Proxy")
        var clearClicks = 0
        composeRule.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Events",
                    searchValue = query.value,
                    searchPlaceholder = "Search",
                    onSearch = { query.value = it },
                    searchTestTag = "history-search",
                    primaryFilter =
                        HistoryFilterChipsConfig(listOf("Proxy"), source.value, { source.value = null }, { it }),
                    secondaryFilter =
                        HistoryFilterChipsConfig(listOf("warn"), severity.value, { severity.value = null }, { it }),
                    onClearFilters = { clearClicks++ },
                )
            }
        }
        val disclosure = composeRule.onNodeWithTag(RipDpiTestTags.historyFilterDisclosure("history-search"))
        disclosure.assertTextContains("3", substring = true)
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterRemove("history-search", "primary")).performClick()
        composeRule.runOnIdle {
            assertEquals(null, source.value)
            assertEquals("warn", severity.value)
            assertEquals("handshake", query.value)
        }
        disclosure.assertTextContains("2", substring = true)
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterRemove("history-search", "query")).performClick()
        composeRule.runOnIdle { assertEquals("", query.value) }
        disclosure.assertTextContains("1", substring = true)
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryFilterClearAll).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, clearClicks) }
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterRemove("history-search", "secondary")).performClick()
        disclosure.assertTextContains("0", substring = true)
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryFilterClearAll).assertDoesNotExist()
    }

    @Test
    fun `filter disclosure survives saved state restoration`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Events",
                    searchValue = "",
                    searchPlaceholder = "Search",
                    onSearch = {},
                    searchTestTag = "history-search",
                    primaryFilter = HistoryFilterChipsConfig(listOf("warn"), null, {}, { "severity-$it" }),
                    secondaryFilter = HistoryFilterChipsConfig(emptyList(), null, {}, { it }),
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterDisclosure("history-search")).performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("severity-warn").assertIsDisplayed()
    }

    @Test
    fun `no filter choices or auto-scroll means no disclosure`() {
        composeRule.setContent {
            RipDpiTheme {
                FilterCard(
                    title = "Connections",
                    searchValue = "handshake",
                    searchPlaceholder = "Search",
                    onSearch = {},
                    searchTestTag = "history-search",
                    primaryFilter = HistoryFilterChipsConfig(emptyList(), null, {}, { it }),
                    secondaryFilter = HistoryFilterChipsConfig(emptyList(), null, {}, { it }),
                )
            }
        }

        composeRule.onNodeWithTag("history-search").assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterRemove("history-search", "query")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryFilterClearAll).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterDisclosure("history-search")).assertDoesNotExist()
    }
}
