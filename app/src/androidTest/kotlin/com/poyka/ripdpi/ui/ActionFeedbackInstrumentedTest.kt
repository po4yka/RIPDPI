package com.poyka.ripdpi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.activities.DiagnosticsEventFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventsUiModel
import com.poyka.ripdpi.activities.DiagnosticsOutcomeSource
import com.poyka.ripdpi.activities.DiagnosticsOutcomeUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.GroupedEventUiModel
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsOutcomeCard
import com.poyka.ripdpi.ui.screens.history.EventsSection
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device acceptance for production UI components; no service or network operation is started. */
@RunWith(AndroidJUnit4::class)
class ActionFeedbackInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun confirmationExpiresOnTheRealClockWithAnimationsDisabled() {
        var disconnects = 0
        compose.setContent {
            FeedbackTheme(RipDpiMotion(animationsEnabled = false)) {
                RipDpiConnectionActuator(
                    state = HomeConnectionActuatorUiState(status = HomeConnectionActuatorStatus.Locked),
                    onActivate = {},
                    onDeactivate = { disconnects++ },
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        val button = compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
        button.performClick()
        compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertIsDisplayed()
        val initialTime = confirmationText()
        compose.waitUntil(timeoutMillis = 2_500) { confirmationText() != initialTime }
        capture("confirmation-static")
        compose.waitUntil(timeoutMillis = 6_000) {
            compose
                .onAllNodesWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.runOnIdle { assertEquals(0, disconnects) }
        button.performClick()
        compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, disconnects) }
        button.performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
    }

    @Test
    fun terminalResultsTransitionOnceAndKeepOneAction() {
        compose.mainClock.autoAdvance = false
        var outcome by mutableStateOf(
            DiagnosticsOutcomeUiModel(DiagnosticsOutcomeSource.Active, summary = "Checking path"),
        )
        var scans = 0
        compose.setContent {
            FeedbackTheme(RipDpiMotion()) {
                DiagnosticsOutcomeCard(
                    outcome = outcome,
                    runNetworkCheck = false,
                    homeDiagnostics = HomeDiagnosticsUiState(),
                    onRunScan = { scans++ },
                    onOpenScan = {},
                    onStartVerifiedVpn = {},
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        listOf("Path measured", "Check failed", "Check cancelled").forEachIndexed { index, headline ->
            compose.runOnUiThread {
                outcome = DiagnosticsOutcomeUiModel(DiagnosticsOutcomeSource.Active, summary = "Checking path")
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                outcome =
                    DiagnosticsOutcomeUiModel(
                        source = DiagnosticsOutcomeSource.Session,
                        evidenceId = "device-run-$index",
                        headline = headline,
                        summary = "Device UI acceptance",
                    )
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.onNodeWithText("Checking path", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(headline).assertIsDisplayed()
            compose.onAllNodesWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction).assertCountEquals(1)
            if (index == 0) capture("diagnostics-transition")
            compose.mainClock.advanceTimeBy(240)
            compose.waitForIdle()
            compose.onAllNodesWithText("Checking path", useUnmergedTree = true).assertCountEquals(0)
        }
        compose.runOnUiThread { outcome = outcome.copy(summary = "Updated evidence") }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.onNodeWithText("Updated evidence").assertIsDisplayed()
        compose.onAllNodesWithText("Device UI acceptance", useUnmergedTree = true).assertCountEquals(0)
        capture("diagnostics-result")
        compose.onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction).performClick()
        compose.runOnIdle { assertEquals(1, scans) }
    }

    @Test
    fun recentEventActionPreservesPositionAndFiltersInReducedMotion() {
        var state by mutableStateOf(eventHistory(0))
        var filterChanges = 0
        compose.setContent {
            FeedbackTheme(RipDpiMotion(reducedMotion = true)) {
                EventsSection(
                    uiState = state,
                    onToggleFilter = { _, _ -> filterChanges++ },
                    onSearch = { filterChanges++ },
                    onClearFilters = { filterChanges++ },
                    onAutoScroll = { filterChanges++ },
                    onSelectEvent = {},
                    onOpenConnection = {},
                )
            }
        }
        compose
            .onNode(hasScrollAction().and(hasAnyDescendant(hasTestTag(RipDpiTestTags.HistoryEventsSearch))))
            .performScrollToIndex(15)
        val oldRow = compose.onNodeWithTag(RipDpiTestTags.historyEvent("event-14"))
        oldRow.assertIsDisplayed()
        val oldTop = oldRow.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state = eventHistory(1) }
        assertEquals(oldTop, oldRow.fetchSemanticsNode().boundsInRoot.top, 1f)
        val latest = compose.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest)
        latest.assertIsDisplayed()
        capture("events-reading")
        latest.performClick()
        compose.onNodeWithTag(RipDpiTestTags.historyEvent("event-new")).assertIsDisplayed()
        compose.onAllNodesWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals("info", state.events.filters.severity)
            assertEquals(0, filterChanges)
        }
    }

    @Test
    fun confirmationRemainsUsableWithLargeFontAndRtl() {
        var disconnects = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                FeedbackTheme(RipDpiMotion(reducedMotion = true), dark = true) {
                    RipDpiConnectionActuator(
                        state = HomeConnectionActuatorUiState(status = HomeConnectionActuatorStatus.Locked),
                        onActivate = {},
                        onDeactivate = { disconnects++ },
                        testTag = RipDpiTestTags.ConnectionActuatorButton,
                    )
                }
            }
        }
        val button = compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
        button.performClick()
        compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining).assertIsDisplayed()
        button.assertIsDisplayed()
        capture("confirmation-font2-rtl-dark")
        button.performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-motion-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }

    private fun confirmationText(): String =
        compose
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }
}

@Composable
private fun FeedbackTheme(
    motion: RipDpiMotion,
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    RipDpiTheme(themePreference = if (dark) "dark" else "light") {
        CompositionLocalProvider(LocalRipDpiMotion provides motion) {
            Column(
                Modifier.fillMaxSize().background(RipDpiThemeTokens.colors.background).padding(16.dp),
            ) { content() }
        }
    }
}

private fun eventHistory(newEvents: Int): HistoryUiState {
    val records = (if (newEvents > 0) listOf("event-new") else emptyList()) + (0 until 40).map { "event-$it" }
    val events =
        records
            .map { id ->
                DiagnosticsEventUiModel(
                    id = id,
                    source = "service",
                    severity = "info",
                    message = "Local event $id",
                    createdAtLabel = "12:00",
                    tone = DiagnosticsTone.Neutral,
                )
            }.toImmutableList()
    return HistoryUiState(
        events =
            DiagnosticsEventsUiModel(
                filters = DiagnosticsEventFiltersUiModel(severity = "info"),
                events = events,
            ),
        groupedEvents = events.map { GroupedEventUiModel(it, 1, null) }.toImmutableList(),
    )
}
