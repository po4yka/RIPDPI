@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleResolver
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.primaryActionWidth
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiConfirmDisconnectDialog
import com.poyka.ripdpi.ui.components.feedback.ripDpiHandOffTarget
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalField
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalFieldHeight
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorUiState
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
private const val PressedScale = 0.98f
private val SessionClockMinSize = 16.sp
private const val SessionClockWeight = 3f
private const val SignalLineWeight = 2f
private const val LineMiddle = 0.5f

private val ConnectionCommitKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * The Status hero: state headline, route labels, the signal line, the session clock, and the one connection
 * action. Status and route information remain outside the connection action target.
 */
@Composable
fun RipDpiConnectionActuator(
    state: StatusConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyIssueDetail: (() -> Unit)? = null,
    onDiagnoseIssue: (() -> Unit)? = null,
    onViewIssueLog: (() -> Unit)? = null,
    testTag: String? = null,
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

            state.isDeactivationAvailable && state.status == StatusConnectionActuatorStatus.Engaging -> {
                onDeactivate()
            }

            state.isDeactivationAvailable -> {
                confirmingDisconnect = true
            }
        }
    }
    if (confirmingDisconnect && state.isDeactivationAvailable) {
        RipDpiConfirmDisconnectDialog(
            sessionDuration = localizedClockDuration(resolved.sessionDuration, LocalConfiguration.current.locales[0]),
            onDismiss = { confirmingDisconnect = false },
            onConfirm = {
                confirmingDisconnect = false
                onDeactivate()
            },
        )
    }

    val press = rememberHeroPress()
    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Elevated) {
        Column(modifier = Modifier.fillMaxWidth().then(press.trackPointer)) {
            ActuatorHeadline(state = resolved, stateStyle = style)
            ActuatorSignalBand(state = resolved, signal = style.signal, press = press)
            if (resolved.strategyLabel.isNotEmpty()) {
                RipDpiConnectionStrategyPanel(
                    strategyLabel = resolved.strategyLabel,
                    facets = resolved.strategyFacets,
                    status = resolved.status,
                    passage = resolved.strategyPassage,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }
            Spacer(modifier = Modifier.height(spacing.md))
            ActuatorAction(resolved.actionLabel, state, enabled, press, testTag, onAction)
            val fault = state.status == StatusConnectionActuatorStatus.Fault
            AnimatedVisibility(
                visible = fault || state.status == StatusConnectionActuatorStatus.Degraded,
                enter = motion.sectionEnterTransition(),
                exit = motion.sectionExitTransition(),
            ) {
                ActuatorIssueDetail(
                    modifier = Modifier.padding(top = spacing.sm),
                    detail = resolved.issueDetail,
                    stateStyle = style,
                    stateRole = state.status.toThemeRole(),
                    icon = if (fault) RipDpiIcons.Error else RipDpiIcons.Warning,
                    onCopy = onCopyIssueDetail.takeIf { fault },
                    onDiagnose = onDiagnoseIssue,
                    onViewLog = onViewIssueLog,
                )
            }
        }
    }
}

/**
 * Press feedback of the hero. The hero recomposes once on press and once on release; each frame between reads
 * [amount] and the pointer position in the draw or layer phase.
 */
private class HeroPress(
    val source: MutableInteractionSource,
    /** How far the action is pressed, from 0 to 1. */
    val amount: State<Float>,
) {
    private val pointerX = mutableFloatStateOf(0f)
    private var lineStart = 0f
    private var lineWidth = 1f

    /** Records where a pointer goes down, without consuming it. */
    val trackPointer: Modifier =
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pointerX.floatValue = down.position.x
            }
        }

    /** The line can start after the clock, so the pointer position is mapped to the line's own bounds. */
    val trackLine: Modifier =
        Modifier.onPlaced {
            lineStart = it.positionInParent().x
            lineWidth = maxOf(it.size.width, 1).toFloat()
        }

    fun positionOnLine(): Float = (pointerX.floatValue - lineStart) / lineWidth
}

@Composable
private fun rememberHeroPress(): HeroPress {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val amount =
        animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = RipDpiThemeTokens.motion.motionAwareSpring(),
            label = "Hero press",
        )
    return remember(source, amount) { HeroPress(source, amount) }
}

/** The one connection action. It shrinks a little while pressed. */
@Composable
private fun ActuatorAction(
    label: String,
    state: StatusConnectionActuatorUiState,
    enabled: Boolean,
    press: HeroPress,
    testTag: String?,
    onAction: () -> Unit,
) {
    RipDpiButton(
        text = label,
        modifier =
            Modifier
                .primaryActionWidth()
                .graphicsLayer {
                    val scale = 1f - (1f - PressedScale) * press.amount.value
                    scaleX = scale
                    scaleY = scale
                }.ripDpiTestTag(testTag)
                .then(rememberConnectionInputModifier(state, onAction)),
        enabled = enabled,
        interactionSource = press.source,
        hapticFeedback = RipDpiHapticFeedback.None,
        variant = if (state.isActivationAvailable) RipDpiButtonVariant.Primary else RipDpiButtonVariant.Outline,
        wrapLabel = true,
        onClick = onAction,
    )
}

/** The clock shares the signal band, so the action below does not move when a session starts. */
@Composable
private fun ActuatorSignalBand(
    state: StatusConnectionActuatorUiState,
    signal: Color,
    press: HeroPress,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActuatorSessionClock(state)
        val mode = state.status.toSignalMode()
        val breakAt = state.failedStageFraction()
        RipDpiSignalField(
            mode = mode,
            color = signal,
            modifier =
                Modifier
                    .weight(SignalLineWeight)
                    .then(press.trackLine)
                    // The first launch hands its line over to this one.
                    .ripDpiHandOffTarget(mode, signal, breakAt),
            breakAt = breakAt,
            pressAt = press::positionOnLine,
            press = { press.amount.value },
        )
    }
}

private fun StatusConnectionActuatorStatus.toSignalMode(): RipDpiSignalMode =
    when (this) {
        StatusConnectionActuatorStatus.Open -> RipDpiSignalMode.Idle
        StatusConnectionActuatorStatus.Engaging -> RipDpiSignalMode.Seeking
        StatusConnectionActuatorStatus.Locked -> RipDpiSignalMode.Live
        StatusConnectionActuatorStatus.Degraded -> RipDpiSignalMode.Unstable
        StatusConnectionActuatorStatus.Fault -> RipDpiSignalMode.Broken
    }

/** Where the line breaks: the middle of the failed stage's share of the path, or the middle of the line. */
private fun StatusConnectionActuatorUiState.failedStageFraction(): Float {
    val failed = stages.indexOfFirst { it.state == StatusConnectionActuatorStageState.Failed }
    return if (failed < 0) LineMiddle else (failed + LineMiddle) / stages.size
}

/** The idle duration is a meaningless 00:00:00, so only a live session shows the clock. */
@Composable
private fun RowScope.ActuatorSessionClock(state: StatusConnectionActuatorUiState) {
    if (state.status != StatusConnectionActuatorStatus.Locked &&
        state.status != StatusConnectionActuatorStatus.Degraded
    ) {
        return
    }
    val type = RipDpiThemeTokens.type
    val locale = LocalConfiguration.current.locales[0]
    // The ticking digits are read out as a spoken duration; the text is not a live region, so no tick is announced.
    val spoken = remember(state.sessionDuration, locale) { spokenDuration(state.sessionDuration, locale) }
    Text(
        modifier =
            Modifier
                // A share of the band, not its natural width, so large text cannot squeeze the line to a stub.
                .weight(SessionClockWeight, fill = false)
                .height(RipDpiSignalFieldHeight)
                .wrapContentHeight()
                .semantics { contentDescription = spoken }
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorDurationLabel),
        text = localizedClockDuration(state.sessionDuration, locale),
        // A clock reads left to right in every locale; without this a right to left paragraph can reorder it.
        style = type.heroValue.copy(textDirection = TextDirection.Ltr),
        color = RipDpiThemeTokens.colors.foreground,
        maxLines = 1,
        // Large font scales shrink the clock to the band; it never wraps or clips.
        autoSize = TextAutoSize.StepBased(minFontSize = SessionClockMinSize, maxFontSize = type.heroValue.fontSize),
    )
}

@Composable
private fun rememberConnectionInputModifier(
    state: StatusConnectionActuatorUiState,
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
    state: StatusConnectionActuatorUiState,
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
            // degraded and fault, and a per-state symbol so color never carries it alone. Metadata stays neutral.
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
                ActuatorRouteLabels(state)
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                ActuatorRouteLabels(state)
            }
        }
    }
}

@Composable
private fun ActuatorRouteLabels(state: StatusConnectionActuatorUiState) {
    val type = RipDpiThemeTokens.type
    val stacked = LocalDensity.current.fontScale >= StackedRouteFontScale
    val labelModifier = if (stacked) Modifier.fillMaxWidth() else Modifier
    Text(
        modifier = labelModifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorRouteLabel),
        text = state.routeLabel,
        style = type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    if (!stacked) ActuatorRouteSeparator()
    Text(
        modifier = labelModifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorTerminalLabel),
        text = state.trailingLabel,
        style = type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
private fun ActuatorRouteSeparator() {
    Text(
        modifier = Modifier.clearAndSetSemantics {},
        text = "·",
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
private fun ActuatorIssueDetail(
    detail: String,
    stateStyle: RipDpiActuatorStateStyle,
    stateRole: RipDpiActuatorStateRole,
    icon: ImageVector,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDiagnose: (() -> Unit)? = null,
    onViewLog: (() -> Unit)? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.xs
    val copyLabel = stringResource(R.string.status_connection_actuator_fault_copy)
    val interactionSource = remember { MutableInteractionSource() }
    val resolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.actuatorFault(stateRole),
            CommonStyle,
            rememberUpdatedStyleState(interactionSource),
        )
    Column(modifier = modifier.fillMaxWidth()) {
        if (detail.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorIssueDetail)
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
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(RipDpiIconSizes.Small),
                    tint = stateStyle.railContent,
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(
                    modifier = Modifier.weight(1f),
                    text = detail,
                    style = RipDpiThemeTokens.type.caption,
                    color = stateStyle.railContent,
                )
            }
        }
        if (onDiagnose != null || onViewLog != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                onDiagnose?.let {
                    RipDpiButton(
                        text = stringResource(R.string.status_connection_actuator_issue_diagnose),
                        modifier = Modifier.weight(1f).ripDpiTestTag(RipDpiTestTags.ConnectionActuatorIssueDiagnose),
                        variant = RipDpiButtonVariant.Outline,
                        wrapLabel = true,
                        onClick = it,
                    )
                }
                onViewLog?.let {
                    RipDpiButton(
                        text = stringResource(R.string.status_connection_actuator_issue_view_log),
                        modifier = Modifier.weight(1f).ripDpiTestTag(RipDpiTestTags.ConnectionActuatorIssueViewLog),
                        variant = RipDpiButtonVariant.Outline,
                        wrapLabel = true,
                        onClick = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusConnectionActuatorUiState.withResolvedLabels(): StatusConnectionActuatorUiState =
    copy(
        actionLabel =
            actionLabel.ifEmpty {
                stringResource(
                    when {
                        !isActivationAvailable && status != StatusConnectionActuatorStatus.Engaging -> {
                            R.string.status_connection_actuator_action_deactivate
                        }

                        status == StatusConnectionActuatorStatus.Engaging -> {
                            R.string.status_connection_actuator_action_cancel
                        }

                        status == StatusConnectionActuatorStatus.Fault -> {
                            R.string.status_connection_actuator_action_retry
                        }

                        else -> {
                            R.string.status_connection_actuator_action_activate
                        }
                    },
                )
            },
        statusDescription =
            statusDescription.ifEmpty {
                stringResource(
                    when (status) {
                        StatusConnectionActuatorStatus.Open -> R.string.status_connection_actuator_state_open

                        StatusConnectionActuatorStatus.Engaging -> R.string.status_connection_actuator_state_engaging

                        StatusConnectionActuatorStatus.Locked -> R.string.status_connection_actuator_state_locked

                        StatusConnectionActuatorStatus.Degraded,
                        StatusConnectionActuatorStatus.Fault,
                        -> R.string.status_attention
                    },
                )
            },
        routeLabel = routeLabel.ifEmpty { stringResource(R.string.status_mode_vpn) },
        trailingLabel = trailingLabel.ifEmpty { stringResource(R.string.status_connection_path_direct) },
    )

private fun StatusConnectionActuatorStatus.toThemeRole(): RipDpiActuatorStateRole =
    when (this) {
        StatusConnectionActuatorStatus.Open -> RipDpiThemeTokens.stateRoles.actuator.open
        StatusConnectionActuatorStatus.Engaging -> RipDpiThemeTokens.stateRoles.actuator.engaging
        StatusConnectionActuatorStatus.Locked -> RipDpiThemeTokens.stateRoles.actuator.locked
        StatusConnectionActuatorStatus.Degraded -> RipDpiThemeTokens.stateRoles.actuator.degraded
        StatusConnectionActuatorStatus.Fault -> RipDpiThemeTokens.stateRoles.actuator.fault
    }
