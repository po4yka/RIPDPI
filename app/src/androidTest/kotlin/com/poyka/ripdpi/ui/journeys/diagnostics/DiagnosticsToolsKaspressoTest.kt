package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics without saved reports (`diagnostics_demo`): idle scan state and the Tools section actions. */
@HiltAndroidTest
class DiagnosticsToolsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Diagnostics().stableRoute,
            dataPreset = AutomationDataPreset.DiagnosticsDemo,
        ),
    ) {
    private fun DiagnosticsPage.openExpertTools() {
        openSection(DiagnosticsSection.Tools)
        click(expertToggle)
        rule.waitUntil(timeoutMillis = 10_000) { reveal(shareArchive) }
    }

    @Test
    fun allThreeSectionsAreReachableFromTheTabs() =
        run {
            step("Every diagnostics section tab is shown, the dashboard hero is the landing content") {
                with(DiagnosticsPage(composeRule)) {
                    awaitScreen()
                    DiagnosticsSection.entries.forEach { entry ->
                        assertTrue(exists(section(entry)))
                    }
                    waitFor(overviewHero)
                }
            }
        }

    @Test
    fun idleScanSectionOffersBothRunActions() =
        run {
            step("Scan section is idle, not content or progress") {
                with(DiagnosticsPage(composeRule)) {
                    openSection(DiagnosticsSection.Scan)
                    rule.waitUntil(timeoutMillis = 10_000) { reveal(scanStateIdle) }
                    assertFalse(exists(scanStateContent))
                    assertFalse(exists(scanStateProgress))
                }
            }
            step("Raw and in-path run actions are enabled for the selected profile") {
                with(DiagnosticsPage(composeRule)) {
                    assertTrue(reveal(scanRunRawAction))
                    node(scanRunRawAction).assertIsEnabled()
                    assertTrue(reveal(scanRunInPathAction))
                    assertFalse(exists(scanCancelAction))
                }
            }
        }

    @Test
    fun shareAndSaveActionsAreEnabledInExpertTools() =
        run {
            step("Expert mode reveals the archive, summary and log actions") {
                with(DiagnosticsPage(composeRule)) {
                    openExpertTools()
                    assertTrue(reveal(saveArchive))
                    assertTrue(reveal(shareSummary))
                    assertTrue(reveal(saveLogs))
                    assertTrue(reveal(openLogs))
                }
            }
            step("The archive actions are enabled") {
                with(DiagnosticsPage(composeRule)) {
                    require(shareArchive)
                    node(shareArchive).assertIsEnabled()
                    require(saveArchive)
                    node(saveArchive).assertIsEnabled()
                }
            }
        }

    @Test
    fun approachChipsSwitchBetweenProfilesAndStrategies() =
        run {
            step("Profiles is the default mode") {
                with(DiagnosticsPage(composeRule)) {
                    val profiles = approachMode(DiagnosticsApproachMode.Profiles)
                    val strategies = approachMode(DiagnosticsApproachMode.Strategies)
                    openExpertTools()
                    assertTrue(reveal(profiles))
                    assertTrue(isSelected(profiles))
                    assertFalse(isSelected(strategies))
                }
            }
            step("Selecting Strategies deselects Profiles") {
                with(DiagnosticsPage(composeRule)) {
                    val profiles = approachMode(DiagnosticsApproachMode.Profiles)
                    val strategies = approachMode(DiagnosticsApproachMode.Strategies)
                    click(strategies)
                    rule.waitUntil(timeoutMillis = 5_000) { isSelected(strategies) }
                    assertFalse(isSelected(profiles))
                }
            }
            step("Selecting Profiles switches back") {
                with(DiagnosticsPage(composeRule)) {
                    val profiles = approachMode(DiagnosticsApproachMode.Profiles)
                    val strategies = approachMode(DiagnosticsApproachMode.Strategies)
                    click(profiles)
                    rule.waitUntil(timeoutMillis = 5_000) { isSelected(profiles) }
                    assertFalse(isSelected(strategies))
                }
            }
        }

    @Test
    fun openLogsFromToolsAndBackReturnsToDiagnostics() =
        run {
            step("The Logs action opens the logs screen") {
                with(DiagnosticsPage(composeRule)) {
                    openExpertTools()
                    require(openLogs)
                    // The tag sits on the whole card; the Manage button is the clickable part.
                    rule.onAllNodes(hasText("Manage")).onFirst().performClick()
                }
                with(LogsPage(composeRule)) { awaitScreen() }
            }
            step("Back returns to diagnostics") {
                with(DiagnosticsPage(composeRule)) {
                    pressBack()
                    awaitScreen()
                    assertFalse(LogsPage(rule).exists(LogsPage(rule).screenTag))
                }
            }
        }
}
