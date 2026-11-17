@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleResolver
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
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
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkMode
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkTile
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.components.signal.SignalTrafficMeter
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay

private const val StackedRouteFontScale = 1.5f
private const val PressedScale = 0.98f
private val SessionClockMinSize = 16.sp

// The share of the link tile that the session clock takes. The figure keeps the rest.
private const val SessionClockShare = 0.4f

private val ConnectionCommitKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * The Status hero: state headline, route labels, the session clock, the link tile, and the one connection
 * action. Status and route information remain outside the connection action target.
 *
 * @param sessionHeld true while the session is paused. The link tile then holds its plug at the latch.
 * @param sessionControls what qualifies the session, such as pause and a pending reconnect. It sits under the
 * connection action, so every session action shares the hero surface.
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
    sessionHeld: Boolean = false,
    sessionControls: (@Composable ColumnScope.() -> Unit)? = null,
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
        Column(modifier = Modifier.fillMaxWidth()) {
            ActuatorHeadline(state = resolved, stateStyle = style)
            ActuatorLink(resolved, style.signal, press, sessionHeld, onAction.takeIf { enabled })
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
            sessionControls?.let {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    content = it,
                )
            }
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
 * [amount] in the draw or layer phase.
 */
private class HeroPress(
    val source: MutableInteractionSource,
    /** True while the action is pressed. */
    val pressed: State<Boolean>,
    /** How far the action is pressed, from 0 to 1. */
    val amount: State<Float>,
)

@Composable
private fun rememberHeroPress(): HeroPress {
    val source = remember { MutableInteractionSource() }
    val pressedState = source.collectIsPressedAsState()
    val pressed by pressedState
    val amount =
        animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = RipDpiThemeTokens.motion.motionAwareSpring(),
            label = "Hero press",
        )
    return remember(source, amount) { HeroPress(source, pressedState, amount) }
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

/**
 * The link tile: the path as a plug and a socket, with the session clock at its start edge while a session is live.
 * The clock is inside the tile, so the action below does not move when a session starts. A drag of the plug does
 * what the connection action does.
 */
@Composable
private fun ActuatorLink(
    state: StatusConnectionActuatorUiState,
    signal: Color,
    press: HeroPress,
    held: Boolean,
    onPlug: (() -> Unit)?,
) {
    val spacing = RipDpiThemeTokens.spacing
    val traffic = rememberSessionTraffic(state)
    val mode = if (held) RipDpiLinkMode.Held else state.status.toLinkMode()
    val timed =
        state.status == StatusConnectionActuatorStatus.Locked || state.status == StatusConnectionActuatorStatus.Degraded
    // How far the clock is shown, from 0 to 1. The figure moves aside for it in the same motion.
    val clock =
        animateFloatAsState(
            targetValue = if (timed) 1f else 0f,
            animationSpec = RipDpiThemeTokens.motion.emphasizedTween(),
            label = "Session clock",
        )
    Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.sm), contentAlignment = Alignment.CenterStart) {
        RipDpiLinkTile(
            mode = mode,
            stages = remember(state.status, state.stages, held) { state.linkStages(held) },
            tone = signal,
            // The first launch hands its line over to the cable of this tile.
            modifier = Modifier.ripDpiHandOffTarget(state.status.toSignalMode(), signal),
            reserve = { SessionClockShare * clock.value },
            press = { press.amount.value },
            pressed = press.pressed.value,
            sent = { traffic.sent.floatValue },
            received = { traffic.received.floatValue },
            onPlug = onPlug,
        )
        if (timed) {
            SessionClockText(
                state = state,
                modifier =
                    Modifier
                        .fillMaxWidth(SessionClockShare)
                        .padding(start = spacing.md)
                        .graphicsLayer { alpha = clock.value },
            )
        }
    }
}

/** The traffic levels of the running session, from 0 to 1. */
@Stable
private class SessionTraffic {
    val sent = mutableFloatStateOf(0f)
    val received = mutableFloatStateOf(0f)
}

/**
 * The traffic levels of the running session, sampled once a second from its packet counters. The counters do not
 * change on a quiet link, so the sample runs on a timer and a quiet link reads as no traffic.
 */
@Composable
private fun rememberSessionTraffic(state: StatusConnectionActuatorUiState): SessionTraffic {
    val traffic = remember { SessionTraffic() }
    val sentPackets by rememberUpdatedState(state.sentPackets)
    val receivedPackets by rememberUpdatedState(state.receivedPackets)
    val live =
        state.status == StatusConnectionActuatorStatus.Locked || state.status == StatusConnectionActuatorStatus.Degraded
    LaunchedEffect(live) {
        val sent = SignalTrafficMeter()
        val received = SignalTrafficMeter()
        traffic.sent.floatValue = 0f
        traffic.received.floatValue = 0f
        while (live) {
            val now = SystemClock.elapsedRealtime()
            traffic.sent.floatValue = sent.level(sentPackets, now)
            traffic.received.floatValue = received.level(receivedPackets, now)
            delay(TrafficSampleMillis)
        }
    }
    return traffic
}

private const val TrafficSampleMillis = 1_000L

private fun StatusConnectionActuatorStatus.toSignalMode(): RipDpiSignalMode =
    when (this) {
        StatusConnectionActuatorStatus.Open -> RipDpiSignalMode.Idle
        StatusConnectionActuatorStatus.Engaging -> RipDpiSignalMode.Seeking
        StatusConnectionActuatorStatus.Locked -> RipDpiSignalMode.Live
        StatusConnectionActuatorStatus.Degraded -> RipDpiSignalMode.Unstable
        StatusConnectionActuatorStatus.Fault -> RipDpiSignalMode.Broken
    }

private fun StatusConnectionActuatorStatus.toLinkMode(): RipDpiLinkMode =
    when (this) {
        StatusConnectionActuatorStatus.Open -> RipDpiLinkMode.Open
        StatusConnectionActuatorStatus.Engaging -> RipDpiLinkMode.Seeking
        StatusConnectionActuatorStatus.Locked -> RipDpiLinkMode.Live
        StatusConnectionActuatorStatus.Degraded -> RipDpiLinkMode.Unstable
        StatusConnectionActuatorStatus.Fault -> RipDpiLinkMode.Broken
    }

/**
 * The lamps of the link tile. The app reports no stage progress while it connects, so every lamp is then active.
 * A live state with no stage detail, such as a seed or a preview, has every stage complete. A fault lights only
 * the stage that it names, and no lamp when it names none.
 */
internal fun StatusConnectionActuatorUiState.linkStages(held: Boolean): ImmutableList<RipDpiLinkStage> {
    if (held) return stages.map { RipDpiLinkStage.Held }.toImmutableList()
    val detailed = stages.any { it.state != StatusConnectionActuatorStageState.Pending }
    val live = status == StatusConnectionActuatorStatus.Locked || status == StatusConnectionActuatorStatus.Degraded
    return stages
        .map { stage ->
            when {
                detailed -> {
                    stage.state.toLinkStage()
                }

                live -> {
                    RipDpiLinkStage.Complete
                }

                status == StatusConnectionActuatorStatus.Engaging -> {
                    RipDpiLinkStage.Active
                }

                else -> {
                    RipDpiLinkStage.Pending
                }
            }
        }.toImmutableList()
}

private fun StatusConnectionActuatorStageState.toLinkStage(): RipDpiLinkStage =
    when (this) {
        StatusConnectionActuatorStageState.Pending -> RipDpiLinkStage.Pending
        StatusConnectionActuatorStageState.Active -> RipDpiLinkStage.Active
        StatusConnectionActuatorStageState.Complete -> RipDpiLinkStage.Complete
        StatusConnectionActuatorStageState.Warning -> RipDpiLinkStage.Warning
        StatusConnectionActuatorStageState.Failed -> RipDpiLinkStage.Failed
    }

/** The idle duration is a meaningless 00:00:00, so only a live session shows the clock. */
@Composable
private fun SessionClockText(
    state: StatusConnectionActuatorUiState,
    modifier: Modifier = Modifier,
) {
    val type = RipDpiThemeTokens.type
    val locale = LocalConfiguration.current.locales[0]
    val text = localizedClockDuration(state.sessionDuration, locale)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The ticking digits are read out as a spoken duration; the text is not a live region, so no tick is announced.
    val spoken = remember(state.sessionDuration, locale) { spokenDuration(state.sessionDuration, locale) }
    Text(
        modifier =
            modifier
                .semantics { contentDescription = spoken }
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorDurationLabel)
                .colonPulse(text, RipDpiThemeTokens.colors.signal) { layout },
        text = text,
        onTextLayout = { layout = it },
        // A clock reads left to right in every locale; without this a right to left paragraph can reorder it.
        style = type.heroValue.copy(textDirection = TextDirection.Ltr),
        color = RipDpiThemeTokens.colors.foreground,
        maxLines = 1,
        // Large font scales shrink the clock to its share of the tile; it never wraps or clips.
        autoSize = TextAutoSize.StepBased(minFontSize = SessionClockMinSize, maxFontSize = type.heroValue.fontSize),
    )
}

/**
 * Tints the colons of the clock with [color] once per tick. The hero already recomposes once per second for the
 * new digits; the fade between ticks reads its alpha in the draw phase only. Reduced motion draws plain colons.
 */
@Composable
private fun Modifier.colonPulse(
    text: String,
    color: Color,
    layout: () -> TextLayoutResult?,
): Modifier {
    val motion = RipDpiThemeTokens.motion
    if (!motion.allowsInfiniteMotion) return this
    val pulse = remember { Animatable(0f) }
    val fade = motion.emphasizedTween<Float>()
    LaunchedEffect(text) {
        pulse.snapTo(1f)
        pulse.animateTo(0f, fade)
    }
    return this
        // Its own layer, so the tint lands on the glyphs alone and not on the card behind them.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val alpha = pulse.value
            val measured = layout()?.takeIf { it.layoutInput.text.text == text }
            if (alpha > 0f && measured != null) {
                text.forEachIndexed { index, char ->
                    if (char == ':') {
                        val box = measured.getBoundingBox(index)
                        drawRect(color, box.topLeft, box.size, alpha = alpha, blendMode = BlendMode.SrcAtop)
                    }
                }
            }
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The state is the header's main status text: foreground ink, with semantic color only for
            // degraded and fault, and a per-state symbol so color never carries it alone. Metadata stays neutral.
            ConnectionStatusFeedback(status = state.status, tint = stateStyle.routeLabel)
            ActuatorStateText(state.statusDescription, stateStyle.routeLabel, Modifier.weight(1f))
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

/**
 * The state text cross-fades on a change, as the symbol beside it does. One node owns the text and the live region;
 * the two copies that fade have no semantics, so a change is announced once. With animations off it is plain text.
 */
@Composable
private fun ActuatorStateText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val motion = RipDpiThemeTokens.motion
    val style = RipDpiThemeTokens.type.screenTitleEmphasis
    if (!motion.animationsEnabled) {
        Text(
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = text,
            style = style,
            color = color,
        )
        return
    }
    AnimatedContent(
        targetState = text,
        modifier =
            modifier.clearAndSetSemantics {
                this.text = AnnotatedString(text)
                liveRegion = LiveRegionMode.Polite
            },
        transitionSpec = { motion.quickFadeContentTransform().using(null) },
        label = "Connection state text",
    ) { visible ->
        Text(text = visible, style = style, color = color)
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
