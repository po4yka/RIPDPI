package com.poyka.ripdpi.ui.screens.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HistoryConnectionRowUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
    fun localizedStatusChipsSelectTheOriginalStatusKeys() {
        var selected: String? = null
        val labels =
            linkedMapOf(
                "completed" to "Сканирование завершено",
                "running" to "Выполняется",
                "failed" to "Ошибка",
                "cancelled" to "Отменено",
                // Connection sessions store their state capitalized.
                "Stopped" to "Остановлено",
                "Reconnecting" to "Переподключение",
            )
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
                            options = labels.keys.toList(),
                            selected = null,
                            onSelect = { selected = it },
                            tagForOption = { "status-$it" },
                        ),
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.historyFilterDisclosure("search")).performClick()
        labels.forEach { (key, label) ->
            composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
            composeRule.runOnIdle { assertEquals(key, selected) }
        }
    }

    @Test
    fun connectionCardShowsLocalizedState() {
        composeRule.setContent {
            RipDpiTheme {
                ConnectionSessionCard(
                    session =
                        HistoryConnectionRowUiModel(
                            id = "connection-1",
                            title = "VPN",
                            subtitle = "wifi",
                            serviceMode = "VPN",
                            connectionState = "Running",
                            networkType = "wifi",
                            startedAtLabel = "",
                            summary = null,
                            metrics = persistentListOf(),
                            tone = DiagnosticsTone.Positive,
                        ),
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Работает").assertIsDisplayed()
    }
}
