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
import com.poyka.ripdpi.ui.components.cards.RipDpiLinkPreviewCard
import com.poyka.ripdpi.ui.components.cards.RipDpiLinkPreviewStrings
import com.poyka.ripdpi.ui.components.cards.RipDpiQrCodeMetadata
import com.poyka.ripdpi.ui.components.cards.RipDpiQrCodeShareCard
import com.poyka.ripdpi.ui.components.cards.sampleLinkPreviewState
import com.poyka.ripdpi.ui.components.chrome.RipDpiSectionHeader
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiDiffKind
import com.poyka.ripdpi.ui.components.feedback.RipDpiDiffLine
import com.poyka.ripdpi.ui.components.feedback.RipDpiDiffViewer
import com.poyka.ripdpi.ui.components.feedback.RipDpiJsonNode
import com.poyka.ripdpi.ui.components.feedback.RipDpiJsonTree
import com.poyka.ripdpi.ui.components.feedback.RipDpiLogEntry
import com.poyka.ripdpi.ui.components.feedback.RipDpiLogLevel
import com.poyka.ripdpi.ui.components.feedback.RipDpiLogStream
import com.poyka.ripdpi.ui.components.feedback.RipDpiTooltip
import com.poyka.ripdpi.ui.components.feedback.RipDpiTooltipRich
import com.poyka.ripdpi.ui.components.indicators.AnalysisProgressIndicator
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiActuatorStatesGallery
import com.poyka.ripdpi.ui.components.indicators.RipDpiBrandBadge
import com.poyka.ripdpi.ui.components.indicators.RipDpiBrandBadgeSize
import com.poyka.ripdpi.ui.components.indicators.RipDpiHeartbeatIndicator
import com.poyka.ripdpi.ui.components.indicators.RipDpiHeartbeatState
import com.poyka.ripdpi.ui.components.indicators.RipDpiKbdShortcut
import com.poyka.ripdpi.ui.components.indicators.RipDpiLiveCounter
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricPill
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.indicators.RipDpiSkeletonBox
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleDataBadge
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleTier
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiCidrInput
import com.poyka.ripdpi.ui.components.inputs.RipDpiCidrValue
import com.poyka.ripdpi.ui.components.inputs.RipDpiCombobox
import com.poyka.ripdpi.ui.components.inputs.RipDpiFilter
import com.poyka.ripdpi.ui.components.inputs.RipDpiFilterBar
import com.poyka.ripdpi.ui.components.inputs.RipDpiSegmentedButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiSlider
import com.poyka.ripdpi.ui.components.inputs.RipDpiStepper
import com.poyka.ripdpi.ui.components.inputs.RipDpiTab
import com.poyka.ripdpi.ui.components.inputs.RipDpiTabs
import com.poyka.ripdpi.ui.components.inputs.RipDpiToggleAlternatives
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
    fun brandBadgeAllSizes() {
        captureBothThemes("brandBadgeAllSizes", widthDp = 360, heightDp = 120) {
            RipDpiBrandBadge(size = RipDpiBrandBadgeSize.AppBarCompact)
        }
    }

    @Test
    fun qrCodeShareCard() {
        captureBothThemes("qrCodeShareCard", widthDp = 700, heightDp = 320) {
            RipDpiQrCodeShareCard(
                qrBitmap = ImageBitmap(160, 160),
                metadata =
                    RipDpiQrCodeMetadata(
                        eyebrow = "QR share · v3",
                        title = "Bundle 0a · 4 endpoints",
                        versionLabel = "QR-3 (29x29)",
                        payloadLabel = "184 chars",
                        schemaLabel = "v1",
                        eccLabel = "M · 15%",
                        caption = "Scan with another RIPDPI install to import this diagnostic — no network traffic.",
                        captionEmphasis = "no network traffic",
                    ),
            )
        }
    }

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
    fun kbdShortcut() {
        captureBothThemes("kbdShortcut", widthDp = 360, heightDp = 120) {
            RipDpiKbdShortcut(keys = persistentListOf("⌘", "K"))
        }
    }

    @Test
    fun sectionHeader() {
        captureBothThemes("sectionHeader", widthDp = 360, heightDp = 120) {
            RipDpiSectionHeader(title = "Connection")
        }
    }

    @Test
    fun staleDataBadge() {
        captureBothThemes("staleDataBadge", widthDp = 360, heightDp = 120) {
            RipDpiStaleDataBadge(label = "14 s ago", tier = RipDpiStaleTier.Recent)
        }
    }

    @Test
    fun liveCounter() {
        captureBothThemes("liveCounter", widthDp = 360, heightDp = 120) {
            RipDpiLiveCounter(value = 1234, suffix = " ms")
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
    fun skeletonBox() {
        captureBothThemes("skeletonBox", widthDp = 360, heightDp = 120) {
            RipDpiSkeletonBox(height = 14.dp)
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
    fun slider() {
        captureBothThemes("slider", widthDp = 360, heightDp = 120) {
            RipDpiSlider(value = 0.5f, onValueChange = {})
        }
    }

    @Test
    fun stepper() {
        captureBothThemes("stepper", widthDp = 360, heightDp = 120) {
            RipDpiStepper(value = 3, onValueChange = {}, valueRange = 0..10)
        }
    }

    @Test
    fun toggleAlternatives() {
        captureBothThemes("toggleAlternatives", widthDp = 360, heightDp = 120) {
            RipDpiToggleAlternatives(selectedIndex = 0, onSelect = {})
        }
    }

    @Test
    fun tooltip() {
        captureBothThemes("tooltip", widthDp = 360, heightDp = 120) {
            RipDpiTooltip(text = "Reconnect tunnel") { Text("Reconnect") }
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
    fun filterBar() {
        captureBothThemes("filterBar", widthDp = 360, heightDp = 120) {
            RipDpiFilterBar(
                filters = persistentListOf(RipDpiFilter("a", "All"), RipDpiFilter("b", "Errors")),
                selectedKeys = persistentSetOf("b"),
                onToggle = {},
            )
        }
    }

    @Test
    fun heartbeatIndicator() {
        captureBothThemes("heartbeatIndicator", widthDp = 360, heightDp = 120) {
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Healthy)
        }
    }

    @Test
    fun actuatorStatesGallery() {
        captureBothThemes("actuatorStatesGallery", widthDp = 360, heightDp = 1000) {
            RipDpiActuatorStatesGallery()
        }
    }

    @Test
    fun cidrInput() {
        captureBothThemes("cidrInput", widthDp = 360, heightDp = 120) {
            RipDpiCidrInput(value = RipDpiCidrValue("10.0.0.0", 8), onValueChange = {})
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

    @Test
    fun combobox() {
        captureBothThemes("combobox", widthDp = 360, heightDp = 120) {
            RipDpiCombobox(
                value = "rel",
                onValueChange = {},
                suggestions = persistentListOf("relay.example.com"),
            )
        }
    }

    @Test
    fun diffViewer() {
        captureBothThemes("diffViewer", widthDp = 360, heightDp = 200) {
            RipDpiDiffViewer(
                lines =
                    persistentListOf(
                        RipDpiDiffLine(RipDpiDiffKind.Added, "x", null, 1),
                        RipDpiDiffLine(RipDpiDiffKind.Removed, "y", 1, null),
                    ),
            )
        }
    }

    @Test
    fun jsonTree() {
        captureBothThemes("jsonTree", widthDp = 360, heightDp = 200) {
            RipDpiJsonTree(
                root =
                    RipDpiJsonNode.Branch(
                        null,
                        persistentListOf(RipDpiJsonNode.Leaf("k", "v", RipDpiJsonNode.Leaf.Kind.String)),
                        isArray = false,
                    ),
            )
        }
    }

    @Test
    fun logStream() {
        captureBothThemes("logStream", widthDp = 360, heightDp = 200) {
            RipDpiLogStream(
                entries = persistentListOf(RipDpiLogEntry(RipDpiLogLevel.Info, "12:00:01", "core", "tunnel up")),
            )
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
    fun linkPreviewCard() {
        captureBothThemes("linkPreviewCard", widthDp = 360, heightDp = 480) {
            RipDpiLinkPreviewCard(
                state =
                    sampleLinkPreviewState(
                        RipDpiLinkPreviewStrings(
                            eyebrowLink = "Generated link",
                            eyebrowPayload = "Fragment payload",
                            copyLabel = "Copy",
                            rowVersion = "Schema version — currently %1\$s",
                            rowAsnFormat = "Origin ASN — %1\$s",
                            rowAsnRedacted = "Origin ASN — redacted",
                            rowTimestampFormat = "Timestamp, in minutes since %1\$s",
                            rowCommitFormat = "Strategy-bundle hash — %1\$s",
                            rowItemsFormat = "Per-endpoint {alive, dpi} tuples — %1\$d",
                            privacyTitle = "Stays on device",
                            privacyMessage =
                                "The fragment never leaves the device — it's decoded locally " +
                                    "by the recipient. Hostnames are hashed unless redaction is off.",
                            privacyEmphasis = "never leaves the device",
                        ),
                    ),
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
