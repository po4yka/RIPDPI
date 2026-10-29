package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
    private fun openSection(section: DiagnosticsSection) {
        composeRule.waitForTag(RipDpiTestTags.screen(Route.Diagnostics()))
        composeRule.clickTag(RipDpiTestTags.diagnosticsSection(section))
    }

    private fun openExpertTools() {
        openSection(DiagnosticsSection.Tools)
        composeRule.clickTag(RipDpiTestTags.DiagnosticsExpertToggle)
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.revealTag(RipDpiTestTags.DiagnosticsShareArchive) }
    }

    @Test
    fun allThreeSectionsAreReachableFromTheTabs() =
        run {
            step("Every diagnostics section tab is shown, the dashboard hero is the landing content") {
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Diagnostics()))
                DiagnosticsSection.entries.forEach { section ->
                    assertTrue(composeRule.tagExists(RipDpiTestTags.diagnosticsSection(section)))
                }
                composeRule.waitForTag(RipDpiTestTags.DiagnosticsOverviewHero)
            }
        }

    @Test
    fun idleScanSectionOffersBothRunActions() =
        run {
            step("Scan section is idle, not content or progress") {
                openSection(DiagnosticsSection.Scan)
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.revealTag(RipDpiTestTags.DiagnosticsScanStateIdle)
                }
                assertFalse(composeRule.tagExists(RipDpiTestTags.DiagnosticsScanStateContent))
                assertFalse(composeRule.tagExists(RipDpiTestTags.DiagnosticsScanStateProgress))
            }
            step("Raw and in-path run actions are enabled for the selected profile") {
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsScanRunRawAction))
                composeRule
                    .onAllNodes(
                        hasTestTag(RipDpiTestTags.DiagnosticsScanRunRawAction),
                    ).onFirst()
                    .assertIsEnabled()
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsScanRunInPathAction))
                assertFalse(composeRule.tagExists(RipDpiTestTags.DiagnosticsScanCancelAction))
            }
        }

    @Test
    fun shareAndSaveActionsAreEnabledInExpertTools() =
        run {
            step("Expert mode reveals the archive, summary and log actions") {
                openExpertTools()
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsSaveArchive))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsShareSummary))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsSaveLogs))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsOpenLogs))
            }
            step("The archive actions are enabled") {
                composeRule.requireTag(RipDpiTestTags.DiagnosticsShareArchive)
                composeRule.onAllNodes(hasTestTag(RipDpiTestTags.DiagnosticsShareArchive)).onFirst().assertIsEnabled()
                composeRule.requireTag(RipDpiTestTags.DiagnosticsSaveArchive)
                composeRule.onAllNodes(hasTestTag(RipDpiTestTags.DiagnosticsSaveArchive)).onFirst().assertIsEnabled()
            }
        }

    @Test
    fun approachChipsSwitchBetweenProfilesAndStrategies() =
        run {
            val profiles = RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles)
            val strategies = RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Strategies)
            step("Profiles is the default mode") {
                openExpertTools()
                assertTrue(composeRule.revealTag(profiles))
                assertTrue(composeRule.isSelected(profiles))
                assertFalse(composeRule.isSelected(strategies))
            }
            step("Selecting Strategies deselects Profiles") {
                composeRule.clickTag(strategies)
                composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.isSelected(strategies) }
                assertFalse(composeRule.isSelected(profiles))
            }
            step("Selecting Profiles switches back") {
                composeRule.clickTag(profiles)
                composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.isSelected(profiles) }
                assertFalse(composeRule.isSelected(strategies))
            }
        }

    @Test
    fun openLogsFromToolsAndBackReturnsToDiagnostics() =
        run {
            step("The Logs action opens the logs screen") {
                openExpertTools()
                composeRule.clickTag(RipDpiTestTags.DiagnosticsOpenLogs)
                composeRule.waitForTag(RipDpiTestTags.LogsScreen)
            }
            step("Back returns to diagnostics") {
                composeRule.pressBack()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Diagnostics()))
                assertFalse(composeRule.tagExists(RipDpiTestTags.LogsScreen))
            }
        }
}
