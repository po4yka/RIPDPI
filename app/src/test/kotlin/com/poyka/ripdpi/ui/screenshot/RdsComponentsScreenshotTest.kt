package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiTooltip
import com.poyka.ripdpi.ui.components.feedback.RipDpiTooltipRich
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
import com.poyka.ripdpi.ui.screens.diagnostics.HandshakeTimelineScreen
import com.poyka.ripdpi.ui.screens.diagnostics.LatencyGraphScreen
import com.poyka.ripdpi.ui.screens.diagnostics.OomRecoveryScreen
import com.poyka.ripdpi.ui.screens.diagnostics.OomRecoveryState
import com.poyka.ripdpi.ui.screens.diagnostics.ProfileVariantsScreen
import com.poyka.ripdpi.ui.screens.diagnostics.QualityGraphsScreen
import com.poyka.ripdpi.ui.screens.diagnostics.StateMachineScreen
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyAbScreen
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyImportScreen
import com.poyka.ripdpi.ui.screens.diagnostics.ThroughputGraphScreen
import com.poyka.ripdpi.ui.screens.diagnostics.sampleHandshakeTimelineState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleLatencyGraphState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleOomRecoveryState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleProfileVariantsState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleQualityGraphsSnapshots
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStateMachineState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStrategyAbState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStrategyImportState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleThroughputGraphState
import com.poyka.ripdpi.ui.screens.logs.LogsStreamCard
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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
    fun handshakeTimeline() {
        captureBothThemes("handshakeTimeline", widthDp = 720, heightDp = 520) {
            HandshakeTimelineScreen(
                state =
                    sampleHandshakeTimelineState(
                        title = "Handshake timeline",
                        subtitle = "cloudflare-dns.com · UDP/443 · attempt 1 of 3",
                        totalLabel = "First packet ready",
                        footerSlowest = "Slowest stage MTU probe · > budget by 100 ms",
                        footerBudget = "Next attempt budget 2.0 s",
                    ),
            )
        }
    }

    @Test
    fun throughputGraph() {
        captureBothThemes("throughputGraph", widthDp = 720, heightDp = 480) {
            ThroughputGraphScreen(
                state =
                    sampleThroughputGraphState(
                        title = "Throughput",
                        downNowLabel = "1.42 MiB/s",
                        upNowLabel = "312 KiB/s",
                        sessionTotalLabel = "↓ 128 MiB",
                    ),
            )
        }
    }

    @Test
    fun latencyGraph() {
        captureBothThemes("latencyGraph", widthDp = 720, heightDp = 480) {
            LatencyGraphScreen(
                state =
                    sampleLatencyGraphState(
                        title = "RTT & loss · last 60 s",
                        p50Label = "32 ms",
                        p95Label = "128 ms",
                        nowLabel = "32 ms",
                        p99Label = "184 ms",
                        spikeCountLabel = "3",
                        packetLossLabel = "4.1%",
                    ),
            )
        }
    }

    @Test
    fun stateMachine() {
        captureBothThemes("stateMachine", widthDp = 720, heightDp = 480) {
            StateMachineScreen(
                state =
                    sampleStateMachineState(
                        currentStateLabel = "Tunneling",
                        transitionCountLabel = "9 transitions / 24 h",
                        disconnectedMetaLabel = "idle",
                        permissioningMetaLabel = "os prompt",
                        connectingMetaLabel = "handshake",
                        tunnelingMetaLabel = "12 m 14 s",
                        reconnectingMetaLabel = "backoff",
                        failedMetaLabel = "last 6 h: 0",
                        degradedMetaLabel = "2 in 24 h",
                    ),
            )
        }
    }

    @Test
    fun oomRecovery() {
        captureBothThemes("oomRecovery", widthDp = 420, heightDp = 300) {
            OomRecoveryScreen(
                state =
                    sampleOomRecoveryState(
                        killTimeLabel = "12:42 UTC",
                        downtimeLabel = "4 m 18 s",
                    ),
                onReconnect = {},
                onViewIncident = {},
                onDismiss = {},
                onBack = {},
            )
        }
    }

    @Test
    fun strategyAb() {
        captureBothThemes("strategyAb", widthDp = 420, heightDp = 480) {
            StrategyAbScreen(
                state = sampleStrategyAbState(),
                onSwitch = {},
                onBack = {},
            )
        }
    }

    @Test
    fun strategyImport() {
        captureBothThemes("strategyImport", widthDp = 420, heightDp = 560) {
            StrategyImportScreen(
                state = sampleStrategyImportState(),
                onBack = {},
            )
        }
    }

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
    fun tooltip() {
        captureBothThemes("tooltip", widthDp = 360, heightDp = 120) {
            RipDpiTooltip(text = "Reconnect tunnel") { Text("Reconnect") }
        }
    }

    @Test
    fun tooltipRich() {
        captureBothThemes("tooltipRich", widthDp = 360, heightDp = 120) {
            RipDpiTooltipRich(title = "Stale data", body = "Last probe 18m ago") {
                Text("18m ago")
            }
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

    @Test
    fun profileVariants() {
        captureBothThemes("profileVariants", widthDp = 420, heightDp = 900) {
            ProfileVariantsScreen(
                state = sampleProfileVariantsState(),
                onBack = {},
            )
        }
    }

    @Test
    fun qualityGraphs() {
        captureBothThemes("qualityGraphs", widthDp = 360, heightDp = 480) {
            QualityGraphsScreen(
                samples = sampleQualityGraphsSnapshots(),
            )
        }
    }
}
