package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HistoryConnectionDetailUiFactory
import com.poyka.ripdpi.activities.HistoryConnectionFilterState
import com.poyka.ripdpi.activities.HistoryDetailState
import com.poyka.ripdpi.activities.HistoryDiagnosticsFilterState
import com.poyka.ripdpi.activities.HistoryEventFilterState
import com.poyka.ripdpi.activities.HistoryRepositorySnapshot
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.activities.HistoryUiStateFactory
import com.poyka.ripdpi.activities.historyEvent
import com.poyka.ripdpi.activities.testDiagnosticsUiCoreSupport
import com.poyka.ripdpi.activities.testHistorySessionDetailUiMapper
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.platform.AndroidStringResolver
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
class HistoryNewEventsTest {
    @get:Rule val composeRule = createComposeRule()

    private val factory =
        HistoryUiStateFactory(
            coreSupport = testDiagnosticsUiCoreSupport(),
            diagnosticsSessionDetailUiMapper = testHistorySessionDetailUiMapper(),
            connectionDetailUiFactory =
                HistoryConnectionDetailUiFactory(
                    stringResolver = AndroidStringResolver(ApplicationProvider.getApplicationContext()),
                    coreSupport = testDiagnosticsUiCoreSupport(),
                ),
        )

    @Test
    fun `new raw events count while preserving the reader anchor`() {
        val initialEvents =
            (1..30).map { id ->
                historyEvent(id = "event-$id", message = "event $id", createdAt = 1_000L - id)
            }
        val state = mutableStateOf(project(initialEvents))
        composeRule.setContent {
            RipDpiTheme {
                Box(Modifier.height(440.dp)) {
                    EventsSection(
                        uiState = state.value,
                        onToggleFilter = { _, _ -> },
                        onSearch = {},
                        onClearFilters = {},
                        onAutoScroll = {},
                        onSelectEvent = {},
                        onOpenConnection = {},
                    )
                }
            }
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-15")
        val row = composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-15"))
        val topBefore =
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top

        composeRule.runOnIdle {
            state.value =
                project(
                    listOf(
                        historyEvent(id = "new-1", message = "New route", createdAt = 2_000L),
                        historyEvent(id = "new-2", message = "New route", createdAt = 2_000L),
                    ) + initialEvents,
                )
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.history_events_jump_to_latest_count, 2))
        assertEquals(
            topBefore,
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
    }

    @Test
    fun `repeated grouped snapshots and older backfill never count twice`() {
        val initial = initialEvents()
        val incoming = listOf(event("new-1", 2_000), event("new-2", 2_000))
        val state = mutableStateOf(project(initial))
        show(state)
        readOlderEvents()
        composeRule.runOnIdle { state.value = project(incoming + initial) }
        assertCount(2)
        composeRule.runOnIdle { state.value = project(incoming.map { it.copy(message = "Updated") } + initial) }
        assertCount(2)
        composeRule.runOnIdle { state.value = project(incoming + initial + event("backfill", 10)) }
        assertCount(2)
        composeRule.runOnIdle { state.value = project(listOf(event("new-3", 2_000)) + incoming + initial) }
        assertCount(3)
    }

    @Test
    fun `filter changes reset the baseline and only matching arrivals count`() {
        val initial = (1..80).map { event("event-$it", 1_000L - it, if (it % 2 == 0) "tunnel" else "proxy") }
        val proxy = HistoryEventFilterState(sourceFilter = "Proxy")
        val tunnel = HistoryEventFilterState(sourceFilter = "Tunnel")
        val mixed = listOf(event("proxy-new", 2_000), event("tunnel-new", 2_000, "tunnel")) + initial
        val state = mutableStateOf(project(initial, proxy))
        show(state)
        readOlderEvents()
        composeRule.runOnIdle { state.value = project(mixed, proxy) }
        assertCount(1)
        composeRule.runOnIdle { state.value = project(mixed, tunnel) }
        assertCount(0)
        composeRule.runOnIdle { state.value = project(listOf(event("tunnel-next", 2_001, "tunnel")) + mixed, tunnel) }
        assertCount(1)
    }

    @Test
    fun `pending count survives restoration and resets after jumping`() {
        val initial = initialEvents()
        val incoming = listOf(event("new-1", 2_000), event("new-2", 2_000))
        val state = mutableStateOf(project(initial))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { eventContent(state) }
        readOlderEvents()
        composeRule.runOnIdle { state.value = project(incoming + initial) }
        assertCount(2)
        restoration.emulateSavedInstanceStateRestore()
        assertCount(2)
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertDoesNotExist()
        readOlderEvents()
        assertCount(0)
    }

    @Test
    fun `an interrupted jump retains unread arrivals`() {
        val initial = (1..80).map { event("event-$it", 1_000L - it) }
        val state = mutableStateOf(project(initial))
        show(state)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-60")
        composeRule.runOnIdle {
            state.value = project(listOf(event("new-1", 2_000), event("new-2", 2_000)) + initial)
        }
        assertCount(2)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(80L)
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            state.value =
                project(listOf(event("new-3", 2_001), event("new-1", 2_000), event("new-2", 2_000)) + initial)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertCount(3)
        composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("new-3")).assertDoesNotExist()
    }

    @Test
    fun `new event count preserves reader anchor with large RTL text`() {
        val initial = initialEvents()
        val state = mutableStateOf(project(initial))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                eventContent(state)
            }
        }
        readOlderEvents()
        val row = composeRule.onNodeWithTag(RipDpiTestTags.historyEvent("event-15"))
        val topBefore =
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top

        composeRule.runOnIdle {
            state.value = project(listOf(event("new-1", 2_000), event("new-2", 2_000)) + initial)
        }

        assertCount(2)
        assertEquals(
            topBefore,
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
    }

    private fun initialEvents() = (1..30).map { event("event-$it", 1_000L - it) }

    private fun event(
        id: String,
        timestamp: Long,
        source: String = "proxy",
    ) = historyEvent(id = id, message = id, createdAt = timestamp, source = source)

    private fun show(state: State<HistoryUiState>) {
        composeRule.setContent { eventContent(state) }
    }

    @androidx.compose.runtime.Composable
    private fun eventContent(state: State<HistoryUiState>) {
        RipDpiTheme {
            Box(Modifier.height(440.dp)) {
                EventsSection(
                    uiState = state.value,
                    onToggleFilter = { _, _ -> },
                    onSearch = {},
                    onClearFilters = {},
                    onAutoScroll = {},
                    onSelectEvent = {},
                    onOpenConnection = {},
                )
            }
        }
    }

    private fun readOlderEvents() {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("event-15")
    }

    private fun assertCount(count: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val label =
            if (count == 0) {
                context.getString(R.string.history_events_jump_to_latest)
            } else {
                context.getString(R.string.history_events_jump_to_latest_count, count)
            }
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertTextEquals(label)
    }

    private fun project(
        events: List<DiagnosticEvent>,
        filters: HistoryEventFilterState = HistoryEventFilterState(),
    ) = factory.buildUiState(
        repositorySnapshot = HistoryRepositorySnapshot(nativeEvents = events),
        selectedSection = HistorySection.Events,
        connectionFilters = HistoryConnectionFilterState(),
        diagnosticsFilters = HistoryDiagnosticsFilterState(),
        eventFilters = filters,
        detailState = HistoryDetailState(),
    )
}
