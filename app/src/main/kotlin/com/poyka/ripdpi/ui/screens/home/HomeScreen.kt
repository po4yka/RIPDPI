package com.poyka.ripdpi.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun HomeScreen(
    uiState: MainUiState,
    pause: com.poyka.ripdpi.activities.HomePauseUiState =
        com.poyka.ripdpi.activities
            .HomePauseUiState(),
    onPause: (Long) -> Unit = {},
    onResumePause: () -> Unit = {},
    onStopPause: () -> Unit = {},
    homeDiagnostics: HomeDiagnosticsUiState = uiState.homeDiagnostics,
    diagnosticCard: HomeModeCardUiState = uiState.diagnosticCard,
    subscriptionExpiry: SubscriptionExpirySummaryUiState = SubscriptionExpirySummaryUiState(),
    onToggleConnection: () -> Unit,
    onBypassToggle: (Boolean) -> Unit = { onToggleConnection() },
    onVpnToggle: (Boolean) -> Unit = { onToggleConnection() },
    onDiagnosticRun: () -> Unit = {},
    onBypassCardClick: () -> Unit = {},
    onVpnCardClick: () -> Unit = {},
    onDiagnosticCardClick: () -> Unit = {},
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
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
    val colors = RipDpiThemeTokens.colors
    val context = LocalContext.current
    val clipboardManager = remember(context) { context.getSystemService(ClipboardManager::class.java) }

    RipDpiDashboardScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Home))
                .fillMaxSize()
                .background(colors.background),
        topBar = { HomeTopBar(title = stringResource(R.string.app_name)) },
    ) {
        // The failure message rides with the control that reports the fault. A
        // banner underneath repeated the rail's own styling and the pipeline's
        // failed stage, so one error occupied three surfaces while only the
        // message and its copy action belonged to the banner exclusively.
        val faultDetail = uiState.connectionActuator.faultDetail
        val errorClipboardLabel = stringResource(R.string.clipboard_label_error)
        RipDpiConnectionActuator(
            state = uiState.connectionActuator,
            onActivate = onToggleConnection,
            onDeactivate = onToggleConnection,
            modifier = Modifier.fillMaxWidth(),
            onCopyFaultDetail =
                faultDetail.takeIf { it.isNotEmpty() }?.let { message ->
                    { clipboardManager?.setPrimaryClip(ClipData.newPlainText(errorClipboardLabel, message)) }
                },
            testTag = RipDpiTestTags.ConnectionActuatorButton,
        )

        if (pause.available || pause.phase != null || pause.requestFailed) {
            HomePauseControls(pause, onPause, onResumePause, onStopPause)
        }

        // One slot, ordered by severity. Setup health, the Xray provider stage,
        // the network condition and subscription expiry each used to render
        // themselves, so a bad enough moment put four full-width surfaces
        // between the primary control and the screen's content, in declaration
        // order rather than by how much any of them mattered.
        HomeAdvisorySlot(
            advisories =
                buildHomeAdvisories(
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
        HomeConnectionMeasurements(
            quality = uiState.connectionQuality,
            source = uiState.connectionQualitySource,
            connected = uiState.isConnected,
            dataTransferred = uiState.dataTransferred,
            onReprobe = onDiagnosticRun,
        )

        HomeAppliedConfigurationPanel(
            state = uiState.appliedConfiguration,
            onReconnect = onReconnectConfiguration,
            onCancelReconnect = onCancelConfigurationReconnect,
        )

        // Seeded from the caller so a persisted choice survives navigating away
        // and back; the local state still owns it within one composition. Hoisted
        // above the two-column split so widening the window past the Expanded
        // breakpoint re-parents the accordion without resetting it.
        var modesExpanded by rememberSaveable { mutableStateOf(initialModesExpanded) }

        HomeContentColumns(
            primary = {
                HomeConnectionHealthEntry(onOpenConnectionHealth = onOpenConnectionHealth)
            },
            secondary = {
                RipDpiAccordion(
                    title = stringResource(R.string.home_modes_diagnostics_title),
                    expanded = modesExpanded,
                    onExpandedChange = {
                        modesExpanded = it
                        onModesExpandedChange(it)
                    },
                    headerTestTag = RipDpiTestTags.HomeModesDiagnosticsHeader,
                    stateTestTag =
                        if (modesExpanded) {
                            RipDpiTestTags.HomeModesDiagnosticsExpanded
                        } else {
                            RipDpiTestTags.HomeModesDiagnosticsCollapsed
                        },
                ) {
                    HomeModeCardList(
                        uiState = uiState,
                        homeDiagnostics = homeDiagnostics,
                        diagnosticCard = diagnosticCard,
                        onBypassToggle = onBypassToggle,
                        onVpnToggle = onVpnToggle,
                        onDiagnosticRun = onDiagnosticRun,
                        onBypassCardClick = onBypassCardClick,
                        onVpnCardClick = onVpnCardClick,
                        onDiagnosticCardClick = onDiagnosticCardClick,
                        onOpenModeEditor = onOpenModeEditor,
                        onTogglePcapRecording = onTogglePcapRecording,
                    )
                }
            },
        )

        HomeDiagnosticsBottomSheetHost(
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
private fun HomeConnectionHealthEntry(onOpenConnectionHealth: () -> Unit) {
    RipDpiCard {
        SettingsRow(
            title = stringResource(R.string.connection_health_home_title),
            subtitle = stringResource(R.string.connection_health_home_subtitle),
            value = stringResource(R.string.connection_health_home_value),
            onClick = onOpenConnectionHealth,
            leadingIcon = RipDpiIcons.NetworkCheck,
            showChevron = true,
            testTag = RipDpiTestTags.HomeConnectionHealthAction,
        )
    }
}

@Composable
private fun HomeModeCardList(
    uiState: MainUiState,
    homeDiagnostics: HomeDiagnosticsUiState,
    diagnosticCard: HomeModeCardUiState,
    onBypassToggle: (Boolean) -> Unit,
    onVpnToggle: (Boolean) -> Unit,
    onDiagnosticRun: () -> Unit,
    onBypassCardClick: () -> Unit,
    onVpnCardClick: () -> Unit,
    onDiagnosticCardClick: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onTogglePcapRecording: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.layout.groupGap),
    ) {
        HomeModeCard(
            uiState = uiState.localBypassCard,
            onPrimaryAction = { onBypassToggle(!uiState.localBypassCard.isActive) },
            onConfigure = onBypassCardClick,
            onCardClick = onBypassCardClick,
            primaryActionVariant = RipDpiButtonVariant.Ghost,
            configureActionVariant = RipDpiButtonVariant.Ghost,
        )
        HomeModeCard(
            uiState = uiState.vpnCard,
            onPrimaryAction = { onVpnToggle(!uiState.vpnCard.isActive) },
            onConfigure = onVpnCardClick,
            onCardClick = onVpnCardClick,
            onDisabledHintClick = onOpenModeEditor,
            primaryActionVariant = RipDpiButtonVariant.Ghost,
            configureActionVariant = RipDpiButtonVariant.Ghost,
        )
        HomeModeCard(
            uiState = diagnosticCard,
            onPrimaryAction = onDiagnosticRun,
            onConfigure = onDiagnosticCardClick,
            onCardClick = onDiagnosticCardClick,
            primaryActionVariant = RipDpiButtonVariant.Outline,
            configureActionVariant = RipDpiButtonVariant.Ghost,
        )
        if (homeDiagnostics.pcapToggleVisible) {
            RipDpiSwitch(
                checked = homeDiagnostics.pcapRecordingRequested,
                onCheckedChange = { onTogglePcapRecording() },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.home_diagnostics_pcap_toggle),
                helperText = stringResource(R.string.home_diagnostics_pcap_helper),
                enabled = homeDiagnostics.analysisAction.enabled,
                testTag = RipDpiTestTags.HomeDiagnosticsPcapToggle,
            )
        }
    }
}
