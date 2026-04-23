package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.poyka.ripdpi.activities.DiagnosticsAutomaticProbeCalloutUiModel
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationStepUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsSessionDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeLiveProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeProgressLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DiagnosticsWorkflowRestrictionActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsWorkflowRestrictionReasonUiModel
import com.poyka.ripdpi.activities.DiagnosticsWorkflowRestrictionUiModel
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeDiagnosticsManager
import com.poyka.ripdpi.activities.HiddenProbeConflictDialogState
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.activities.createDiagnosticsViewModel
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidence
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditCoverage
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Suppress("LongMethod")
    @Test
    fun resolverRecommendationLadderOpensDnsSettings() {
        var openDnsSettingsCalls = 0
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    selectedProfile =
                                        DiagnosticsProfileOptionUiModel(
                                            id = "automatic-probing",
                                            name = "Automatic probing",
                                            source = "bundled",
                                            kind = ScanKind.STRATEGY_PROBE,
                                            strategyProbeSuiteId = "quick_v1",
                                        ),
                                    latestSession =
                                        DiagnosticsSessionRowUiModel(
                                            id = "session-1",
                                            profileId = "default",
                                            title = "Latest report",
                                            subtitle = "Resolver recommendation available",
                                            pathMode = "IN_PATH",
                                            serviceMode = "VPN",
                                            status = "completed",
                                            startedAtLabel = "now",
                                            summary = "DNS substitution detected",
                                            metrics = persistentListOf(),
                                            tone = DiagnosticsTone.Warning,
                                        ),
                                    resolverRecommendation =
                                        DiagnosticsResolverRecommendationUiModel(
                                            headline = "Switch DNS to Cloudflare",
                                            rationale = "UDP DNS showed substitution while DoH matched.",
                                            fields = emptyList(),
                                            appliedTemporarily = true,
                                            persistable = true,
                                        ),
                                    remediationLadder =
                                        remediationLadder(
                                            title = "Review DNS remediation",
                                            summary = "Open DNS settings and review the recommended resolver.",
                                            actionLabel = "Open DNS Settings",
                                            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_DNS_SETTINGS,
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenDnsSettings = { openDnsSettingsCalls += 1 },
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderCard).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsResolverRecommendationCard).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderAction).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, openDnsSettingsCalls) }
    }

    @Test
    fun hiddenProbeConflictDialogWiresWaitCancelAndDismissActions() {
        var waitClicks = 0
        var cancelClicks = 0
        var dismissClicks = 0

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    hiddenProbeConflictDialog =
                                        HiddenProbeConflictDialogState(
                                            requestId = "hidden-request",
                                            profileName = "Automatic probing",
                                            pathMode = com.poyka.ripdpi.diagnostics.ScanPathMode.RAW_PATH,
                                            scanKind = ScanKind.STRATEGY_PROBE,
                                            isFullAudit = false,
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onWaitForHiddenProbeAndRun = { waitClicks += 1 },
                    onCancelHiddenProbeAndRun = { cancelClicks += 1 },
                    onDismissHiddenProbeConflictDialog = { dismissClicks += 1 },
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictDialog).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictWait).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictCancelAndRun).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictDismiss).performClick()

        assertEquals(1, waitClicks)
        assertEquals(1, cancelClicks)
        assertEquals(1, dismissClicks)
    }

    @Test
    fun automaticAuditDefaultsToWinningPathAndHidesMatrixRows() {
        val tcpCandidateDetail = auditCandidateDetail()

        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    strategyProbeReport = auditReport(tcpCandidateDetail),
                ),
        )

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyProbeReport).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningPath).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyProbeSummary).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle).fetchSemanticsNode()
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.diagnosticsStrategyCandidate(tcpCandidateDetail.id),
            ).assertDoesNotExist()
    }

    @Test
    fun automaticAuditToggleRevealsAndHidesFullMatrixRows() {
        val tcpCandidateDetail = auditCandidateDetail()
        val quicCandidateDetail = auditQuicCandidateDetail()

        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    strategyProbeReport = auditReport(tcpCandidateDetail, quicCandidateDetail),
                ),
        )

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.diagnosticsStrategyCandidate(tcpCandidateDetail.id),
            ).assertDoesNotExist()
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.diagnosticsStrategyCandidate(quicCandidateDetail.id),
            ).assertDoesNotExist()

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide full matrix").assertIsDisplayed()

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Show full matrix").assertIsDisplayed()
    }

    @Test
    fun automaticAuditCandidateBottomSheetShowsHumanReadableSections() {
        val candidateDetail = auditCandidateDetail()

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan = DiagnosticsScanUiModel(strategyProbeReport = auditReport(candidateDetail)),
                            selectedStrategyProbeCandidate = candidateDetail,
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyCandidateDetailSheet).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyCandidateNotesSection).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyCandidateSignatureSection).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyCandidateResultsSection).fetchSemanticsNode()
    }

    @Test
    fun overviewRendersRecentAutomaticProbeCallout() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Dashboard.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Dashboard,
                            overview =
                                com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel(
                                    recentAutomaticProbe =
                                        DiagnosticsAutomaticProbeCalloutUiModel(
                                            title = "Hidden automatic probing finished",
                                            summary = "Automatic probe summary",
                                            detail = "Completed Mar 27, 12:00 • After handover: Transport Switch",
                                            actionLabel = "Open History",
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewAutomaticProbeCard).assertIsDisplayed()
        composeRule.onNodeWithText("Automatic probe summary").assertIsDisplayed()
    }

    @Test
    fun automaticAuditLowConfidenceRendersWarningBannerAndCoverageMetrics() {
        val tcpCandidateDetail = auditCandidateDetail()

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    strategyProbeReport =
                                        auditReport(
                                            tcpCandidateDetail,
                                            auditAssessment =
                                                auditAssessment(
                                                    level = StrategyProbeAuditConfidenceLevel.LOW,
                                                    score = 35,
                                                    matrixCoveragePercent = 42,
                                                    winnerCoveragePercent = 48,
                                                    warnings =
                                                        listOf(
                                                            "TCP matrix coverage stayed" +
                                                                " below 75% of planned candidates.",
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningPath).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyAuditAssessment).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyAuditLowConfidenceBanner).fetchSemanticsNode()
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.diagnosticsStrategyCandidate(tcpCandidateDetail.id),
            ).assertDoesNotExist()
    }

    @Test
    fun automaticProbeDnsShortCircuitRendersResolverRecommendationState() {
        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "automatic-probing",
                            name = "Automatic probing",
                            source = "bundled",
                            kind = ScanKind.STRATEGY_PROBE,
                            strategyProbeSuiteId = "quick_v1",
                        ),
                    strategyProbeReport =
                        auditReport(
                            candidateDetail = auditCandidateDetail(),
                            suiteId = "quick_v1",
                            suiteLabel = "Automatic probing",
                            completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
                        ),
                ),
        )

        composeRule.onNodeWithText("Resolver recommendation ready").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recommendation ready").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningPath).assertDoesNotExist()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyProbeReport).fetchSemanticsNode()
        composeRule.onNodeWithText("RESOLVER RECOMMENDATION").assertExists()
    }

    @Test
    fun automaticAuditDnsShortCircuitUsesIncompleteStatusAndHidesWinningPath() {
        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "automatic-audit",
                            name = "Automatic audit",
                            source = "bundled",
                            kind = ScanKind.STRATEGY_PROBE,
                            strategyProbeSuiteId = "full_matrix_v1",
                        ),
                    strategyProbeReport =
                        auditReport(
                            candidateDetail = auditCandidateDetail(skipped = true),
                            quicCandidateDetail = auditQuicCandidateDetail(skipped = true),
                            completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
                            auditAssessment =
                                auditAssessment(
                                    level = StrategyProbeAuditConfidenceLevel.LOW,
                                    score = 35,
                                    matrixCoveragePercent = 0,
                                    winnerCoveragePercent = 0,
                                    warnings =
                                        listOf(
                                            "Baseline DNS tampering short-circuited" +
                                                " the audit before fallback candidates ran.",
                                        ),
                                    dnsShortCircuited = true,
                                ),
                        ),
                ),
        )

        composeRule.onNodeWithText("Audit short-circuited").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Audit ready").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningPath).assertDoesNotExist()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyProbeReport).fetchSemanticsNode()
        composeRule.onNodeWithText("INCOMPLETE AUDIT").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyAuditLowConfidenceBanner).fetchSemanticsNode()
    }

    @Test
    fun automaticAuditMediumConfidenceRendersCautionNote() {
        val tcpCandidateDetail = auditCandidateDetail()

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    strategyProbeReport =
                                        auditReport(
                                            tcpCandidateDetail,
                                            auditAssessment =
                                                auditAssessment(
                                                    level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                                    score = 70,
                                                    matrixCoveragePercent = 68,
                                                    winnerCoveragePercent = 100,
                                                    warnings =
                                                        listOf(
                                                            "TCP matrix coverage stayed" +
                                                                " below 75% of planned candidates.",
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyAuditAssessment).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyAuditMediumConfidenceNote).fetchSemanticsNode()
    }

    @Test
    fun nonAuditStrategyProbeDoesNotRenderAssessmentBlock() {
        val candidateDetail = auditCandidateDetail()

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    strategyProbeReport =
                                        auditReport(
                                            candidateDetail = candidateDetail,
                                            suiteId = "quick_v1",
                                            suiteLabel = "Automatic probing",
                                            auditAssessment = null,
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithText("Audit confidence").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningPath).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsStrategyCandidate(candidateDetail.id)).assertIsDisplayed()
    }

    @Test
    fun tappingWinningTcpCardOpensCandidateDetailSheet() {
        val tcpCandidateDetail = auditCandidateDetail()
        var selectedCandidateId: String? = null

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            var selectedStrategyProbeCandidate by remember {
                mutableStateOf<DiagnosticsStrategyProbeCandidateDetailUiModel?>(null)
            }
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            selectedStrategyProbeCandidate = selectedStrategyProbeCandidate,
                            scan =
                                DiagnosticsScanUiModel(
                                    strategyProbeReport = auditReport(tcpCandidateDetail),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {
                        selectedCandidateId = it.id
                        selectedStrategyProbeCandidate = it
                    },
                    onDismissStrategyProbeCandidate = { selectedStrategyProbeCandidate = null },
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningTcpAction).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(tcpCandidateDetail.id, selectedCandidateId) }
    }

    @Test
    fun tappingWinningQuicCardOpensCandidateDetailSheet() {
        val quicCandidateDetail = auditQuicCandidateDetail()
        var selectedCandidateId: String? = null

        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            var selectedStrategyProbeCandidate by remember {
                mutableStateOf<DiagnosticsStrategyProbeCandidateDetailUiModel?>(null)
            }
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            selectedStrategyProbeCandidate = selectedStrategyProbeCandidate,
                            scan =
                                DiagnosticsScanUiModel(
                                    strategyProbeReport = auditReport(auditCandidateDetail(), quicCandidateDetail),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {
                        selectedCandidateId = it.id
                        selectedStrategyProbeCandidate = it
                    },
                    onDismissStrategyProbeCandidate = { selectedStrategyProbeCandidate = null },
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyWinningQuicAction).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(quicCandidateDetail.id, selectedCandidateId) }
    }

    @Test
    fun strategyProbeProgressCardRendersLaneCounterAndCandidateLabelForQuickSuite() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    selectedProfileId = "automatic-probing",
                                    selectedProfile =
                                        DiagnosticsProfileOptionUiModel(
                                            id = "automatic-probing",
                                            name = "Automatic probing",
                                            source = "bundled",
                                            kind = ScanKind.STRATEGY_PROBE,
                                            strategyProbeSuiteId = "quick_v1",
                                        ),
                                    activeProgress =
                                        strategyProbeProgressUiModel(
                                            strategyProbeProgress =
                                                DiagnosticsStrategyProbeLiveProgressUiModel(
                                                    lane = DiagnosticsStrategyProbeProgressLaneUiModel.TCP,
                                                    candidateIndex = 3,
                                                    candidateTotal = 7,
                                                    candidateId = "tcp_fake_tls",
                                                    candidateLabel = "TCP fake TLS",
                                                ),
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanProgressCard).assertIsDisplayed()
        composeRule.onNodeWithText("3/7").assertIsDisplayed()
        composeRule.onNodeWithText("TCP fake TLS").assertIsDisplayed()
    }

    @Test
    fun connectivityProgressCardDoesNotRenderCandidateCounterWithoutStrategyProgress() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan =
                                DiagnosticsScanUiModel(
                                    selectedProfileId = "connectivity",
                                    selectedProfile =
                                        DiagnosticsProfileOptionUiModel(
                                            id = "connectivity",
                                            name = "Connectivity checks",
                                            source = "bundled",
                                            kind = ScanKind.CONNECTIVITY,
                                        ),
                                    activeProgress =
                                        strategyProbeProgressUiModel(
                                            scanKind = ScanKind.CONNECTIVITY,
                                            currentProbeLabel = "DNS probe example.org",
                                            strategyProbeProgress = null,
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanProgressCard).assertIsDisplayed()
        composeRule.onNodeWithText("DNS probe example.org").assertIsDisplayed()
        composeRule.onNodeWithText("3/7").assertDoesNotExist()
    }

    @Test
    fun blockedAutomaticAuditRendersExactRemediationAndOpensAdvancedSettings() {
        var openAdvancedSettingsCalls = 0

        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "automatic-audit",
                            name = "Automatic audit",
                            source = "bundled",
                            kind = ScanKind.STRATEGY_PROBE,
                            strategyProbeSuiteId = "full_matrix_v1",
                        ),
                    selectedProfileScopeLabel = "Automatic audit · raw-path only · blocked by command-line mode",
                    runRawEnabled = false,
                    runInPathEnabled = false,
                    remediationLadder =
                        remediationLadder(
                            title = "Automatic audit unavailable",
                            summary =
                                "Automatic audit is blocked because Use command line settings is enabled. " +
                                    "Turn it off in Advanced Settings before running this workflow.",
                            actionLabel = "Open Advanced Settings",
                            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS,
                            tone = DiagnosticsTone.Negative,
                        ),
                    workflowRestriction =
                        workflowRestriction(
                            title = "Automatic audit unavailable",
                            body =
                                "Automatic audit is blocked because Use command line settings is enabled. " +
                                    "Turn it off in Advanced Settings before running this workflow.",
                        ),
                ),
            onOpenAdvancedSettings = { openAdvancedSettingsCalls += 1 },
        )

        composeRule
            .onNodeWithTag(
                RipDpiTestTags.DiagnosticsRemediationLadderCard,
            ).performScrollTo()
            .fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderAction).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, openAdvancedSettingsCalls) }
    }

    @Test
    fun blockedAutomaticProbingRendersProbeSpecificRemediation() {
        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "automatic-probing",
                            name = "Automatic probing",
                            source = "bundled",
                            kind = ScanKind.STRATEGY_PROBE,
                            strategyProbeSuiteId = "quick_v1",
                        ),
                    selectedProfileScopeLabel = "Automatic probing · raw-path only · blocked by command-line mode",
                    runRawEnabled = false,
                    runInPathEnabled = false,
                    remediationLadder =
                        remediationLadder(
                            title = "Automatic probing unavailable",
                            summary =
                                "Automatic probing is blocked because Use command line settings is enabled. " +
                                    "Turn it off in Advanced Settings before running this workflow.",
                            actionLabel = "Open Advanced Settings",
                            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS,
                            tone = DiagnosticsTone.Negative,
                        ),
                    workflowRestriction =
                        workflowRestriction(
                            title = "Automatic probing unavailable",
                            body =
                                "Automatic probing is blocked because Use command line settings is enabled. " +
                                    "Turn it off in Advanced Settings before running this workflow.",
                        ),
                ),
        )

        composeRule
            .onNodeWithTag(
                RipDpiTestTags.DiagnosticsRemediationLadderCard,
            ).performScrollTo()
            .fetchSemanticsNode()
    }

    @Test
    fun availableStrategyProbeKeepsRawPathHintAndDoesNotRenderRestrictionCard() {
        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "automatic-probing",
                            name = "Automatic probing",
                            source = "bundled",
                            kind = ScanKind.STRATEGY_PROBE,
                            strategyProbeSuiteId = "quick_v1",
                        ),
                    selectedProfileScopeLabel = "Automatic probing · raw-path only",
                    runRawEnabled = true,
                    runInPathEnabled = false,
                    runRawHint =
                        "Automatic probing starts a temporary raw-path RIPDPI runtime" +
                            " and returns a manual recommendation.",
                    runInPathHint =
                        "Automatic probing is raw-path only because it launches isolated temporary strategy trials.",
                ),
        )

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithText("Automatic probing · raw-path only").fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderCard).assertDoesNotExist()
    }

    @Test
    fun connectivityWorkflowDoesNotRenderRestrictionCard() {
        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "connectivity",
                            name = "Connectivity checks",
                            source = "bundled",
                            kind = ScanKind.CONNECTIVITY,
                        ),
                    runRawEnabled = true,
                    runInPathEnabled = true,
                ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderCard).assertDoesNotExist()
    }

    @Test
    fun vpnPermissionWarningRenderedForStrategyProbeWhenVpnDisabled() {
        var vpnPermissionClicks = 0

        setScanScreen(
            scan =
                DiagnosticsScanUiModel(
                    selectedProfile =
                        DiagnosticsProfileOptionUiModel(
                            id = "automatic-probing",
                            name = "Automatic probing",
                            source = "bundled",
                            kind = ScanKind.STRATEGY_PROBE,
                            strategyProbeSuiteId = "quick_v1",
                        ),
                    runRawEnabled = true,
                    runInPathEnabled = false,
                    remediationLadder =
                        remediationLadder(
                            title = "VPN permission recommended",
                            summary =
                                "Automatic probing recommendations assume VPN routing. " +
                                    "Without VPN permission, the recommended strategy cannot be applied " +
                                    "in VPN mode. Grant permission before running this audit for actionable results.",
                            actionLabel = "Grant VPN permission",
                            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_VPN_PERMISSION,
                        ),
                    workflowRestriction =
                        vpnPermissionRestriction(
                            title = "VPN permission recommended",
                            body =
                                "Automatic probing recommendations assume VPN routing. " +
                                    "Without VPN permission, the recommended strategy cannot be applied " +
                                    "in VPN mode. Grant permission before running this audit for actionable results.",
                        ),
                ),
            onRequestVpnPermission = { vpnPermissionClicks += 1 },
        )

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderCard).fetchSemanticsNode()
        composeRule.onNodeWithText("Use command line settings").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsRemediationLadderAction).performClick()
        composeRule.runOnIdle { assertEquals(1, vpnPermissionClicks) }
    }

    private fun remediationLadder(
        title: String,
        summary: String,
        actionLabel: String,
        actionKind: DiagnosticsRemediationActionKindUiModel,
        tone: DiagnosticsTone = DiagnosticsTone.Warning,
    ) = DiagnosticsRemediationLadderUiModel(
        title = title,
        summary = summary,
        steps =
            persistentListOf(
                DiagnosticsRemediationStepUiModel("Open the recommended next step."),
                DiagnosticsRemediationStepUiModel("Review the current diagnostics state."),
                DiagnosticsRemediationStepUiModel("Retry the workflow after the change."),
            ),
        primaryAction = DiagnosticsRemediationActionUiModel(label = actionLabel, kind = actionKind),
        tone = tone,
    )

    @Suppress("LongMethod")
    private fun auditReport(
        candidateDetail: DiagnosticsStrategyProbeCandidateDetailUiModel,
        quicCandidateDetail: DiagnosticsStrategyProbeCandidateDetailUiModel = auditQuicCandidateDetail(),
        suiteId: String = "full_matrix_v1",
        suiteLabel: String = "Automatic audit",
        completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
        auditAssessment: StrategyProbeAuditAssessment? =
            auditAssessment(
                level = StrategyProbeAuditConfidenceLevel.HIGH,
                score = 92,
                matrixCoveragePercent = 92,
                winnerCoveragePercent = 100,
                warnings = emptyList(),
            ),
    ) = DiagnosticsStrategyProbeReportUiModel(
        suiteId = suiteId,
        suiteLabel = suiteLabel,
        summaryMetrics =
            persistentListOf(
                DiagnosticsMetricUiModel(label = "Worked", value = "2", tone = DiagnosticsTone.Positive),
                DiagnosticsMetricUiModel(label = "Partial", value = "1", tone = DiagnosticsTone.Warning),
                DiagnosticsMetricUiModel(label = "Failed", value = "1", tone = DiagnosticsTone.Negative),
                DiagnosticsMetricUiModel(label = "N/A", value = "1"),
            ),
        completionKind = completionKind,
        auditAssessment = auditAssessment,
        recommendation =
            DiagnosticsStrategyProbeRecommendationUiModel(
                headline =
                    if (completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED) {
                        "Resolver override recommended"
                    } else {
                        "TLS record + hostfake + QUIC realistic burst"
                    },
                rationale =
                    if (completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED) {
                        "DNS tampering classified before fallback; keep current strategy and prefer resolver override."
                    } else {
                        "Best combined recovery across TCP and QUIC."
                    },
                fields =
                    listOf(
                        DiagnosticsFieldUiModel(
                            if (completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED) {
                                "TCP fallback"
                            } else {
                                "TCP recommendation"
                            },
                            "TLS record + hostfake",
                        ),
                        DiagnosticsFieldUiModel(
                            if (completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED) {
                                "QUIC fallback"
                            } else {
                                "QUIC recommendation"
                            },
                            "QUIC realistic burst",
                        ),
                    ),
                signature =
                    persistentListOf(
                        DiagnosticsFieldUiModel("Chain", "tlsrec(extlen) -> hostfake(endhost+8)"),
                    ),
            ),
        winningPath =
            if (suiteId == "full_matrix_v1" && completionKind != StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED) {
                DiagnosticsStrategyProbeWinningPathUiModel(
                    tcpWinner =
                        DiagnosticsStrategyProbeWinningCandidateUiModel(
                            id = candidateDetail.id,
                            label = candidateDetail.label,
                            familyLabel = candidateDetail.familyLabel,
                            outcome = candidateDetail.outcome,
                            rationale = candidateDetail.rationale,
                            metrics = candidateDetail.metrics,
                            tone = candidateDetail.tone,
                            hiddenCandidateCount = 1,
                        ),
                    quicWinner =
                        DiagnosticsStrategyProbeWinningCandidateUiModel(
                            id = quicCandidateDetail.id,
                            label = quicCandidateDetail.label,
                            familyLabel = quicCandidateDetail.familyLabel,
                            outcome = quicCandidateDetail.outcome,
                            rationale = quicCandidateDetail.rationale,
                            metrics = quicCandidateDetail.metrics,
                            tone = quicCandidateDetail.tone,
                            hiddenCandidateCount = 1,
                        ),
                    dnsLaneLabel = "System DNS",
                )
            } else {
                null
            },
        families =
            persistentListOf(
                DiagnosticsStrategyProbeFamilyUiModel(
                    title = "TCP / HTTP / HTTPS matrix",
                    candidates =
                        persistentListOf(
                            DiagnosticsStrategyProbeCandidateUiModel(
                                id = candidateDetail.id,
                                label = candidateDetail.label,
                                outcome = candidateDetail.outcome,
                                rationale = candidateDetail.rationale,
                                metrics = candidateDetail.metrics,
                                tone = candidateDetail.tone,
                                skipped = candidateDetail.outcome == "Skipped",
                                recommended = true,
                            ),
                        ),
                ),
                DiagnosticsStrategyProbeFamilyUiModel(
                    title = "QUIC matrix",
                    candidates =
                        persistentListOf(
                            DiagnosticsStrategyProbeCandidateUiModel(
                                id = quicCandidateDetail.id,
                                label = quicCandidateDetail.label,
                                outcome = quicCandidateDetail.outcome,
                                rationale = quicCandidateDetail.rationale,
                                metrics = quicCandidateDetail.metrics,
                                tone = quicCandidateDetail.tone,
                                skipped = quicCandidateDetail.outcome == "Skipped",
                                recommended = true,
                            ),
                        ),
                ),
            ),
        candidateDetails =
            persistentMapOf(
                candidateDetail.id to candidateDetail,
                quicCandidateDetail.id to quicCandidateDetail,
            ),
    )

    private fun auditAssessment(
        level: StrategyProbeAuditConfidenceLevel,
        score: Int,
        matrixCoveragePercent: Int,
        winnerCoveragePercent: Int,
        warnings: List<String>,
        dnsShortCircuited: Boolean = false,
    ) = StrategyProbeAuditAssessment(
        dnsShortCircuited = dnsShortCircuited,
        coverage =
            StrategyProbeAuditCoverage(
                tcpCandidatesPlanned = 11,
                tcpCandidatesExecuted = 6,
                tcpCandidatesSkipped = 0,
                tcpCandidatesNotApplicable = 0,
                quicCandidatesPlanned = 2,
                quicCandidatesExecuted = 2,
                quicCandidatesSkipped = 0,
                quicCandidatesNotApplicable = 0,
                tcpWinnerSucceededTargets = 3,
                tcpWinnerTotalTargets = 3,
                quicWinnerSucceededTargets = 1,
                quicWinnerTotalTargets = 1,
                matrixCoveragePercent = matrixCoveragePercent,
                winnerCoveragePercent = winnerCoveragePercent,
            ),
        confidence =
            StrategyProbeAuditConfidence(
                level = level,
                score = score,
                rationale = "Audit assessment rationale",
                warnings = warnings,
            ),
    )

    private fun strategyProbeProgressUiModel(
        scanKind: ScanKind = ScanKind.STRATEGY_PROBE,
        currentProbeLabel: String = "TCP fake TLS",
        strategyProbeProgress: DiagnosticsStrategyProbeLiveProgressUiModel?,
    ) = DiagnosticsProgressUiModel(
        phase = if (scanKind == ScanKind.STRATEGY_PROBE) "tcp" else "dns",
        summary = currentProbeLabel,
        completedSteps = 3,
        totalSteps = 12,
        fraction = 0.25f,
        scanKind = scanKind,
        isFullAudit = false,
        elapsedLabel = "30s",
        etaLabel = "~1m 30s remaining",
        phaseSteps =
            if (scanKind == ScanKind.STRATEGY_PROBE) {
                listOf(
                    PhaseStepUiModel(label = "TCP", state = PhaseState.Active, tone = DiagnosticsTone.Warning),
                    PhaseStepUiModel(label = "QUIC", state = PhaseState.Pending, tone = DiagnosticsTone.Neutral),
                )
            } else {
                listOf(
                    PhaseStepUiModel(label = "DNS", state = PhaseState.Active, tone = DiagnosticsTone.Warning),
                    PhaseStepUiModel(label = "Reach", state = PhaseState.Pending, tone = DiagnosticsTone.Neutral),
                )
            },
        currentProbeLabel = currentProbeLabel,
        strategyProbeProgress = strategyProbeProgress,
    )

    private fun setScanScreen(
        scan: DiagnosticsScanUiModel,
        onOpenAdvancedSettings: () -> Unit = {},
        onRequestVpnPermission: () -> Unit = {},
        onOpenDnsSettings: () -> Unit = {},
        onOpenHistory: () -> Unit = {},
    ) {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan = scan,
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenAdvancedSettings = onOpenAdvancedSettings,
                    onOpenDnsSettings = onOpenDnsSettings,
                    onRequestVpnPermission = onRequestVpnPermission,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun workflowRestriction(
        title: String,
        body: String,
    ) = DiagnosticsWorkflowRestrictionUiModel(
        reason = DiagnosticsWorkflowRestrictionReasonUiModel.COMMAND_LINE_MODE_ACTIVE,
        title = title,
        body = body,
        actionLabel = "Open Advanced Settings",
        actionKind = DiagnosticsWorkflowRestrictionActionKindUiModel.OPEN_ADVANCED_SETTINGS,
    )

    @Suppress("UnusedPrivateMember")
    private fun vpnPermissionRestriction(
        title: String,
        body: String,
    ) = DiagnosticsWorkflowRestrictionUiModel(
        reason = DiagnosticsWorkflowRestrictionReasonUiModel.VPN_PERMISSION_DISABLED,
        title = title,
        body = body,
        actionLabel = "Grant VPN permission",
        actionKind = DiagnosticsWorkflowRestrictionActionKindUiModel.OPEN_VPN_PERMISSION,
    )

    // -- Characterization tests: section switching --

    @Test
    fun overviewSectionRendersHealthHeroByDefault() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Dashboard.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState = DiagnosticsUiState(selectedSection = DiagnosticsSection.Dashboard),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsSection(DiagnosticsSection.Dashboard)).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewHero).assertIsDisplayed()
    }

    // -- Characterization tests: share section with busy archive --

    @Test
    fun shareArchiveButtonsDisabledWhenArchiveBusy() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Tools.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Tools,
                            share =
                                DiagnosticsShareUiModel(
                                    isArchiveBusy = true,
                                    archiveStateMessage = "Creating archive...",
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsSharePreviewCard).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsArchiveStateIndicator).fetchSemanticsNode()
    }

    // -- Characterization tests: bottom-sheet visibility --

    @Test
    fun sessionDetailBottomSheetVisibleWhenSelected() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Dashboard.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Dashboard,
                            selectedSessionDetail =
                                DiagnosticsSessionDetailUiModel(
                                    session =
                                        DiagnosticsSessionRowUiModel(
                                            id = "s1",
                                            profileId = "default",
                                            title = "Test Session",
                                            subtitle = "Raw path scan",
                                            pathMode = "RAW_PATH",
                                            serviceMode = "VPN",
                                            status = "completed",
                                            startedAtLabel = "now",
                                            summary = "All probes passed",
                                            metrics = persistentListOf(),
                                            tone = DiagnosticsTone.Neutral,
                                        ),
                                    probeGroups = emptyList(),
                                    snapshots = emptyList(),
                                    events = emptyList(),
                                    contextGroups = emptyList(),
                                    hasSensitiveDetails = false,
                                    sensitiveDetailsVisible = false,
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsSessionDetailSheet).fetchSemanticsNode()
    }

    @Test
    fun probeDetailBottomSheetVisibleWhenSelected() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Scan.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            selectedProbe =
                                DiagnosticsProbeResultUiModel(
                                    id = "probe-1",
                                    probeType = "dns",
                                    target = "blocked.example",
                                    outcome = "substituted",
                                    tone = DiagnosticsTone.Warning,
                                    details =
                                        listOf(
                                            DiagnosticsFieldUiModel("resolver", "cloudflare"),
                                        ),
                                ),
                        ),
                    pagerState = pagerState,
                    onSelectSection = {},
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onKeepResolverRecommendation = {},
                    onSaveResolverRecommendation = {},
                    onSelectSession = {},
                    onDismissSessionDetail = {},
                    onSelectStrategyProbeCandidate = {},
                    onDismissStrategyProbeCandidate = {},
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    onDismissApproachDetail = {},
                    onSelectEvent = {},
                    onDismissEventDetail = {},
                    onSelectProbe = {},
                    onDismissProbeDetail = {},
                    onToggleSensitiveSessionDetails = {},
                    onSessionPathFilter = {},
                    onSessionStatusFilter = {},
                    onSessionSearch = {},
                    onToggleEventFilter = { _, _ -> },
                    onEventSearch = {},
                    onEventAutoScroll = {},
                    onShareSummary = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                    onSaveLogs = {},
                    onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsProbeDetailSheet).fetchSemanticsNode()
    }

    private fun auditCandidateDetail(skipped: Boolean = false) =
        DiagnosticsStrategyProbeCandidateDetailUiModel(
            id = "tlsrec_hostfake",
            label = "TLS record + hostfake",
            familyLabel = "Hostfake",
            suiteLabel = "Automatic audit",
            outcome = if (skipped) "Skipped" else "Worked",
            rationale =
                if (skipped) {
                    "DNS tampering detected before fallback; TCP strategy escalation skipped."
                } else {
                    "Recovered HTTPS across all audit targets."
                },
            tone = if (skipped) DiagnosticsTone.Info else DiagnosticsTone.Positive,
            recommended = true,
            notes = persistentListOf("Adaptive warm-up applied"),
            metrics =
                persistentListOf(
                    DiagnosticsMetricUiModel(label = "Targets", value = "3/3"),
                    DiagnosticsMetricUiModel(label = "Latency", value = "180 ms", tone = DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel(
                        label = "Selected",
                        value = if (skipped) "Fallback" else "Winner",
                        tone = if (skipped) DiagnosticsTone.Info else DiagnosticsTone.Positive,
                    ),
                ),
            signature =
                persistentListOf(
                    DiagnosticsFieldUiModel("Chain", "tlsrec(extlen) -> hostfake(endhost+8)"),
                    DiagnosticsFieldUiModel("QUIC fake profile", "realistic_initial"),
                ),
            resultGroups =
                persistentListOf(
                    DiagnosticsProbeGroupUiModel(
                        title = "HTTPS results",
                        items =
                            listOf(
                                DiagnosticsProbeResultUiModel(
                                    id = "probe-https",
                                    probeType = "https",
                                    target = "audit.example",
                                    outcome = "ok",
                                    tone = DiagnosticsTone.Positive,
                                    details =
                                        listOf(
                                            DiagnosticsFieldUiModel("protocol", "https"),
                                            DiagnosticsFieldUiModel("latencyMs", "180"),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    private fun auditQuicCandidateDetail(skipped: Boolean = false) =
        DiagnosticsStrategyProbeCandidateDetailUiModel(
            id = "quic_realistic_burst",
            label = "QUIC realistic burst",
            familyLabel = "QUIC realistic burst",
            suiteLabel = "Automatic audit",
            outcome = if (skipped) "Skipped" else "Worked",
            rationale =
                if (skipped) {
                    "DNS tampering detected before fallback; QUIC strategy escalation skipped."
                } else {
                    "Recovered QUIC handshakes on the audit targets."
                },
            tone = if (skipped) DiagnosticsTone.Info else DiagnosticsTone.Positive,
            recommended = true,
            notes = persistentListOf("Selected after TCP winner was fixed"),
            metrics =
                persistentListOf(
                    DiagnosticsMetricUiModel(label = "Targets", value = "1/1"),
                    DiagnosticsMetricUiModel(label = "Latency", value = "95 ms", tone = DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel(
                        label = "Selected",
                        value = if (skipped) "Fallback" else "Winner",
                        tone = if (skipped) DiagnosticsTone.Info else DiagnosticsTone.Positive,
                    ),
                ),
            signature =
                persistentListOf(
                    DiagnosticsFieldUiModel("QUIC fake profile", "realistic_initial"),
                ),
            resultGroups =
                persistentListOf(
                    DiagnosticsProbeGroupUiModel(
                        title = "QUIC results",
                        items =
                            listOf(
                                DiagnosticsProbeResultUiModel(
                                    id = "probe-quic",
                                    probeType = "quic",
                                    target = "audit.example",
                                    outcome = "ok",
                                    tone = DiagnosticsTone.Positive,
                                    details =
                                        listOf(
                                            DiagnosticsFieldUiModel("protocol", "quic"),
                                            DiagnosticsFieldUiModel("latencyMs", "95"),
                                        ),
                                ),
                            ),
                    ),
                ),
        )
}
