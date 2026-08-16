package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.AnalysisProgressUiState
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportState
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeLiveProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeProgressLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogsUiState
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.data.xray.XrayOutboundHealth
import com.poyka.ripdpi.data.xray.XrayProviderDiagnosticsFixtures
import com.poyka.ripdpi.data.xray.XrayProviderReadiness
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceDevice
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceStatus
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceStep
import com.poyka.ripdpi.ui.components.chrome.RipDpiRemediationCard
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.screens.diagnostics.LocalScanClockMs
import com.poyka.ripdpi.ui.screens.diagnostics.PluggableTransportProbeCard
import com.poyka.ripdpi.ui.screens.diagnostics.RemoteDeviceAcceptanceCard
import com.poyka.ripdpi.ui.screens.diagnostics.ScanProgressCard
import com.poyka.ripdpi.ui.screens.home.HomeModeCard
import com.poyka.ripdpi.ui.screens.logs.LogsScreen
import com.poyka.ripdpi.ui.screens.xray.XrayProviderStatusCard
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi coverage for the busy render branches -- the surfaces that only exist while an
 * operation is in flight. Every one of these was previously unreachable from any golden, so a
 * layout or wiring regression in a busy branch was invisible to the screenshot suite even though
 * the corresponding idle and completed states were locked.
 *
 * These renders deliberately do NOT lock the pulsing marker. Every capture path sets
 * `inspectionMode(true)`, and `StatusIndicator` suppresses its pulse under `LocalInspectionMode`
 * precisely so a screenshot never chases an infinite animation. What these fixtures lock is which
 * branch renders and what it contains: the staged pipeline instead of the summary panel on Home,
 * the live probe lane on the scan card, the running affordances on the tool and gate cards.
 *
 * These are NEW screenshots, not a re-bless of any existing golden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BusyStateScreenshotTest {
    /**
     * Home rendering a diagnostic run in flight. The card swaps its summary panel for the staged
     * pipeline only when `analysisProgress` is non-null, and that branch shipped with no coverage.
     */
    @Test
    fun homeAnalysisRunning() {
        capture("homeAnalysisRunning", heightDp = 420) {
            HomeModeCard(
                uiState =
                    HomeModeCardUiState(
                        mode = HomeMode.Diagnostic,
                        title = "Diagnostics",
                        primaryLabel = "Stage 4 of 8 - testing TLS handshakes",
                        secondaryLabel = "Analysing this network",
                        isLoading = true,
                        primaryActionLabel = "Cancel",
                        configureLabel = "Configure",
                        analysisProgress =
                            AnalysisProgressUiState(
                                // Eight stages, matching HomeCompositeStageSpecs: a full run emits
                                // exactly this many, and the fixture is the only place the screenshot
                                // suite ever sees the real length.
                                stages =
                                    persistentListOf(
                                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.4f),
                                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                                    ),
                                activeStageIndex = 3,
                            ),
                    ),
                onPrimaryAction = {},
                onConfigure = {},
            )
        }
    }

    /**
     * The scan card with a strategy-probe lane in flight. The clock is pinned through
     * [LocalScanClockMs] so the elapsed label is a fixed value rather than wall-clock drift; the ETA
     * stays absent because the estimator needs a second forward step to have a rate at all.
     */
    @Test
    fun scanProgressRunning() {
        capture("scanProgressRunning", heightDp = 520) {
            CompositionLocalProvider(LocalScanClockMs provides ScanStartedAtMs + 42_000L) {
                ScanProgressCard(
                    progress =
                        DiagnosticsProgressUiModel(
                            phase = "tls",
                            summary = "Probing TLS handshakes",
                            completedSteps = 7,
                            totalSteps = 18,
                            fraction = 7f / 18f,
                            scanKind = ScanKind.STRATEGY_PROBE,
                            isFullAudit = false,
                            scanStartedAtMs = ScanStartedAtMs,
                            phaseSteps =
                                persistentListOf(
                                    PhaseStepUiModel("DNS", PhaseState.Completed, DiagnosticsTone.Positive),
                                    PhaseStepUiModel("TCP", PhaseState.Completed, DiagnosticsTone.Positive),
                                    PhaseStepUiModel("TLS", PhaseState.Active, DiagnosticsTone.Warning),
                                    PhaseStepUiModel("QUIC", PhaseState.Pending, DiagnosticsTone.Neutral),
                                ),
                            currentProbeLabel = "example.org",
                            strategyProbeProgress =
                                DiagnosticsStrategyProbeLiveProgressUiModel(
                                    lane = DiagnosticsStrategyProbeProgressLaneUiModel.TCP,
                                    candidateIndex = 4,
                                    candidateTotal = 11,
                                    candidateId = "tcp-split-host",
                                    candidateLabel = "split(host+1)",
                                    succeededTargets = 2,
                                    totalTargets = 5,
                                ),
                        ),
                    strategyProbeSelected = true,
                )
            }
        }
    }

    /**
     * A diagnostics tool header mid-run. Twelve tool cards share this header idiom, and its state
     * enum folds Running into a tone the palette renders as Idle, so the running branch is exactly
     * the one that used to look identical to a tool that had never been started.
     */
    @Test
    fun toolProbeRunning() {
        capture("toolProbeRunning", heightDp = 320) {
            PluggableTransportProbeCard(
                tool =
                    DiagnosticsPluggableTransportToolUiModel(
                        state = DiagnosticsPluggableTransportState.Running,
                        rows =
                            persistentListOf(
                                DiagnosticsPluggableTransportRowUiModel(
                                    label = "obfs4",
                                    verdict = "reachable",
                                    detail = "handshake 214 ms",
                                    tone = DiagnosticsTone.Positive,
                                ),
                                DiagnosticsPluggableTransportRowUiModel(
                                    label = "snowflake",
                                    verdict = "probing",
                                    detail = "broker rendezvous",
                                    tone = DiagnosticsTone.Info,
                                ),
                            ),
                    ),
                onRun = {},
            )
        }
    }

    /** The remote-device acceptance gate mid-run, with its steps in a mixed terminal/pending state. */
    @Test
    fun deviceAcceptanceRunning() {
        capture("deviceAcceptanceRunning", heightDp = 560) {
            RemoteDeviceAcceptanceCard(
                report =
                    RemoteDeviceAcceptanceReport(
                        status = RemoteDeviceAcceptanceStatus.Running,
                        device =
                            RemoteDeviceAcceptanceDevice(
                                model = "Pixel 8",
                                csc = "XAA",
                                api = 35,
                                abi = "arm64-v8a",
                            ),
                        transportKind = "relay-udp",
                        steps =
                            listOf(
                                RemoteDeviceAcceptanceStep(
                                    id = "tunnel-up",
                                    status = RemoteDeviceAcceptanceStatus.Pass,
                                    durationMs = 812L,
                                ),
                                RemoteDeviceAcceptanceStep(
                                    id = "payload-health",
                                    status = RemoteDeviceAcceptanceStatus.Running,
                                ),
                                RemoteDeviceAcceptanceStep(
                                    id = "uid-policy",
                                    status = RemoteDeviceAcceptanceStatus.Idle,
                                ),
                            ),
                    ),
                onRun = {},
                onShare = {},
            )
        }
    }

    /**
     * The Xray provider card while the engine is coming up. `XrayProviderDiagnosticsFixtures` only
     * carries terminal states, so the in-flight readiness is derived from the healthy fixture --
     * `ListenerReady` is what `XrayConnectionStage` maps to `ProbingOutbound`.
     */
    @Test
    fun xrayProbingOutbound() {
        val healthy = XrayProviderDiagnosticsFixtures.healthy
        val probing =
            healthy.copy(
                snapshot =
                    healthy.snapshot.copy(
                        readiness = XrayProviderReadiness.ListenerReady,
                        // Kept consistent with the stage: a snapshot claiming a reachable outbound
                        // while the card says it is still probing would be a fixture that contradicts
                        // itself, and the golden would lock that contradiction in place.
                        outboundHealth = XrayOutboundHealth.Probing,
                    ),
            )
        capture("xrayProbingOutbound", heightDp = 620) {
            XrayProviderStatusCard(report = probing, onRunProbe = {}, probeRunning = true)
        }
    }

    /** Startup recovery while it is still preparing, before it either succeeds or reports failure. */
    @Test
    fun startupRecoveryPreparing() {
        capture("startupRecoveryPreparing", heightDp = 260) {
            RipDpiRemediationCard(
                title = "Preparing RIPDPI",
                summary = "Restoring settings after an interrupted upgrade.",
                steps = persistentListOf(),
                tone = StatusIndicatorTone.Active,
                pulsing = true,
                actionLabel = "Retry",
                onAction = {},
            )
        }
    }

    /** The logs screen with a live buffer, which is the only state where the stream reads as active. */
    @Test
    fun logsStreamLive() {
        capture("logsStreamLive", heightDp = 900) {
            LogsScreen(
                uiState =
                    LogsUiState(
                        logs =
                            persistentListOf(
                                LogEntry(
                                    id = "1",
                                    createdAtMs = ScanStartedAtMs,
                                    timestamp = "12:00:01",
                                    subsystem = LogSubsystem.Service,
                                    severity = LogSeverity.Info,
                                    message = "Tunnel established",
                                    source = "VpnService",
                                    isActiveSession = true,
                                ),
                                LogEntry(
                                    id = "2",
                                    createdAtMs = ScanStartedAtMs + 1_000L,
                                    timestamp = "12:00:02",
                                    subsystem = LogSubsystem.Diagnostics,
                                    severity = LogSeverity.Warn,
                                    message = "Probe retry 1 of 3",
                                    source = "ScanWorker",
                                    isActiveSession = true,
                                ),
                            ),
                    ),
                onRefresh = {},
                onToggleSubsystemFilter = {},
                onToggleSeverityFilter = {},
                onAutoScrollChanged = {},
                onActiveSessionOnlyChanged = {},
                onClearLogs = {},
                onSaveLogs = {},
                onShareSupportBundle = {},
            )
        }
    }

    private fun capture(
        name: String,
        heightDp: Int,
        content: @Composable () -> Unit,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = heightDp, testClassFqn = FQN) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(RipDpiThemeTokens.layout.horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            ) {
                content()
            }
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.BusyStateScreenshotTest"

        /** Fixed epoch so anything the busy surfaces derive from a timestamp stays byte-stable. */
        const val ScanStartedAtMs = 1_700_000_000_000L
    }
}
