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
import androidx.compose.foundation.horizontalScroll
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
import com.poyka.ripdpi.activities.launchDiagnosticsExport
import com.poyka.ripdpi.core.detection.AutoTuneFix
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.MethodologyVersion
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
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
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val ProtanopiaUnlockTapCount = 10

@Composable
internal fun DetectionCheckRoute(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
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
            onOpenSettings = onOpenSettings,
            onDismissOnboarding = remember(viewModel) { viewModel::dismissOnboarding },
            onApplyFixes = remember(viewModel) { viewModel::applyAllFixes },
            onPrivacyModeChange = remember(viewModel) { viewModel.setPrivacyModeEnabled },
            onCdnPullingChange = remember(viewModel) { viewModel.setCdnPullingEnabled },
            onDebugModeChange = remember(viewModel) { viewModel.setDebugModeEnabled },
            onColorVisionModeChange = remember(viewModel) { viewModel.setColorVisionMode },
            onUnlockProtanopiaVariant = remember(viewModel) { viewModel.unlockProtanopiaVariant },
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
    onOpenSettings: () -> Unit = {},
    onDismissOnboarding: () -> Unit,
    onApplyFixes: () -> Unit,
    onPrivacyModeChange: (Boolean) -> Unit,
    onCdnPullingChange: (Boolean) -> Unit = {},
    onDebugModeChange: (Boolean) -> Unit = {},
    onColorVisionModeChange: (DetectionColorVisionMode) -> Unit = {},
    onUnlockProtanopiaVariant: () -> Unit = {},
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
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.DetectionCheck)),
        topBar = {
            com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar(
                title = stringResource(R.string.title_detection_check),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
                actions = {
                    RipDpiIconButton(
                        icon = RipDpiIcons.Settings,
                        contentDescription = stringResource(R.string.title_detection_settings),
                        onClick = onOpenSettings,
                    )
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
                onPrivacyModeChange = onPrivacyModeChange,
                onCdnPullingChange = onCdnPullingChange,
                onDebugModeChange = onDebugModeChange,
                onColorVisionModeChange = onColorVisionModeChange,
                onUnlockProtanopiaVariant = onUnlockProtanopiaVariant,
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
    onPrivacyModeChange: (Boolean) -> Unit,
    onCdnPullingChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onColorVisionModeChange: (DetectionColorVisionMode) -> Unit,
    onUnlockProtanopiaVariant: () -> Unit,
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
        DetectionCheckSettingsControls(
            uiState = uiState,
            onPrivacyModeChange = onPrivacyModeChange,
            onCdnPullingChange = onCdnPullingChange,
            onDebugModeChange = onDebugModeChange,
            onColorVisionModeChange = onColorVisionModeChange,
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
            narrative = uiState.narrative,
            stealthScore = uiState.stealthScore,
            stealthLabel = uiState.stealthLabel,
            autoTuneFixes = uiState.suggestedFixes,
            recommendations = uiState.recommendations,
            reportText = uiState.reportText,
            debugReportText = uiState.debugReportText,
            privacyModeEnabled = uiState.privacyModeEnabled,
            colorVisionMode = uiState.colorVisionMode,
            protanopiaVariantUnlocked = uiState.redGreenAltEnabled,
            onApplyFixes = onApplyFixes,
            onUnlockProtanopiaVariant = onUnlockProtanopiaVariant,
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
private fun DetectionCheckSettingsControls(
    uiState: DetectionCheckUiState,
    onPrivacyModeChange: (Boolean) -> Unit,
    onCdnPullingChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onColorVisionModeChange: (DetectionColorVisionMode) -> Unit,
) {
    DetectionPrivacyModeToggle(
        enabled = uiState.privacyModeEnabled,
        onEnabledChange = onPrivacyModeChange,
    )
    DetectionCdnPullingToggle(
        enabled = uiState.cdnPullingEnabled,
        onEnabledChange = onCdnPullingChange,
        controlEnabled = !uiState.isRunning,
    )
    DetectionDebugModeToggle(
        enabled = uiState.debugModeEnabled,
        onEnabledChange = onDebugModeChange,
        controlEnabled = !uiState.isRunning,
    )
    DetectionTlsKeylogWarning(path = uiState.tlsKeylogWarningPath)
    DetectionColorVisionControls(
        selectedMode = uiState.colorVisionMode,
        protanopiaVariantUnlocked = uiState.redGreenAltEnabled,
        onModeChange = onColorVisionModeChange,
        controlEnabled = !uiState.isRunning,
    )
}

@Composable
private fun DetectionTlsKeylogWarning(path: String?) {
    if (path.isNullOrBlank()) return

    WarningBanner(
        title = stringResource(R.string.detection_tls_keylog_warning_title),
        message = stringResource(R.string.detection_tls_keylog_warning_message, path),
        tone = WarningBannerTone.Restricted,
    )
}

@Composable
private fun DetectionColorVisionControls(
    selectedMode: DetectionColorVisionMode,
    protanopiaVariantUnlocked: Boolean,
    onModeChange: (DetectionColorVisionMode) -> Unit,
    controlEnabled: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.detection_status_visuals_title),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetectionColorVisionMode.entries.forEach { mode ->
                RipDpiChip(
                    text = mode.displayLabel(),
                    selected = selectedMode == mode,
                    enabled = controlEnabled,
                    onClick = { onModeChange(mode) },
                )
            }
        }
        StatusVisualPreviewRow(mode = selectedMode)
        if (protanopiaVariantUnlocked) {
            Text(
                text = stringResource(R.string.detection_protanopia_unlocked_message),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun DetectionPrivacyModeToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    RipDpiSwitch(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        label = stringResource(R.string.detection_privacy_mode),
    )
}

@Composable
private fun DetectionCdnPullingToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    controlEnabled: Boolean,
) {
    RipDpiSwitch(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        enabled = controlEnabled,
        label = stringResource(R.string.detection_cdn_trace_mitm_label),
    )
}

@Composable
private fun DetectionDebugModeToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    controlEnabled: Boolean,
) {
    RipDpiSwitch(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        enabled = controlEnabled,
        label = stringResource(R.string.detection_debug_diagnostics_label),
    )
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
    narrative: com.poyka.ripdpi.core.detection.VerdictNarrative?,
    stealthScore: Int?,
    stealthLabel: String?,
    autoTuneFixes: List<AutoTuneFix>,
    recommendations: List<Recommendation>,
    reportText: String?,
    debugReportText: String?,
    privacyModeEnabled: Boolean,
    colorVisionMode: DetectionColorVisionMode,
    protanopiaVariantUnlocked: Boolean,
    onApplyFixes: () -> Unit,
    onUnlockProtanopiaVariant: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    result?.let {
        LaunchedEffect(it.verdict) {
            when (it.verdict) {
                Verdict.NOT_DETECTED -> performHaptic(RipDpiHapticFeedback.Success)
                Verdict.NEEDS_REVIEW -> performHaptic(RipDpiHapticFeedback.Acknowledge)
                Verdict.DETECTED -> performHaptic(RipDpiHapticFeedback.Error)
            }
        }

        DetectionNarrativeSummary(
            result = it,
            narrative = narrative,
            stealthScore = stealthScore,
            stealthLabel = stealthLabel,
            privacyModeEnabled = privacyModeEnabled,
            colorVisionMode = colorVisionMode,
            protanopiaVariantUnlocked = protanopiaVariantUnlocked,
            onUnlockProtanopiaVariant = onUnlockProtanopiaVariant,
        )

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

        DetectionReportActions(
            result = it,
            reportText = reportText,
            debugReportText = debugReportText,
            privacyModeEnabled = privacyModeEnabled,
            performHaptic = performHaptic,
        )

        DetectionCategoryCards(
            result = it,
            privacyModeEnabled = privacyModeEnabled,
            colorVisionMode = colorVisionMode,
        )
    }
}

@Composable
private fun DetectionReportActions(
    result: DetectionCheckResult,
    reportText: String?,
    debugReportText: String?,
    privacyModeEnabled: Boolean,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    val context = LocalContext.current
    val spacing = RipDpiThemeTokens.spacing
    val diagnosticsClipLabel = stringResource(R.string.clipboard_label_detection_diagnostics)
    var exportDialogVisible by rememberSaveable { mutableStateOf(false) }
    var exportFailureCode by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        DetectionCopyReportButton(
            reportText = reportText,
            context = context,
            performHaptic = performHaptic,
            modifier = Modifier.weight(1f),
        )
        DetectionShareReportButton(
            onClick = {
                performHaptic(RipDpiHapticFeedback.Acknowledge)
                exportDialogVisible = true
            },
            modifier = Modifier.weight(1f),
        )
    }
    DetectionExportFormatDialog(
        visible = exportDialogVisible,
        result = result,
        privacyModeEnabled = privacyModeEnabled,
        debugModeEnabled = debugReportText != null,
        onDismiss = { exportDialogVisible = false },
        context = context,
        performHaptic = performHaptic,
        onExportFailure = { code -> exportFailureCode = code },
    )
    DetectionExportFailureDialog(
        supportCode = exportFailureCode,
        context = context,
        onDismiss = { exportFailureCode = null },
    )
    debugReportText?.let { text ->
        val diagnosticsClipLabel = stringResource(R.string.clipboard_label_detection_diagnostics)
        RipDpiButton(
            text = stringResource(R.string.detection_check_copy_diagnostics),
            onClick = {
                performHaptic(RipDpiHapticFeedback.Acknowledge)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(diagnosticsClipLabel, text),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Outline,
        )
    }
}

@Composable
private fun DetectionCopyReportButton(
    reportText: String?,
    context: Context,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiButton(
        text = stringResource(R.string.detection_check_copy),
        onClick = {
            performHaptic(RipDpiHapticFeedback.Acknowledge)
            reportText?.let { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.clipboard_label_detection_report), text),
                )
            }
        },
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.DetectionCopy),
        variant = RipDpiButtonVariant.Outline,
    )
}

@Composable
private fun DetectionShareReportButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiButton(
        text = stringResource(R.string.detection_check_share),
        onClick = onClick,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.DetectionShare),
        variant = RipDpiButtonVariant.Outline,
    )
}

@Composable
private fun DetectionExportFormatDialog(
    visible: Boolean,
    result: DetectionCheckResult,
    privacyModeEnabled: Boolean,
    debugModeEnabled: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onExportFailure: (String) -> Unit,
) {
    if (!visible) return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.detection_check_share_dialog_title),
        dismissAction = RipDpiDialogAction(label = stringResource(R.string.action_dismiss), onClick = onDismiss),
    ) {
        RipDpiButton(
            text = stringResource(R.string.detection_check_export_format_markdown),
            onClick = {
                performHaptic(RipDpiHapticFeedback.Acknowledge)
                shareDetectionExport(
                    context,
                    result,
                    privacyModeEnabled,
                    DetectionExportFormat.MARKDOWN,
                    onExportFailure,
                )
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        RipDpiButton(
            text = stringResource(R.string.detection_check_export_format_json),
            onClick = {
                performHaptic(RipDpiHapticFeedback.Acknowledge)
                shareDetectionExport(
                    context,
                    result,
                    privacyModeEnabled,
                    DetectionExportFormat.JSON,
                    onExportFailure,
                )
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Outline,
        )
        if (debugModeEnabled) {
            RipDpiButton(
                text = stringResource(R.string.detection_check_copy_markdown),
                onClick = {
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                    val text =
                        DetectionExportShare.renderText(
                            result = result,
                            privacyModeEnabled = privacyModeEnabled,
                            format = DetectionExportFormat.MARKDOWN,
                        )
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            context.getString(R.string.clipboard_label_detection_markdown_export),
                            text,
                        ),
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

private fun shareDetectionExport(
    context: Context,
    result: DetectionCheckResult,
    privacyModeEnabled: Boolean,
    format: DetectionExportFormat,
    onFailure: (String) -> Unit,
) {
    when (
        val preparation =
            DetectionExportShare.prepareShareIntent(
                context = context,
                result = result,
                privacyModeEnabled = privacyModeEnabled,
                format = format,
            )
    ) {
        is DetectionExportShare.Preparation.Failed -> {
            onFailure(preparation.supportCode)
        }

        is DetectionExportShare.Preparation.Ready -> {
            val failureCode =
                launchDiagnosticsExport(Intent.createChooser(preparation.intent, null)) { intent ->
                    context.startActivity(intent)
                }
            failureCode?.let(onFailure)
        }
    }
}

@Composable
private fun DetectionExportFailureDialog(
    supportCode: String?,
    context: Context,
    onDismiss: () -> Unit,
) {
    val code = supportCode ?: return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.detection_check_export_failed),
        dismissAction = RipDpiDialogAction(label = stringResource(R.string.action_dismiss), onClick = onDismiss),
    ) {
        Text(text = code, style = RipDpiThemeTokens.type.body)
        RipDpiButton(
            text = stringResource(R.string.home_diagnostics_copy_support_code),
            onClick = {
                context
                    .getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(
                        ClipData.newPlainText(context.getString(R.string.clipboard_label_error), code),
                    )
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Outline,
        )
    }
}

@Composable
private fun DetectionNarrativeSummary(
    result: DetectionCheckResult,
    narrative: com.poyka.ripdpi.core.detection.VerdictNarrative?,
    stealthScore: Int?,
    stealthLabel: String?,
    privacyModeEnabled: Boolean,
    colorVisionMode: DetectionColorVisionMode,
    protanopiaVariantUnlocked: Boolean,
    onUnlockProtanopiaVariant: () -> Unit,
) {
    var verdictTapCount by rememberSaveable(result.verdict) { mutableStateOf(0) }
    val verdictNarrative = narrative ?: result.verdictNarrative
    VerdictScoreCard(
        result = result,
        score = stealthScore,
        label = stealthLabel,
        explanation = result.verdictExplanation,
        narrative = verdictNarrative,
        colorVisionMode = colorVisionMode,
        onHeroTap = {
            if (!protanopiaVariantUnlocked) {
                verdictTapCount += 1
                if (verdictTapCount >= ProtanopiaUnlockTapCount) {
                    onUnlockProtanopiaVariant()
                }
            }
        },
    )
    verdictNarrative?.let {
        VerdictNarrativeCard(
            narrative = it,
            privacyModeEnabled = privacyModeEnabled,
        )
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
