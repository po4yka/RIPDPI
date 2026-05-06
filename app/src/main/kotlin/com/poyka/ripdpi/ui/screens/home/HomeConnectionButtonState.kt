package com.poyka.ripdpi.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val haloAlphaConnected = 0.08f
private const val haloAlphaConnecting = 0.14f
private const val haloAlphaError = 0.12f
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

@Stable
internal data class HomeConnectionButtonVisuals(
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val haloColor: Color,
    val borderColor: Color,
)

@Stable
internal data class HomeConnectionButtonMotionState(
    val buttonScale: State<Float>,
    val haloScale: State<Float>,
    val shakeOffset: State<Float>,
    val pressScale: Float,
    val connectingHaloAlpha: Float,
)

@Composable
internal fun rememberHomeConnectionButtonVisuals(state: ConnectionState): HomeConnectionButtonVisuals {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val scheme = MaterialTheme.colorScheme
    val targetVisuals =
        remember(state, colors, scheme) {
            HomeConnectionButtonVisuals(
                icon = state.homeConnectionIcon(),
                containerColor =
                    when (state) {
                        ConnectionState.Connected, ConnectionState.Connecting -> colors.foreground
                        ConnectionState.Disconnected, ConnectionState.Error -> scheme.surface
                    },
                contentColor =
                    when (state) {
                        ConnectionState.Connected, ConnectionState.Connecting -> colors.background
                        ConnectionState.Disconnected, ConnectionState.Error -> colors.foreground
                    },
                haloColor =
                    when (state) {
                        ConnectionState.Connected -> colors.foreground.copy(alpha = haloAlphaConnected)
                        ConnectionState.Connecting -> colors.foreground.copy(alpha = haloAlphaConnecting)
                        ConnectionState.Disconnected -> colors.accent
                        ConnectionState.Error -> colors.destructive.copy(alpha = haloAlphaError)
                    },
                borderColor =
                    when (state) {
                        ConnectionState.Connected, ConnectionState.Connecting -> Color.Transparent
                        ConnectionState.Disconnected -> colors.cardBorder
                        ConnectionState.Error -> colors.destructive
                    },
            )
        }
    val containerColor by animateColorAsState(
        targetValue = targetVisuals.containerColor,
        animationSpec = motion.stateTween(),
        label = "homeConnectionContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = targetVisuals.contentColor,
        animationSpec = motion.stateTween(),
        label = "homeConnectionContent",
    )
    val haloColor by animateColorAsState(
        targetValue = targetVisuals.haloColor,
        animationSpec = motion.stateTween(),
        label = "homeConnectionHalo",
    )
    val borderColor by animateColorAsState(
        targetValue = targetVisuals.borderColor,
        animationSpec = motion.quickTween(),
        label = "homeConnectionBorder",
    )
    return targetVisuals.copy(
        containerColor = containerColor,
        contentColor = contentColor,
        haloColor = haloColor,
        borderColor = borderColor,
    )
}

@Composable
internal fun rememberHomeConnectionButtonMotionState(
    state: ConnectionState,
    isPressed: Boolean,
): HomeConnectionButtonMotionState {
    val motion = RipDpiThemeTokens.motion
    val density = LocalDensity.current
    val buttonScale = remember { Animatable(1f) }
    val haloScale = remember { Animatable(1f) }
    val shakeOffset = remember { Animatable(0f) }
    val previousState = remember { mutableStateOf(state) }
    val performHaptic = rememberRipDpiHapticPerformer()
    val shakeDistance = with(density) { shakeDistanceDp.dp.toPx() }
    val pressScale by animateFloatAsState(
        targetValue =
            if (isPressed && state != ConnectionState.Connecting) {
                0.94f
            } else {
                1f
            },
        animationSpec = motion.quickTween(),
        label = "homeConnectionPressScale",
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
                        motion.durationTween(
                            baseDurationMillis = connectingPulseDurationMs,
                            easing = LinearEasing,
                        ),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "connectingHaloAlpha",
        ) ?: animateFloatAsState(
            targetValue = 1f,
            animationSpec = motion.stateTween(),
            label = "connectingHaloAlphaStatic",
        )
    )

    LaunchedEffect(state, motion.animationsEnabled) {
        val priorState = previousState.value
        previousState.value = state
        animateHomeConnectionState(
            state = state,
            priorState = priorState,
            motion = motion,
            buttonScale = buttonScale,
            haloScale = haloScale,
            shakeOffset = shakeOffset,
            shakeDistance = shakeDistance,
            performHaptic = performHaptic,
        )
    }

    return HomeConnectionButtonMotionState(
        buttonScale = buttonScale.asState(),
        haloScale = haloScale.asState(),
        shakeOffset = shakeOffset.asState(),
        pressScale = pressScale,
        connectingHaloAlpha = connectingHaloAlpha,
    )
}

private suspend fun animateHomeConnectionState(
    state: ConnectionState,
    priorState: ConnectionState,
    motion: RipDpiMotion,
    buttonScale: Animatable<Float, *>,
    haloScale: Animatable<Float, *>,
    shakeOffset: Animatable<Float, *>,
    shakeDistance: Float,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    if (priorState == state) return

    if (!motion.animationsEnabled) {
        buttonScale.snapTo(1f)
        haloScale.snapTo(1f)
        shakeOffset.snapTo(0f)
        return
    }

    when (state) {
        ConnectionState.Connecting -> {
            animateConnecting(motion, buttonScale, haloScale, shakeOffset)
        }

        ConnectionState.Connected -> {
            animateConnected(motion, buttonScale, haloScale, shakeOffset, performHaptic)
        }

        ConnectionState.Error -> {
            animateConnectionError(
                motion = motion,
                buttonScale = buttonScale,
                haloScale = haloScale,
                shakeOffset = shakeOffset,
                shakeDistance = shakeDistance,
                performHaptic = performHaptic,
            )
        }

        ConnectionState.Disconnected -> {
            animateDisconnected(
                motion = motion,
                priorState = priorState,
                buttonScale = buttonScale,
                haloScale = haloScale,
                shakeOffset = shakeOffset,
                performHaptic = performHaptic,
            )
        }
    }
}

private suspend fun animateConnecting(
    motion: RipDpiMotion,
    buttonScale: Animatable<Float, *>,
    haloScale: Animatable<Float, *>,
    shakeOffset: Animatable<Float, *>,
) {
    coroutineScope {
        launch {
            buttonScale.snapTo(buttonScaleConnectingInitial)
            buttonScale.animateTo(buttonScaleConnectingTarget, animationSpec = motion.quickTween())
            buttonScale.animateTo(1f, animationSpec = motion.stateTween())
        }
        launch {
            haloScale.snapTo(haloScaleConnectingInitial)
            haloScale.animateTo(haloScaleConnectingTarget, animationSpec = motion.emphasizedTween())
            haloScale.animateTo(haloScaleConnectingSettle, animationSpec = motion.stateTween())
        }
        launch { shakeOffset.snapTo(0f) }
    }
}

private suspend fun animateConnected(
    motion: RipDpiMotion,
    buttonScale: Animatable<Float, *>,
    haloScale: Animatable<Float, *>,
    shakeOffset: Animatable<Float, *>,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    performHaptic(RipDpiHapticFeedback.Success)
    coroutineScope {
        launch {
            buttonScale.snapTo(buttonScaleConnectedInitial)
            buttonScale.animateTo(motion.selectionScale, animationSpec = motion.emphasizedTween())
        }
        launch {
            haloScale.snapTo(haloScaleConnectedInitial)
            haloScale.animateTo(haloScaleConnectedTarget, animationSpec = motion.emphasizedTween())
            haloScale.animateTo(haloScaleConnectedSettle, animationSpec = motion.stateTween())
        }
        launch { shakeOffset.animateTo(0f, animationSpec = motion.quickTween()) }
    }
}

private suspend fun animateConnectionError(
    motion: RipDpiMotion,
    buttonScale: Animatable<Float, *>,
    haloScale: Animatable<Float, *>,
    shakeOffset: Animatable<Float, *>,
    shakeDistance: Float,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    performHaptic(RipDpiHapticFeedback.Error)
    coroutineScope {
        launch {
            buttonScale.snapTo(buttonScaleErrorInitial)
            buttonScale.animateTo(1f, animationSpec = motion.stateTween())
        }
        launch {
            haloScale.snapTo(haloScaleErrorInitial)
            haloScale.animateTo(haloScaleErrorTarget, animationSpec = motion.quickTween())
            haloScale.animateTo(1f, animationSpec = motion.stateTween())
        }
        launch { animateShake(motion, shakeOffset, shakeDistance) }
    }
}

private suspend fun animateDisconnected(
    motion: RipDpiMotion,
    priorState: ConnectionState,
    buttonScale: Animatable<Float, *>,
    haloScale: Animatable<Float, *>,
    shakeOffset: Animatable<Float, *>,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    if (priorState == ConnectionState.Connected || priorState == ConnectionState.Connecting) {
        performHaptic(RipDpiHapticFeedback.Toggle)
    }
    coroutineScope {
        launch { buttonScale.animateTo(1f, animationSpec = motion.stateTween()) }
        launch { haloScale.animateTo(1f, animationSpec = motion.stateTween()) }
        launch { shakeOffset.animateTo(0f, animationSpec = motion.quickTween()) }
    }
}

private suspend fun animateShake(
    motion: RipDpiMotion,
    shakeOffset: Animatable<Float, *>,
    shakeDistance: Float,
) {
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

private fun ConnectionState.homeConnectionIcon(): ImageVector =
    when (this) {
        ConnectionState.Connected -> RipDpiIcons.Connected
        ConnectionState.Connecting -> RipDpiIcons.Vpn
        ConnectionState.Disconnected -> RipDpiIcons.Offline
        ConnectionState.Error -> RipDpiIcons.Warning
    }
