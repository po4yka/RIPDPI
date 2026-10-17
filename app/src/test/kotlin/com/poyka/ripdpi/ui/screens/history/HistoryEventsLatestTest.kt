package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsEventFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventsUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.GroupedEventUiModel
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HistoryEventsLatestTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `new events preserve reader position and latest action preserves filters`() {
        val filters = DiagnosticsEventFiltersUiModel(source = "Proxy", severity = "info", search = "event")
        val state = mutableStateOf(eventHistory((1..30).toList(), filters))
        var filterChanges = 0
        showEvents(state, onFilterChange = { filterChanges++ })
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-15")
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed()
        val row = composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-15"))
        val topBefore =
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top

        composeRule.runOnIdle { state.value = eventHistory((0..30).toList(), filters) }

        assertEquals(
            topBefore,
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-0")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        composeRule.runOnIdle { state.value = eventHistory(listOf(100) + (0..30).toList(), filters) }
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-100")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        assertEquals(filters, state.value.events.filters)
        assertEquals(0, filterChanges)
    }

    @Test
    fun `event burst keeps following when the first scroll has only just started`() {
        assertBurstKeepsFollowing(advanceBetweenMillis = 0L)
    }

    @Test
    fun `event burst keeps following during a partially completed scroll`() {
        assertBurstKeepsFollowing(advanceBetweenMillis = 80L)
    }

    @Test
    fun `jump follows incoming bursts while automatic following is disabled`() {
        val filters = DiagnosticsEventFiltersUiModel(autoScroll = false)
        val initialIds = (1..80).toList()
        val firstBurst = (100..129).toList()
        val secondBurst = (200..229).toList()
        val state = mutableStateOf(eventHistory(initialIds, filters))
        showEvents(state)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-40")
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(80L)
        composeRule.waitForIdle()

        composeRule.runOnUiThread { state.value = eventHistory(firstBurst + initialIds, filters) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(80L)
        composeRule.waitForIdle()
        composeRule.runOnUiThread { state.value = eventHistory(secondBurst + firstBurst + initialIds, filters) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-200")).assertIsNotDisplayed()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-200")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        assertEquals(filters, state.value.events.filters)
    }

    @Test
    fun `user drag cancels following before the next burst`() {
        val filters = DiagnosticsEventFiltersUiModel()
        val initialIds = (1..80).toList()
        val firstBurst = (100..129).toList()
        val state = mutableStateOf(eventHistory(initialIds, filters))
        showEvents(state)
        val stream = composeRule.onNode(hasScrollToIndexAction())
        stream.performScrollToKey("event-1")
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { state.value = eventHistory(firstBurst + initialIds, filters) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(80L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-100")).assertIsNotDisplayed()
        stream.performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            state.value = eventHistory((200..229).toList() + firstBurst + initialIds, filters)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-200")).assertDoesNotExist()
    }

    private fun assertBurstKeepsFollowing(advanceBetweenMillis: Long) {
        val filters = DiagnosticsEventFiltersUiModel()
        val initialIds = (1..80).toList()
        val firstBurst = (100..129).toList()
        val secondBurst = (200..229).toList()
        val state = mutableStateOf(eventHistory(initialIds, filters))
        showEvents(state)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-1")
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-1")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { state.value = eventHistory(firstBurst + initialIds, filters) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-1")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-100")).assertIsNotDisplayed()
        composeRule.mainClock.advanceTimeBy(advanceBetweenMillis)
        composeRule.waitForIdle()
        if (advanceBetweenMillis > 0L) {
            composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-100")).assertIsNotDisplayed()
        composeRule.runOnUiThread { state.value = eventHistory(secondBurst + firstBurst + initialIds, filters) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-200")).assertIsNotDisplayed()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-200")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
    }

    @Test
    fun `disabled motion jumps to latest in the next frame`() {
        assertImmediateJump(RipDpiMotion(animationsEnabled = false))
    }

    @Test
    fun `reduced motion jumps to latest in the next frame`() {
        assertImmediateJump(RipDpiMotion(reducedMotion = true))
    }

    private fun assertImmediateJump(motion: RipDpiMotion) {
        val state = mutableStateOf(eventHistory((1..30).toList(), DiagnosticsEventFiltersUiModel()))
        showEvents(state, motion)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-15")
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-1")).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
    }

    private fun showEvents(
        state: State<HistoryUiState>,
        motion: RipDpiMotion = RipDpiMotion(),
        onFilterChange: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides motion) {
                    Box(Modifier.height(440.dp)) {
                        EventsSection(
                            uiState = state.value,
                            onToggleFilter = { _, _ -> onFilterChange() },
                            onSearch = { onFilterChange() },
                            onClearFilters = onFilterChange,
                            onAutoScroll = { onFilterChange() },
                            onSelectEvent = {},
                            onOpenConnection = {},
                        )
                    }
                }
            }
        }
    }

    private fun eventHistory(
        ids: List<Int>,
        filters: DiagnosticsEventFiltersUiModel,
    ): HistoryUiState {
        val events =
            ids
                .map { id ->
                    DiagnosticsEventUiModel(
                        id = "event-$id",
                        source = "Proxy",
                        severity = "info",
                        message = "event $id",
                        createdAtLabel = "12:00",
                        tone = DiagnosticsTone.Info,
                    )
                }.toImmutableList()
        return HistoryUiState(
            events = DiagnosticsEventsUiModel(filters = filters, events = events),
            groupedEvents = events.map { GroupedEventUiModel(it, 1, null) }.toImmutableList(),
        )
    }
}
