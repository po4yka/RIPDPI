package com.poyka.ripdpi.ui.journeys.diagnostics

import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Test

/** The `clean_home` preset clears runtime history, so every History section shows its empty state. */
@HiltAndroidTest
class HistoryEmptyStatesKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.History.stableRoute,
            dataPreset = AutomationDataPreset.CleanHome,
        ),
    ) {
    @Test
    fun everySectionShowsItsEmptyStateWithoutSearch() =
        run {
            step("Connections is empty and offers no search") {
                composeRule.waitForTag(RipDpiTestTags.screen(Route.History))
                composeRule.waitForTag(RipDpiTestTags.HistoryConnectionsStateEmpty)
                assertFalse(composeRule.tagExists(RipDpiTestTags.HistoryConnectionsSearch))
            }
            step("Diagnostics is empty and offers no search") {
                composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Diagnostics))
                composeRule.waitForTag(RipDpiTestTags.HistoryDiagnosticsStateEmpty)
                assertFalse(composeRule.tagExists(RipDpiTestTags.HistoryDiagnosticsSearch))
            }
            step("Events is empty") {
                composeRule.clickTag(RipDpiTestTags.historySection(HistorySection.Events))
                composeRule.waitForTag(RipDpiTestTags.HistoryEventsStateEmpty)
                assertFalse(composeRule.tagExists(RipDpiTestTags.HistoryEventsStateContent))
            }
        }
}
