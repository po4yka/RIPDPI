package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventsUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionsUiModel
import com.poyka.ripdpi.activities.HistoryConnectionFiltersUiModel
import com.poyka.ripdpi.activities.HistoryConnectionsUiModel
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
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
@Config(sdk = [35])
class HistoryEmptyStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyConnectionsOfferAnActionInsteadOfSearch() {
        var opened = 0
        showHistory(HistoryUiState(), onOpenConnection = { opened++ })
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryConnectionsSearch).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenConnection).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenDiagnostics).assertDoesNotExist()
        assertEquals(1, opened)
    }

    @Test
    fun emptyDiagnosticsOfferAnActionEvenWithAStaleQuery() {
        var opened = 0
        showHistory(
            HistoryUiState(
                selectedSection = HistorySection.Diagnostics,
                diagnostics = DiagnosticsSessionsUiModel(filters = DiagnosticsSessionFiltersUiModel(query = "old")),
            ),
            onOpenDiagnostics = { opened++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryDiagnosticsSearch).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenDiagnostics).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenConnection).assertDoesNotExist()
        assertEquals(1, opened)
    }

    @Test
    fun emptyEventsOfferAnActionEvenWithStaleFilters() {
        var opened = 0
        showHistory(
            HistoryUiState(
                selectedSection = HistorySection.Events,
                events =
                    DiagnosticsEventsUiModel(
                        filters = DiagnosticsEventFiltersUiModel(source = "Proxy", search = "old"),
                    ),
            ),
            onOpenConnection = { opened++ },
        )
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsSearch).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenConnection).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenDiagnostics).assertDoesNotExist()
        assertEquals(1, opened)
    }

    @Test
    fun filteredEmptyConnectionsOfferClearFilters() {
        assertFilteredEmpty(
            HistoryUiState(
                connections = HistoryConnectionsUiModel(filters = HistoryConnectionFiltersUiModel(query = "missing")),
                hasConnectionHistory = true,
            ),
            RipDpiTestTags.HistoryConnectionsSearch,
        )
    }

    @Test
    fun filteredEmptyDiagnosticsOfferClearFilters() {
        assertFilteredEmpty(
            HistoryUiState(
                selectedSection = HistorySection.Diagnostics,
                diagnostics = DiagnosticsSessionsUiModel(filters = DiagnosticsSessionFiltersUiModel(query = "missing")),
                hasDiagnosticsHistory = true,
            ),
            RipDpiTestTags.HistoryDiagnosticsSearch,
        )
    }

    @Test
    fun filteredEmptyEventsOfferClearFilters() {
        assertFilteredEmpty(
            HistoryUiState(
                selectedSection = HistorySection.Events,
                events = DiagnosticsEventsUiModel(filters = DiagnosticsEventFiltersUiModel(search = "missing")),
                hasEventHistory = true,
            ),
            RipDpiTestTags.HistoryEventsSearch,
        )
    }

    @Test
    fun largeFontActionKeepsItsWholeLabelAndTouchTargetAt320dp() {
        val label = "Проверить сеть"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        HistoryEmptyStateCard(
                            title = "История пуста",
                            body = "Проверьте сеть, чтобы сохранить результаты.",
                            actionLabel = label,
                            actionTestTag = RipDpiTestTags.HistoryEmptyOpenDiagnostics,
                            onAction = {},
                        )
                    }
                }
            }
        }
        val action = composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenDiagnostics)
        action.assertIsDisplayed()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f)
        assertTrue(bounds.height >= 48f)
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse(layout.hasVisualOverflow)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.size.width <= bounds.width)
    }

    private fun assertFilteredEmpty(
        state: HistoryUiState,
        searchTag: String,
    ) {
        var cleared = 0
        showHistory(state, onClearFilters = { cleared++ })
        composeRule.onNodeWithTag(searchTag).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryFilterClearAll).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenConnection).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEmptyOpenDiagnostics).assertDoesNotExist()
        composeRule.onNode(hasScrollAction().and(hasAnyDescendant(hasTestTag(searchTag)))).performScrollToIndex(1)
        composeRule
            .onNodeWithTag(RipDpiTestTags.HistoryEmptyClearFilters)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(RuntimeEnvironment.getApplication().getString(R.string.history_clear_filters_action))
            .performClick()
        assertEquals(1, cleared)
    }

    private fun showHistory(
        state: HistoryUiState,
        onOpenConnection: () -> Unit = {},
        onOpenDiagnostics: () -> Unit = {},
        onClearFilters: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                HistoryScreen(
                    uiState = state,
                    onBack = {},
                    onOpenConnection = onOpenConnection,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onRefresh = {},
                    onSelectSection = {},
                    onConnectionModeFilter = {},
                    onConnectionStatusFilter = {},
                    onConnectionSearch = {},
                    onClearConnectionFilters = onClearFilters,
                    onSelectConnection = {},
                    onDismissConnectionDetail = {},
                    onDiagnosticsPathFilter = {},
                    onDiagnosticsStatusFilter = {},
                    onDiagnosticsSearch = {},
                    onClearDiagnosticsFilters = onClearFilters,
                    onSelectDiagnosticsSession = {},
                    onDismissDiagnosticsDetail = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onClearEventFilters = onClearFilters,
                    onEventAutoScroll = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                )
            }
        }
    }
}
