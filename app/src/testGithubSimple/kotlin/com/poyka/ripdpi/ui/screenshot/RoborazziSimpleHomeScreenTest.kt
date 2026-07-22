package com.poyka.ripdpi.ui.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
                SimpleRunningReport()
            }
        }
    }

    @Test
    fun simpleHomeReportCompleted() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 915) {
            RipDpiTheme(themePreference = "light") {
                SimpleCompletedReport()
            }
        }
    }

    @Test
    fun simpleHomeReportRunningMaximumFont() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 915, fontScale = 2f) {
            RipDpiTheme(themePreference = "light") {
                SimpleRunningReport()
            }
        }
    }

    @Test
    fun simpleHomeReportCompletedMaximumFont() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 915, fontScale = 2f) {
            RipDpiTheme(themePreference = "light") {
                SimpleCompletedReport()
            }
        }
    }

    @Test
    fun simpleHomeReportCompletedDarkLargeFont() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 1100, fontScale = 1.5f) {
            RipDpiTheme(themePreference = "dark") {
                SimpleCompletedReport()
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rSA")
    fun simpleHomeReportCompletedRtl() {
        captureRipDpiScreenshot(widthDp = 412, heightDp = 915) {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SimpleCompletedReport(
                        headline = "اكتمل تحليل الشبكة",
                        summary = "يوجد إعدادان موصى بهما جاهزان للمراجعة.",
                    )
                }
            }
        }
    }

    @Test
    fun simpleHomeExpanded() {
        captureRipDpiScreenshot(widthDp = 1200, heightDp = 900) {
            RipDpiTheme(themePreference = "light") {
                SimpleExpandedHome()
            }
        }
    }

    @Test
    fun simpleHomeExpandedDarkLargeFont() {
        captureRipDpiScreenshot(widthDp = 1200, heightDp = 1000, fontScale = 1.5f) {
            RipDpiTheme(themePreference = "dark") {
                SimpleExpandedHome()
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rSA")
    fun simpleHomeExpandedRtl() {
        captureRipDpiScreenshot(widthDp = 1200, heightDp = 900) {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SimpleExpandedHome(supportingText = "جاهز لتحليل هذه الشبكة")
                }
            }
        }
    }
}

@Composable
private fun SimpleCompletedReport(
    headline: String = "Network analysis complete",
    summary: String = "Two recommended settings are ready to review.",
) {
    SimpleHomeContent(
        connectionState = ConnectionState.Disconnected,
        diagnostics =
            HomeDiagnosticsUiState(
                analysisAction = HomeDiagnosticsActionUiState(enabled = true),
                analysisRunStatus = HomeDiagnosticsRunUiStatus.COMPLETED,
                analysisSheet =
                    HomeDiagnosticsAnalysisSheetUiState(
                        runId = "run-1",
                        headline = headline,
                        summary = summary,
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

@Composable
private fun SimpleRunningReport() {
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

@Composable
private fun SimpleExpandedHome(supportingText: String = "Ready to analyze this network") {
    SimpleHomeContent(
        connectionState = ConnectionState.Disconnected,
        diagnostics =
            HomeDiagnosticsUiState(
                analysisAction =
                    HomeDiagnosticsActionUiState(
                        supportingText = supportingText,
                        enabled = true,
                    ),
            ),
        activeTransport = null,
        snackbarHostState = SnackbarHostState(),
        onToggleConnection = {},
        onRunReport = {},
        onCancelReport = {},
    )
}
