package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AnalysisProgressUiState
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.failover.ActiveTransportDescriptor
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.scaffold.RipDpiAdaptiveColumns
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiContentGrouping
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * The entire UI of the "simple" flavor: two actions over the compiled-in config.
 *
 * - Connect / Disconnect toggles the VPN using the embedded relay profile (seeded
 *   at first launch — see M2). Wires to the same [MainViewModel] entry points the
 *   full UI uses, so the connection/permission flow is identical.
 * - Run diagnostic report kicks off the full home analysis. The completed report is
 *   handed to the OS share sheet — that auto-share-on-complete hand-off, plus the
 *   active-protocol field, lands in M5; M1 wires the trigger and progress.
 */
@Composable
fun SimpleHomeScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.homeDiagnosticsUiState.collectAsStateWithLifecycle()
    val activeTransport by viewModel.activeTransportDescriptor.collectAsStateWithLifecycle()

    val connectionActive =
        uiState.connectionState == ConnectionState.Connecting ||
            uiState.connectionState == ConnectionState.Connected
    // The shared resolver labels the actuator in the full experience's "secure line"
    // wording. This flavor speaks plainly for field testers, so keep the design-system
    // control but keep this flavor's own label and its nine locales. The lockdown owner
    // still wins: when it blocks disconnect it supplies its own label.
    val simpleActionLabel =
        stringResource(if (connectionActive) R.string.simple_disconnect else R.string.simple_connect)

    SimpleHomeContent(
        connectionState = uiState.connectionState,
        connectionActuator =
            if (uiState.hardKillSwitch.blocksDisconnect) {
                uiState.connectionActuator
            } else {
                uiState.connectionActuator.copy(actionLabel = simpleActionLabel)
            },
        blocksDisconnect = uiState.hardKillSwitch.blocksDisconnect,
        disconnectBlockedReason = uiState.hardKillSwitch.summary,
        diagnostics = diagnostics,
        activeTransport = activeTransport,
        snackbarHostState = snackbarHostState,
        onToggleConnection = { active ->
            if (active) viewModel.onStopRequested() else viewModel.onToggleVpn(enabled = true)
        },
        onRunReport = viewModel::onRunHomeFullAnalysis,
        onCancelReport = viewModel::onCancelHomeAnalysis,
        onShareReport = viewModel.onShareHomeAnalysis,
        onSaveReport = viewModel.onSaveHomeAnalysis,
        modifier = modifier,
    )
}

@Composable
internal fun SimpleHomeContent(
    connectionState: ConnectionState,
    connectionActuator: HomeConnectionActuatorUiState = HomeConnectionActuatorUiState(),
    blocksDisconnect: Boolean = false,
    disconnectBlockedReason: String = "",
    diagnostics: HomeDiagnosticsUiState,
    activeTransport: ActiveTransportDescriptor?,
    snackbarHostState: SnackbarHostState,
    onToggleConnection: (active: Boolean) -> Unit,
    onRunReport: () -> Unit,
    onCancelReport: () -> Unit,
    onShareReport: () -> Unit = {},
    onSaveReport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    val connecting = connectionState == ConnectionState.Connecting
    val connected = connectionState == ConnectionState.Connected
    val active = connecting || connected
    val reportBusy = diagnostics.analysisAction.busy
    val disconnectBlocked = active && blocksDisconnect
    val reportCancellable =
        diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.STARTING ||
            diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING
    val protocolLabel = activeTransport?.toProtocolLabel()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        snackbarHost = { RipDpiSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            val split = layout.contentGrouping == RipDpiContentGrouping.SplitColumns
            Column(
                modifier =
                    Modifier
                        // A split layout earns the wider content bound; a single column stays
                        // at form width so line length does not run away.
                        .widthIn(max = if (split) layout.contentMaxWidth else layout.formMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Identity and connection on one side, the report and its output on the
                // other. On compact and medium widths this collapses to the same single
                // column as before; only an expanded window pays for the second one.
                RipDpiAdaptiveColumns(
                    primary = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SimpleHomeIdentity(
                                connectionState = connectionState,
                                connectionActuator = connectionActuator,
                                protocolLabel = protocolLabel,
                                disconnectBlocked = disconnectBlocked,
                                disconnectBlockedReason = disconnectBlockedReason,
                                reportCancellable = reportCancellable,
                                onToggleConnection = onToggleConnection,
                                onCancelReport = onCancelReport,
                            )
                        }
                    },
                    secondary = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SimpleHomeReport(
                                diagnostics = diagnostics,
                                reportBusy = reportBusy,
                                reportCancellable = reportCancellable,
                                onRunReport = onRunReport,
                                onCancelReport = onCancelReport,
                                onShareReport = onShareReport,
                                onSaveReport = onSaveReport,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SimpleHomeIdentity(
    connectionState: ConnectionState,
    connectionActuator: HomeConnectionActuatorUiState,
    protocolLabel: String?,
    disconnectBlocked: Boolean,
    disconnectBlockedReason: String,
    reportCancellable: Boolean,
    onToggleConnection: (active: Boolean) -> Unit,
    onCancelReport: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val active =
        connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Connected

    Text(
        text = stringResource(R.string.simple_title),
        style = RipDpiThemeTokens.type.screenTitle,
        color = colors.foreground,
        textAlign = TextAlign.Center,
    )
    SimpleConnectionStatus(
        connectionState = connectionState,
        modifier = Modifier.padding(top = spacing.sm),
    )

    if (protocolLabel != null) {
        Text(
            modifier = Modifier.padding(top = spacing.xs),
            text = protocolLabel,
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
    }

    // The design system owns one representation of "connect": the actuator.
    // It carries the connection stages, its own semantics and the
    // reduced-motion/large-font fallback, none of which a plain button had.
    //
    // A running scan never gates the connection: the user must always be able to
    // tear the tunnel down. Bringing it up or down mid-scan changes the measured
    // path, so cancel the scan rather than report a result gathered across two
    // different network paths.
    RipDpiConnectionActuator(
        state = connectionActuator,
        onActivate = {
            if (reportCancellable) onCancelReport()
            onToggleConnection(false)
        },
        onDeactivate = {
            if (reportCancellable) onCancelReport()
            onToggleConnection(true)
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = spacing.xl),
        testTag = RipDpiTestTags.ConnectionActuatorButton,
    )

    // A disabled primary action must say why. The lockdown owner is the only
    // thing that can disable it, and it carries its own explanation.
    if (disconnectBlocked && disconnectBlockedReason.isNotBlank()) {
        Text(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.sm),
            text = disconnectBlockedReason,
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColumnScope.SimpleHomeReport(
    diagnostics: HomeDiagnosticsUiState,
    reportBusy: Boolean,
    reportCancellable: Boolean,
    onRunReport: () -> Unit,
    onCancelReport: () -> Unit,
    onShareReport: () -> Unit,
    onSaveReport: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    RipDpiButton(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis),
        text =
            stringResource(
                if (reportCancellable) R.string.diagnostics_action_cancel else R.string.simple_run_report,
            ),
        onClick = if (reportCancellable) onCancelReport else onRunReport,
        enabled = reportCancellable || (!reportBusy && diagnostics.analysisAction.enabled),
        variant = RipDpiButtonVariant.Outline,
    )

    // Scan progress, status and results belong to the report action directly
    // above them. Placed under the connection status instead, the progress bar
    // read as connection progress.
    SimpleDiagnosticsStatus(
        diagnostics = diagnostics,
        onShareReport = onShareReport,
        onSaveReport = onSaveReport,
        modifier = Modifier.padding(top = spacing.lg),
    )
}

@Composable
internal fun SimpleDiagnosticsStatus(
    diagnostics: HomeDiagnosticsUiState,
    onShareReport: () -> Unit = {},
    onSaveReport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusLabel =
        when (diagnostics.analysisRunStatus) {
            HomeDiagnosticsRunUiStatus.IDLE -> {
                diagnostics.analysisAction.supportingText.takeIf { it.isNotBlank() }
            }

            HomeDiagnosticsRunUiStatus.STARTING -> {
                diagnostics.analysisAction.supportingText
            }

            HomeDiagnosticsRunUiStatus.RUNNING -> {
                diagnostics.analysisAction.supportingText
            }

            HomeDiagnosticsRunUiStatus.COMPLETED -> {
                diagnostics.analysisSheet?.headline ?: stringResource(R.string.diagnostics_snackbar_scan_complete)
            }

            HomeDiagnosticsRunUiStatus.CANCELLED -> {
                stringResource(R.string.simple_report_cancelled)
            }

            HomeDiagnosticsRunUiStatus.FAILED -> {
                stringResource(R.string.simple_report_failed)
            }
        }
    if (statusLabel == null) return

    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val completedResultVisible =
        diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.COMPLETED && diagnostics.analysisSheet != null
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING) {
            RipDpiProgressBar(
                progress = diagnostics.analysisProgress.overallProgress(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val announcement =
            if (diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING) {
                statusLabel.substringBefore(" · ")
            } else {
                statusLabel
            }
        val statusModifier =
            if (diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING) {
                Modifier.clearAndSetSemantics {
                    stateDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                }
            } else {
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            }
        Text(
            modifier = Modifier.fillMaxWidth().then(statusModifier),
            text = statusLabel,
            style = if (completedResultVisible) RipDpiThemeTokens.type.sectionTitle else RipDpiThemeTokens.type.caption,
            color = if (completedResultVisible) colors.foreground else colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        diagnostics.analysisSheet
            ?.takeIf { diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.COMPLETED }
            ?.let { sheet ->
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = sheet.summary,
                    style = RipDpiThemeTokens.type.body,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
                RipDpiButton(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsShareAction),
                    text = stringResource(R.string.home_diagnostics_share_action),
                    onClick = onShareReport,
                    loading = sheet.shareBusy,
                    enabled = !sheet.shareBusy,
                    variant = RipDpiButtonVariant.Outline,
                )
                RipDpiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.diagnostics_save_archive_action),
                    onClick = onSaveReport,
                    enabled = !sheet.shareBusy,
                    variant = RipDpiButtonVariant.Outline,
                )
            }
    }
}

internal fun AnalysisProgressUiState?.overallProgress(): Float {
    val stages = this?.stages.orEmpty()
    if (stages.isEmpty()) return 0f
    return stages
        .sumOf { it.progress.toDouble() }
        .toFloat()
        .div(stages.size)
        .coerceIn(0f, 1f)
}

@Composable
internal fun SimpleConnectionStatus(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val statusLabel = stringResource(simpleStatusLabel(connectionState))
    Text(
        modifier =
            modifier.clearAndSetSemantics {
                stateDescription = statusLabel
                liveRegion = LiveRegionMode.Polite
            },
        text = statusLabel,
        style = RipDpiThemeTokens.type.body,
        color = simpleStatusColor(connectionState, colors),
        textAlign = TextAlign.Center,
    )
}

/**
 * A failed connection must not read as an idle one. Error carries the destructive
 * role; only the resting states stay muted.
 */
internal fun simpleStatusColor(
    state: ConnectionState,
    colors: RipDpiExtendedColors,
): Color =
    when (state) {
        ConnectionState.Connected -> colors.success
        ConnectionState.Error -> colors.destructive
        ConnectionState.Disconnected, ConnectionState.Connecting -> colors.mutedForeground
    }

private fun simpleStatusLabel(state: ConnectionState): Int =
    when (state) {
        ConnectionState.Disconnected -> R.string.simple_status_disconnected
        ConnectionState.Connecting -> R.string.simple_status_connecting
        ConnectionState.Connected -> R.string.simple_status_connected
        ConnectionState.Error -> R.string.simple_status_error
    }

/**
 * Maps privacy-safe active transport details to a localized display label.
 *
 * Called from the composable scope where string resources are available.
 * Unknown protocol kinds fall back to the raw kind string — forward-compat
 * for protocol kinds added after this build.
 */
@Composable
internal fun ActiveTransportDescriptor.toProtocolLabel(): String =
    when {
        protocolKind == RelayKindVless && vlessTransport == RelayVlessTransportXhttp -> {
            stringResource(R.string.simple_protocol_vless_xhttp)
        }

        protocolKind == RelayKindVlessReality -> {
            stringResource(R.string.simple_protocol_vless_reality)
        }

        protocolKind == RelayKindHysteria2 -> {
            stringResource(R.string.simple_protocol_hysteria2)
        }

        protocolKind == "amneziawg" -> {
            stringResource(R.string.simple_protocol_awg)
        }

        else -> {
            protocolKind
        }
    }
