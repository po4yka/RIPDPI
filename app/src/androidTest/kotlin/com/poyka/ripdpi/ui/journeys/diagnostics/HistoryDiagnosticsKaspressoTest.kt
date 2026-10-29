package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
    private val search = RipDpiTestTags.HistoryDiagnosticsSearch
    private val disclosure = RipDpiTestTags.historyFilterDisclosure(search)
    private val reportTag = RipDpiTestTags.historyDiagnosticsSession(ReportSessionId)
    private val pathTag = RipDpiTestTags.historyDiagnosticsPathFilter(ScanPathMode.RAW_PATH.name)
    private val statusTag = RipDpiTestTags.historyDiagnosticsStatusFilter("completed")

    private fun openDiagnostics() {
        composeRule.waitForTag(RipDpiTestTags.screen(Route.History))
        composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Diagnostics))
        composeRule.waitForTag(RipDpiTestTags.HistoryDiagnosticsStateContent)
        composeRule.waitForTag(reportTag)
    }

    private fun activeFilterCount(count: Int): Boolean =
        composeRule.exists(hasTestTag(disclosure) and hasText("· $count", substring = true))

    private fun typeQuery(query: String) {
        composeRule.requireTag(search)
        composeRule.onAllNodes(hasTestTag(search)).onFirst().performTextReplacement(query)
    }

    @Test
    fun exactPathAndStatusFilterLifecycleRetainsTheReport() =
        run {
            step("The seeded report is listed with no active filters") {
                openDiagnostics()
                composeRule.clickTag(disclosure)
                composeRule.waitForTag(RipDpiTestTags.historyFilterOptions(search))
                assertTrue(activeFilterCount(0))
            }
            step("Selecting the raw-path option selects exactly that chip") {
                composeRule.clickTag(pathTag)
                composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.isSelected(pathTag) }
                assertFalse(composeRule.isSelected(statusTag))
                assertTrue(activeFilterCount(1))
            }
            step("Selecting completed adds a second filter, then a query adds a third") {
                composeRule.clickTag(statusTag)
                composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.isSelected(statusTag) }
                assertTrue(activeFilterCount(2))
                typeQuery("Automation")
                composeRule.waitUntil(timeoutMillis = 5_000) { activeFilterCount(3) }
            }
            step("Switching sections keeps every filter") {
                composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Connections))
                composeRule.waitForTag(RipDpiTestTags.HistoryConnectionsStateEmpty)
                composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Diagnostics))
                composeRule.waitForTag(search)
                assertTrue(activeFilterCount(3))
            }
            step("Removing the status chip keeps the query and path") {
                composeRule.clickTag(RipDpiTestTags.historyFilterRemove(search, "secondary"))
                composeRule.waitUntil(timeoutMillis = 5_000) { activeFilterCount(2) }
                if (!composeRule.tagExists(RipDpiTestTags.historyFilterOptions(search))) {
                    composeRule.clickTag(disclosure)
                }
                composeRule.waitForTag(RipDpiTestTags.historyFilterOptions(search))
                composeRule.requireTag(pathTag)
                assertTrue(composeRule.isSelected(pathTag))
                composeRule.requireTag(statusTag)
                assertFalse(composeRule.isSelected(statusTag))
            }
            step("Clear all removes every filter and keeps the saved report") {
                composeRule.clickTag(RipDpiTestTags.HistoryFilterClearAll)
                composeRule.waitUntil(timeoutMillis = 5_000) { activeFilterCount(0) }
                composeRule.requireTag(reportTag)
            }
            step("An unmatched query shows no results and the recovery action restores the report") {
                typeQuery("missing-record-7d4f07")
                composeRule.waitForTag(RipDpiTestTags.HistoryNoResults)
                assertFalse(composeRule.tagExists(reportTag))
                composeRule.clickTag(RipDpiTestTags.HistoryEmptyClearFilters)
                composeRule.waitForTag(reportTag)
                assertFalse(composeRule.tagExists(RipDpiTestTags.HistoryDiagnosticsDetailSheet))
            }
        }

    @Test
    fun openingTheReportShowsItsDetailSheet() =
        run {
            step("Tapping the saved report opens the detail sheet") {
                openDiagnostics()
                composeRule.clickTag(reportTag)
                composeRule.waitForTag(RipDpiTestTags.HistoryDiagnosticsDetailSheet)
            }
            step("Back closes the sheet and keeps the list") {
                composeRule.pressBack()
                composeRule.waitForNoTag(RipDpiTestTags.HistoryDiagnosticsDetailSheet)
                assertTrue(composeRule.tagExists(reportTag))
            }
        }
}
