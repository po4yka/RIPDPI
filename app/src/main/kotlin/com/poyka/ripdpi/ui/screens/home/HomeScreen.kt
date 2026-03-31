package com.poyka.ripdpi.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StageProgressIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
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
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        uiState = uiState,
        modifier = modifier,
        onToggleConnection = remember(viewModel) { { viewModel.onPrimaryConnectionAction() } },
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenHistory = onOpenHistory,
        onRepairPermission = viewModel::onRepairPermissionRequested,
        onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
        onDismissBatteryBanner = viewModel::onDismissBatteryBanner,
        onDismissBackgroundGuidance = viewModel::onDismissBackgroundGuidance,
        onRunFullAnalysis = viewModel::onRunHomeFullAnalysis,
        onStartVerifiedVpn = viewModel::onStartVerifiedVpn,
        onShareAnalysis = viewModel::onShareHomeAnalysis,
        onDismissAnalysisSheet = viewModel::dismissHomeAnalysisSheet,
        onDismissVerificationSheet = viewModel::dismissHomeVerificationSheet,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBatteryBanner: () -> Unit = {},
    onDismissBackgroundGuidance: () -> Unit = {},
    onRunFullAnalysis: () -> Unit = {},
    onStartVerifiedVpn: () -> Unit = {},
    onShareAnalysis: () -> Unit = {},
    onDismissAnalysisSheet: () -> Unit = {},
    onDismissVerificationSheet: () -> Unit = {},
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
                    modifier = Modifier.weight(1.08f),
                    verticalArrangement = Arrangement.spacedBy(layout.groupGap),
                ) {
                    HomeStatusCard(
                        uiState = uiState,
                        onToggleConnection = onToggleConnection,
                    )
                    HomeDiagnosticsCard(
                        uiState = uiState,
                        onRunFullAnalysis = onRunFullAnalysis,
                        onStartVerifiedVpn = onStartVerifiedVpn,
                    )

                    uiState.approachSummary?.let { summary ->
                        HomeApproachCard(
                            summary = summary,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                    HomeHistoryCard(onOpenHistory = onOpenHistory)
                }
                Column(
                    modifier = Modifier.weight(0.92f),
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
            HomeStatusCard(
                uiState = uiState,
                onToggleConnection = onToggleConnection,
            )
            HomeDiagnosticsCard(
                uiState = uiState,
                onRunFullAnalysis = onRunFullAnalysis,
                onStartVerifiedVpn = onStartVerifiedVpn,
            )

            if (uiState.connectionState == ConnectionState.Connected) {
                uiState.approachSummary?.let { summary ->
                    HomeApproachCard(
                        summary = summary,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
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
            onShareAnalysis = onShareAnalysis,
            onDismissAnalysisSheet = onDismissAnalysisSheet,
            onDismissVerificationSheet = onDismissVerificationSheet,
        )
    }
}

@Composable
private fun HomeApproachCard(
    summary: HomeApproachSummaryUiState,
    onOpenDiagnostics: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeApproachCard),
        onClick = onOpenDiagnostics,
        variant = RipDpiCardVariant.Elevated,
    ) {
        Text(
            text = stringResource(R.string.home_approach_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
        )
        Text(
            text = summary.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = "${summary.verification} · ${summary.successRate}",
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = summary.supportingText,
            style = RipDpiThemeTokens.type.monoConfig,
            color = colors.foreground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.home_approach_cta),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun HomeHistoryCard(onOpenHistory: () -> Unit) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeHistoryCard),
        onClick = onOpenHistory,
        variant = RipDpiCardVariant.Outlined,
    ) {
        Text(
            text = stringResource(R.string.history_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.home_history_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.home_history_body),
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.home_history_cta),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun HomeDiagnosticsCard(
    uiState: MainUiState,
    onRunFullAnalysis: () -> Unit,
    onStartVerifiedVpn: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsCard),
        variant = RipDpiCardVariant.Elevated,
    ) {
        Text(
            text = stringResource(R.string.home_diagnostics_section),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.home_diagnostics_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.home_diagnostics_body),
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        uiState.homeDiagnostics.latestAudit?.let { result ->
            Spacer(modifier = Modifier.height(spacing.sm))
            val headlineColor =
                when {
                    result.failedStageCount > 0 -> colors.destructive
                    result.completedStageCount == result.totalStageCount && result.totalStageCount > 0 -> colors.success
                    else -> colors.foreground
                }
            Text(
                text = result.headline,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = headlineColor,
            )
            if (result.totalStageCount > 0) {
                Spacer(modifier = Modifier.height(spacing.xs))
                StageProgressIndicator(
                    completedCount = result.completedStageCount,
                    failedCount = result.failedStageCount,
                    totalCount = result.totalStageCount,
                )
            }
            result.recommendationSummary?.let { recommendation ->
                Text(
                    text = recommendation,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.foreground,
                )
            }
            if (result.stale) {
                Text(
                    text = stringResource(R.string.home_diagnostics_run_again),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.warning,
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        HorizontalDivider(color = colors.divider)
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = uiState.homeDiagnostics.analysisAction.supportingText,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        RipDpiButton(
            text = uiState.homeDiagnostics.analysisAction.label,
            onClick = onRunFullAnalysis,
            enabled = uiState.homeDiagnostics.analysisAction.enabled,
            variant = RipDpiButtonVariant.Primary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis),
        )
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = uiState.homeDiagnostics.verifiedVpnAction.supportingText,
            style = RipDpiThemeTokens.type.secondaryBody,
            color =
                if (!uiState.homeDiagnostics.verifiedVpnAction.enabled) {
                    colors.mutedForeground
                } else {
                    colors.foreground
                },
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        RipDpiButton(
            text = uiState.homeDiagnostics.verifiedVpnAction.label,
            onClick = onStartVerifiedVpn,
            enabled = uiState.homeDiagnostics.verifiedVpnAction.enabled,
            variant = RipDpiButtonVariant.Outline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.HomeDiagnosticsVerifiedVpn),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeDiagnosticsBottomSheetHost(
    uiState: MainUiState,
    onOpenDiagnostics: () -> Unit,
    onShareAnalysis: () -> Unit,
    onDismissAnalysisSheet: () -> Unit,
    onDismissVerificationSheet: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors

    uiState.homeDiagnostics.analysisSheet?.let { sheet ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissAnalysisSheet,
            title = stringResource(R.string.home_diagnostics_analysis_sheet_title),
            message = sheet.headline,
            icon = RipDpiIcons.Search,
            testTag = RipDpiTestTags.HomeDiagnosticsAnalysisSheet,
            primaryAction =
                RipDpiSheetAction(
                    label = stringResource(R.string.home_diagnostics_share_action),
                    onClick = onShareAnalysis,
                    testTag = RipDpiTestTags.HomeDiagnosticsShareAction,
                ),
            secondaryAction =
                RipDpiSheetAction(
                    label = stringResource(R.string.home_diagnostics_open_diagnostics_action),
                    onClick = {
                        onDismissAnalysisSheet()
                        onOpenDiagnostics()
                    },
                    testTag = RipDpiTestTags.HomeDiagnosticsOpenDiagnosticsAction,
                    variant = RipDpiButtonVariant.Outline,
                ),
        ) {
            Text(
                text = sheet.summary,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            if (sheet.stageSummaries.isNotEmpty()) {
                StageProgressIndicator(
                    completedCount = sheet.completedStageCount,
                    failedCount = sheet.failedStageCount,
                    totalCount = sheet.stageSummaries.size,
                )
            }
            sheet.confidenceSummary?.let { value ->
                val confidenceColor =
                    when {
                        value.contains("low", ignoreCase = true) -> colors.destructive
                        value.contains("medium", ignoreCase = true) -> colors.warning
                        value.contains("high", ignoreCase = true) -> colors.success
                        else -> colors.foreground
                    }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = RipDpiThemeTokens.spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_diagnostics_confidence_label),
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                    Text(
                        text = value,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = confidenceColor,
                    )
                }
            }
            sheet.coverageSummary?.let { value ->
                SettingsRow(title = stringResource(R.string.home_diagnostics_coverage_label), value = value)
            }
            sheet.recommendationSummary?.let { value ->
                SettingsRow(title = stringResource(R.string.home_diagnostics_recommendation_label), value = value)
            }
            if (sheet.appliedSettings.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.home_diagnostics_applied_settings_label),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                sheet.appliedSettings.forEach { applied ->
                    SettingsRow(title = applied.label, value = applied.value)
                }
            } else {
                Text(
                    text = stringResource(R.string.home_diagnostics_no_settings_applied),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            if (sheet.stageSummaries.isNotEmpty()) {
                val recommendationMarker = stringResource(R.string.home_diagnostics_stage_recommendation_marker)
                val failedMarker = stringResource(R.string.home_diagnostics_stage_failed_marker)
                Text(
                    text = stringResource(R.string.home_diagnostics_stage_results_label),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                sheet.stageSummaries.forEach { stage ->
                    SettingsRow(
                        title = stage.label,
                        value =
                            buildString {
                                append(stage.summary)
                                if (stage.recommendationContributor) {
                                    append(" · ")
                                    append(recommendationMarker)
                                }
                                if (stage.failed) {
                                    append(" · ")
                                    append(failedMarker)
                                }
                            },
                    )
                }
            }
        }
    }

    uiState.homeDiagnostics.verificationSheet?.let { sheet ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissVerificationSheet,
            title = stringResource(R.string.home_diagnostics_verified_sheet_title),
            message = sheet.headline,
            icon = if (sheet.success) RipDpiIcons.Connected else RipDpiIcons.Warning,
            testTag = RipDpiTestTags.HomeDiagnosticsVerificationSheet,
            primaryAction =
                RipDpiSheetAction(
                    label = stringResource(R.string.home_diagnostics_open_diagnostics_action),
                    onClick = {
                        onDismissVerificationSheet()
                        onOpenDiagnostics()
                    },
                    testTag = RipDpiTestTags.HomeDiagnosticsVerificationOpenDiagnosticsAction,
                ),
        ) {
            Text(
                text = sheet.summary,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            sheet.detail?.let { detail ->
                Text(
                    text = detail,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun HomeStatusCard(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
) {
    TrackRecomposition("HomeStatusCard")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        variant =
            if (uiState.connectionState == ConnectionState.Connected) {
                RipDpiCardVariant.Status
            } else if (uiState.connectionState == ConnectionState.Connecting) {
                RipDpiCardVariant.Elevated
            } else {
                RipDpiCardVariant.Outlined
            },
    ) {
        Text(
            text = stringResource(R.string.home_status_section),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        StatusIndicator(
            label = homeStatusLabel(uiState.connectionState),
            tone = homeIndicatorTone(uiState.connectionState),
        )
        Text(
            text = homeHeadline(uiState.connectionState),
            style = type.screenTitle,
            color = colors.foreground,
        )
        if (uiState.connectionState == ConnectionState.Disconnected && uiState.approachSummary != null) {
            Text(
                text = uiState.approachSummary.title,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        } else if (uiState.connectionState != ConnectionState.Disconnected) {
            Text(
                text = homeSupportingCopy(uiState),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
        HomeConnectionButton(
            state = uiState.connectionState,
            label = homePrimaryActionLabel(uiState),
            modeLabel = homeModeLabel(currentMode(uiState)),
            onClick = onToggleConnection,
        )
    }
}

@Composable
private fun HomeConnectionButton(
    state: ConnectionState,
    label: String,
    modeLabel: String,
    onClick: () -> Unit,
) {
    TrackRecomposition("HomeConnectionButton")
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type
    val scheme = MaterialTheme.colorScheme
    val homeChrome = rememberHomeChromeMetrics()
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val performHaptic = rememberRipDpiHapticPerformer()
    val connectionStateDescription = "${homeStatusLabel(state)}, $modeLabel"
    val pressScale by animateFloatAsState(
        targetValue =
            if (isPressed && state != ConnectionState.Connecting) {
                0.94f
            } else {
                1f
            },
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "homeConnectionPressScale",
    )
    val buttonScale = remember { Animatable(1f) }
    val haloScale = remember { Animatable(1f) }
    val shakeOffset = remember { Animatable(0f) }
    val previousState = remember { mutableStateOf(state) }
    val shakeDistance =
        with(density) {
            12.dp.toPx()
        }

    val containerColor =
        remember(state, colors, scheme) {
            when (state) {
                ConnectionState.Connected,
                ConnectionState.Connecting,
                -> colors.foreground

                ConnectionState.Disconnected,
                ConnectionState.Error,
                -> scheme.surface
            }
        }
    val contentColor =
        remember(state, colors) {
            when (state) {
                ConnectionState.Connected,
                ConnectionState.Connecting,
                -> colors.background

                ConnectionState.Disconnected,
                ConnectionState.Error,
                -> colors.foreground
            }
        }
    val haloColor =
        remember(state, colors) {
            when (state) {
                ConnectionState.Connected -> colors.foreground.copy(alpha = 0.08f)
                ConnectionState.Connecting -> colors.foreground.copy(alpha = 0.14f)
                ConnectionState.Disconnected -> colors.accent
                ConnectionState.Error -> colors.destructive.copy(alpha = 0.12f)
            }
        }
    val borderColor =
        remember(state, colors) {
            when (state) {
                ConnectionState.Connected,
                ConnectionState.Connecting,
                -> Color.Transparent

                ConnectionState.Disconnected -> colors.cardBorder

                ConnectionState.Error -> colors.destructive
            }
        }
    val icon =
        remember(state) {
            when (state) {
                ConnectionState.Connected -> RipDpiIcons.Connected
                ConnectionState.Connecting -> RipDpiIcons.Vpn
                ConnectionState.Disconnected -> RipDpiIcons.Offline
                ConnectionState.Error -> RipDpiIcons.Warning
            }
        }
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "homeConnectionContainer",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "homeConnectionContent",
    )
    val animatedHaloColor by animateColorAsState(
        targetValue = haloColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "homeConnectionHalo",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "homeConnectionBorder",
    )
    val connectingPulse =
        if (state == ConnectionState.Connecting && motion.allowsInfiniteMotion) {
            rememberInfiniteTransition(label = "connectingPulse")
        } else {
            null
        }
    val connectingHaloAlpha by (
        connectingPulse?.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = motion.duration(1200), easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "connectingHaloAlpha",
        ) ?: animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
            label = "connectingHaloAlphaStatic",
        )
    )

    LaunchedEffect(state, motion.animationsEnabled) {
        val priorState = previousState.value
        previousState.value = state

        if (priorState == state) {
            return@LaunchedEffect
        }

        if (!motion.animationsEnabled) {
            buttonScale.snapTo(1f)
            haloScale.snapTo(1f)
            shakeOffset.snapTo(0f)
            return@LaunchedEffect
        }

        when (state) {
            ConnectionState.Connecting -> {
                coroutineScope {
                    launch {
                        buttonScale.snapTo(0.98f)
                        buttonScale.animateTo(
                            targetValue = 1.03f,
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                        buttonScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.snapTo(0.88f)
                        haloScale.animateTo(
                            targetValue = 1.18f,
                            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
                        )
                        haloScale.animateTo(
                            targetValue = 1.08f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        shakeOffset.snapTo(0f)
                    }
                }
            }

            ConnectionState.Connected -> {
                performHaptic(RipDpiHapticFeedback.Success)
                coroutineScope {
                    launch {
                        buttonScale.snapTo(1.08f)
                        buttonScale.animateTo(
                            targetValue = motion.selectionScale,
                            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.snapTo(1.08f)
                        haloScale.animateTo(
                            targetValue = 1.22f,
                            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
                        )
                        haloScale.animateTo(
                            targetValue = 1.02f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        shakeOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                    }
                }
            }

            ConnectionState.Error -> {
                performHaptic(RipDpiHapticFeedback.Error)
                coroutineScope {
                    launch {
                        buttonScale.snapTo(0.95f)
                        buttonScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.snapTo(1.04f)
                        haloScale.animateTo(
                            targetValue = 1.1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                        haloScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        shakeOffset.snapTo(0f)
                        val totalDuration = motion.duration(285)
                        shakeOffset.animateTo(
                            targetValue = 0f,
                            animationSpec =
                                keyframes {
                                    durationMillis = totalDuration
                                    -shakeDistance at (totalDuration * 50 / 285)
                                    (shakeDistance * 0.8f) at (totalDuration * 110 / 285)
                                    (-shakeDistance * 0.5f) at (totalDuration * 165 / 285)
                                    (shakeDistance * 0.25f) at (totalDuration * 215 / 285)
                                },
                        )
                    }
                }
            }

            ConnectionState.Disconnected -> {
                if (priorState == ConnectionState.Connected || priorState == ConnectionState.Connecting) {
                    performHaptic(RipDpiHapticFeedback.Toggle)
                }
                coroutineScope {
                    launch {
                        buttonScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        shakeOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(homeChrome.connectionHaloSize)
                    .graphicsLayer {
                        scaleX = haloScale.value
                        scaleY = haloScale.value
                        alpha = connectingHaloAlpha
                        translationX = shakeOffset.value * 0.2f
                    }.background(animatedHaloColor, CircleShape),
        )
        Column(
            modifier =
                Modifier
                    .ripDpiTestTag(RipDpiTestTags.HomeConnectionButton)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = connectionStateDescription
                    }.size(homeChrome.connectionButtonSize)
                    .graphicsLayer {
                        scaleX = buttonScale.value * pressScale
                        scaleY = buttonScale.value * pressScale
                        translationX = shakeOffset.value
                    }.background(animatedContainerColor, CircleShape)
                    .border(width = 1.dp, color = animatedBorderColor, shape = CircleShape)
                    .clip(CircleShape)
                    .ripDpiClickable(
                        enabled = state != ConnectionState.Connecting,
                        role = androidx.compose.ui.semantics.Role.Button,
                        interactionSource = interactionSource,
                        hapticFeedback =
                            when (state) {
                                ConnectionState.Connected -> RipDpiHapticFeedback.Toggle

                                ConnectionState.Connecting -> RipDpiHapticFeedback.None

                                ConnectionState.Disconnected,
                                ConnectionState.Error,
                                -> RipDpiHapticFeedback.Action
                            },
                        onClick = onClick,
                    ).padding(
                        horizontal = homeChrome.connectionHorizontalPadding,
                        vertical = homeChrome.connectionVerticalPadding,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (
                        fadeIn(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        ) + scaleIn(initialScale = 0.88f)
                    ) togetherWith (
                        fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        ) + scaleOut(targetScale = 0.88f)
                    )
                },
                label = "homeConnectionIcon",
            ) { currentIcon ->
                Icon(
                    imageVector = currentIcon,
                    contentDescription = null,
                    tint = animatedContentColor,
                    modifier = Modifier.size(homeChrome.connectionIconSize),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith
                        fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                },
                label = "homeConnectionLabel",
            ) { currentLabel ->
                Text(
                    text = currentLabel,
                    style = type.bodyEmphasis,
                    color = animatedContentColor,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            AnimatedContent(
                targetState = modeLabel,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    ) togetherWith
                        fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                },
                label = "homeConnectionModeLabel",
            ) { currentModeLabel ->
                Text(
                    text = currentModeLabel,
                    style = type.caption,
                    color = animatedContentColor.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HomeStatsGrid(uiState: MainUiState) {
    TrackRecomposition("HomeStatsGrid")
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val resolvedMode = currentMode(uiState)
    val formattedDuration =
        remember(uiState.connectionDuration) {
            formatConnectionDuration(uiState.connectionDuration)
        }
    val formattedTraffic =
        remember(uiState.dataTransferred) {
            Formatter.formatShortFileSize(context, uiState.dataTransferred)
        }
    val routeValue =
        when (resolvedMode) {
            Mode.VPN -> stringResource(R.string.home_route_local)
            Mode.Proxy -> stringResource(R.string.proxy_address, uiState.proxyIp, uiState.proxyPort)
        }

    Column(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeStatsGrid),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_duration),
                value = formattedDuration,
            )
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_traffic),
                value = formattedTraffic,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_mode),
                value = homeModeLabel(resolvedMode),
            )
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_route),
                value = routeValue,
            )
        }
    }
}

@Composable
private fun HomeStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = label,
                style = type.smallLabel,
                color = colors.mutedForeground,
            )
            Text(
                text = value,
                style = type.monoValue,
                color = colors.foreground,
            )
        }
    }
}

@Composable
private fun homeStatusLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Disconnected -> stringResource(R.string.vpn_disconnected)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting)
        ConnectionState.Connected -> stringResource(R.string.vpn_connected)
        ConnectionState.Error -> stringResource(R.string.home_status_attention)
    }

private fun homeIndicatorTone(state: ConnectionState): StatusIndicatorTone =
    when (state) {
        ConnectionState.Disconnected -> StatusIndicatorTone.Idle
        ConnectionState.Connecting -> StatusIndicatorTone.Warning
        ConnectionState.Connected -> StatusIndicatorTone.Active
        ConnectionState.Error -> StatusIndicatorTone.Error
    }

@Composable
private fun homeHeadline(state: ConnectionState): String =
    when (state) {
        ConnectionState.Disconnected -> stringResource(R.string.home_status_disconnected_title)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting_title)
        ConnectionState.Connected -> stringResource(R.string.home_status_connected_title)
        ConnectionState.Error -> stringResource(R.string.home_status_error_title)
    }

@Composable
private fun homeSupportingCopy(uiState: MainUiState): String =
    when (uiState.connectionState) {
        ConnectionState.Disconnected -> stringResource(R.string.home_status_disconnected_body)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting_body)
        ConnectionState.Connected -> stringResource(R.string.home_status_connected_body)
        ConnectionState.Error -> stringResource(R.string.home_status_error_body)
    }

@Composable
private fun homePrimaryActionLabel(uiState: MainUiState): String =
    when (uiState.connectionState) {
        ConnectionState.Connecting -> {
            stringResource(R.string.home_connection_button_connecting)
        }

        ConnectionState.Connected -> {
            when (uiState.activeMode) {
                Mode.VPN -> stringResource(R.string.vpn_disconnect)
                Mode.Proxy -> stringResource(R.string.proxy_stop)
            }
        }

        ConnectionState.Disconnected,
        ConnectionState.Error,
        -> {
            when (uiState.configuredMode) {
                Mode.VPN -> stringResource(R.string.vpn_connect)
                Mode.Proxy -> stringResource(R.string.proxy_start)
            }
        }
    }

@Composable
private fun homeModeLabel(mode: Mode): String =
    when (mode) {
        Mode.VPN -> stringResource(R.string.home_mode_vpn)
        Mode.Proxy -> stringResource(R.string.home_mode_proxy)
    }

private fun currentMode(uiState: MainUiState): Mode =
    if (uiState.connectionState == ConnectionState.Connected) {
        uiState.activeMode
    } else {
        uiState.configuredMode
    }

private fun formatConnectionDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

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
