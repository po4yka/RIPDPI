package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
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
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private fun LogsPage.failureRowShown(): Boolean = rowShown(FailureText)

    private fun LogsPage.publishServiceHistory() {
        // StateFlow conflates back-to-back writes, so let the first lifecycle row land before the next status.
        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        waitFor(stream)
        serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
        serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError(FailureText))
        waitFor(stream)
        rule.waitUntil(timeoutMillis = 10_000) { failureRowShown() }
    }

    @Test
    fun emptyBufferOffersSaveButNoClear() =
        run {
            step("An empty buffer shows the empty state and no stream") {
                with(LogsPage(composeRule)) {
                    awaitScreen()
                    waitFor(emptyState)
                    assertFalse(exists(stream))
                }
            }
            step("Top bar: save is enabled") {
                with(LogsPage(composeRule)) {
                    waitFor(save)
                    node(save).assertIsEnabled()
                }
            }
            step("Save opens the in-app export preview and cancel returns to the logs") {
                with(LogsPage(composeRule)) {
                    click(save)
                    waitFor(ExportPreviewTestTags.Content, timeoutMillis = 30_000)
                    click(ExportPreviewTestTags.Cancel)
                    waitGone(ExportPreviewTestTags.Content)
                    assertTrue(exists(screenTag))
                }
            }
        }

    @Test
    fun filterControlsFlipAndSummaryTracksSelection() =
        run {
            step("Auto-scroll and active-session switches toggle") {
                with(LogsPage(composeRule)) {
                    awaitScreen()
                    openFilters()
                    val autoBefore = toggleState(autoScroll)
                    assertEquals(ToggleableState.On, autoBefore)
                    click(autoScroll)
                    rule.waitUntil(timeoutMillis = 5_000) { toggleState(autoScroll) == ToggleableState.Off }
                    val sessionBefore = toggleState(activeSessionOnly)
                    assertEquals(ToggleableState.Off, sessionBefore)
                    click(activeSessionOnly)
                    rule.waitUntil(timeoutMillis = 5_000) { toggleState(activeSessionOnly) == ToggleableState.On }
                }
            }
            step("All chips start selected; deselecting one flips only that chip") {
                with(LogsPage(composeRule)) {
                    val diagnostics = subsystemFilter(LogSubsystem.Diagnostics)
                    val proxy = subsystemFilter(LogSubsystem.Proxy)
                    val warn = severityFilter(LogSeverity.Warn)
                    require(diagnostics)
                    assertTrue(isSelected(diagnostics))
                    click(diagnostics)
                    rule.waitUntil(timeoutMillis = 5_000) { !isSelected(diagnostics) }
                    assertTrue(isSelected(proxy))
                    require(warn)
                    click(warn)
                    rule.waitUntil(timeoutMillis = 5_000) { !isSelected(warn) }
                }
            }
            step("The toolbar filter summary reflects the selection") {
                with(LogsPage(composeRule)) {
                    closeSheet()
                    val summary = stateDescription(filters)
                    val allSubsystems = LogSubsystem.entries.size
                    val allSeverities = LogSeverity.entries.size
                    assertNotEquals(
                        "Subsystems: $allSubsystems • Severities: $allSeverities",
                        summary,
                    )
                    assertEquals("Subsystems: ${allSubsystems - 1} • Severities: ${allSeverities - 1}", summary)
                }
            }
        }

    @Test
    fun severityAndSubsystemChipsFilterTheStream() =
        run {
            step("Service history shows lifecycle and failure rows") {
                with(LogsPage(composeRule)) {
                    awaitScreen()
                    publishServiceHistory()
                    assertFalse(exists(emptyState))
                }
            }
            step("Hiding Error severity drops the failure row but keeps the stream") {
                with(LogsPage(composeRule)) {
                    openFilters()
                    click(severityFilter(LogSeverity.Error))
                    closeSheet()
                    rule.waitUntil(timeoutMillis = 5_000) { !failureRowShown() }
                    assertTrue(exists(stream))
                }
            }
            step("Hiding the Service subsystem empties the stream") {
                with(LogsPage(composeRule)) {
                    openFilters()
                    click(subsystemFilter(LogSubsystem.Service))
                    closeSheet()
                    waitFor(emptyState)
                    assertFalse(exists(stream))
                }
            }
            step("Restoring both chips brings every row back") {
                with(LogsPage(composeRule)) {
                    openFilters()
                    click(subsystemFilter(LogSubsystem.Service))
                    click(severityFilter(LogSeverity.Error))
                    closeSheet()
                    waitFor(stream)
                    rule.waitUntil(timeoutMillis = 5_000) { failureRowShown() }
                }
            }
        }

    @Test
    fun clearEmptiesTheBuffer() =
        run {
            step("Clear is enabled once the buffer has rows and removes them") {
                with(LogsPage(composeRule)) {
                    awaitScreen()
                    publishServiceHistory()
                    click(actions)
                    waitFor(clear)
                    node(clear).assertIsEnabled()
                    click(clear)
                    waitFor(clearConfirm)
                    click(clearConfirm)
                    waitFor(emptyState)
                    assertFalse(exists(stream))
                }
            }
        }
}
