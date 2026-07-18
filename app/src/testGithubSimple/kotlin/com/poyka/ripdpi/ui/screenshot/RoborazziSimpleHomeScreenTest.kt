package com.poyka.ripdpi.ui.screenshot

import androidx.compose.material3.SnackbarHostState
import com.poyka.ripdpi.activities.AnalysisProgressUiState
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.ui.screens.simple.SimpleHomeContent
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class RoborazziSimpleHomeScreenTest {
    @Test
    fun simpleHomeReportRunning() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 915) {
            RipDpiTheme(themePreference = "light") {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics =
                        HomeDiagnosticsUiState(
                            analysisAction =
                                HomeDiagnosticsActionUiState(
                                    supportingText = "Stage 2 of 4 · Testing TLS handshake",
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
                        ),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                )
            }
        }
    }

    @Test
    fun simpleHomeReportCompleted() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 915) {
            RipDpiTheme(themePreference = "light") {
                SimpleHomeContent(
                    connectionState = ConnectionState.Disconnected,
                    diagnostics =
                        HomeDiagnosticsUiState(
                            analysisAction = HomeDiagnosticsActionUiState(enabled = true),
                            analysisRunStatus = HomeDiagnosticsRunUiStatus.COMPLETED,
                            analysisSheet =
                                HomeDiagnosticsAnalysisSheetUiState(
                                    runId = "run-1",
                                    headline = "Network analysis complete",
                                    summary = "Two recommended settings are ready to review.",
                                ),
                        ),
                    activeTransport = null,
                    snackbarHostState = SnackbarHostState(),
                    onToggleConnection = {},
                    onRunReport = {},
                    onCancelReport = {},
                    onShareReport = {},
                )
            }
        }
    }
}
