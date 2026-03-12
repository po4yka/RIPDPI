package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
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
}
