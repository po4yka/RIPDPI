package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.AnalysisProgressUiState
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class SimpleHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `running report exposes stage progress cancel and disables connect`() {
        var cancelClicks = 0
        val diagnostics =
            HomeDiagnosticsUiState(
                analysisAction =
                    HomeDiagnosticsActionUiState(
                        supportingText = "Stage 2 of 4 · Testing TLS",
                        busy = true,
                    ),
                analysisProgress =
                    AnalysisProgressUiState(
                        stages =
                            persistentListOf(
                                AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                                AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.5f),
                                AnalysisStageUiState(AnalysisStageStatus.PENDING),
                                AnalysisStageUiState(AnalysisStageStatus.PENDING),
                            ),
                        activeStageIndex = 1,
                    ),
                analysisRunStatus = HomeDiagnosticsRunUiStatus.RUNNING,
            )

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics = diagnostics,
                    activeTransportKind = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = { cancelClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Connect").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel active scan").assertIsEnabled().performClick()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Stage 2 of 4 · Testing TLS",
                ),
            ).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.runOnIdle { assertEquals(1, cancelClicks) }
    }

    @Test
    fun `terminal report states remain visible`() {
        val expectedLabels =
            listOf(
                HomeDiagnosticsRunUiStatus.COMPLETED to "Scan complete",
                HomeDiagnosticsRunUiStatus.CANCELLED to "Diagnostic report cancelled",
                HomeDiagnosticsRunUiStatus.FAILED to "Diagnostic report failed. Try again.",
            )

        composeRule.setContent {
            RipDpiTheme {
                Column {
                    expectedLabels.forEach { (status, _) ->
                        SimpleDiagnosticsStatus(
                            diagnostics = HomeDiagnosticsUiState(analysisRunStatus = status),
                        )
                    }
                }
            }
        }
        expectedLabels.forEach { (_, label) -> composeRule.onNodeWithText(label).assertExists() }
    }

    @Test
    fun `overall progress includes completed and active stage work`() {
        val progress =
            AnalysisProgressUiState(
                stages =
                    persistentListOf(
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.5f),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 1,
            )

        assertEquals(0.375f, progress.overallProgress(), 0.001f)
    }
}
