package com.poyka.ripdpi.ui.screens.detection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.AutoTuneFix
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.MethodologyVersion
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DetectionCheckRoute(
    onBack: () -> Unit,
    viewModel: DetectionCheckViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DetectionPermissionHandler(
        uiState = uiState,
        onPermissionsResult = remember(viewModel) { viewModel::onPermissionsResult },
    ) { onRequestPermissions ->
        DetectionCheckScreen(
            uiState = uiState,
            onStart = remember(viewModel) { viewModel::startCheck },
            onStop = remember(viewModel) { viewModel::stopCheck },
            onBack = onBack,
            onDismissOnboarding = remember(viewModel) { viewModel::dismissOnboarding },
            onApplyFixes = remember(viewModel) { viewModel::applyAllFixes },
            onReloadCommunityStats = remember(viewModel) { viewModel::reloadCommunityStats },
            onRequestPermissions = onRequestPermissions,
        )
    }
}

@Composable
private fun DetectionPermissionHandler(
    uiState: DetectionCheckUiState,
    onPermissionsResult: () -> Unit,
    content: @Composable (onRequestPermissions: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { onPermissionsResult() }

    content {
        when (uiState.permissionAction) {
            DetectionPermissionPlanner.Action.REQUEST,
            DetectionPermissionPlanner.Action.SHOW_RATIONALE,
            -> {
                permissionLauncher.launch(uiState.missingPermissions.toTypedArray())
            }

            DetectionPermissionPlanner.Action.OPEN_SETTINGS -> {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                context.startActivity(intent)
            }

            DetectionPermissionPlanner.Action.NONE -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetectionCheckScreen(
    uiState: DetectionCheckUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onApplyFixes: () -> Unit,
    onReloadCommunityStats: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val performHaptic = rememberRipDpiHapticPerformer()

    var showMethodologyDialog by rememberSaveable { mutableStateOf(false) }

    DetectionDialogHost(
        showMethodologyDialog = showMethodologyDialog,
        showOnboarding = uiState.showOnboarding,
        onDismissMethodology = { showMethodologyDialog = false },
        onDismissOnboarding = onDismissOnboarding,
        onRequestPermissions = onRequestPermissions,
    )

    RipDpiScreenScaffold(
        topBar = {
            com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar(
                title = stringResource(R.string.title_detection_check),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
                actions = {
                    RipDpiIconButton(
                        icon = RipDpiIcons.Info,
                        contentDescription = stringResource(R.string.detection_methodology_info),
                        onClick = { showMethodologyDialog = true },
                    )
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRunning,
            onRefresh = onStart,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            DetectionCheckScreenContent(
                uiState = uiState,
                onStart = onStart,
                onStop = onStop,
                onApplyFixes = onApplyFixes,
                onReloadCommunityStats = onReloadCommunityStats,
                onRequestPermissions = onRequestPermissions,
                performHaptic = performHaptic,
            )
        }
    }
}

@Composable
private fun DetectionCheckScreenContent(
    uiState: DetectionCheckUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApplyFixes: () -> Unit,
    onReloadCommunityStats: () -> Unit,
    onRequestPermissions: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = layout.horizontalPadding)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(R.string.detection_check_subtitle),
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        DetectionPermissionWarning(
            missingPermissions = uiState.missingPermissions,
            permissionAction = uiState.permissionAction,
            onRequestPermissions = onRequestPermissions,
        )
        DetectionCheckControls(
            isRunning = uiState.isRunning,
            progress = uiState.progress,
            onStart = onStart,
            onStop = onStop,
            performHaptic = performHaptic,
        )
        DetectionErrorCard(error = uiState.error, onRetry = onStart)
        DetectionResultSummary(
            result = uiState.result,
            stealthScore = uiState.stealthScore,
            stealthLabel = uiState.stealthLabel,
            autoTuneFixes = uiState.autoTuneFixes,
            recommendations = uiState.recommendations,
            reportText = uiState.reportText,
            onApplyFixes = onApplyFixes,
            performHaptic = performHaptic,
        )
        DetectionHistoryCommunitySection(
            history = uiState.history,
            hasResult = uiState.result != null,
            isRunning = uiState.isRunning,
            communityStatsLoading = uiState.communityStatsLoading,
            communityStatsError = uiState.communityStatsError,
            communityStats = uiState.communityStats,
            onReload = onReloadCommunityStats,
        )
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}

@Composable
private fun DetectionDialogHost(
    showMethodologyDialog: Boolean,
    showOnboarding: Boolean,
    onDismissMethodology: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    if (showMethodologyDialog) {
        RipDpiDialog(
            onDismissRequest = onDismissMethodology,
            title = stringResource(R.string.detection_methodology_info),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.action_dismiss),
                    onClick = onDismissMethodology,
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = MethodologyVersion.summary(),
                ),
        )
    }

    if (showOnboarding) {
        RipDpiDialog(
            onDismissRequest = onDismissOnboarding,
            title = stringResource(R.string.detection_onboarding_title),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.detection_onboarding_skip),
                    onClick = onDismissOnboarding,
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.detection_onboarding_allow),
                    onClick = {
                        onDismissOnboarding()
                        onRequestPermissions()
                    },
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.detection_onboarding_body),
                ),
        )
    }
}

@Composable
private fun DetectionPermissionWarning(
    missingPermissions: List<String>,
    permissionAction: DetectionPermissionPlanner.Action,
    onRequestPermissions: () -> Unit,
) {
    if (missingPermissions.isEmpty()) return

    WarningBanner(
        title = stringResource(R.string.detection_permission_title),
        message =
            when (permissionAction) {
                DetectionPermissionPlanner.Action.OPEN_SETTINGS -> {
                    stringResource(R.string.detection_permission_settings)
                }

                else -> {
                    stringResource(R.string.detection_permission_rationale)
                }
            },
        tone =
            when (permissionAction) {
                DetectionPermissionPlanner.Action.OPEN_SETTINGS -> WarningBannerTone.Restricted
                else -> WarningBannerTone.Info
            },
        onClick = onRequestPermissions,
    )
}

@Composable
private fun DetectionCheckControls(
    isRunning: Boolean,
    progress: com.poyka.ripdpi.core.detection.DetectionProgress?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    if (isRunning) {
        RipDpiButton(
            text = stringResource(R.string.detection_check_stop),
            onClick = {
                performHaptic(RipDpiHapticFeedback.Action)
                onStop()
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DetectionStopCheck),
            variant = RipDpiButtonVariant.Outline,
        )
        progress?.let { StageProgressCard(it) }
    } else {
        RipDpiButton(
            text = stringResource(R.string.detection_check_start),
            onClick = {
                performHaptic(RipDpiHapticFeedback.Action)
                onStart()
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DetectionRunCheck),
        )
    }
}

@Composable
private fun DetectionErrorCard(
    error: String?,
    onRetry: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    error?.let {
        RipDpiCard(variant = RipDpiCardVariant.Status) {
            Text(text = it, style = type.body, color = colors.destructive)
            RipDpiButton(
                text = stringResource(R.string.detection_error_retry),
                onClick = onRetry,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
private fun DetectionResultSummary(
    result: DetectionCheckResult?,
    stealthScore: Int?,
    stealthLabel: String?,
    autoTuneFixes: List<AutoTuneFix>,
    recommendations: List<Recommendation>,
    reportText: String?,
    onApplyFixes: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    val context = LocalContext.current
    val spacing = RipDpiThemeTokens.spacing
    result?.let {
        LaunchedEffect(it.verdict) {
            when (it.verdict) {
                Verdict.NOT_DETECTED -> performHaptic(RipDpiHapticFeedback.Success)
                Verdict.NEEDS_REVIEW -> performHaptic(RipDpiHapticFeedback.Acknowledge)
                Verdict.DETECTED -> performHaptic(RipDpiHapticFeedback.Error)
            }
        }

        VerdictScoreCard(it.verdict, stealthScore, stealthLabel)

        if (autoTuneFixes.isNotEmpty()) {
            AutoTuneCard(
                fixes = autoTuneFixes,
                onApplyAll = {
                    performHaptic(RipDpiHapticFeedback.Confirm)
                    onApplyFixes()
                },
                applyTestTag = RipDpiTestTags.DetectionApplyFixes,
            )
        }

        if (recommendations.isNotEmpty()) {
            DetectionRecommendations(recommendations)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.detection_check_copy),
                onClick = {
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                    reportText?.let { text ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Detection Report", text))
                    }
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.DetectionCopy),
                variant = RipDpiButtonVariant.Outline,
            )
            RipDpiButton(
                text = stringResource(R.string.detection_check_share),
                onClick = {
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                    reportText?.let { text ->
                        val intent =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.DetectionShare),
                variant = RipDpiButtonVariant.Outline,
            )
        }

        DetectionCategoryCards(it)
    }
}

@Composable
private fun DetectionHistoryCommunitySection(
    history: List<DetectionHistoryEntry>,
    hasResult: Boolean,
    isRunning: Boolean,
    communityStatsLoading: Boolean,
    communityStatsError: String?,
    communityStats: CommunityStats?,
    onReload: () -> Unit,
) {
    DetectionHistorySection(
        history = history,
        hasResult = hasResult,
        isRunning = isRunning,
    )
    DetectionCommunityStatsSection(
        isLoading = communityStatsLoading,
        error = communityStatsError,
        stats = communityStats,
        onReload = onReload,
    )
}

@Composable
private fun DetectionHistorySection(
    history: List<DetectionHistoryEntry>,
    hasResult: Boolean,
    isRunning: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    if (history.isNotEmpty()) {
        HistoryCard(history)
    } else if (!hasResult && !isRunning) {
        RipDpiCard(variant = RipDpiCardVariant.Outlined) {
            Text(
                text = stringResource(R.string.detection_empty_history),
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun DetectionCommunityStatsSection(
    isLoading: Boolean,
    error: String?,
    stats: CommunityStats?,
    onReload: () -> Unit,
) {
    when {
        isLoading -> {
            CommunityStatsLoadingCard()
        }

        error != null -> {
            CommunityStatsErrorCard(message = error, onRetry = onReload)
        }

        stats != null && stats.totalReports > 0 -> {
            CommunityStatsCard(stats)
        }
    }
}
