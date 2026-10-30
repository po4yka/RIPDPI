package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
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
    private fun HistoryPage.hasEvent(message: String): Boolean = textShown(message)

    private fun HistoryPage.openEvents() {
        awaitScreen()
        click(section(HistorySection.Events))
        waitFor(eventsSearch)
        rule.waitUntil(timeoutMillis = 10_000) { hasEvent(StartedEvent) }
    }

    private fun HistoryPage.filterCountShown(count: Int): Boolean = filterCountShown(eventsSearch, count)

    @Test
    fun allThreeSectionsAreOfferedAndConnectionsIsEmpty() =
        run {
            step("The sections are present") {
                with(HistoryPage(composeRule)) {
                    awaitScreen()
                    HistorySection.entries.forEach { entry ->
                        assertTrue(exists(section(entry)))
                    }
                }
            }
            step("Connections has no history: empty state and no search field") {
                with(HistoryPage(composeRule)) {
                    click(section(HistorySection.Connections))
                    waitFor(connectionsStateEmpty)
                    assertFalse(exists(connectionsSearch))
                }
            }
            step("Diagnostics has no reports: empty state") {
                with(HistoryPage(composeRule)) {
                    click(section(HistorySection.Diagnostics))
                    waitFor(diagnosticsStateEmpty)
                }
            }
        }

    @Test
    fun searchNarrowsTheEventList() =
        run {
            step("Both seeded events are listed") {
                with(HistoryPage(composeRule)) {
                    openEvents()
                    assertTrue(hasEvent(RetriedEvent))
                }
            }
            step("A query keeps only the matching event") {
                with(HistoryPage(composeRule)) {
                    node(eventsSearch).performTextReplacement("retried")
                    rule.waitUntil(timeoutMillis = 5_000) { !hasEvent(StartedEvent) }
                    assertTrue(hasEvent(RetriedEvent))
                    rule.waitUntil(timeoutMillis = 5_000) { filterCountShown(1) }
                }
            }
            step("A query without matches shows the no-results state and clearing restores both events") {
                with(HistoryPage(composeRule)) {
                    node(eventsSearch).performTextReplacement("no-such-event-7d4f07")
                    waitFor(noResults)
                    click(emptyClearFilters)
                    rule.waitUntil(timeoutMillis = 5_000) { hasEvent(StartedEvent) && hasEvent(RetriedEvent) }
                }
            }
        }

    @Test
    fun severityChipFiltersAndClearAllRestores() =
        run {
            step("The disclosure reveals the filter options and the auto-scroll switch") {
                with(HistoryPage(composeRule)) {
                    val disclosure = filterDisclosure(eventsSearch)
                    openEvents()
                    assertFalse(exists(filterOptions(eventsSearch)))
                    click(disclosure)
                    waitFor(filterOptions(eventsSearch))
                    require(eventsAutoScroll)
                }
            }
            step("Selecting the warn severity leaves only the retried event") {
                with(HistoryPage(composeRule)) {
                    val warn = eventSeverityFilter("warn")
                    click(warn)
                    rule.waitUntil(timeoutMillis = 5_000) { !hasEvent(StartedEvent) }
                    assertTrue(hasEvent(RetriedEvent))
                    rule.waitUntil(timeoutMillis = 5_000) { isSelected(warn) }
                    assertTrue(filterCountShown(1))
                }
            }
            step("Clear all removes the filter and restores the list") {
                with(HistoryPage(composeRule)) {
                    click(filterClearAll)
                    rule.waitUntil(timeoutMillis = 5_000) { hasEvent(StartedEvent) && hasEvent(RetriedEvent) }
                    assertTrue(filterCountShown(0))
                }
            }
        }

    @Test
    fun autoScrollSwitchFlips() =
        run {
            step("Auto-scroll is on by default and toggles off") {
                with(HistoryPage(composeRule)) {
                    openEvents()
                    click(filterDisclosure(eventsSearch))
                    waitFor(eventsAutoScroll)
                    assertEquals(ToggleableState.On, toggleState(eventsAutoScroll))
                    click(eventsAutoScroll)
                    rule.waitUntil(timeoutMillis = 5_000) { toggleState(eventsAutoScroll) == ToggleableState.Off }
                }
            }
        }
}
