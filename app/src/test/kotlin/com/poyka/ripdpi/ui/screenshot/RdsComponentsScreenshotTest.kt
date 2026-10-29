package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.indicators.AnalysisProgressIndicator
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricPill
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleDataBadge
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleTier
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSegmentedButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiTab
import com.poyka.ripdpi.ui.components.inputs.RipDpiTabs
import com.poyka.ripdpi.ui.screens.logs.LogsStreamCard
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RdsComponentsScreenshotTest {
    // capture helpers live in RoborazziCaptureHelpers.kt (same package)

    @Test
    fun staleDataBadge() {
        captureBothThemes("staleDataBadge", widthDp = 360, heightDp = 120) {
            RipDpiStaleDataBadge(label = "14 s ago", tier = RipDpiStaleTier.Recent)
        }
    }

    @Test
    fun spinner() {
        captureBothThemes("spinner", widthDp = 360, heightDp = 120) {
            RipDpiSpinner(size = RipDpiSpinnerSize.Standard)
        }
    }

    @Test
    fun progressBar() {
        captureBothThemes("progressBar", widthDp = 360, heightDp = 120) {
            RipDpiProgressBar(progress = 0.6f)
        }
    }

    @Test
    fun tabs() {
        captureBothThemes("tabs", widthDp = 360, heightDp = 120) {
            RipDpiTabs(
                tabs = persistentListOf(RipDpiTab("a", "Home"), RipDpiTab("b", "Logs")),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }

    @Test
    fun segmentedButton() {
        captureBothThemes("segmentedButton", widthDp = 360, heightDp = 120) {
            RipDpiSegmentedButton(
                options = persistentListOf("Auto", "Manual"),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }

    @Test
    fun accordion() {
        captureBothThemes("accordion", widthDp = 360, heightDp = 200) {
            RipDpiAccordion(
                title = "Advanced",
                expanded = true,
                onExpandedChange = {},
            ) { Text("Inside content") }
        }
    }

    @Test
    fun commandPalettePlaceholder() {
        captureBothThemes("commandPalettePlaceholder", widthDp = 360, heightDp = 120) {
            Text(
                "Command palette is a modal; capture inside Dialog requires runtime context.",
            )
        }
    }

    // === Spec-alignment gallery for the 6 audit-HAVE primitives ===

    @Test
    fun analysisProgress() {
        captureBothThemes("analysisProgress", widthDp = 360, heightDp = 200) {
            AnalysisProgressIndicator(
                stages =
                    persistentListOf(
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED),
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.4f),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 2,
                stageLabel = "Stage 3 of 5 — testing TLS handshakes",
            )
        }
    }

    @Test
    fun logRow() {
        captureBothThemes("logRow", widthDp = 360, heightDp = 200) {
            LogRow(
                timestamp = "12:18:42.013",
                type = "CONN",
                message = "strategy.applied tlsrec_split_host",
                tone = LogRowTone.Connection,
            )
        }
    }

    @Test
    fun logsStreamCopyActions() {
        captureBothThemes("logsStreamCopyActions", widthDp = 420, heightDp = 360) {
            LogsStreamCard(
                entries =
                    listOf(
                        LogEntry(
                            id = "service-started",
                            createdAtMs = 1_711_452_264_000,
                            timestamp = "12:31:04",
                            subsystem = LogSubsystem.Service,
                            severity = LogSeverity.Info,
                            message = "VPN service started",
                            source = "service",
                        ),
                        LogEntry(
                            id = "diagnostics-failure",
                            createdAtMs = 1_711_452_282_000,
                            timestamp = "12:31:22",
                            subsystem = LogSubsystem.Diagnostics,
                            severity = LogSeverity.Error,
                            message = "Proxy service failed to start",
                            source = "diagnostics",
                        ),
                    ),
                listState = rememberLazyListState(),
                onCopyEntry = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun logsStreamLongMetadata() {
        captureBothThemes("logsStreamLongMetadata", widthDp = 371, heightDp = 360) {
            LogsStreamCard(
                entries =
                    listOf(
                        LogEntry(
                            id = "diagnostics-active",
                            createdAtMs = 1_711_452_282_000,
                            timestamp = "12:31:22",
                            subsystem = LogSubsystem.Diagnostics,
                            severity = LogSeverity.Info,
                            message = "Diagnostic scan started",
                            source = "diagnostics",
                            runtimeId = "5d8e4a11-5067-44b2-9a17-e84826709d28",
                            diagnosticsSessionId = "f87bba7f-d81f-4376-af1e-7282c4c55f62",
                            isActiveSession = true,
                        ),
                    ),
                listState = rememberLazyListState(),
                onCopyEntry = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun logsStreamCopyActionsMaximumFont() {
        captureBothThemes(
            name = "logsStreamCopyActionsMaximumFont",
            widthDp = 411,
            heightDp = 640,
            fontScale = 2f,
        ) {
            LogsStreamCard(
                entries =
                    listOf(
                        LogEntry(
                            id = "diagnostics-maximum-font",
                            createdAtMs = 1_711_452_282_000,
                            timestamp = "12:31:22",
                            subsystem = LogSubsystem.Diagnostics,
                            severity = LogSeverity.Warn,
                            message = "Connectivity probe timed out while waiting for a response",
                            source = "diagnostics",
                            runtimeId = "5d8e4a11-5067-44b2-9a17-e84826709d28",
                            diagnosticsSessionId = "f87bba7f-d81f-4376-af1e-7282c4c55f62",
                            isActiveSession = true,
                        ),
                    ),
                listState = rememberLazyListState(),
                onCopyEntry = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun metricPill() {
        captureBothThemes("metricPill", widthDp = 360, heightDp = 120) {
            RipDpiMetricPill(text = "RTT 12 ms", tone = RipDpiMetricTone.Positive)
        }
    }

    @Test
    fun presetCard() {
        captureBothThemes("presetCard", widthDp = 360, heightDp = 200) {
            PresetCard(
                title = "tlsrec_split_host",
                description = "Splits TLS ClientHello after the SNI extension.",
                badgeText = "ACTIVE",
                selected = true,
                onClick = {},
            )
        }
    }

    @Test
    fun stageProgress() {
        captureBothThemes("stageProgress", widthDp = 360, heightDp = 120) {
            StageProgressIndicator(completedCount = 3, failedCount = 1, totalCount = 6)
        }
    }

    @Test
    fun pageIndicators() {
        captureBothThemes("pageIndicators", widthDp = 360, heightDp = 120) {
            RipDpiPageIndicators(currentPage = 1, pageCount = 3)
        }
    }
}
