@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleResolver
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiConfirmDisconnectDialog
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStateRole
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStateStyle
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.actuatorFault
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleAppearance

private const val StackedRouteFontScale = 1.5f
private val ConnectionCommitKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/** Status and route information remain outside the connection action target. */
@Composable
@NonRestartableComposable
fun RipDpiConnectionActuator(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyFaultDetail: (() -> Unit)? = null,
    testTag: String? = null,
) {
    RipDpiConnectionActuator(
        state = state,
        onActivate = onActivate,
        onDeactivate = onDeactivate,
        modifier = modifier,
        onCopyFaultDetail = onCopyFaultDetail,
        testTag = testTag,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiConnectionActuator(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyFaultDetail: (() -> Unit)? = null,
    testTag: String? = null,
    visualStyle: CommonStyle = CommonStyle,
) {
    val resolved = state.withResolvedLabels()
    val style = RipDpiThemeTokens.state.actuator.resolve(role = state.status.toThemeRole())
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    // An untimed dialog guards against an accidental disconnect (WCAG 2.2.1: no time limit to confirm).
    // Keyed on availability so a session that ends while the dialog is open does not reopen it later.
    var confirmingDisconnect by rememberSaveable(state.isDeactivationAvailable) { mutableStateOf(false) }

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
                confirmingDisconnect = true
            }
        }
    }
    if (confirmingDisconnect && state.isDeactivationAvailable) {
        RipDpiConfirmDisconnectDialog(
            sessionDuration = resolved.sessionDuration,
            onDismiss = { confirmingDisconnect = false },
            onConfirm = {
                confirmingDisconnect = false
                onDeactivate()
            },
        )
    }

    Column(modifier = modifier) {
        ActuatorHeadline(state = resolved, stateStyle = style)
        if (resolved.strategyLabel.isNotEmpty()) {
            RipDpiConnectionStrategyPanel(
                strategyLabel = resolved.strategyLabel,
                facets = resolved.strategyFacets,
                modifier = Modifier.padding(top = spacing.md),
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        RipDpiButton(
            text = resolved.actionLabel,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(testTag)
                    .then(rememberConnectionInputModifier(state, onAction)),
            enabled = enabled,
            hapticFeedback = RipDpiHapticFeedback.None,
            variant = if (state.isActivationAvailable) RipDpiButtonVariant.Primary else RipDpiButtonVariant.Outline,
            wrapLabel = true,
            visualStyle = visualStyle,
            onClick = onAction,
        )
        AnimatedVisibility(
            visible = resolved.faultDetail.isNotEmpty(),
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            ActuatorFaultDetail(
                modifier = Modifier.padding(top = spacing.sm),
                detail = resolved.faultDetail,
                stateStyle = style,
                stateRole = state.status.toThemeRole(),
                onCopy = onCopyFaultDetail,
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
            // The state is the header's main status text: foreground ink, with semantic color only for
            // degraded and fault (the route color), and a per-state symbol so color never carries it alone.
            ConnectionStatusFeedback(status = state.status, tint = stateStyle.routeLabel)
            Text(
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                text = state.statusDescription,
                style = type.screenTitleEmphasis,
                color = stateStyle.routeLabel,
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
    val stacked = LocalDensity.current.fontScale >= StackedRouteFontScale
    val labelModifier = if (stacked) Modifier.fillMaxWidth() else Modifier
    Text(
        modifier = labelModifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorRouteLabel),
        text = state.routeLabel,
        style = type.secondaryBody,
        color = stateStyle.routeLabel,
    )
    if (!stacked) {
        Text(
            modifier = Modifier.clearAndSetSemantics {},
            text = "·",
            style = type.secondaryBody,
            color = stateStyle.routeLabel,
        )
    }
    Text(
        modifier = labelModifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorTerminalLabel),
        text = state.trailingLabel,
        style = type.secondaryBody,
        color = stateStyle.routeLabel,
    )
}

@Composable
private fun ActuatorFaultDetail(
    detail: String,
    stateStyle: RipDpiActuatorStateStyle,
    stateRole: RipDpiActuatorStateRole,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.extraSmallCornerRadius)
    val copyLabel = stringResource(R.string.home_connection_actuator_fault_copy)
    val interactionSource = remember { MutableInteractionSource() }
    val resolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.actuatorFault(stateRole),
            CommonStyle,
            rememberUpdatedStyleState(interactionSource),
        )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorFaultDetail)
                .styleResolver(resolver)
                .clip(shape)
                .then(
                    if (onCopy != null) {
                        Modifier.ripDpiClickable(
                            role = Role.Button,
                            onClickLabel = copyLabel,
                            hapticFeedback = RipDpiHapticFeedback.Acknowledge,
                            interactionSource = interactionSource,
                            onClick = onCopy,
                        )
                    } else {
                        Modifier
                    },
                ).ripDpiStyleAppearance(resolver, shape)
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
        trailingLabel = trailingLabel.ifEmpty { stringResource(R.string.home_connection_path_direct) },
    )

private fun HomeConnectionActuatorStatus.toThemeRole(): RipDpiActuatorStateRole =
    when (this) {
        HomeConnectionActuatorStatus.Open -> RipDpiThemeTokens.stateRoles.actuator.open
        HomeConnectionActuatorStatus.Engaging -> RipDpiThemeTokens.stateRoles.actuator.engaging
        HomeConnectionActuatorStatus.Locked -> RipDpiThemeTokens.stateRoles.actuator.locked
        HomeConnectionActuatorStatus.Degraded -> RipDpiThemeTokens.stateRoles.actuator.degraded
        HomeConnectionActuatorStatus.Fault -> RipDpiThemeTokens.stateRoles.actuator.fault
    }
