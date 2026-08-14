package com.poyka.ripdpi.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
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
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationMetric
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationStrip
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.resolveDegradationTone

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun HomeScreen(
    uiState: MainUiState,
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
        HomeDegradationStrip(
            quality = uiState.connectionQuality,
            dataTransferred = uiState.dataTransferred,
            onReprobe = onDiagnosticRun,
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

@Composable
private fun HomeDegradationStrip(
    quality: ConnectionQualitySnapshot?,
    dataTransferred: Long,
    onReprobe: () -> Unit,
) {
    if (quality == null) return
    // A null tone means every indicator is in range, which used to render
    // nothing at all: the screen reported loss, RTT and jitter only once
    // something was already wrong, so a healthy connection said nothing about
    // itself. Report the same measurements in a neutral tone instead.
    val tone = resolveDegradationTone(quality) ?: RipDpiDegradationTone.Nominal
    val titleRes =
        when (tone) {
            RipDpiDegradationTone.Nominal -> R.string.vpn_quality_strip_nominal_title
            RipDpiDegradationTone.Warning -> R.string.vpn_quality_strip_warning_title
            RipDpiDegradationTone.Critical -> R.string.vpn_quality_strip_critical_title
        }
    val bodyRes =
        when (tone) {
            RipDpiDegradationTone.Nominal -> R.string.vpn_quality_strip_body_nominal
            RipDpiDegradationTone.Warning -> R.string.vpn_quality_strip_body_warning
            RipDpiDegradationTone.Critical -> R.string.vpn_quality_strip_body_critical
        }
    val metrics =
        persistentListOf(
            RipDpiDegradationMetric(
                label = stringResource(R.string.vpn_quality_metric_loss),
                value = stringResource(R.string.home_quality_metric_loss_format, quality.lossPct),
                delta = "",
                deltaIsBad = false,
            ),
            RipDpiDegradationMetric(
                label = stringResource(R.string.vpn_quality_metric_rtt_p50),
                value = stringResource(R.string.home_quality_metric_ms_format, quality.rttP50Ms),
                delta = "",
                deltaIsBad = false,
            ),
            RipDpiDegradationMetric(
                label = stringResource(R.string.vpn_quality_metric_jitter),
                value = stringResource(R.string.home_quality_metric_ms_format, quality.jitterMs),
                delta = "",
                deltaIsBad = false,
            ),
            // dataTransferred was polled once a second, resolved, and carried all
            // the way into MainUiState without a single composable reading it.
            // This is the slot it was always missing.
            RipDpiDegradationMetric(
                label = stringResource(R.string.home_stat_traffic),
                value = Formatter.formatShortFileSize(LocalContext.current, dataTransferred),
                delta = "",
                deltaIsBad = false,
            ),
        )
    val sampleCountLabel = stringResource(R.string.vpn_quality_graph_samples_format, quality.sampleCount)
    val sinceLabel = stringResource(R.string.vpn_quality_strip_since_format, sampleCountLabel)
    RipDpiDegradationStrip(
        title = stringResource(titleRes),
        body = stringResource(bodyRes),
        metrics = metrics,
        sinceLabel = sinceLabel,
        primaryAction =
            RipDpiDegradationAction(
                label = stringResource(R.string.vpn_quality_strip_reprobe),
                onClick = onReprobe,
            ),
        secondaryAction = null,
        tone = tone,
        modifier = Modifier.fillMaxWidth(),
    )
}
