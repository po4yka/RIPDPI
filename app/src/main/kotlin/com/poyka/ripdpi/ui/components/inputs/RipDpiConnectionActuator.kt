package com.poyka.ripdpi.ui.components.inputs

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStateRole
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStateStyle
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.delay

private const val DisconnectConfirmationMillis = 4_000L
private const val CountdownFrameMillis = 50L
private const val CountdownSecondMillis = 1_000L
private val ConfirmationTrackHeight = 4.dp
private const val StackedRouteFontScale = 1.5f
private val ConnectionCommitKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/** Status and route information remain outside the connection action target. */
@Composable
fun RipDpiConnectionActuator(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyFaultDetail: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val resolved = state.withResolvedLabels()
    val style = RipDpiThemeTokens.state.actuator.resolve(role = state.status.toThemeRole())
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    var armedAt by remember(state.status, state.isDeactivationAvailable, state.isActivationAvailable) {
        mutableStateOf<Long?>(null)
    }
    val remainingMillis = rememberDisconnectConfirmationRemaining(armedAt) { armedAt = null }
    val confirmLabel = stringResource(R.string.home_connection_actuator_action_confirm_release)
    val actionLabel = if (armedAt != null) confirmLabel else resolved.actionLabel

    val enabled = state.isActivationAvailable || state.isDeactivationAvailable
    val performHaptic = rememberRipDpiHapticPerformer()
    val onAction: () -> Unit = {
        if (enabled) performHaptic(RipDpiHapticFeedback.Action)
        when {
            state.isActivationAvailable -> {
                onActivate()
            }

            state.isDeactivationAvailable && state.status == HomeConnectionActuatorStatus.Engaging -> {
                onDeactivate()
            }

            state.isDeactivationAvailable -> {
                val now = SystemClock.elapsedRealtime()
                val startedAt = armedAt
                if (startedAt != null && now - startedAt < DisconnectConfirmationMillis) {
                    armedAt = null
                    onDeactivate()
                } else {
                    armedAt = now
                }
            }
        }
    }

    Column(modifier = modifier) {
        ActuatorHeadline(state = resolved, stateStyle = style)
        Spacer(modifier = Modifier.height(spacing.md))
        RipDpiButton(
            text = actionLabel,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(testTag)
                    .then(rememberConnectionInputModifier(state, onAction))
                    .semantics { if (armedAt != null) liveRegion = LiveRegionMode.Polite },
            enabled = enabled,
            hapticFeedback = RipDpiHapticFeedback.None,
            variant = if (state.isActivationAvailable) RipDpiButtonVariant.Primary else RipDpiButtonVariant.Outline,
            wrapLabel = true,
            onClick = onAction,
        )
        if (remainingMillis > 0L) {
            DisconnectConfirmationCountdown(remainingMillis = remainingMillis)
        }
        AnimatedVisibility(
            visible = resolved.faultDetail.isNotEmpty(),
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            ActuatorFaultDetail(
                modifier = Modifier.padding(top = spacing.sm),
                detail = resolved.faultDetail,
                stateStyle = style,
                onCopy = onCopyFaultDetail,
            )
        }
    }
}

@Composable
private fun rememberDisconnectConfirmationRemaining(
    armedAt: Long?,
    onExpired: () -> Unit,
): Long {
    var remainingMillis by remember(armedAt) {
        mutableLongStateOf(
            armedAt?.let { (DisconnectConfirmationMillis - (SystemClock.elapsedRealtime() - it)).coerceAtLeast(0L) }
                ?: 0L,
        )
    }
    val updateMillis =
        if (RipDpiThemeTokens.motion.allowsInfiniteMotion) CountdownFrameMillis else CountdownSecondMillis
    LaunchedEffect(armedAt, updateMillis) {
        val startedAt = armedAt ?: return@LaunchedEffect
        while (true) {
            remainingMillis =
                (DisconnectConfirmationMillis - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            if (remainingMillis == 0L) {
                onExpired()
                break
            }
            // This is a real deadline, not animation duration. Static and reduced motion
            // change the display cadence only; neither can shorten the confirmation window.
            delay(minOf(remainingMillis, updateMillis))
        }
    }
    return remainingMillis
}

@Composable
private fun DisconnectConfirmationCountdown(remainingMillis: Long) {
    val spacing = RipDpiThemeTokens.spacing
    val remainingSeconds = ((remainingMillis + CountdownSecondMillis - 1L) / CountdownSecondMillis).toInt()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.home_connection_actuator_confirmation_remaining, remainingSeconds),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorConfirmationRemaining)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ConfirmationTrackHeight)
                    .clip(RipDpiThemeTokens.shapes.full)
                    .background(RipDpiThemeTokens.colors.muted)
                    // The readable seconds label owns announcements, not the visual frame updates.
                    .clearAndSetSemantics { testTag = RipDpiTestTags.ConnectionActuatorConfirmationProgress },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(remainingMillis.toFloat() / DisconnectConfirmationMillis)
                        .fillMaxHeight()
                        .background(RipDpiThemeTokens.colors.foreground),
            )
        }
    }
}

@Composable
private fun rememberConnectionInputModifier(
    state: HomeConnectionActuatorUiState,
    onAction: () -> Unit,
): Modifier {
    val enabled = state.isActivationAvailable || state.isDeactivationAvailable
    var pressedKey by remember(state.status, state.isActivationAvailable, state.isDeactivationAvailable) {
        mutableStateOf<Key?>(null)
    }
    return Modifier
        // Consume a horizontal pan so it cannot turn into an accidental confirmation tap.
        .draggable(rememberDraggableState {}, Orientation.Horizontal, enabled = enabled)
        .onFocusChanged { if (!it.isFocused) pressedKey = null }
        .onPreviewKeyEvent { event ->
            if (enabled && event.key in ConnectionCommitKeys) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) pressedKey = event.key
                    }

                    KeyEventType.KeyUp -> {
                        val matchesPress = pressedKey == event.key
                        pressedKey = null
                        if (matchesPress && !event.nativeKeyEvent.isCanceled) onAction()
                    }
                }
                true
            } else {
                false
            }
        }
}

@Composable
private fun ActuatorHeadline(
    state: HomeConnectionActuatorUiState,
    stateStyle: RipDpiActuatorStateStyle,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionStatusFeedback(status = state.status, tint = stateStyle.label)
            Text(
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                text = state.statusDescription,
                style = type.bodyEmphasisBold,
                color = stateStyle.label,
            )
        }
        if (LocalDensity.current.fontScale >= StackedRouteFontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ActuatorRouteLabels(state, stateStyle)
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                ActuatorRouteLabels(state, stateStyle)
            }
        }
    }
}

@Composable
private fun ActuatorRouteLabels(
    state: HomeConnectionActuatorUiState,
    stateStyle: RipDpiActuatorStateStyle,
) {
    val type = RipDpiThemeTokens.type
    val labelModifier =
        if (LocalDensity.current.fontScale >= StackedRouteFontScale) Modifier.fillMaxWidth() else Modifier
    Text(
        modifier = labelModifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorRouteLabel),
        text = state.routeLabel,
        style = type.smallLabel,
        color = stateStyle.routeLabel,
    )
    Text(
        modifier = labelModifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorTerminalLabel),
        text = state.trailingLabel,
        style = type.smallLabel,
        color = stateStyle.routeLabel,
    )
}

@Composable
private fun ActuatorFaultDetail(
    detail: String,
    stateStyle: RipDpiActuatorStateStyle,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.extraSmallCornerRadius)
    val copyLabel = stringResource(R.string.home_connection_actuator_fault_copy)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorFaultDetail)
                .clip(shape)
                .then(
                    if (onCopy != null) {
                        Modifier.ripDpiClickable(
                            role = Role.Button,
                            onClickLabel = copyLabel,
                            hapticFeedback = RipDpiHapticFeedback.Acknowledge,
                            onClick = onCopy,
                        )
                    } else {
                        Modifier
                    },
                ).background(stateStyle.rail, shape)
                .border(RipDpiStroke.Thin, stateStyle.railBorder, shape)
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = RipDpiIcons.Error,
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Small),
            tint = stateStyle.label,
        )
        Spacer(modifier = Modifier.width(spacing.xs))
        Text(
            modifier = Modifier.weight(1f),
            text = detail,
            style = RipDpiThemeTokens.type.caption,
            color = stateStyle.label,
        )
    }
}

@Composable
private fun HomeConnectionActuatorUiState.withResolvedLabels(): HomeConnectionActuatorUiState =
    copy(
        actionLabel =
            actionLabel.ifEmpty {
                stringResource(
                    when {
                        !isActivationAvailable && status != HomeConnectionActuatorStatus.Engaging -> {
                            R.string.home_connection_actuator_action_deactivate
                        }

                        status == HomeConnectionActuatorStatus.Engaging -> {
                            R.string.home_connection_actuator_action_cancel
                        }

                        status == HomeConnectionActuatorStatus.Fault -> {
                            R.string.home_connection_actuator_action_retry
                        }

                        else -> {
                            R.string.home_connection_actuator_action_activate
                        }
                    },
                )
            },
        statusDescription =
            statusDescription.ifEmpty {
                stringResource(
                    when (status) {
                        HomeConnectionActuatorStatus.Open -> R.string.home_connection_actuator_state_open

                        HomeConnectionActuatorStatus.Engaging -> R.string.home_connection_actuator_state_engaging

                        HomeConnectionActuatorStatus.Locked -> R.string.home_connection_actuator_state_locked

                        HomeConnectionActuatorStatus.Degraded,
                        HomeConnectionActuatorStatus.Fault,
                        -> R.string.home_status_attention
                    },
                )
            },
        routeLabel = routeLabel.ifEmpty { stringResource(R.string.home_mode_vpn) },
        trailingLabel = trailingLabel.ifEmpty { stringResource(R.string.home_connection_actuator_direct) },
    )

private fun HomeConnectionActuatorStatus.toThemeRole(): RipDpiActuatorStateRole =
    when (this) {
        HomeConnectionActuatorStatus.Open -> RipDpiThemeTokens.stateRoles.actuator.open
        HomeConnectionActuatorStatus.Engaging -> RipDpiThemeTokens.stateRoles.actuator.engaging
        HomeConnectionActuatorStatus.Locked -> RipDpiThemeTokens.stateRoles.actuator.locked
        HomeConnectionActuatorStatus.Degraded -> RipDpiThemeTokens.stateRoles.actuator.degraded
        HomeConnectionActuatorStatus.Fault -> RipDpiThemeTokens.stateRoles.actuator.fault
    }
