package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.ExportPreviewTestTags
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject

private const val FailureText = "proxy crashed"

@HiltAndroidTest
class LogsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Logs.stableRoute,
            dataPreset = AutomationDataPreset.DiagnosticsDemo,
        ),
    ) {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private fun openFilters() {
        composeRule.clickTag(RipDpiTestTags.LogsFilters)
        composeRule.waitForTag(RipDpiTestTags.LogsFiltersSheet)
    }

    private fun closeSheet() {
        composeRule.clickTag(RipDpiTestTags.LogsSheetDone)
        composeRule.waitForNoTag(RipDpiTestTags.LogsFiltersSheet)
    }

    private fun publishServiceHistory() {
        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
        serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError(FailureText))
        composeRule.waitForTag(RipDpiTestTags.LogsStream)
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.exists(hasText(FailureText, substring = true)) }
    }

    @Test
    fun emptyBufferOffersSaveButNoClear() =
        run {
            step("An empty buffer shows the empty state and no stream") {
                composeRule.waitForTag(RipDpiTestTags.LogsScreen)
                composeRule.waitForTag(RipDpiTestTags.LogsEmptyState)
                assertFalse(composeRule.tagExists(RipDpiTestTags.LogsStream))
            }
            step("Actions sheet: save is enabled, clear is disabled") {
                composeRule.clickTag(RipDpiTestTags.LogsActions)
                composeRule.waitForTag(RipDpiTestTags.LogsSave)
                composeRule.onAllNodes(hasTestTag(RipDpiTestTags.LogsSave)).onFirst().assertIsEnabled()
                composeRule.onAllNodes(hasTestTag(RipDpiTestTags.LogsClear)).onFirst().assertIsNotEnabled()
            }
            step("Save opens the in-app export preview and cancel returns to the logs") {
                composeRule.clickTag(RipDpiTestTags.LogsSave)
                composeRule.waitForTag(ExportPreviewTestTags.Content, timeoutMillis = 30_000)
                composeRule.clickTag(ExportPreviewTestTags.Cancel)
                composeRule.waitForNoTag(ExportPreviewTestTags.Content)
                assertTrue(composeRule.tagExists(RipDpiTestTags.LogsScreen))
            }
        }

    @Test
    fun filterControlsFlipAndSummaryTracksSelection() =
        run {
            step("Auto-scroll and active-session switches toggle") {
                composeRule.waitForTag(RipDpiTestTags.LogsScreen)
                openFilters()
                val autoBefore = composeRule.toggleState(RipDpiTestTags.LogsAutoScroll)
                assertEquals(ToggleableState.On, autoBefore)
                composeRule.clickTag(RipDpiTestTags.LogsAutoScroll)
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.toggleState(RipDpiTestTags.LogsAutoScroll) == ToggleableState.Off
                }
                val sessionBefore = composeRule.toggleState(RipDpiTestTags.LogsActiveSessionOnly)
                assertEquals(ToggleableState.Off, sessionBefore)
                composeRule.clickTag(RipDpiTestTags.LogsActiveSessionOnly)
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.toggleState(RipDpiTestTags.LogsActiveSessionOnly) == ToggleableState.On
                }
            }
            step("All chips start selected; deselecting one flips only that chip") {
                val diagnostics = RipDpiTestTags.logsSubsystemFilter(LogSubsystem.Diagnostics)
                val proxy = RipDpiTestTags.logsSubsystemFilter(LogSubsystem.Proxy)
                val warn = RipDpiTestTags.logsSeverityFilter(LogSeverity.Warn)
                composeRule.requireTag(diagnostics)
                assertTrue(composeRule.isSelected(diagnostics))
                composeRule.clickTag(diagnostics)
                composeRule.waitUntil(timeoutMillis = 5_000) { !composeRule.isSelected(diagnostics) }
                assertTrue(composeRule.isSelected(proxy))
                composeRule.requireTag(warn)
                composeRule.clickTag(warn)
                composeRule.waitUntil(timeoutMillis = 5_000) { !composeRule.isSelected(warn) }
            }
            step("The toolbar filter summary reflects the selection") {
                closeSheet()
                val summary = composeRule.stateDescription(RipDpiTestTags.LogsFilters)
                val allSubsystems = LogSubsystem.entries.size
                val allSeverities = LogSeverity.entries.size
                assertNotEquals(
                    "Subsystems: $allSubsystems • Severities: $allSeverities",
                    summary,
                )
                assertEquals("Subsystems: ${allSubsystems - 1} • Severities: ${allSeverities - 1}", summary)
            }
        }

    @Test
    fun severityAndSubsystemChipsFilterTheStream() =
        run {
            step("Service history shows lifecycle and failure rows") {
                composeRule.waitForTag(RipDpiTestTags.LogsScreen)
                publishServiceHistory()
                assertFalse(composeRule.tagExists(RipDpiTestTags.LogsEmptyState))
            }
            step("Hiding Error severity drops the failure row but keeps the stream") {
                openFilters()
                composeRule.clickTag(RipDpiTestTags.logsSeverityFilter(LogSeverity.Error))
                closeSheet()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    !composeRule.exists(hasText(FailureText, substring = true))
                }
                assertTrue(composeRule.tagExists(RipDpiTestTags.LogsStream))
            }
            step("Hiding the Service subsystem empties the stream") {
                openFilters()
                composeRule.clickTag(RipDpiTestTags.logsSubsystemFilter(LogSubsystem.Service))
                closeSheet()
                composeRule.waitForTag(RipDpiTestTags.LogsEmptyState)
                assertFalse(composeRule.tagExists(RipDpiTestTags.LogsStream))
            }
            step("Restoring both chips brings every row back") {
                openFilters()
                composeRule.clickTag(RipDpiTestTags.logsSubsystemFilter(LogSubsystem.Service))
                composeRule.clickTag(RipDpiTestTags.logsSeverityFilter(LogSeverity.Error))
                closeSheet()
                composeRule.waitForTag(RipDpiTestTags.LogsStream)
                composeRule.waitUntil(
                    timeoutMillis = 5_000,
                ) { composeRule.exists(hasText(FailureText, substring = true)) }
            }
        }

    @Test
    fun clearEmptiesTheBuffer() =
        run {
            step("Clear is enabled once the buffer has rows and removes them") {
                composeRule.waitForTag(RipDpiTestTags.LogsScreen)
                publishServiceHistory()
                composeRule.clickTag(RipDpiTestTags.LogsActions)
                composeRule.waitForTag(RipDpiTestTags.LogsClear)
                composeRule.onAllNodes(hasTestTag(RipDpiTestTags.LogsClear)).onFirst().assertIsEnabled()
                composeRule.clickTag(RipDpiTestTags.LogsClear)
                composeRule.waitForTag(RipDpiTestTags.LogsEmptyState)
                assertFalse(composeRule.tagExists(RipDpiTestTags.LogsStream))
            }
        }
}
