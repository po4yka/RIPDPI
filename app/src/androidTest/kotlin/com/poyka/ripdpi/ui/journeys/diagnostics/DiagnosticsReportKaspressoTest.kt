package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
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

private const val ReportSessionId = "automation-report-demo-session"
private const val CandidateNote = "Automation fixture note"

/** Diagnostics on the seeded strategy report (`diagnostics_report_demo`); no network scan is started. */
@HiltAndroidTest
class DiagnosticsReportKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Diagnostics().stableRoute,
            dataPreset = AutomationDataPreset.DiagnosticsReportDemo,
        ),
    ) {
    private val tlsRecordCandidate = RipDpiTestTags.diagnosticsStrategyCandidate("tlsrec_split_host")
    private val quicCandidate = RipDpiTestTags.diagnosticsStrategyCandidate("quic_sni_split")

    private fun openScanSection() {
        composeRule.waitForTag(RipDpiTestTags.screen(Route.Diagnostics()))
        composeRule.clickTag(RipDpiTestTags.diagnosticsSection(DiagnosticsSection.Scan))
        composeRule.waitUntil(
            timeoutMillis = 15_000,
        ) { composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyProbeReport) }
    }

    private fun expectCandidateSheetThenBack() {
        composeRule.waitForTag(RipDpiTestTags.DiagnosticsStrategyCandidateDetailSheet)
        composeRule.waitForTag(RipDpiTestTags.DiagnosticsStrategyCandidateNotesSection)
        assertTrue(composeRule.exists(hasText(CandidateNote, substring = true)))
        assertTrue(composeRule.exists(hasText("Automation fixture candidate", substring = true)))
        composeRule.pressBack()
        composeRule.waitForNoTag(RipDpiTestTags.DiagnosticsStrategyCandidateDetailSheet)
        assertTrue(composeRule.tagExists(RipDpiTestTags.screen(Route.Diagnostics())))
    }

    @Test
    fun dashboardOffersScanAndReviewChoicesForTheSeededReport() =
        run {
            step("Dashboard shows the outcome hero, the run-scan action and the candidate-review funnel") {
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Diagnostics()))
                composeRule.waitForTag(RipDpiTestTags.DiagnosticsOverviewHero)
                composeRule.requireTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction)
                composeRule.requireTag(RipDpiTestTags.DiagnosticsSimpleFunnel)
                composeRule.requireTag(RipDpiTestTags.DiagnosticsSimpleApply)
            }
            step("The funnel apply action is enabled because a recommendation exists") {
                composeRule
                    .onAllNodes(hasTestTag(RipDpiTestTags.DiagnosticsSimpleApply))
                    .onFirst()
                    .assertIsEnabled()
            }
        }

    @Test
    fun reportShowsWinningPathAuditAndContentState() =
        run {
            step("Scan section is in the content state, not idle") {
                openScanSection()
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsScanStateContent))
                assertFalse(composeRule.tagExists(RipDpiTestTags.DiagnosticsScanStateIdle))
            }
            step("Winning path, summary and the medium-confidence audit note are shown") {
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyWinningPath))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyWinningTcpAction))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyWinningQuicAction))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyProbeSummary))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyAuditAssessment))
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyAuditMediumConfidenceNote))
                assertFalse(composeRule.tagExists(RipDpiTestTags.DiagnosticsStrategyAuditLowConfidenceBanner))
            }
        }

    @Test
    fun fullMatrixToggleRevealsAndHidesEveryCandidate() =
        run {
            step("With a winning path the candidate matrix starts hidden") {
                openScanSection()
                assertTrue(composeRule.revealTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle))
                assertFalse(composeRule.revealTag(tlsRecordCandidate))
                assertFalse(composeRule.tagExists(quicCandidate))
            }
            step("Show full matrix lists the TCP and QUIC candidates") {
                composeRule.clickTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle)
                composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.revealTag(tlsRecordCandidate) }
                assertTrue(composeRule.revealTag(RipDpiTestTags.diagnosticsStrategyCandidate("split_host")))
                assertTrue(composeRule.revealTag(quicCandidate))
                assertTrue(composeRule.revealTag(RipDpiTestTags.diagnosticsStrategyCandidate("quic_disabled")))
            }
            step("A candidate row opens its detail sheet") {
                composeRule.clickTag(tlsRecordCandidate)
                expectCandidateSheetThenBack()
            }
            step("Hide full matrix removes the candidates again") {
                composeRule.clickTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle)
                composeRule.waitUntil(timeoutMillis = 5_000) { !composeRule.tagExists(tlsRecordCandidate) }
            }
        }

    @Test
    fun winningTcpAndQuicActionsOpenCandidateSheets() =
        run {
            step("The winning TCP path opens its candidate sheet") {
                openScanSection()
                composeRule.clickTag(RipDpiTestTags.DiagnosticsStrategyWinningTcpAction)
                expectCandidateSheetThenBack()
            }
            step("The winning QUIC path opens its candidate sheet") {
                composeRule.clickTag(RipDpiTestTags.DiagnosticsStrategyWinningQuicAction)
                expectCandidateSheetThenBack()
            }
        }

    @Test
    fun sessionAndProbeRowsOpenTheirDetailSheets() =
        run {
            step("The latest session row opens the session sheet") {
                openScanSection()
                composeRule.clickTag(RipDpiTestTags.diagnosticsSession(ReportSessionId))
                composeRule.waitForTag(RipDpiTestTags.DiagnosticsSessionDetailSheet)
                composeRule.pressBack()
                composeRule.waitForNoTag(RipDpiTestTags.DiagnosticsSessionDetailSheet)
            }
            step("A probe evidence row opens the probe sheet") {
                val probeRow = tagStartingWith("diagnostics-probe-")
                assertTrue(composeRule.reveal(probeRow))
                composeRule.onAllNodes(probeRow).onFirst().performClick()
                composeRule.waitForTag(RipDpiTestTags.DiagnosticsProbeDetailSheet)
                composeRule.pressBack()
                composeRule.waitForNoTag(RipDpiTestTags.DiagnosticsProbeDetailSheet)
            }
        }
}
