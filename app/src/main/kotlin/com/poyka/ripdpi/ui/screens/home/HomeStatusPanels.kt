package com.poyka.ripdpi.ui.screens.home

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration

private const val haloAlphaConnected = 0.08f
private const val haloAlphaConnecting = 0.14f
private const val haloAlphaError = 0.12f
private const val modeLabelAlpha = 0.72f
private const val buttonScaleConnectingInitial = 0.98f
private const val buttonScaleConnectingTarget = 1.03f
private const val buttonScaleConnectedInitial = 1.08f
private const val buttonScaleErrorInitial = 0.95f
private const val haloScaleConnectingInitial = 0.88f
private const val haloScaleConnectingTarget = 1.18f
private const val haloScaleConnectingSettle = 1.08f
private const val haloScaleConnectedInitial = 1.08f
private const val haloScaleConnectedTarget = 1.22f
private const val haloScaleConnectedSettle = 1.02f
private const val haloScaleErrorInitial = 1.04f
private const val haloScaleErrorTarget = 1.1f
private const val connectingPulseAlphaTarget = 0.5f
private const val haloTranslationFraction = 0.2f
private const val iconTransitionScale = 0.88f
private const val shakeDistanceDp = 12
private const val shakeTotalDurationMs = 285
private const val shakeKeyframeT1 = 50
private const val shakeKeyframeT2 = 110
private const val shakeKeyframeT3 = 165
private const val shakeKeyframeT4 = 215
private const val shakeFractionT2 = 0.8f
private const val shakeFractionT3 = 0.5f
private const val shakeFractionT4 = 0.25f
private const val connectingPulseDurationMs = 1200
private const val connectionButtonIconSpacerDp = 12
private const val connectionButtonModeSpacerDp = 6
private const val secondsPerHour = 3_600
private const val secondsPerMinute = 60

@Composable
internal fun HomeStatusCard(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
) {
    TrackRecomposition("HomeStatusCard")
    val colors = RipDpiThemeTokens.colors
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
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun HomeConnectionButton(
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
    val shakeDistance = with(density) { shakeDistanceDp.dp.toPx() }

    val containerColor =
        remember(state, colors, scheme) {
            when (state) {
                ConnectionState.Connected, ConnectionState.Connecting -> colors.foreground
                ConnectionState.Disconnected, ConnectionState.Error -> scheme.surface
            }
        }
    val contentColor =
        remember(state, colors) {
            when (state) {
                ConnectionState.Connected, ConnectionState.Connecting -> colors.background
                ConnectionState.Disconnected, ConnectionState.Error -> colors.foreground
            }
        }
    val haloColor =
        remember(state, colors) {
            when (state) {
                ConnectionState.Connected -> colors.foreground.copy(alpha = haloAlphaConnected)
                ConnectionState.Connecting -> colors.foreground.copy(alpha = haloAlphaConnecting)
                ConnectionState.Disconnected -> colors.accent
                ConnectionState.Error -> colors.destructive.copy(alpha = haloAlphaError)
            }
        }
    val borderColor =
        remember(state, colors) {
            when (state) {
                ConnectionState.Connected, ConnectionState.Connecting -> Color.Transparent
                ConnectionState.Disconnected -> colors.cardBorder
                ConnectionState.Error -> colors.destructive
            }
        }
    val icon =
        remember(state) {
            when (state) {
                ConnectionState.Connected -> com.poyka.ripdpi.ui.theme.RipDpiIcons.Connected
                ConnectionState.Connecting -> com.poyka.ripdpi.ui.theme.RipDpiIcons.Vpn
                ConnectionState.Disconnected -> com.poyka.ripdpi.ui.theme.RipDpiIcons.Offline
                ConnectionState.Error -> com.poyka.ripdpi.ui.theme.RipDpiIcons.Warning
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
            targetValue = connectingPulseAlphaTarget,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = motion.duration(connectingPulseDurationMs),
                            easing = LinearEasing,
                        ),
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

        if (priorState == state) return@LaunchedEffect

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
                        buttonScale.snapTo(buttonScaleConnectingInitial)
                        buttonScale.animateTo(
                            targetValue = buttonScaleConnectingTarget,
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                        buttonScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.snapTo(haloScaleConnectingInitial)
                        haloScale.animateTo(
                            targetValue = haloScaleConnectingTarget,
                            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
                        )
                        haloScale.animateTo(
                            targetValue = haloScaleConnectingSettle,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch { shakeOffset.snapTo(0f) }
                }
            }

            ConnectionState.Connected -> {
                performHaptic(RipDpiHapticFeedback.Success)
                coroutineScope {
                    launch {
                        buttonScale.snapTo(buttonScaleConnectedInitial)
                        buttonScale.animateTo(
                            targetValue = motion.selectionScale,
                            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.snapTo(haloScaleConnectedInitial)
                        haloScale.animateTo(
                            targetValue = haloScaleConnectedTarget,
                            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
                        )
                        haloScale.animateTo(
                            targetValue = haloScaleConnectedSettle,
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
                        buttonScale.snapTo(buttonScaleErrorInitial)
                        buttonScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        haloScale.snapTo(haloScaleErrorInitial)
                        haloScale.animateTo(
                            targetValue = haloScaleErrorTarget,
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                        haloScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        )
                    }
                    launch {
                        shakeOffset.snapTo(0f)
                        val totalDuration = motion.duration(shakeTotalDurationMs)
                        val kf1 = totalDuration * shakeKeyframeT1 / shakeTotalDurationMs
                        val kf2 = totalDuration * shakeKeyframeT2 / shakeTotalDurationMs
                        val kf3 = totalDuration * shakeKeyframeT3 / shakeTotalDurationMs
                        val kf4 = totalDuration * shakeKeyframeT4 / shakeTotalDurationMs
                        shakeOffset.animateTo(
                            targetValue = 0f,
                            animationSpec =
                                keyframes {
                                    durationMillis = totalDuration
                                    -shakeDistance at kf1
                                    (shakeDistance * shakeFractionT2) at kf2
                                    (-shakeDistance * shakeFractionT3) at kf3
                                    (shakeDistance * shakeFractionT4) at kf4
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

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(homeChrome.connectionHaloSize)
                    .graphicsLayer {
                        scaleX = haloScale.value
                        scaleY = haloScale.value
                        alpha = connectingHaloAlpha
                        translationX = shakeOffset.value * haloTranslationFraction
                    }.background(animatedHaloColor, androidx.compose.foundation.shape.CircleShape),
        )
        Column(
            modifier =
                Modifier
                    .ripDpiTestTag(RipDpiTestTags.HomeConnectionButton)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = connectionStateDescription
                        liveRegion = LiveRegionMode.Polite
                    }.size(homeChrome.connectionButtonSize)
                    .graphicsLayer {
                        scaleX = buttonScale.value * pressScale
                        scaleY = buttonScale.value * pressScale
                        translationX = shakeOffset.value
                    }.background(animatedContainerColor, androidx.compose.foundation.shape.CircleShape)
                    .border(
                        width = 1.dp,
                        color = animatedBorderColor,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ).clip(androidx.compose.foundation.shape.CircleShape)
                    .ripDpiClickable(
                        enabled = state != ConnectionState.Connecting,
                        role = androidx.compose.ui.semantics.Role.Button,
                        interactionSource = interactionSource,
                        hapticFeedback =
                            when (state) {
                                ConnectionState.Connected -> RipDpiHapticFeedback.Toggle
                                ConnectionState.Connecting -> RipDpiHapticFeedback.None
                                ConnectionState.Disconnected, ConnectionState.Error -> RipDpiHapticFeedback.Action
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
                        fadeIn(animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis))) +
                            scaleIn(initialScale = iconTransitionScale)
                    ) togetherWith (
                        fadeOut(animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis))) +
                            scaleOut(targetScale = iconTransitionScale)
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
            Spacer(modifier = Modifier.height(connectionButtonIconSpacerDp.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)))
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
            Spacer(modifier = Modifier.height(connectionButtonModeSpacerDp.dp))
            AnimatedContent(
                targetState = modeLabel,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    ) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)))
                },
                label = "homeConnectionModeLabel",
            ) { currentModeLabel ->
                Text(
                    text = currentModeLabel,
                    style = type.caption,
                    color = animatedContentColor.copy(alpha = modeLabelAlpha),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun HomeStatsGrid(uiState: MainUiState) {
    TrackRecomposition("HomeStatsGrid")
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val resolvedMode = currentMode(uiState)
    val formattedDuration =
        remember(uiState.connectionDuration) { formatConnectionDuration(uiState.connectionDuration) }
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
        HomeStatCard(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.home_stat_quality),
            value = connectionQualityLabel(uiState.connectionState),
            valueColor = connectionQualityColor(uiState.connectionState),
        )
    }
}

@Composable
private fun connectionQualityLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Connected -> stringResource(R.string.home_quality_excellent)
        ConnectionState.Connecting -> stringResource(R.string.home_quality_connecting)
        ConnectionState.Disconnected -> stringResource(R.string.home_quality_offline)
        ConnectionState.Error -> stringResource(R.string.home_quality_offline)
    }

@Composable
private fun connectionQualityColor(state: ConnectionState): Color {
    val colors = RipDpiThemeTokens.colors
    return when (state) {
        ConnectionState.Connected -> colors.success
        ConnectionState.Connecting -> colors.warning
        ConnectionState.Disconnected -> colors.mutedForeground
        ConnectionState.Error -> colors.destructive
    }
}

@Composable
internal fun HomeStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
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
                color = valueColor ?: colors.foreground,
            )
        }
    }
}

@Composable
internal fun homeStatusLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Disconnected -> stringResource(R.string.vpn_disconnected)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting)
        ConnectionState.Connected -> stringResource(R.string.vpn_connected)
        ConnectionState.Error -> stringResource(R.string.home_status_attention)
    }

internal fun homeIndicatorTone(state: ConnectionState): StatusIndicatorTone =
    when (state) {
        ConnectionState.Disconnected -> StatusIndicatorTone.Idle
        ConnectionState.Connecting -> StatusIndicatorTone.Warning
        ConnectionState.Connected -> StatusIndicatorTone.Active
        ConnectionState.Error -> StatusIndicatorTone.Error
    }

@Composable
internal fun homeHeadline(state: ConnectionState): String =
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
internal fun homeModeLabel(mode: Mode): String =
    when (mode) {
        Mode.VPN -> stringResource(R.string.home_mode_vpn)
        Mode.Proxy -> stringResource(R.string.home_mode_proxy)
    }

internal fun currentMode(uiState: MainUiState): Mode =
    if (uiState.connectionState == ConnectionState.Connected) {
        uiState.activeMode
    } else {
        uiState.configuredMode
    }

internal fun formatConnectionDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0L)
    val hours = totalSeconds / secondsPerHour
    val minutes = (totalSeconds % secondsPerHour) / secondsPerMinute
    val seconds = totalSeconds % secondsPerMinute
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
