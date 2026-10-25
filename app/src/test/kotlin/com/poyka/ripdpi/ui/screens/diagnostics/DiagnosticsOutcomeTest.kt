package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.DiagnosticsLiveUiModel
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsScreenUiState
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.HomeDiagnosticsLatestAuditUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w500dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsOutcomeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `overview presents the completed audit outcome once`() {
        composeRule.setContent {
            RipDpiTheme {
                OverviewSection(
                    overview = DiagnosticsOverviewUiModel(headline = "Measured audit", body = "Audit result"),
                    scan = DiagnosticsScanUiModel(),
                    live = DiagnosticsLiveUiModel(),
                    isActiveScan = false,
                    onSelectSection = {},
                    onRunScan = {},
                    onReviewRecommendedPath = {},
                    onSelectSession = {},
                    onOpenHistory = {},
                    homeDiagnostics =
                        HomeDiagnosticsUiState(
                            latestAudit =
                                HomeDiagnosticsLatestAuditUiState(
                                    headline = "Measured audit",
                                    summary = "Audit result",
                                ),
                        ),
                )
            }
        }

        composeRule.onAllNodesWithText("Measured audit").assertCountEquals(1)
    }

    @Test
    fun `technical disclosure keeps simple persona label and callback aligned`() {
        assertPrimaryOperation("simple", "Check network")
    }

    @Test
    fun `technical disclosure keeps advanced persona label and callback aligned`() {
        assertPrimaryOperation("advanced", "Check network")
    }

    private fun assertPrimaryOperation(
        persona: String,
        label: String,
    ) {
        var checks = 0
        var scans = 0
        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState = DiagnosticsScreenUiState(uiPersona = persona),
                    pagerState = rememberPagerState { DiagnosticsSection.entries.size },
                    actions =
                        DiagnosticsScreenActions(
                            onCheckNetwork = { checks++ },
                            onRunScan = { scans++ },
                        ),
                )
            }
        }
        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsExpertToggle).performClick()
        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(if (persona == "simple") 1 else 0, checks)
            assertEquals(if (persona == "simple") 0 else 1, scans)
        }
    }
}
