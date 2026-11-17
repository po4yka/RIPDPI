package com.poyka.ripdpi.ui.screens.status

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.platform.withoutBidiIsolates
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun StatusScreen(
    uiState: MainUiState,
    pause: com.poyka.ripdpi.ui.screens.status.StatusPauseUiState =
        com.poyka.ripdpi.ui.screens.status
            .StatusPauseUiState(),
    onPause: (Long) -> Unit = {},
    onResumePause: () -> Unit = {},
    onStopPause: () -> Unit = {},
    homeDiagnostics: StatusDiagnosticsUiState = uiState.homeDiagnostics,
    diagnosticCard: StatusModeCardUiState = uiState.diagnosticCard,
    subscriptionExpiry: SubscriptionExpirySummaryUiState = SubscriptionExpirySummaryUiState(),
    onToggleConnection: () -> Unit,
    onDiagnosticRun: () -> Unit = {},
    onRunFullDiagnosticRun: () -> Unit = onDiagnosticRun,
    onCancelDiagnosticRun: () -> Unit = {},
    onBypassCardClick: () -> Unit = {},
    onVpnCardClick: () -> Unit = {},
    onDiagnosticCardClick: () -> Unit = {},
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenConnectionHealth: () -> Unit = {},
    onOpenSubscriptionStatus: () -> Unit = {},
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenModeEditor: () -> Unit = {},
    onOpenOwnedStackBrowser: (String) -> Unit = {},
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBatteryBanner: () -> Unit = {},
    onDismissBackgroundGuidance: () -> Unit = {},
    onShareAnalysis: () -> Unit = {},
    onDismissAnalysisSheet: () -> Unit = {},
    onDismissVerificationSheet: () -> Unit = {},
    onTogglePcapRecording: () -> Unit = {},
    onCaptivePortalSignIn: () -> Unit = {},
    initialModesExpanded: Boolean = false,
    onModesExpandedChange: (Boolean) -> Unit = {},
    onReconnectConfiguration: (com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime) -> Unit = {},
    onCancelConfigurationReconnect: () -> Unit = {},
) {
    TrackRecomposition("HomeScreen")
    val context = LocalContext.current
    val clipboardManager = remember(context) { context.getSystemService(ClipboardManager::class.java) }

    RipDpiDashboardScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Status))
                .fillMaxSize()
                .ripDpiScreenBackground(),
        topBar = { StatusTopBar(title = stringResource(R.string.app_name)) },
    ) {
        // Seeded from the caller so a persisted choice survives navigating away
        // and back; the local state still owns it within one composition. Hoisted
        // above the two-column split so widening the window past the Expanded
        // breakpoint re-parents the accordion without resetting it.
        var modesExpanded by rememberSaveable { mutableStateOf(initialModesExpanded) }
        // Cards that are restored open are already there; only a tap reveals them one by one.
        var modesRevealed by remember { mutableStateOf(false) }

        StatusSupportingPaneLayout(
            primary = {
                // The failure message rides with the control that reports the fault. A
                // banner underneath repeated the rail's own styling and the pipeline's
                // failed stage, so one error occupied three surfaces while only the
                // message and its copy action belonged to the banner exclusively.
                val issueDetail = uiState.connectionActuator.issueDetail
                val errorClipboardLabel = stringResource(R.string.clipboard_label_error)
                // Pause and a pending reconnect qualify the connection action, so they sit under it
                // in the hero card instead of taking surfaces, and section gaps, of their own.
                val pauseVisible = pause.available || pause.phase != null || pause.requestFailed
                val sessionControls: (@Composable ColumnScope.() -> Unit)? =
                    if (pauseVisible || uiState.appliedConfiguration.visible) {
                        {
                            if (pauseVisible) StatusPauseControls(pause, onPause, onResumePause, onStopPause)
                            StatusAppliedConfigurationPanel(
                                state = uiState.appliedConfiguration,
                                onReconnect = onReconnectConfiguration,
                                onCancelReconnect = onCancelConfigurationReconnect,
                            )
                        }
                    } else {
                        null
                    }
                RipDpiConnectionActuator(
                    state = uiState.connectionActuator,
                    onActivate = onToggleConnection,
                    onDeactivate = onToggleConnection,
                    modifier = Modifier.fillMaxWidth(),
                    onCopyIssueDetail =
                        issueDetail.takeIf { it.isNotEmpty() }?.let { message ->
                            {
                                clipboardManager?.setPrimaryClip(
                                    ClipData.newPlainText(errorClipboardLabel, message.withoutBidiIsolates()),
                                )
                            }
                        },
                    onDiagnoseIssue = onOpenDiagnostics,
                    onViewIssueLog = onOpenLogs,
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                    sessionHeld = pause.phase == com.poyka.ripdpi.data.PausePhase.Paused,
                    sessionControls = sessionControls,
                )

                // One slot, ordered by severity. Setup health, the Xray provider stage,
                // the network condition and subscription expiry each used to render
                // themselves, so a bad enough moment put four full-width surfaces
                // between the primary control and the screen's content, in declaration
                // order rather than by how much any of them mattered.
                StatusAdvisorySlot(
                    advisories =
                        buildStatusAdvisories(
                            uiState = uiState,
                            subscriptionExpiry = subscriptionExpiry,
                            onRepairPermission = onRepairPermission,
                            onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
                            onDismissBatteryBanner = onDismissBatteryBanner,
                            onDismissBackgroundGuidance = onDismissBackgroundGuidance,
                            onOpenSubscriptionStatus = onOpenSubscriptionStatus,
                            onCaptivePortalSignIn = onCaptivePortalSignIn,
                        ),
                )

                // Not an advisory: these are measurements the user asked for, and the
                // traffic counter lives here. Folding it into the slot above would have
                // put a number worth watching behind a warning header.
                StatusConnectionMeasurements(
                    quality = uiState.connectionQuality,
                    source = uiState.connectionQualitySource,
                    connected = uiState.isConnected,
                    dataTransferred = uiState.dataTransferred,
                    onReprobe = onRunFullDiagnosticRun,
                    onOpenConnectionHealth = onOpenConnectionHealth,
                )
            },
            supporting = {
                RipDpiAccordion(
                    title = stringResource(R.string.status_modes_diagnostics_title),
                    expanded = modesExpanded || homeDiagnostics.analysisAction.busy,
                    onExpandedChange = {
                        modesExpanded = it
                        modesRevealed = true
                        onModesExpandedChange(it)
                    },
                    headerTestTag = RipDpiTestTags.StatusModesDiagnosticsHeader,
                    stateTestTag =
                        if (modesExpanded || homeDiagnostics.analysisAction.busy) {
                            RipDpiTestTags.StatusModesDiagnosticsExpanded
                        } else {
                            RipDpiTestTags.StatusModesDiagnosticsCollapsed
                        },
                ) {
                    StatusModeCardList(
                        uiState = uiState,
                        homeDiagnostics = homeDiagnostics,
                        diagnosticCard = diagnosticCard,
                        onDiagnosticRun = onDiagnosticRun,
                        onRunFullDiagnosticRun = onRunFullDiagnosticRun,
                        onCancelDiagnosticRun = onCancelDiagnosticRun,
                        onBypassCardClick = onBypassCardClick,
                        onVpnCardClick = onVpnCardClick,
                        onDiagnosticCardClick = onDiagnosticCardClick,
                        onTogglePcapRecording = onTogglePcapRecording,
                        reveal = modesRevealed,
                    )
                }
            },
        )

        StatusDiagnosticsBottomSheetHost(
            homeDiagnostics = homeDiagnostics,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
            onShareAnalysis = onShareAnalysis,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
            onDismissVerificationSheet = onDismissVerificationSheet,
        )
    }
}

@Composable
private fun StatusModeCardList(
    uiState: MainUiState,
    homeDiagnostics: StatusDiagnosticsUiState,
    diagnosticCard: StatusModeCardUiState,
    onDiagnosticRun: () -> Unit,
    onRunFullDiagnosticRun: () -> Unit,
    onCancelDiagnosticRun: () -> Unit,
    onBypassCardClick: () -> Unit,
    onVpnCardClick: () -> Unit,
    onDiagnosticCardClick: () -> Unit,
    onTogglePcapRecording: () -> Unit,
    reveal: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.layout.groupGap),
    ) {
        StatusStaggeredReveal(index = 0, animate = reveal) {
            StatusModeCard(
                uiState = uiState.localBypassCard,
                onConfigure = onBypassCardClick,
                onCardClick = onBypassCardClick,
                configureActionVariant = RipDpiButtonVariant.Ghost,
            )
        }
        StatusStaggeredReveal(index = 1, animate = reveal) {
            StatusModeCard(
                uiState = uiState.vpnCard,
                onConfigure = onVpnCardClick,
                onCardClick = onVpnCardClick,
                configureActionVariant = RipDpiButtonVariant.Ghost,
            )
        }
        StatusStaggeredReveal(index = 2, animate = reveal) {
            StatusModeCard(
                uiState = diagnosticCard,
                onPrimaryAction =
                    if (
                        homeDiagnostics.analysisRunStatus == StatusDiagnosticsRunUiStatus.STARTING ||
                        homeDiagnostics.analysisRunStatus == StatusDiagnosticsRunUiStatus.RUNNING
                    ) {
                        onCancelDiagnosticRun
                    } else {
                        onDiagnosticRun
                    },
                onConfigure = onDiagnosticCardClick,
                onCardClick = onDiagnosticCardClick,
                primaryActionVariant = RipDpiButtonVariant.Outline,
                configureActionVariant = RipDpiButtonVariant.Ghost,
            )
        }
        StatusStaggeredReveal(index = 3, animate = reveal) {
            RipDpiButton(
                text = stringResource(R.string.status_diagnostics_run_analysis),
                onClick = onRunFullDiagnosticRun,
                enabled = homeDiagnostics.analysisAction.enabled && !homeDiagnostics.analysisAction.busy,
                variant = RipDpiButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis),
            )
        }
        if (homeDiagnostics.pcapToggleVisible) {
            RipDpiSwitch(
                checked = homeDiagnostics.pcapRecordingRequested,
                onCheckedChange = { onTogglePcapRecording() },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.status_diagnostics_pcap_toggle),
                helperText = stringResource(R.string.status_diagnostics_pcap_helper),
                enabled = homeDiagnostics.analysisAction.enabled,
                testTag = RipDpiTestTags.StatusDiagnosticsPcapToggle,
            )
        }
    }
}
