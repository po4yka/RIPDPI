package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ReportSessionId = "automation-report-demo-session"

/** History diagnostics section on the `diagnostics_report_demo` preset: one saved completed raw-path report. */
@HiltAndroidTest
class HistoryDiagnosticsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.History.stableRoute,
            dataPreset = AutomationDataPreset.DiagnosticsReportDemo,
        ),
    ) {
    private fun HistoryPage.openDiagnostics() {
        awaitScreen()
        click(section(HistorySection.Diagnostics))
        waitFor(diagnosticsStateContent)
        waitFor(diagnosticsSession(ReportSessionId))
    }

    private fun HistoryPage.activeFilterCount(count: Int): Boolean = filterCountShown(diagnosticsSearch, count)

    private fun HistoryPage.typeQuery(query: String) {
        require(diagnosticsSearch)
        node(diagnosticsSearch).performTextReplacement(query)
    }

    @Test
    fun exactPathAndStatusFilterLifecycleRetainsTheReport() =
        run {
            step("The seeded report is listed with no active filters") {
                with(HistoryPage(composeRule)) {
                    openDiagnostics()
                    click(filterDisclosure(diagnosticsSearch))
                    waitFor(filterOptions(diagnosticsSearch))
                    assertTrue(activeFilterCount(0))
                }
            }
            step("Selecting the raw-path option selects exactly that chip") {
                with(HistoryPage(composeRule)) {
                    val pathTag = diagnosticsPathFilter(ScanPathMode.RAW_PATH.name)
                    val statusTag = diagnosticsStatusFilter("completed")
                    click(pathTag)
                    rule.waitUntil(timeoutMillis = 5_000) { isSelected(pathTag) }
                    assertFalse(isSelected(statusTag))
                    assertTrue(activeFilterCount(1))
                }
            }
            step("Selecting completed adds a second filter, then a query adds a third") {
                with(HistoryPage(composeRule)) {
                    val statusTag = diagnosticsStatusFilter("completed")
                    click(statusTag)
                    rule.waitUntil(timeoutMillis = 5_000) { isSelected(statusTag) }
                    assertTrue(activeFilterCount(2))
                    typeQuery("Automation")
                    rule.waitUntil(timeoutMillis = 5_000) { activeFilterCount(3) }
                }
            }
            step("Switching sections keeps every filter") {
                with(HistoryPage(composeRule)) {
                    click(section(HistorySection.Connections))
                    waitFor(connectionsStateEmpty)
                    click(section(HistorySection.Diagnostics))
                    waitFor(diagnosticsSearch)
                    assertTrue(activeFilterCount(3))
                }
            }
            step("Removing the status chip keeps the query and path") {
                with(HistoryPage(composeRule)) {
                    val pathTag = diagnosticsPathFilter(ScanPathMode.RAW_PATH.name)
                    val statusTag = diagnosticsStatusFilter("completed")
                    click(filterRemove(diagnosticsSearch, "secondary"))
                    rule.waitUntil(timeoutMillis = 5_000) { activeFilterCount(2) }
                    if (!exists(filterOptions(diagnosticsSearch))) {
                        click(filterDisclosure(diagnosticsSearch))
                    }
                    waitFor(filterOptions(diagnosticsSearch))
                    require(pathTag)
                    assertTrue(isSelected(pathTag))
                    require(statusTag)
                    assertFalse(isSelected(statusTag))
                }
            }
            step("Clear all removes every filter and keeps the saved report") {
                with(HistoryPage(composeRule)) {
                    click(filterClearAll)
                    rule.waitUntil(timeoutMillis = 5_000) { activeFilterCount(0) }
                    require(diagnosticsSession(ReportSessionId))
                }
            }
            step("An unmatched query shows no results and the recovery action restores the report") {
                with(HistoryPage(composeRule)) {
                    val reportTag = diagnosticsSession(ReportSessionId)
                    typeQuery("missing-record-7d4f07")
                    waitFor(noResults)
                    assertFalse(exists(reportTag))
                    click(emptyClearFilters)
                    waitFor(reportTag)
                    assertFalse(exists(diagnosticsDetailSheet))
                }
            }
        }

    @Test
    fun openingTheReportShowsItsDetailSheet() =
        run {
            step("Tapping the saved report opens the detail sheet") {
                with(HistoryPage(composeRule)) {
                    openDiagnostics()
                    click(diagnosticsSession(ReportSessionId))
                    waitFor(diagnosticsDetailSheet)
                }
            }
            step("Back closes the sheet and keeps the list") {
                with(HistoryPage(composeRule)) {
                    pressBack()
                    waitGone(diagnosticsDetailSheet)
                    assertTrue(exists(diagnosticsSession(ReportSessionId)))
                }
            }
        }
}
