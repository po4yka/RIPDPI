package com.poyka.ripdpi.ui.screens.home

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeApproachSummaryUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
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
    onStartConfiguredMode: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUiState by rememberUpdatedState(uiState)

    HomeScreen(
        uiState = uiState,
        modifier = modifier,
        onToggleConnection =
            remember(onStartConfiguredMode, viewModel) {
                {
                    if (shouldStartConnection(currentUiState)) {
                        onStartConfiguredMode()
                    } else {
                        viewModel.onPrimaryConnectionAction()
                    }
                }
            },
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenHistory = onOpenHistory,
        onRepairPermission = viewModel::onRepairPermissionRequested,
        onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
    )
}

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type

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
                modifier = Modifier.fillMaxWidth(),
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
        } ?: uiState.permissionSummary.recommendedIssue?.let { warning ->
            WarningBanner(
                title = warning.title,
                message = warning.message,
                tone = WarningBannerTone.Warning,
                testTag = RipDpiTestTags.HomePermissionRecommendationBanner,
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
        }

        uiState.permissionSummary.backgroundGuidance?.let { guidance ->
            WarningBanner(
                title = guidance.title,
                message = guidance.message,
                tone = WarningBannerTone.Info,
                modifier = Modifier.fillMaxWidth(),
                testTag = RipDpiTestTags.HomeBackgroundGuidanceBanner,
            )
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

            uiState.approachSummary?.let { summary ->
                HomeApproachCard(
                    summary = summary,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
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
            style = RipDpiThemeTokens.type.body,
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
private fun HomeStatusCard(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
) {
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
        Text(
            text = homeSupportingCopy(uiState),
            style = type.body,
            color = colors.mutedForeground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        HomeConnectionButton(
            state = uiState.connectionState,
            label = homePrimaryActionLabel(uiState),
            modeLabel = homeModeLabel(currentMode(uiState)),
            onClick = onToggleConnection,
        )
    }
}

internal fun shouldStartConnection(uiState: MainUiState): Boolean =
    when (uiState.connectionState) {
        ConnectionState.Connecting,
        ConnectionState.Connected,
        -> false

        ConnectionState.Disconnected,
        ConnectionState.Error,
        -> uiState.appStatus == AppStatus.Halted
    }

@Composable
private fun HomeConnectionButton(
    state: ConnectionState,
    label: String,
    modeLabel: String,
    onClick: () -> Unit,
) {
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
        when (state) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            -> colors.foreground

            ConnectionState.Disconnected,
            ConnectionState.Error,
            -> scheme.surface
        }
    val contentColor =
        when (state) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            -> colors.background

            ConnectionState.Disconnected,
            ConnectionState.Error,
            -> colors.foreground
        }
    val haloColor =
        when (state) {
            ConnectionState.Connected -> colors.foreground.copy(alpha = 0.08f)
            ConnectionState.Connecting -> colors.foreground.copy(alpha = 0.14f)
            ConnectionState.Disconnected -> colors.accent
            ConnectionState.Error -> colors.destructive.copy(alpha = 0.12f)
        }
    val borderColor =
        when (state) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            -> Color.Transparent

            ConnectionState.Disconnected -> colors.cardBorder

            ConnectionState.Error -> colors.destructive
        }
    val icon =
        when (state) {
            ConnectionState.Connected -> RipDpiIcons.Connected
            ConnectionState.Connecting -> RipDpiIcons.Vpn
            ConnectionState.Disconnected -> RipDpiIcons.Offline
            ConnectionState.Error -> RipDpiIcons.Warning
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
                        shakeOffset.animateTo(
                            targetValue = -shakeDistance,
                            animationSpec = tween(durationMillis = motion.duration(50)),
                        )
                        shakeOffset.animateTo(
                            targetValue = shakeDistance * 0.8f,
                            animationSpec = tween(durationMillis = motion.duration(60)),
                        )
                        shakeOffset.animateTo(
                            targetValue = -shakeDistance * 0.5f,
                            animationSpec = tween(durationMillis = motion.duration(55)),
                        )
                        shakeOffset.animateTo(
                            targetValue = shakeDistance * 0.25f,
                            animationSpec = tween(durationMillis = motion.duration(50)),
                        )
                        shakeOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = motion.duration(70)),
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
                    style = type.monoSmall,
                    color = animatedContentColor.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HomeStatsGrid(uiState: MainUiState) {
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
