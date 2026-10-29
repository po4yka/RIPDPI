package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val StartedEvent = "Automation demo proxy started"
private const val RetriedEvent = "Automation demo route retried"

/** History screen on the `diagnostics_demo` data preset: two runtime events, no connections or reports. */
@HiltAndroidTest
class HistoryEventsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.History.stableRoute,
            dataPreset = AutomationDataPreset.DiagnosticsDemo,
        ),
    ) {
    private fun hasEvent(message: String): Boolean = composeRule.exists(hasText(message, substring = true))

    private fun openEvents() {
        composeRule.waitForTag(RipDpiTestTags.screen(Route.History))
        composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Events))
        composeRule.waitForTag(RipDpiTestTags.HistoryEventsSearch)
        composeRule.waitUntil(timeoutMillis = 10_000) { hasEvent(StartedEvent) }
    }

    private fun filterCountShown(count: Int): Boolean =
        composeRule.exists(
            hasTestTag(RipDpiTestTags.historyFilterDisclosure(RipDpiTestTags.HistoryEventsSearch)) and
                hasText("· $count", substring = true),
        )

    @Test
    fun allThreeSectionsAreOfferedAndConnectionsIsEmpty() =
        run {
            step("The sections are present") {
                composeRule.waitForTag(RipDpiTestTags.screen(Route.History))
                HistorySection.entries.forEach { section ->
                    assertTrue(composeRule.tagExists(RipDpiTestTags.historySection(section)))
                }
            }
            step("Connections has no history: empty state and no search field") {
                composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Connections))
                composeRule.waitForTag(RipDpiTestTags.HistoryConnectionsStateEmpty)
                assertFalse(composeRule.tagExists(RipDpiTestTags.HistoryConnectionsSearch))
            }
            step("Diagnostics has no reports: empty state") {
                composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Diagnostics))
                composeRule.waitForTag(RipDpiTestTags.HistoryDiagnosticsStateEmpty)
            }
        }

    @Test
    fun searchNarrowsTheEventList() =
        run {
            step("Both seeded events are listed") {
                openEvents()
                assertTrue(hasEvent(RetriedEvent))
            }
            step("A query keeps only the matching event") {
                composeRule
                    .onAllNodes(hasTestTag(RipDpiTestTags.HistoryEventsSearch))
                    .onFirst()
                    .performTextReplacement("retried")
                composeRule.waitUntil(timeoutMillis = 5_000) { !hasEvent(StartedEvent) }
                assertTrue(hasEvent(RetriedEvent))
                composeRule.waitUntil(timeoutMillis = 5_000) { filterCountShown(1) }
            }
            step("A query without matches shows the no-results state and clearing restores both events") {
                composeRule
                    .onAllNodes(hasTestTag(RipDpiTestTags.HistoryEventsSearch))
                    .onFirst()
                    .performTextReplacement("no-such-event-7d4f07")
                composeRule.waitForTag(RipDpiTestTags.HistoryNoResults)
                composeRule.clickTag(RipDpiTestTags.HistoryEmptyClearFilters)
                composeRule.waitUntil(timeoutMillis = 5_000) { hasEvent(StartedEvent) && hasEvent(RetriedEvent) }
            }
        }

    @Test
    fun severityChipFiltersAndClearAllRestores() =
        run {
            val disclosure = RipDpiTestTags.historyFilterDisclosure(RipDpiTestTags.HistoryEventsSearch)
            step("The disclosure reveals the filter options and the auto-scroll switch") {
                openEvents()
                assertFalse(
                    composeRule.tagExists(RipDpiTestTags.historyFilterOptions(RipDpiTestTags.HistoryEventsSearch)),
                )
                composeRule.clickTag(disclosure)
                composeRule.waitForTag(RipDpiTestTags.historyFilterOptions(RipDpiTestTags.HistoryEventsSearch))
                composeRule.requireTag(RipDpiTestTags.HistoryEventsAutoScroll)
            }
            step("Selecting the warn severity leaves only the retried event") {
                val warn = RipDpiTestTags.historyEventSeverityFilter("warn")
                composeRule.clickTag(warn)
                composeRule.waitUntil(timeoutMillis = 5_000) { !hasEvent(StartedEvent) }
                assertTrue(hasEvent(RetriedEvent))
                composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.isSelected(warn) }
                assertTrue(filterCountShown(1))
            }
            step("Clear all removes the filter and restores the list") {
                composeRule.clickTag(RipDpiTestTags.HistoryFilterClearAll)
                composeRule.waitUntil(timeoutMillis = 5_000) { hasEvent(StartedEvent) && hasEvent(RetriedEvent) }
                assertTrue(filterCountShown(0))
            }
        }

    @Test
    fun autoScrollSwitchFlips() =
        run {
            step("Auto-scroll is on by default and toggles off") {
                openEvents()
                composeRule.clickTag(RipDpiTestTags.historyFilterDisclosure(RipDpiTestTags.HistoryEventsSearch))
                composeRule.waitForTag(RipDpiTestTags.HistoryEventsAutoScroll)
                assertEquals(ToggleableState.On, composeRule.toggleState(RipDpiTestTags.HistoryEventsAutoScroll))
                composeRule.clickTag(RipDpiTestTags.HistoryEventsAutoScroll)
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.toggleState(RipDpiTestTags.HistoryEventsAutoScroll) == ToggleableState.Off
                }
            }
        }
}
