package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

        composeRule.onNodeWithText("Temporary DNS override active").fetchSemanticsNode()
        composeRule.onNodeWithText("Keep for this session").fetchSemanticsNode()
        composeRule.onNodeWithText("Save as DNS setting").fetchSemanticsNode()
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
        composeRule.onNodeWithText("Audit matrix").fetchSemanticsNode()
        composeRule.onNodeWithText("Audit summary").fetchSemanticsNode()
        composeRule
            .onAllNodesWithText("Tap a candidate for per-target evidence and configuration details.")
            .assertCountEquals(2)
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

        composeRule.onNodeWithText("Notes").fetchSemanticsNode()
        composeRule.onNodeWithText("Candidate configuration").fetchSemanticsNode()
        composeRule.onNodeWithText("Per-target outcomes").fetchSemanticsNode()
        composeRule.onNodeWithText("Adaptive warm-up applied").fetchSemanticsNode()
        composeRule.onNodeWithText("HTTPS results").fetchSemanticsNode()
        composeRule.onNodeWithText("audit.example").fetchSemanticsNode()
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
