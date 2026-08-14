package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.AnalysisProgressUiState
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
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
    fun `connected session exposes enabled disconnect when lockdown is off`() {
        var toggleClicks = 0

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Connected,
                    blocksDisconnect = false,
                    diagnostics = HomeDiagnosticsUiState(),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = { toggleClicks += 1 },
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Disconnect").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, toggleClicks) }
    }

    @Test
    fun `connected session disables disconnect while lockdown owns vpn lifecycle`() {
        var toggleClicks = 0

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Connected,
                    blocksDisconnect = true,
                    diagnostics = HomeDiagnosticsUiState(),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = { toggleClicks += 1 },
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Disconnect").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, toggleClicks) }
    }

    @Test
    fun `connecting session exposes cancellation before lockdown snapshot arrives`() {
        var toggleClicks = 0

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Connecting,
                    blocksDisconnect = false,
                    diagnostics = HomeDiagnosticsUiState(),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = { toggleClicks += 1 },
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Disconnect").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, toggleClicks) }
    }

    @Test
    fun `disconnected session keeps connect enabled`() {
        var toggleClicks = 0

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    blocksDisconnect = true,
                    diagnostics = HomeDiagnosticsUiState(),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = { toggleClicks += 1 },
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, toggleClicks) }
    }

    @Test
    fun `running report exposes stage progress cancel and keeps connect available`() {
        var cancelClicks = 0
        var toggleClicks = 0
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
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = { toggleClicks += 1 },
                    onRunReport = {},
                    onCancelReport = { cancelClicks += 1 },
                )
            }
        }

        // Connecting mid-scan cancels the scan rather than reporting a result measured
        // across two different network paths.
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, cancelClicks)
            assertEquals(1, toggleClicks)
        }
        composeRule.onNodeWithText("Cancel active scan").assertIsEnabled().performClick()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Stage 2 of 4",
                ),
            ).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        composeRule.runOnIdle { assertEquals(2, cancelClicks) }
    }

    @Test
    fun `starting report exposes cancellation while connection remains available`() {
        var cancelClicks = 0
        var toggleClicks = 0
        val diagnostics =
            HomeDiagnosticsUiState(
                analysisAction = HomeDiagnosticsActionUiState(supportingText = "Starting diagnostics", busy = true),
                analysisRunStatus = HomeDiagnosticsRunUiStatus.STARTING,
            )

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics = diagnostics,
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = { toggleClicks += 1 },
                    onRunReport = {},
                    onCancelReport = { cancelClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Cancel active scan").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Starting diagnostics").assertExists()
        composeRule.runOnIdle {
            assertEquals(2, cancelClicks)
            assertEquals(1, toggleClicks)
        }
    }

    @Test
    fun `idle report action follows authoritative availability and explains why it is disabled`() {
        val diagnostics =
            HomeDiagnosticsUiState(
                analysisAction =
                    HomeDiagnosticsActionUiState(
                        supportingText = "Disable command line settings to run diagnostics",
                        enabled = false,
                    ),
            )

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics = diagnostics,
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Run diagnostic report").assertIsNotEnabled()
        composeRule.onNodeWithText("Disable command line settings to run diagnostics").assertIsDisplayed()
    }

    @Test
    fun `idle report action is enabled when authoritative state allows it`() {
        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics =
                        HomeDiagnosticsUiState(
                            analysisAction = HomeDiagnosticsActionUiState(enabled = true),
                        ),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Run diagnostic report").assertIsEnabled()
    }

    @Test
    fun `completed report shows result and a working share action`() {
        var shareClicks = 0
        val diagnostics =
            HomeDiagnosticsUiState(
                analysisAction = HomeDiagnosticsActionUiState(enabled = true),
                analysisRunStatus = HomeDiagnosticsRunUiStatus.COMPLETED,
                analysisSheet =
                    HomeDiagnosticsAnalysisSheetUiState(
                        runId = "run-1",
                        headline = "Network analysis complete",
                        summary = "Two recommended settings are ready to review.",
                    ),
            )

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics = diagnostics,
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                    onShareReport = { shareClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Network analysis complete").assertIsDisplayed()
        composeRule.onNodeWithText("Two recommended settings are ready to review.").assertIsDisplayed()
        composeRule.onNodeWithText("Share Logs & Results").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, shareClicks) }
    }

    @Test
    @Config(qualifiers = "en-w1200dp-h900dp")
    fun `expanded window keeps primary controls at centered form width`() {
        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics =
                        HomeDiagnosticsUiState(
                            analysisAction = HomeDiagnosticsActionUiState(enabled = true),
                        ),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Connect")
            .assertWidthIsEqualTo(680.dp)
            .assertLeftPositionInRootIsEqualTo(260.dp)
        composeRule.onNodeWithText("Run diagnostic report").assertWidthIsEqualTo(680.dp)
    }

    @Test
    fun `compact height can scroll to the report action`() {
        val diagnostics =
            HomeDiagnosticsUiState(
                analysisAction = HomeDiagnosticsActionUiState(supportingText = "Stage 1 of 8", busy = true),
                analysisRunStatus = HomeDiagnosticsRunUiStatus.RUNNING,
            )

        composeRule.setContent {
            RipDpiTheme {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics = diagnostics,
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                    modifier = Modifier.height(320.dp),
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Cancel active scan"))
        composeRule.onNodeWithText("Cancel active scan").assertIsDisplayed()
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
