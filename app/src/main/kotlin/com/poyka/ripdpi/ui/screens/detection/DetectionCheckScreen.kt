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
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.MethodologyVersion
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
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

private const val historyEntryLimit = 5
private const val highStealthScoreThreshold = 70
private const val mediumStealthScoreThreshold = 40
private const val detectedPercentageAlertThreshold = 50
private const val percentScale = 100.0

@Composable
internal fun DetectionCheckRoute(
    onBack: () -> Unit,
    viewModel: DetectionCheckViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.onPermissionsResult() }

    DetectionCheckScreen(
        uiState = uiState,
        onStart = viewModel::startCheck,
        onStop = viewModel::stopCheck,
        onBack = onBack,
        onDismissOnboarding = viewModel::dismissOnboarding,
        onApplyFixes = viewModel::applyAllFixes,
        onRequestPermissions = {
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
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun DetectionCheckScreen(
    uiState: DetectionCheckUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onApplyFixes: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion
    val performHaptic = rememberRipDpiHapticPerformer()

    var showMethodologyDialog by rememberSaveable { mutableStateOf(false) }

    if (showMethodologyDialog) {
        RipDpiDialog(
            onDismissRequest = { showMethodologyDialog = false },
            title = stringResource(R.string.detection_methodology_info),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.action_dismiss),
                    onClick = { showMethodologyDialog = false },
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = MethodologyVersion.summary(),
                ),
        )
    }

    if (uiState.showOnboarding) {
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

                if (uiState.missingPermissions.isNotEmpty()) {
                    WarningBanner(
                        title = stringResource(R.string.detection_permission_title),
                        message =
                            when (uiState.permissionAction) {
                                DetectionPermissionPlanner.Action.OPEN_SETTINGS -> {
                                    stringResource(R.string.detection_permission_settings)
                                }

                                else -> {
                                    stringResource(R.string.detection_permission_rationale)
                                }
                            },
                        tone =
                            when (uiState.permissionAction) {
                                DetectionPermissionPlanner.Action.OPEN_SETTINGS -> WarningBannerTone.Restricted
                                else -> WarningBannerTone.Info
                            },
                        onClick = onRequestPermissions,
                    )
                }

                if (uiState.isRunning) {
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
                    uiState.progress?.let { progress -> StageProgressCard(progress) }
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

                uiState.error?.let { error ->
                    RipDpiCard(variant = RipDpiCardVariant.Status) {
                        Text(text = error, style = type.body, color = colors.destructive)
                        RipDpiButton(
                            text = stringResource(R.string.detection_error_retry),
                            onClick = onStart,
                            variant = RipDpiButtonVariant.Outline,
                        )
                    }
                }

                uiState.result?.let { result ->
                    LaunchedEffect(result.verdict) {
                        when (result.verdict) {
                            Verdict.NOT_DETECTED -> performHaptic(RipDpiHapticFeedback.Success)
                            Verdict.NEEDS_REVIEW -> performHaptic(RipDpiHapticFeedback.Acknowledge)
                            Verdict.DETECTED -> performHaptic(RipDpiHapticFeedback.Error)
                        }
                    }

                    VerdictScoreCard(result.verdict, uiState.stealthScore, uiState.stealthLabel)

                    if (uiState.autoTuneFixes.isNotEmpty()) {
                        AutoTuneCard(
                            fixes = uiState.autoTuneFixes,
                            onApplyAll = {
                                performHaptic(RipDpiHapticFeedback.Confirm)
                                onApplyFixes()
                            },
                            applyTestTag = RipDpiTestTags.DetectionApplyFixes,
                        )
                    }

                    if (uiState.recommendations.isNotEmpty()) {
                        RecommendationsCard(uiState.recommendations)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        RipDpiButton(
                            text = stringResource(R.string.detection_check_copy),
                            onClick = {
                                performHaptic(RipDpiHapticFeedback.Acknowledge)
                                uiState.reportText?.let { text ->
                                    val cb =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                    cb.setPrimaryClip(
                                        ClipData.newPlainText("Detection Report", text),
                                    )
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
                                uiState.reportText?.let { text ->
                                    val intent =
                                        Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, text)
                                    context.startActivity(
                                        Intent.createChooser(intent, null),
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .ripDpiTestTag(RipDpiTestTags.DetectionShare),
                            variant = RipDpiButtonVariant.Outline,
                        )
                    }

                    CollapsibleCategoryCards(result)
                }

                if (uiState.history.isNotEmpty()) {
                    HistoryCard(uiState.history)
                } else if (uiState.result == null && !uiState.isRunning) {
                    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
                        Text(
                            text = stringResource(R.string.detection_empty_history),
                            style = type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                }

                uiState.communityStats?.let { stats ->
                    if (stats.totalReports > 0) CommunityStatsCard(stats)
                }

                Spacer(modifier = Modifier.height(spacing.lg))
            }
        }
    }
}

@Composable
private fun StageProgressCard(progress: com.poyka.ripdpi.core.detection.DetectionProgress) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Elevated) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
            Column {
                Text(text = progress.label, style = type.bodyEmphasis, color = colors.foreground)
                Text(text = progress.detail, style = type.caption, color = colors.mutedForeground)
            }
        }
        LinearProgressIndicator(
            progress = { progress.completedStages.size.toFloat() / DetectionStage.entries.size },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.muted,
        )
    }
}

@Composable
private fun VerdictScoreCard(
    verdict: Verdict,
    score: Int?,
    label: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion

    val (verdictLabel, indicatorTone) =
        when (verdict) {
            Verdict.NOT_DETECTED -> {
                stringResource(R.string.detection_check_verdict_not_detected) to StatusIndicatorTone.Active
            }

            Verdict.NEEDS_REVIEW -> {
                stringResource(R.string.detection_check_verdict_needs_review) to StatusIndicatorTone.Warning
            }

            Verdict.DETECTED -> {
                stringResource(R.string.detection_check_verdict_detected) to StatusIndicatorTone.Error
            }
        }

    val scoreColor by animateColorAsState(
        targetValue =
            when {
                score == null -> colors.mutedForeground
                score >= 70 -> colors.success
                score >= 40 -> colors.warning
                else -> colors.destructive
            },
        animationSpec = tween(motion.stateDurationMillis),
        label = "scoreColor",
    )
    val animatedScore by animateIntAsState(
        targetValue = score ?: 0,
        animationSpec = tween(motion.emphasizedDurationMillis),
        label = "score",
    )

    RipDpiCard(
        variant = RipDpiCardVariant.Elevated,
        modifier =
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .ripDpiTestTag(RipDpiTestTags.DetectionVerdict),
    ) {
        StatusIndicator(label = verdictLabel, tone = indicatorTone)
        if (score != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.detection_stealth_score),
                        style = type.caption,
                        color = colors.mutedForeground,
                    )
                    Text(
                        text = "$animatedScore",
                        style = type.screenTitle,
                        color = scoreColor,
                    )
                }
                label?.let {
                    Text(text = it, style = type.bodyEmphasis, color = scoreColor)
                }
            }
            LinearProgressIndicator(
                progress = { StealthScore.normalizedProgress(score) },
                modifier = Modifier.fillMaxWidth(),
                color = scoreColor,
                trackColor = scoreColor.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
private fun AutoTuneCard(
    fixes: List<AutoTuneFix>,
    onApplyAll: () -> Unit,
    applyTestTag: String? = null,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        Text(
            text = stringResource(R.string.detection_auto_tune_title).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        for (fix in fixes) {
            Text(text = fix.title, style = type.body, color = colors.foreground)
        }
        RipDpiButton(
            text = stringResource(R.string.detection_auto_tune_apply),
            onClick = onApplyAll,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(applyTestTag),
        )
    }
}

@Composable
private fun RecommendationsCard(recommendations: List<Recommendation>) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.detection_check_recommendations).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        for (rec in recommendations) {
            Column {
                Text(text = rec.title, style = type.bodyEmphasis, color = colors.foreground)
                Text(text = rec.description, style = type.caption, color = colors.mutedForeground)
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun CollapsibleCategoryCards(result: DetectionCheckResult) {
    var expandedCategories by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing

    data class CategoryEntry(
        val title: String,
        val category: CategoryResult,
        val key: String,
        val icon: ImageVector,
    )

    val categories =
        buildList {
            add(
                CategoryEntry(
                    stringResource(R.string.detection_check_category_geoip),
                    result.geoIp,
                    "geoip",
                    RipDpiIcons.Public,
                ),
            )
            add(
                CategoryEntry(
                    stringResource(R.string.detection_check_category_direct),
                    result.directSigns,
                    "direct",
                    RipDpiIcons.Visibility,
                ),
            )
            add(
                CategoryEntry(
                    stringResource(R.string.detection_check_category_indirect),
                    result.indirectSigns,
                    "indirect",
                    RipDpiIcons.NetworkCheck,
                ),
            )
            add(
                CategoryEntry(
                    stringResource(R.string.detection_check_category_location),
                    result.locationSignals,
                    "location",
                    RipDpiIcons.LocationOn,
                ),
            )
            result.dnsLeak?.let {
                add(
                    CategoryEntry(
                        stringResource(R.string.detection_check_category_dns_leak),
                        it,
                        "dns",
                        RipDpiIcons.Dns,
                    ),
                )
            }
            result.webRtcLeak?.let {
                add(
                    CategoryEntry(
                        stringResource(R.string.detection_check_category_webrtc),
                        it,
                        "webrtc",
                        RipDpiIcons.Videocam,
                    ),
                )
            }
            result.tlsFingerprint?.let {
                add(
                    CategoryEntry(
                        stringResource(R.string.detection_check_category_tls),
                        it,
                        "tls",
                        RipDpiIcons.Lock,
                    ),
                )
            }
            result.timingAnalysis?.let {
                add(
                    CategoryEntry(
                        stringResource(R.string.detection_check_category_timing),
                        it,
                        "timing",
                        RipDpiIcons.Timer,
                    ),
                )
            }
        }

    CollapsibleCard(
        title = stringResource(R.string.detection_check_category_bypass),
        icon = RipDpiIcons.Shield,
        detected = result.bypassResult.detected,
        needsReview = result.bypassResult.needsReview,
        key = "bypass",
        expandedCategories = expandedCategories,
        onToggle = { expandedCategories = it },
        findings = result.bypassResult.findings,
    )

    for (entry in categories) {
        CollapsibleCard(
            title = entry.title,
            icon = entry.icon,
            detected = entry.category.detected,
            needsReview = entry.category.needsReview,
            key = entry.key,
            expandedCategories = expandedCategories,
            onToggle = { expandedCategories = it },
            findings = entry.category.findings,
        )
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    icon: ImageVector,
    detected: Boolean,
    needsReview: Boolean,
    key: String,
    expandedCategories: Set<String>,
    onToggle: (Set<String>) -> Unit,
    findings: List<Finding>,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing

    val tone =
        when {
            detected -> StatusIndicatorTone.Error
            needsReview -> StatusIndicatorTone.Warning
            else -> StatusIndicatorTone.Active
        }
    val statusLabel =
        when {
            detected -> stringResource(R.string.detection_status_detected)
            needsReview -> stringResource(R.string.detection_status_review)
            else -> stringResource(R.string.detection_status_ok)
        }
    val isExpanded = key in expandedCategories || detected || needsReview

    RipDpiCard(
        variant = RipDpiCardVariant.Outlined,
        onClick = {
            onToggle(
                if (key in expandedCategories) {
                    expandedCategories - key
                } else {
                    expandedCategories + key
                },
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.mutedForeground,
                )
                Text(text = title, style = type.bodyEmphasis)
            }
            StatusIndicator(label = statusLabel, tone = tone)
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                findings.forEach { FindingRow(it) }
            }
        }
    }
}

@Composable
private fun HistoryCard(entries: List<DetectionHistoryEntry>) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val limited = entries.take(historyEntryLimit)
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.detection_history_title).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        for ((index, entry) in limited.withIndex()) {
            val scoreColor =
                when {
                    entry.stealthScore >= highStealthScoreThreshold -> colors.success
                    entry.stealthScore >= mediumStealthScoreThreshold -> colors.warning
                    else -> colors.destructive
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.networkSummary, style = type.body, maxLines = 1)
                    Text(
                        formatTimestamp(entry.timestamp),
                        style = type.caption,
                        color = colors.mutedForeground,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index > 0) {
                        val diff = entry.stealthScore - limited[index - 1].stealthScore
                        val (icon, desc, tint) =
                            when {
                                diff > 0 -> {
                                    Triple(
                                        RipDpiIcons.KeyboardArrowUp,
                                        stringResource(R.string.detection_score_improved),
                                        colors.success,
                                    )
                                }

                                diff < 0 -> {
                                    Triple(
                                        RipDpiIcons.KeyboardArrowDown,
                                        stringResource(R.string.detection_score_degraded),
                                        colors.destructive,
                                    )
                                }

                                else -> {
                                    Triple(RipDpiIcons.Remove, null, colors.mutedForeground)
                                }
                            }
                        Icon(icon, contentDescription = desc, modifier = Modifier.size(16.dp), tint = tint)
                    }
                    Text(
                        "${entry.stealthScore}",
                        style = type.bodyEmphasis,
                        color = scoreColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityStatsCard(stats: CommunityStats) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val title =
        if (stats.isLocalOnly) {
            stringResource(R.string.detection_community_local_title)
        } else {
            stringResource(R.string.detection_community_title)
        }
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(text = title.uppercase(), style = type.sectionTitle, color = colors.mutedForeground)
        Text(
            text = stringResource(R.string.detection_community_reports, stats.totalReports),
            style = type.body,
        )
        if (stats.averageStealthScore > 0) {
            Text(
                text =
                    stringResource(
                        R.string.detection_community_avg_score,
                        stats.averageStealthScore.toInt(),
                    ),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
        val detected = stats.verdictDistribution["DETECTED"] ?: 0
        if (stats.totalReports > 0) {
            val pct = (detected * percentScale / stats.totalReports).toInt()
            Text(
                text = stringResource(R.string.detection_community_detected_pct, pct),
                style = type.bodyEmphasis,
                color = if (pct > detectedPercentageAlertThreshold) colors.destructive else colors.success,
            )
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val dotColor =
        when {
            finding.detected -> colors.destructive
            finding.needsReview -> colors.warning
            else -> colors.mutedForeground
        }
    val dotDescription =
        when {
            finding.detected -> stringResource(R.string.detection_finding_detected)
            finding.needsReview -> stringResource(R.string.detection_finding_review)
            else -> stringResource(R.string.detection_finding_ok)
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier =
                Modifier
                    .size(8.dp)
                    .semantics { contentDescription = dotDescription },
        ) {
            drawCircle(color = dotColor)
        }
        Text(
            text = finding.description,
            style = type.caption,
            color = dotColor,
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}
