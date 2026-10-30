package com.poyka.ripdpi.ui.journeys.diagnostics

import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Test

/** The `settings_ready` preset clears runtime history, so every History section shows its empty state. */
@HiltAndroidTest
class HistoryEmptyStatesKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.History.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Test
    fun everySectionShowsItsEmptyStateWithoutSearch() =
        run {
            step("Connections is empty and offers no search") {
                with(HistoryPage(composeRule)) {
                    awaitScreen()
                    waitFor(connectionsStateEmpty)
                    assertFalse(exists(connectionsSearch))
                }
            }
            step("Diagnostics is empty and offers no search") {
                with(HistoryPage(composeRule)) {
                    click(section(HistorySection.Diagnostics))
                    waitFor(diagnosticsStateEmpty)
                    assertFalse(exists(diagnosticsSearch))
                }
            }
            step("Events is empty") {
                with(HistoryPage(composeRule)) {
                    click(section(HistorySection.Events))
                    waitFor(eventsStateEmpty)
                    assertFalse(exists(eventsStateContent))
                }
            }
        }
}
