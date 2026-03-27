package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeDiagnosticsManager
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsSessionDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.createDiagnosticsViewModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resolverRecommendationActionsAreRendered() {
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
                                            metrics = emptyList(),
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

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsResolverRecommendationCard).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsResolverKeepSession).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsResolverSaveSetting).fetchSemanticsNode()
    }

    @Test
    fun automaticAuditMatrixRendersSummaryAndCandidateRows() {
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
                                    strategyProbeReport = auditReport(candidateDetail),
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
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyProbeReport).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyProbeSummary).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsStrategyCandidate(candidateDetail.id)).fetchSemanticsNode()
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

    private fun auditReport(candidateDetail: DiagnosticsStrategyProbeCandidateDetailUiModel) =
        DiagnosticsStrategyProbeReportUiModel(
            suiteId = "full_matrix_v1",
            suiteLabel = "Automatic audit",
            summaryMetrics =
                listOf(
                    DiagnosticsMetricUiModel(label = "Worked", value = "2", tone = DiagnosticsTone.Positive),
                    DiagnosticsMetricUiModel(label = "Partial", value = "1", tone = DiagnosticsTone.Warning),
                    DiagnosticsMetricUiModel(label = "Failed", value = "1", tone = DiagnosticsTone.Negative),
                    DiagnosticsMetricUiModel(label = "N/A", value = "1"),
                ),
            recommendation =
                DiagnosticsStrategyProbeRecommendationUiModel(
                    headline = "TLS record + hostfake + QUIC realistic burst",
                    rationale = "Best combined recovery across TCP and QUIC.",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("TCP recommendation", "TLS record + hostfake"),
                            DiagnosticsFieldUiModel("QUIC recommendation", "QUIC realistic burst"),
                        ),
                    signature =
                        listOf(
                            DiagnosticsFieldUiModel("Chain", "tlsrec(extlen) -> hostfake(endhost+8)"),
                        ),
                ),
            families =
                listOf(
                    DiagnosticsStrategyProbeFamilyUiModel(
                        title = "TCP / HTTP / HTTPS matrix",
                        candidates =
                            listOf(
                                DiagnosticsStrategyProbeCandidateUiModel(
                                    id = candidateDetail.id,
                                    label = candidateDetail.label,
                                    outcome = candidateDetail.outcome,
                                    rationale = candidateDetail.rationale,
                                    metrics = candidateDetail.metrics,
                                    tone = candidateDetail.tone,
                                    skipped = false,
                                    recommended = true,
                                ),
                            ),
                    ),
                    DiagnosticsStrategyProbeFamilyUiModel(
                        title = "QUIC matrix",
                        candidates = emptyList(),
                    ),
                ),
            candidateDetails = mapOf(candidateDetail.id to candidateDetail),
        )

    // -- Characterization tests: section switching --

    @Test
    fun overviewSectionRendersHealthHeroByDefault() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Overview.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState = DiagnosticsUiState(selectedSection = DiagnosticsSection.Overview),
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

        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsSection(DiagnosticsSection.Overview)).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewHero).assertIsDisplayed()
    }

    // -- Characterization tests: share section with busy archive --

    @Test
    fun shareArchiveButtonsDisabledWhenArchiveBusy() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Share.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Share,
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
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsShareArchive).fetchSemanticsNode()
    }

    // -- Characterization tests: bottom-sheet visibility --

    @Test
    fun sessionDetailBottomSheetVisibleWhenSelected() {
        composeRule.setContent {
            val pagerState =
                rememberPagerState(
                    initialPage = DiagnosticsSection.Overview.ordinal,
                    pageCount = { DiagnosticsSection.entries.size },
                )
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsUiState(
                            selectedSection = DiagnosticsSection.Overview,
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
                                            metrics = emptyList(),
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

    private fun auditCandidateDetail() =
        DiagnosticsStrategyProbeCandidateDetailUiModel(
            id = "tlsrec_hostfake",
            label = "TLS record + hostfake",
            familyLabel = "Hostfake",
            suiteLabel = "Automatic audit",
            outcome = "Worked",
            rationale = "Recovered HTTPS across all audit targets.",
            tone = DiagnosticsTone.Positive,
            recommended = true,
            notes = listOf("Adaptive warm-up applied"),
            metrics =
                listOf(
                    DiagnosticsMetricUiModel(label = "Targets", value = "3/3"),
                    DiagnosticsMetricUiModel(label = "Latency", value = "180 ms", tone = DiagnosticsTone.Info),
                ),
            signature =
                listOf(
                    DiagnosticsFieldUiModel("Chain", "tlsrec(extlen) -> hostfake(endhost+8)"),
                    DiagnosticsFieldUiModel("QUIC fake profile", "realistic_initial"),
                ),
            resultGroups =
                listOf(
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
}
