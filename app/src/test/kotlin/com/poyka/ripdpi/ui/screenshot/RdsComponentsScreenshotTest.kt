package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiQrCodeMetadata
import com.poyka.ripdpi.ui.components.cards.RipDpiQrCodeShareCard
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
import com.poyka.ripdpi.ui.screens.diagnostics.StateMachineScreen
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyAbScreen
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyImportScreen
import com.poyka.ripdpi.ui.screens.diagnostics.ThroughputGraphScreen
import com.poyka.ripdpi.ui.screens.diagnostics.sampleHandshakeTimelineState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleLatencyGraphState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleOomRecoveryState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleProfileVariantsState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStateMachineState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStrategyAbState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStrategyImportState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleThroughputGraphState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val CROSS_PLATFORM_OPTIONS =
    RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01F),
    )

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RdsComponentsScreenshotTest {
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureBothThemes(
        name: String,
        widthDp: Int = 360,
        heightDp: Int = 200,
        content: @Composable () -> Unit,
    ) {
        val moduleRoot = System.getProperty("user.dir")
        listOf("light", "dark").forEach { theme ->
            captureRoboImage(
                filePath =
                    "$moduleRoot/src/test/screenshots/" +
                        "com.poyka.ripdpi.ui.screenshot.RdsComponentsScreenshotTest." +
                        "${name}_$theme.png",
                roborazziOptions = CROSS_PLATFORM_OPTIONS,
                roborazziComposeOptions =
                    RoborazziComposeOptions {
                        size(widthDp = widthDp, heightDp = heightDp)
                        fontScale(1f)
                        inspectionMode(true)
                    },
            ) {
                RipDpiTheme(themePreference = theme) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(heightDp.dp)
                                .background(RipDpiThemeTokens.colors.background)
                                .padding(16.dp),
                    ) { content() }
                }
            }
        }
    }

    // === 25 tests follow ===

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
            RipDpiKbdShortcut(keys = listOf("⌘", "K"))
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
                tabs = listOf(RipDpiTab("a", "Home"), RipDpiTab("b", "Logs")),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }

    @Test
    fun segmentedButton() {
        captureBothThemes("segmentedButton", widthDp = 360, heightDp = 120) {
            RipDpiSegmentedButton(
                options = listOf("Auto", "Manual"),
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
                filters = listOf(RipDpiFilter("a", "All"), RipDpiFilter("b", "Errors")),
                selectedKeys = setOf("b"),
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
                suggestions = listOf("relay.example.com"),
            )
        }
    }

    @Test
    fun diffViewer() {
        captureBothThemes("diffViewer", widthDp = 360, heightDp = 200) {
            RipDpiDiffViewer(
                lines =
                    listOf(
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
                        listOf(RipDpiJsonNode.Leaf("k", "v", RipDpiJsonNode.Leaf.Kind.String)),
                        isArray = false,
                    ),
            )
        }
    }

    @Test
    fun logStream() {
        captureBothThemes("logStream", widthDp = 360, heightDp = 200) {
            RipDpiLogStream(
                entries = listOf(RipDpiLogEntry(RipDpiLogLevel.Info, "12:00:01", "core", "tunnel up")),
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
}
