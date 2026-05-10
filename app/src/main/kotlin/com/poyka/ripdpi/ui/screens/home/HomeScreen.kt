package com.poyka.ripdpi.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Duration.Companion.ZERO

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
    onBypassToggle: (Boolean) -> Unit = { onToggleConnection() },
    onVpnToggle: (Boolean) -> Unit = { onToggleConnection() },
    onDiagnosticRun: () -> Unit = {},
    onBypassCardClick: () -> Unit = {},
    onVpnCardClick: () -> Unit = {},
    onDiagnosticCardClick: () -> Unit = {},
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenModeEditor: () -> Unit = {},
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBatteryBanner: () -> Unit = {},
    onDismissBackgroundGuidance: () -> Unit = {},
    onShareAnalysis: () -> Unit = {},
    onDismissAnalysisSheet: () -> Unit = {},
    onDismissVerificationSheet: () -> Unit = {},
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
        if (uiState.connectionState == ConnectionState.Error && uiState.errorMessage != null) {
            WarningBanner(
                title = stringResource(R.string.home_status_error_title),
                message = uiState.errorMessage,
                tone = WarningBannerTone.Error,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboardManager?.setPrimaryClip(ClipData.newPlainText("error", uiState.errorMessage))
                                performHaptic(RipDpiHapticFeedback.Acknowledge)
                            },
                        ),
                testTag = RipDpiTestTags.HomeErrorBanner,
            )
        }

        uiState.permissionSummary.issue?.let { issue ->
            WarningBanner(
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
                tone = WarningBannerTone.Restricted,
                testTag = RipDpiTestTags.HomePermissionIssueBanner,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            when (issue.recovery) {
                                PermissionRecovery.OpenBatteryOptimizationSettings -> {
                                    Modifier.ripDpiClickable(
                                        role = Role.Button,
                                        onClick = { onRepairPermission(PermissionKind.BatteryOptimization) },
                                    )
                                }

                                PermissionRecovery.ShowVpnPermissionDialog -> {
                                    Modifier.ripDpiClickable(
                                        role = Role.Button,
                                        onClick = onOpenVpnPermissionDialog,
                                    )
                                }

                                else -> {
                                    Modifier
                                }
                            },
                        ),
            )
        } ?: run {
            val warning = uiState.permissionSummary.recommendedIssue
            val guidance = uiState.permissionSummary.backgroundGuidance
            if (warning != null) {
                val combinedMessage =
                    if (guidance != null) {
                        "${warning.message} ${guidance.message}"
                    } else {
                        warning.message
                    }
                WarningBanner(
                    title = warning.title,
                    message = combinedMessage,
                    tone = WarningBannerTone.Warning,
                    testTag = RipDpiTestTags.HomePermissionRecommendationBanner,
                    onDismiss = {
                        onDismissBatteryBanner()
                        if (guidance != null) onDismissBackgroundGuidance()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (warning.kind == PermissionKind.BatteryOptimization) {
                                    Modifier.ripDpiClickable(
                                        role = Role.Button,
                                        onClick = { onRepairPermission(PermissionKind.BatteryOptimization) },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                )
            } else if (guidance != null) {
                WarningBanner(
                    title = guidance.title,
                    message = guidance.message,
                    tone = WarningBannerTone.Info,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = RipDpiTestTags.HomeBackgroundGuidanceBanner,
                    onDismiss = onDismissBackgroundGuidance,
                )
            }
        }

        HomeModeCardList(
            uiState = uiState,
            onBypassToggle = onBypassToggle,
            onVpnToggle = onVpnToggle,
            onDiagnosticRun = onDiagnosticRun,
            onBypassCardClick = onBypassCardClick,
            onVpnCardClick = onVpnCardClick,
            onDiagnosticCardClick = onDiagnosticCardClick,
        )

        HomeDiagnosticsBottomSheetHost(
            uiState = uiState,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenModeEditor = onOpenModeEditor,
            onShareAnalysis = onShareAnalysis,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
            onDismissVerificationSheet = onDismissVerificationSheet,
        )
    }
}

@Composable
private fun HomeModeCardList(
    uiState: MainUiState,
    onBypassToggle: (Boolean) -> Unit,
    onVpnToggle: (Boolean) -> Unit,
    onDiagnosticRun: () -> Unit,
    onBypassCardClick: () -> Unit,
    onVpnCardClick: () -> Unit,
    onDiagnosticCardClick: () -> Unit,
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
        )
        HomeModeCard(
            uiState = uiState.vpnCard,
            onPrimaryAction = { onVpnToggle(!uiState.vpnCard.isActive) },
            onConfigure = onVpnCardClick,
            onCardClick = onVpnCardClick,
        )
        HomeModeCard(
            uiState = uiState.diagnosticCard,
            onPrimaryAction = onDiagnosticRun,
            onConfigure = onDiagnosticCardClick,
            onCardClick = onDiagnosticCardClick,
        )
    }
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
                HomeMode.LocalDpiBypass -> "Local DPI Bypass"
                HomeMode.RemoteVpn -> "VPN with Remote Server"
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
