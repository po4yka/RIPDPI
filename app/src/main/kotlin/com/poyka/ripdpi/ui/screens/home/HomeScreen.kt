package com.poyka.ripdpi.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
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
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

private const val columnWeightPrimary = 1.08f
private const val columnWeightSecondary = 0.92f

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenModeEditor: () -> Unit = {},
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBatteryBanner: () -> Unit = {},
    onDismissBackgroundGuidance: () -> Unit = {},
    onRunFullAnalysis: () -> Unit = {},
    onRunQuickAnalysis: () -> Unit = {},
    onStartVerifiedVpn: () -> Unit = {},
    onShareAnalysis: () -> Unit = {},
    onDismissAnalysisSheet: () -> Unit = {},
    onDismissVerificationSheet: () -> Unit = {},
    onTogglePcapRecording: () -> Unit = {},
) {
    TrackRecomposition("HomeScreen")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val clipboardManager = LocalContext.current.getSystemService(ClipboardManager::class.java)
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

        if (layout.widthClass == RipDpiWidthClass.Expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(layout.groupGap),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(columnWeightPrimary),
                    verticalArrangement = Arrangement.spacedBy(layout.groupGap),
                ) {
                    HomeStatusCard(uiState = uiState, onToggleConnection = onToggleConnection)
                    HomeDiagnosticsCard(
                        uiState = uiState,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onOpenHistory = onOpenHistory,
                        onOpenAdvancedSettings = onOpenAdvancedSettings,
                        onOpenModeEditor = onOpenModeEditor,
                        onRunFullAnalysis = onRunFullAnalysis,
                        onRunQuickAnalysis = onRunQuickAnalysis,
                        onStartVerifiedVpn = onStartVerifiedVpn,
                        onTogglePcapRecording = onTogglePcapRecording,
                    )
                    uiState.approachSummary?.let { summary ->
                        HomeApproachCard(summary = summary, onOpenDiagnostics = onOpenDiagnostics)
                    }
                    HomeHistoryCard(onOpenHistory = onOpenHistory)
                }
                Column(
                    modifier = Modifier.weight(columnWeightSecondary),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.home_overview_title),
                        style = type.sectionTitle,
                        color = colors.mutedForeground,
                    )
                    HomeStatsGrid(uiState = uiState)
                }
            }
        } else {
            HomeStatusCard(uiState = uiState, onToggleConnection = onToggleConnection)
            HomeDiagnosticsCard(
                uiState = uiState,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenHistory = onOpenHistory,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onOpenModeEditor = onOpenModeEditor,
                onRunFullAnalysis = onRunFullAnalysis,
                onRunQuickAnalysis = onRunQuickAnalysis,
                onStartVerifiedVpn = onStartVerifiedVpn,
                onTogglePcapRecording = onTogglePcapRecording,
            )

            if (uiState.connectionState == ConnectionState.Connected) {
                uiState.approachSummary?.let { summary ->
                    HomeApproachCard(summary = summary, onOpenDiagnostics = onOpenDiagnostics)
                }
            }
            HomeHistoryCard(onOpenHistory = onOpenHistory)

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(
                    text = stringResource(R.string.home_overview_title),
                    style = type.sectionTitle,
                    color = colors.mutedForeground,
                )
                HomeStatsGrid(uiState = uiState)
            }
        }

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

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeScreenDisconnectedPreview() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState = MainUiState(),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeScreenConnectedPreview() {
    RipDpiTheme(themePreference = "dark") {
        HomeScreen(
            uiState =
                MainUiState(
                    connectionState = ConnectionState.Connected,
                    connectionDuration = Duration.parse("PT18M42S"),
                    dataTransferred = 18_242_560L,
                    approachSummary =
                        HomeApproachSummaryUiState(
                            title = "VPN Split · HTTP/HTTPS",
                            verification = "Validated",
                            successRate = "83%",
                            supportingText = "Stable on the last 3 runtime sessions",
                        ),
                ),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
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
