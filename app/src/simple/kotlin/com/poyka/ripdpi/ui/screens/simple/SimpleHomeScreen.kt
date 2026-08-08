package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
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

    SimpleHomeContent(
        connectionState = uiState.connectionState,
        blocksDisconnect = uiState.hardKillSwitch.blocksDisconnect,
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
    blocksDisconnect: Boolean = false,
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
    val reportStarting = diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.STARTING
    val reportCancellable = diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING
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
            Column(
                modifier =
                    Modifier
                        .widthIn(max = layout.formMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
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

                SimpleDiagnosticsStatus(
                    diagnostics = diagnostics,
                    onShareReport = onShareReport,
                    onSaveReport = onSaveReport,
                    modifier = Modifier.padding(top = spacing.lg),
                )

                RipDpiButton(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xl)
                            .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorButton),
                    text =
                        stringResource(
                            if (active) R.string.simple_disconnect else R.string.simple_connect,
                        ),
                    onClick = { onToggleConnection(active) },
                    enabled = !reportBusy && !disconnectBlocked,
                    variant = RipDpiButtonVariant.Primary,
                )

                RipDpiButton(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.md)
                            .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis),
                    text =
                        stringResource(
                            if (reportCancellable) R.string.diagnostics_action_cancel else R.string.simple_run_report,
                        ),
                    onClick = if (reportCancellable) onCancelReport else onRunReport,
                    loading = reportStarting,
                    enabled = reportCancellable || (!reportBusy && diagnostics.analysisAction.enabled),
                    variant = RipDpiButtonVariant.Outline,
                )
            }
        }
    }
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
        color =
            if (connectionState == ConnectionState.Connected) {
                colors.success
            } else {
                colors.mutedForeground
            },
        textAlign = TextAlign.Center,
    )
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
