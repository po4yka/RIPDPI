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
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationMetric
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationStrip
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.resolveDegradationTone
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Duration.Companion.ZERO

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
    val performHaptic = rememberRipDpiHapticPerformer()

    RipDpiDashboardScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Home))
                .fillMaxSize()
                .background(colors.background),
        topBar = { HomeTopBar(title = stringResource(R.string.app_name)) },
    ) {
        RipDpiConnectionActuator(
            state = uiState.connectionActuator,
            onActivate = onToggleConnection,
            onDeactivate = onToggleConnection,
            modifier = Modifier.fillMaxWidth(),
            testTag = RipDpiTestTags.ConnectionActuatorButton,
        )

        if (uiState.connectionState == ConnectionState.Error && uiState.errorMessage != null) {
            val errorMessage = uiState.errorMessage
            val errorClipboardLabel = stringResource(R.string.clipboard_label_error)
            WarningBanner(
                title = stringResource(R.string.home_status_error_title),
                message = errorMessage,
                tone = WarningBannerTone.Error,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    clipboardManager?.setPrimaryClip(ClipData.newPlainText(errorClipboardLabel, errorMessage))
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                },
                testTag = RipDpiTestTags.HomeErrorBanner,
            )
        }

        HomeSetupHealthRow(
            uiState = uiState,
            onRepairPermission = onRepairPermission,
            onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
            onDismissBatteryBanner = onDismissBatteryBanner,
            onDismissBackgroundGuidance = onDismissBackgroundGuidance,
        )

        // Embedded-Xray provider stage. Provider-distinct treatment and active-only
        // rendering live in [HomeXrayProviderBanner]; nothing renders when inactive.
        HomeXrayProviderBanner(snapshot = uiState.xrayProviderSnapshot)

        HomeNetworkConditionBanner(
            condition = uiState.networkCondition,
            onCaptivePortalSignIn = onCaptivePortalSignIn,
        )

        HomeSubscriptionExpiryBanner(
            state = subscriptionExpiry,
            onOpenStatus = onOpenSubscriptionStatus,
        )

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

private data class HomeSetupHealthItem(
    val title: String,
    val message: String,
    val actionLabel: String?,
    val onClick: (() -> Unit)?,
    val compact: Boolean = false,
)

@Composable
private fun HomeSetupHealthRow(
    uiState: MainUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    onDismissBatteryBanner: () -> Unit,
    onDismissBackgroundGuidance: () -> Unit,
) {
    val items =
        buildHomeSetupHealthItems(
            uiState = uiState,
            onRepairPermission = onRepairPermission,
            onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
            onDismissBatteryBanner = onDismissBatteryBanner,
            onDismissBackgroundGuidance = onDismissBackgroundGuidance,
        )
    if (items.isEmpty()) return

    if (items.size == 1 && items.single().compact) {
        val item = items.single()
        RipDpiCard {
            HomeSetupHealthActionRow(item = item, showDivider = false)
        }
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    RipDpiCard {
        SettingsRow(
            title = stringResource(R.string.home_setup_health_title),
            subtitle =
                stringResource(
                    if (expanded) {
                        R.string.home_setup_health_expanded
                    } else {
                        R.string.home_setup_health_collapsed_format
                    },
                    items.size,
                ),
            value =
                stringResource(
                    if (expanded) {
                        R.string.semantic_action_collapse
                    } else {
                        R.string.settings_permission_action_review
                    },
                ),
            onClick = { expanded = !expanded },
            leadingIcon = RipDpiIcons.Settings,
            showChevron = true,
            testTag = RipDpiTestTags.HomeSetupHealthRow,
        )
        if (expanded) {
            Column(
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeSetupHealthDetails),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                items.forEach { item ->
                    HomeSetupHealthActionRow(item = item, showDivider = true)
                }
            }
        }
    }
}

@Composable
private fun HomeSetupHealthActionRow(
    item: HomeSetupHealthItem,
    showDivider: Boolean,
) {
    SettingsRow(
        title = item.title,
        subtitle = item.message.takeUnless { item.compact },
        value =
            item.actionLabel?.let { label ->
                if (item.compact) {
                    "$label →"
                } else {
                    label
                }
            },
        onClick = item.onClick,
        leadingIcon = RipDpiIcons.Info,
        showDivider = showDivider,
        variant = if (item.compact) SettingsRowVariant.Tonal else SettingsRowVariant.Default,
        testTag = RipDpiTestTags.HomeSetupHealthAction,
    )
}

@Composable
private fun buildHomeSetupHealthItems(
    uiState: MainUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    onDismissBatteryBanner: () -> Unit,
    onDismissBackgroundGuidance: () -> Unit,
): List<HomeSetupHealthItem> {
    val items = mutableListOf<HomeSetupHealthItem>()
    uiState.permissionSummary.issue?.let { issue ->
        items +=
            HomeSetupHealthItem(
                title = issue.title,
                message =
                    when (issue.recovery) {
                        PermissionRecovery.OpenSettings,
                        PermissionRecovery.OpenBatteryOptimizationSettings,
                        -> stringResource(R.string.home_permission_issue_with_settings, issue.message)

                        PermissionRecovery.ShowVpnPermissionDialog,
                        PermissionRecovery.RetryPrompt,
                        -> stringResource(R.string.home_permission_issue_with_retry, issue.message)
                    },
                actionLabel = issue.actionLabel,
                onClick =
                    when (issue.recovery) {
                        PermissionRecovery.OpenBatteryOptimizationSettings -> {
                            { onRepairPermission(PermissionKind.BatteryOptimization) }
                        }

                        PermissionRecovery.ShowVpnPermissionDialog,
                        PermissionRecovery.RetryPrompt,
                        -> {
                            onOpenVpnPermissionDialog
                        }

                        PermissionRecovery.OpenSettings -> {
                            { onRepairPermission(issue.kind) }
                        }
                    },
                compact = issue.kind == PermissionKind.BatteryOptimization,
            )
    } ?: run {
        uiState.permissionSummary.recommendedIssue?.let { warning ->
            items +=
                HomeSetupHealthItem(
                    title = warning.title,
                    message = warning.message,
                    actionLabel = warning.actionLabel,
                    onClick =
                        if (warning.kind == PermissionKind.BatteryOptimization) {
                            {
                                onDismissBatteryBanner()
                                onRepairPermission(PermissionKind.BatteryOptimization)
                            }
                        } else {
                            { onDismissBatteryBanner() }
                        },
                    compact = warning.kind == PermissionKind.BatteryOptimization,
                )
        }
        uiState.permissionSummary.backgroundGuidance?.let { guidance ->
            items +=
                HomeSetupHealthItem(
                    title = guidance.title,
                    message = guidance.message,
                    actionLabel = stringResource(R.string.settings_permission_action_review),
                    onClick = onDismissBackgroundGuidance,
                )
        }
    }
    // Gated on relevance to the configured path only. Requiring an active VPN
    // card withheld the advisory in exactly the states where it can still be
    // acted on -- idle, connecting, and a tunnel that just failed.
    if (uiState.hardKillSwitch.visible) {
        items +=
            HomeSetupHealthItem(
                title = uiState.hardKillSwitch.label,
                message = uiState.hardKillSwitch.summary,
                actionLabel = uiState.hardKillSwitch.actionLabel,
                onClick =
                    if (uiState.hardKillSwitch.warning) {
                        { onRepairPermission(PermissionKind.VpnLockdown) }
                    } else {
                        null
                    },
            )
    }
    return items
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

@Preview(showBackground = true)
@Composable
private fun HomeScreenAllInactivePreview() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState = MainUiState(modeCards = previewHomeModeCards()),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenBypassActivePreview() {
    RipDpiTheme(themePreference = "dark") {
        HomeScreen(
            uiState = MainUiState(modeCards = previewHomeModeCards(activeMode = HomeMode.LocalDpiBypass)),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenVpnActivePreview() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState = MainUiState(modeCards = previewHomeModeCards(activeMode = HomeMode.RemoteVpn)),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenDiagnosticRunningPreview() {
    RipDpiTheme(themePreference = "dark") {
        HomeScreen(
            uiState = MainUiState(modeCards = previewHomeModeCards(loadingMode = HomeMode.Diagnostic)),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState =
                MainUiState(
                    connectionState = ConnectionState.Error,
                    errorMessage = "Failed to start VPN",
                    configuredMode = Mode.Proxy,
                    proxyIp = "127.0.0.1",
                    proxyPort = "1080",
                    connectionDuration = ZERO,
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

private fun previewHomeModeCards(
    activeMode: HomeMode? = null,
    loadingMode: HomeMode? = null,
) = persistentListOf(
    previewHomeModeCard(
        mode = HomeMode.LocalDpiBypass,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
    previewHomeModeCard(
        mode = HomeMode.RemoteVpn,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
    previewHomeModeCard(
        mode = HomeMode.Diagnostic,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
)

private fun previewHomeModeCard(
    mode: HomeMode,
    activeMode: HomeMode?,
    loadingMode: HomeMode?,
): HomeModeCardUiState {
    val active = mode == activeMode
    val loading = mode == loadingMode
    return HomeModeCardUiState(
        mode = mode,
        title =
            when (mode) {
                HomeMode.LocalDpiBypass -> "Local bypass"
                HomeMode.RemoteVpn -> "VPN"
                HomeMode.Diagnostic -> "Network Diagnostic"
            },
        primaryLabel =
            when (mode) {
                HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                HomeMode.RemoteVpn -> "relay.example"
                HomeMode.Diagnostic -> if (loading) "Stage 2 of 4 - Testing TCP" else "No analysis yet"
            },
        secondaryLabel = if (active) "Connected 00:18:42" else null,
        statusLine =
            when {
                loading -> "Running"
                active -> "Connected 00:18:42"
                else -> "Inactive"
            },
        primaryActionLabel =
            when {
                mode == HomeMode.Diagnostic -> "Run Scan"
                active -> "Disable"
                else -> "Enable"
            },
        configureLabel = "Configure",
        primaryActionEnabled = !loading,
        isActive = active,
        isLoading = loading,
    )
}
