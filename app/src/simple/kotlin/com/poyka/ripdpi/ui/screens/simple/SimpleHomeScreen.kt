package com.poyka.ripdpi.ui.screens.simple

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiContentGrouping
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

    // This flavor has no nav host, so About is a saved-state overlay rather than a route.
    // AboutRoute brings its own view model, so nothing has to be threaded through here.
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = aboutVisible) { aboutVisible = false }
    if (aboutVisible) {
        AboutRoute(onBack = { aboutVisible = false }, modifier = modifier)
        return
    }

    SimpleHomeContent(
        connectionState = uiState.connectionState,
        connectionActuator = uiState.connectionActuator,
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
        onOpenAbout = { aboutVisible = true },
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
    onOpenAbout: () -> Unit = {},
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

    // The actuator is this screen's status display, so it has to speak the flavor's
    // language rather than the shared resolver's "secure line" wording, and it has to do
    // so here rather than in the caller -- previews, tests and screenshots render this
    // composable directly. The lockdown owner still wins: when it blocks disconnect it
    // supplies its own label.
    val actuatorState =
        if (blocksDisconnect) {
            connectionActuator
        } else {
            connectionActuator.copy(
                actionLabel =
                    stringResource(if (active) R.string.simple_disconnect else R.string.simple_connect),
                statusDescription = stringResource(simpleStatusLabel(connectionState)),
            )
        }

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
                                connectionActuator = actuatorState,
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

                // Without a nav host or app bar this flavor had no route to version,
                // licences or the privacy notice at all. The full experience reaches the
                // same screen from settings.
                RipDpiButton(
                    modifier =
                        Modifier
                            .padding(top = spacing.lg)
                            .ripDpiTestTag(RipDpiTestTags.SimpleAboutAction),
                    text = stringResource(R.string.about_category),
                    onClick = onOpenAbout,
                    variant = RipDpiButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SimpleHomeIdentity(
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

    Text(
        // The screen has no app bar, so this is the only heading TalkBack's
        // heading navigation can land on.
        modifier = Modifier.semantics { heading() },
        // Deliberately not app_name: TerminologyCanonTest pins app_name as the
        // locale-independent launcher label and simple_title as the localized on-screen
        // one. The two are meant to differ outside English.
        text = stringResource(R.string.simple_title),
        style = RipDpiThemeTokens.type.screenTitle,
        color = colors.foreground,
        textAlign = TextAlign.Center,
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
                // A failed run still produces an outcome with a headline naming what went
                // wrong. Showing only "try again" threw that away and left the user with
                // nothing to act on; the completed branch has always used it.
                diagnostics.analysisSheet?.headline ?: stringResource(R.string.simple_report_failed)
            }
        }
    if (statusLabel == null) return

    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    // A failed run is exactly when its archive is worth reading and sending on, so the
    // result block belongs to both terminal outcomes rather than only the happy one.
    val resultReady =
        diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.COMPLETED ||
            diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.FAILED
    val completedResultVisible = resultReady && diagnostics.analysisSheet != null
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
        // The announcement comes from state, not from re-parsing the display string. The
        // old split on a literal " · " broke in any locale that separates differently and
        // threw away the stage name, so a screen-reader user heard the counter but never
        // what was being tested.
        val announcement = diagnostics.analysisAction.stageAnnouncement.ifBlank { statusLabel }
        val statusModifier =
            if (diagnostics.analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING) {
                // The visible text carries a detail that ticks continuously, so the node is
                // renamed to the coarse announcement rather than left to read it out. Naming
                // it -- rather than hanging a stateDescription on an unnamed node -- keeps it
                // both announceable and findable.
                Modifier.clearAndSetSemantics {
                    contentDescription = announcement
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
            ?.takeIf { resultReady }
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
                // Saving is fire-and-forget: it emits an effect that hands the archive to
                // the system save dialog, which is itself the feedback. There is no save
                // busy state to reflect, and shareBusy belongs to the share flow -- gating
                // on it only disabled this button while an unrelated share was in flight.
                RipDpiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.diagnostics_save_archive_action),
                    onClick = onSaveReport,
                    variant = RipDpiButtonVariant.Outline,
                )

                // Both actions put an archive of this device's network activity somewhere
                // the user chooses, including off-device. Naming what is inside belongs
                // next to the buttons, not behind them.
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.diagnostics_share_archive_body),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
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

/**
 * The words the actuator shows as its status line.
 *
 * There is no separate status label on this screen: the actuator is the status display,
 * and a second line saying the same thing was the duplication this flavor did not need.
 * A failed connection stays distinguishable through the actuator's own Fault role, which
 * paints the rail and its border destructive -- a stronger signal than a coloured word,
 * and one that does not rely on colour alone.
 */
internal fun simpleStatusLabel(state: ConnectionState): Int =
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
