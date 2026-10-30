package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.exists
import com.poyka.ripdpi.ui.journeys.reveal
import com.poyka.ripdpi.ui.journeys.tagStartingWith
import com.poyka.ripdpi.ui.navigation.Route
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
    private fun DiagnosticsPage.openScanSection() {
        awaitScreen()
        click(section(DiagnosticsSection.Scan))
        rule.waitUntil(timeoutMillis = 15_000) { reveal(strategyProbeReport) }
    }

    private fun DiagnosticsPage.expectCandidateSheetThenBack() {
        waitFor(strategyCandidateDetailSheet)
        waitFor(strategyCandidateNotesSection)
        assertTrue(rule.exists(hasText(CandidateNote, substring = true)))
        assertTrue(rule.exists(hasText("Automation fixture candidate", substring = true)))
        pressBack()
        waitGone(strategyCandidateDetailSheet)
        assertTrue(exists(screenTag))
    }

    @Test
    fun dashboardOffersScanAndReviewChoicesForTheSeededReport() =
        run {
            step("Dashboard shows the outcome hero, the run-scan action and the candidate-review funnel") {
                with(DiagnosticsPage(composeRule)) {
                    awaitScreen()
                    waitFor(overviewHero)
                    require(overviewRunScanAction)
                    require(simpleFunnel)
                    require(simpleApply)
                }
            }
            step("The funnel apply action is enabled because a recommendation exists") {
                with(DiagnosticsPage(composeRule)) {
                    node(simpleApply).assertIsEnabled()
                }
            }
        }

    @Test
    fun reportShowsWinningPathAuditAndContentState() =
        run {
            step("Scan section is in the content state, not idle") {
                with(DiagnosticsPage(composeRule)) {
                    openScanSection()
                    assertTrue(reveal(scanStateContent))
                    assertFalse(exists(scanStateIdle))
                }
            }
            step("Winning path, summary and the medium-confidence audit note are shown") {
                with(DiagnosticsPage(composeRule)) {
                    assertTrue(reveal(strategyWinningPath))
                    assertTrue(reveal(strategyWinningTcpAction))
                    assertTrue(reveal(strategyWinningQuicAction))
                    assertTrue(reveal(strategyProbeSummary))
                    assertTrue(reveal(strategyAuditAssessment))
                    assertTrue(reveal(strategyAuditMediumConfidenceNote))
                    assertFalse(exists(strategyAuditLowConfidenceBanner))
                }
            }
        }

    @Test
    fun fullMatrixToggleRevealsAndHidesEveryCandidate() =
        run {
            step("With a winning path the candidate matrix starts hidden") {
                with(DiagnosticsPage(composeRule)) {
                    openScanSection()
                    assertTrue(reveal(strategyFullMatrixToggle))
                    assertFalse(reveal(strategyCandidate("tlsrec_split_host")))
                    assertFalse(exists(strategyCandidate("quic_sni_split")))
                }
            }
            step("Show full matrix lists the TCP and QUIC candidates") {
                with(DiagnosticsPage(composeRule)) {
                    click(strategyFullMatrixToggle)
                    rule.waitUntil(timeoutMillis = 5_000) { reveal(strategyCandidate("tlsrec_split_host")) }
                    assertTrue(reveal(strategyCandidate("split_host")))
                    assertTrue(reveal(strategyCandidate("quic_sni_split")))
                    assertTrue(reveal(strategyCandidate("quic_disabled")))
                }
            }
            step("A candidate row opens its detail sheet") {
                with(DiagnosticsPage(composeRule)) {
                    click(strategyCandidate("tlsrec_split_host"))
                    expectCandidateSheetThenBack()
                }
            }
            step("Hide full matrix removes the candidates again") {
                with(DiagnosticsPage(composeRule)) {
                    click(strategyFullMatrixToggle)
                    rule.waitUntil(timeoutMillis = 5_000) { !exists(strategyCandidate("tlsrec_split_host")) }
                }
            }
        }

    @Test
    fun winningTcpAndQuicActionsOpenCandidateSheets() =
        run {
            step("The winning TCP path opens its candidate sheet") {
                with(DiagnosticsPage(composeRule)) {
                    openScanSection()
                    click(strategyWinningTcpAction)
                    expectCandidateSheetThenBack()
                }
            }
            step("The winning QUIC path opens its candidate sheet") {
                with(DiagnosticsPage(composeRule)) {
                    click(strategyWinningQuicAction)
                    expectCandidateSheetThenBack()
                }
            }
        }

    @Test
    fun sessionAndProbeRowsOpenTheirDetailSheets() =
        run {
            step("The latest session row opens the session sheet") {
                with(DiagnosticsPage(composeRule)) {
                    openScanSection()
                    click(session(ReportSessionId))
                    waitFor(sessionDetailSheet)
                    pressBack()
                    waitGone(sessionDetailSheet)
                }
            }
            step("A probe evidence row opens the probe sheet") {
                with(DiagnosticsPage(composeRule)) {
                    val probeRow = tagStartingWith("diagnostics-probe-")
                    assertTrue(rule.reveal(probeRow))
                    rule.onAllNodes(probeRow).onFirst().performClick()
                    waitFor(probeDetailSheet)
                    pressBack()
                    waitGone(probeDetailSheet)
                }
            }
        }
}
