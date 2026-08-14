package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.labelRes
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStageRole
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStageStyle
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStateRole
import com.poyka.ripdpi.ui.theme.RipDpiActuatorStateStyle
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How much of the carriage's travel a drag has to cover before it commits, in
 * either direction.
 *
 * Releasing used to clear at 0.28 while engaging took 0.72, which inverts the
 * risk the rail is built around: the outcome worth guarding is a line dropped
 * by accident, not one raised by accident, and that was the cheaper of the two
 * gestures by a factor of two and a half.
 */
private const val CommitDragThreshold = 0.72f
private const val ActiveStagePulseAlpha = 0.72f
private const val WarningStagePulseAlpha = 0.82f
private const val StripeStepPx = 10f
private const val StripeStrokePx = 2f
private const val CarriageGripCount = 3
private const val EndpointLabelHorizontalGapCount = 2
private const val AccessibilityLayoutFontScale = 1.5f
private const val TrackFillAlpha = 0.22f
private const val GripAlpha = 0.42f
private const val LaneLabelRestFraction = 0.5f

/**
 * How fast the action label fades as the carriage leaves its resting lane.
 * At this gain the label is gone once the carriage has covered roughly 40% of
 * its travel, which is where it would otherwise start rendering underneath it.
 */
private const val LabelFadeGain = 2.5f

/**
 * How far the carriage pulls back when a release tap arms instead of committing.
 * Far enough to read as the control answering the tap and pointing at the
 * gesture, well short of the threshold that gesture would have to clear.
 */
private const val ArmedNudgeFraction = 0.18f

/** How long an armed release waits for the tap that confirms it. */
private const val ArmWindowMillis = 4_000L

/** Keys that count as an explicit commit on a focused rail. */
private val ActuatorCommitKeys =
    setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * Single actuator for the connection lifecycle: the rail *is* the control.
 *
 * The track carries the action affordance (labelled lane plus a directional
 * carriage), so there is no second button competing for the same tap. A
 * plain button replaces the rail only when the user runs an accessibility
 * font scale, where a drag target is not a reasonable ask.
 *
 * Commit model: engaging the line accepts a tap, because a stray one costs the
 * user nothing. Releasing a live line takes either the drag the carriage
 * advertises or two taps — the first arms and says so, the second commits.
 *
 * Withholding the release tap outright was the earlier answer, and it fails
 * WCAG 2.2 SC 2.5.7: dragging here is a deliberate friction choice rather than
 * an essential one, so the release has to stay reachable with a single pointer
 * that never drags, and a keyboard path and a semantics action are different
 * modalities that do not satisfy it. Arming keeps what the withheld tap was
 * protecting — one stray touch still cannot drop a live line — while answering
 * the tap instead of ignoring it, which is what made the control read as dead.
 */
@Composable
fun RipDpiConnectionActuator(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val motion = RipDpiThemeTokens.motion
    val performHaptic = rememberRipDpiHapticPerformer()
    // Re-keyed on status so a landing state change always disarms: the prompt
    // belongs to one pending release, not to whatever the line does next.
    val armed = remember(state.status) { mutableStateOf(false) }
    val confirmLabel = stringResource(R.string.home_connection_actuator_action_confirm_release)
    // A blank field means the resolver has not answered yet, which is the state
    // every launch renders first. Resolving here keeps that frame in the user's
    // language instead of the data class's literals.
    val state =
        state.withResolvedLabels().let { resolved ->
            // While armed the control's action really has changed, so the lane's
            // label, the switch's name and the label on its click action move
            // together off this one field rather than drifting apart.
            if (armed.value) resolved.copy(actionLabel = confirmLabel) else resolved
        }
    val stateStyle = actuatorStateStyle(state)
    val railColor = animateColorAsState(stateStyle.rail, motion.stateTween(), label = "actuatorRail")
    val carriageColor = animateColorAsState(stateStyle.carriage, motion.stateTween(), label = "actuatorCarriage")
    val terminalColor = animateColorAsState(stateStyle.terminal, motion.stateTween(), label = "actuatorTerminal")
    val baseFraction =
        animateFloatAsState(
            targetValue = state.carriageFraction.coerceIn(0f, 1f),
            animationSpec = motion.stateTween(),
            label = "actuatorCarriageFraction",
        )
    val useAccessibilityLayout = LocalDensity.current.fontScale >= AccessibilityLayoutFontScale
    val interactionModifier =
        rememberActuatorInteractionModifier(
            state = state,
            dragEnabled = !useAccessibilityLayout,
            nodeTestTag = testTag,
            armed = armed,
            onActivate = onActivate,
            onDeactivate = onDeactivate,
            performHaptic = performHaptic,
        )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        // The headline and route stay outside the switch so a screen reader can
        // read them as their own items. Merging them into the control made the
        // route the control's name and buried its state in a paragraph.
        ActuatorHeadline(state = state, stateStyle = stateStyle)
        if (useAccessibilityLayout) {
            ActuatorFallbackAction(
                state = state,
                stateStyle = stateStyle,
                modifier = interactionModifier.modifier,
            )
        } else {
            ActuatorRailLayout(
                // Start, not centre: the rail is capped narrower than the content
                // column on wide windows, and centring it left its own headline
                // stranded at the far left with no edge to line up against.
                modifier = Modifier.align(Alignment.Start),
                state = state,
                stateStyle = stateStyle,
                railColor = railColor,
                terminalColor = terminalColor,
                carriageColor = carriageColor,
                baseFraction = baseFraction,
                interactionModifier = interactionModifier,
            )
        }
        if (state.status.showsPipeline) {
            ActuatorPipeline(
                stages = state.stages,
                useAccessibilityLayout = useAccessibilityLayout,
            )
        }
    }
}

@Composable
private fun ActuatorRailLayout(
    state: HomeConnectionActuatorUiState,
    stateStyle: RipDpiActuatorStateStyle,
    railColor: State<Color>,
    terminalColor: State<Color>,
    carriageColor: State<Color>,
    baseFraction: State<Float>,
    interactionModifier: ActuatorInteractionModifier,
    modifier: Modifier = Modifier,
) {
    val metrics = RipDpiThemeTokens.components.actuator
    val spacing = RipDpiThemeTokens.spacing
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier =
            modifier
                // Drag travel is measured from the rail's own width, so an
                // uncapped rail turns a single binary action into an arm-length
                // sweep on a tablet or an unfolded foldable.
                .widthIn(max = metrics.railMaxWidth)
                .fillMaxWidth()
                .height(metrics.railHeight)
                .then(interactionModifier.modifier),
    ) {
        val endpointLayout =
            rememberActuatorEndpointLayout(
                availableWidth = maxWidth,
                trailingLabel = state.trailingLabel,
            )
        val insetPx = with(density) { metrics.trackInset.toPx() }
        val occupiedPx =
            with(density) {
                (metrics.trackInset * 2 + metrics.carriageWidth + endpointLayout.terminalWidth + spacing.sm).toPx()
            }
        // Travel stops short of the terminal slot, so neither endpoint is ever
        // hidden under the carriage — the pre-refactor rail buried both.
        val travelPx = (constraints.maxWidth - occupiedPx).coerceAtLeast(0f)
        // Commit thresholds are measured against carriage travel, not rail width.
        // Anything else leaves the carriage pinned at the end while the gesture is
        // still short of firing, which reads as a dead control.
        SideEffect { interactionModifier.onTravelChanged(travelPx) }
        val fraction = {
            val dragFraction =
                if (travelPx > 0f) interactionModifier.dragDeltaPx.value / travelPx else 0f
            (baseFraction.value + dragFraction).coerceIn(0f, 1f)
        }

        ActuatorTrackSurface(
            railColor = railColor,
            fillColor = stateStyle.carriage.copy(alpha = TrackFillAlpha),
            borderColor = stateStyle.railBorder,
            insetPx = insetPx,
            travelPx = travelPx,
            carriageWidthPx = with(density) { metrics.carriageWidth.toPx() },
            fraction = fraction,
        )
        // The carriage travels straight through the label's lane, so the label
        // fades as the carriage covers it. Keying that to the rendered fraction
        // rather than the raw drag delta is what makes it work in Engaging and
        // Fault, where the carriage rests mid-track with no finger on it and the
        // label used to sit visibly underneath it.
        val restFraction = if (state.carriageFraction < LaneLabelRestFraction) 0f else 1f
        ActuatorTrackContent(
            state = state,
            stateStyle = stateStyle,
            terminalColor = terminalColor,
            endpointLayout = endpointLayout,
            labelAlpha = {
                (1f - abs(fraction() - restFraction) * LabelFadeGain).coerceIn(0f, 1f)
            },
        )
        ActuatorCarriage(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(x = (insetPx + fraction() * travelPx).roundToInt(), y = 0)
                    },
            state = state,
            carriageColor = carriageColor,
            carriageContentColor = stateStyle.carriageContent,
        )
    }
}

/** Track background plus a progress fill that follows the live drag. */
@Composable
private fun ActuatorTrackSurface(
    railColor: State<Color>,
    fillColor: Color,
    borderColor: Color,
    insetPx: Float,
    travelPx: Float,
    carriageWidthPx: Float,
    fraction: () -> Float,
) {
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.compactCornerRadius)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorRail)
                .clip(shape)
                .drawBehind {
                    drawRect(railColor.value)
                    val fillWidth = insetPx + fraction() * travelPx + carriageWidthPx
                    // The carriage and terminal mirror under RTL, but a rect that
                    // defaults to Offset.Zero does not: the fill grew away from the
                    // carriage instead of trailing it, on every connect animation
                    // in Arabic and Persian.
                    val originX =
                        if (layoutDirection == LayoutDirection.Rtl) size.width - fillWidth else 0f
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(originX, 0f),
                        size = Size(fillWidth, size.height),
                    )
                }.border(RipDpiStroke.Thin, borderColor, shape),
    )
}

/**
 * Static track content: the action label sits in whichever lane the carriage
 * is not resting in, and the terminal slot always stays visible.
 */
@Composable
private fun ActuatorTrackContent(
    state: HomeConnectionActuatorUiState,
    stateStyle: RipDpiActuatorStateStyle,
    terminalColor: State<Color>,
    endpointLayout: ActuatorEndpointLayout,
    labelAlpha: () -> Float,
) {
    val metrics = RipDpiThemeTokens.components.actuator
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val carriageLane = metrics.carriageWidth + spacing.sm
    val carriageAtStart = state.carriageFraction < LaneLabelRestFraction

    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.trackInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (carriageAtStart) {
            Spacer(modifier = Modifier.width(carriageLane))
        }
        Text(
            modifier =
                Modifier
                    .weight(1f)
                    .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorActionLabel)
                    .graphicsLayer { alpha = labelAlpha() },
            text = state.actionLabel,
            style = type.caption,
            color = stateStyle.label,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!carriageAtStart) {
            Spacer(modifier = Modifier.width(carriageLane))
        }
        TerminalSlot(
            label = state.trailingLabel.takeIf { endpointLayout.showLabels },
            icon = state.status.terminalIcon(),
            width = endpointLayout.terminalWidth,
            container = terminalColor,
            content = stateStyle.slotContent,
            border = stateStyle.terminalBorder,
        )
    }
}

/**
 * Fills blank labels from resources, so an unresolved state still renders in the
 * user's language rather than falling back to literals baked into the model.
 */
@Composable
private fun HomeConnectionActuatorUiState.withResolvedLabels(): HomeConnectionActuatorUiState {
    val hasBlank =
        actionLabel.isEmpty() ||
            statusDescription.isEmpty() ||
            routeLabel.isEmpty() ||
            trailingLabel.isEmpty() ||
            stages.any { it.label.isEmpty() }
    if (!hasBlank) return this
    return copy(
        actionLabel = actionLabel.ifEmpty { stringResource(R.string.home_connection_actuator_action_activate) },
        statusDescription =
            statusDescription.ifEmpty { stringResource(R.string.home_connection_actuator_state_open) },
        routeLabel = routeLabel.ifEmpty { stringResource(R.string.home_mode_vpn) },
        trailingLabel = trailingLabel.ifEmpty { stringResource(R.string.home_connection_actuator_direct) },
        stages =
            stages
                .map { stage ->
                    if (stage.label.isNotEmpty()) {
                        stage
                    } else {
                        stage.copy(label = stringResource(stage.stage.labelRes()))
                    }
                }.toImmutableList(),
    )
}

@Composable
private fun actuatorStateStyle(state: HomeConnectionActuatorUiState): RipDpiActuatorStateStyle =
    RipDpiThemeTokens.state.actuator.resolve(role = state.status.toThemeRole())

@Composable
private fun rememberActuatorEndpointLayout(
    availableWidth: Dp,
    trailingLabel: String,
): ActuatorEndpointLayout {
    val metrics = RipDpiThemeTokens.components.actuator
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // The name promised memoization that only the measurer got: the width
    // derivation below runs a full text-shaping pass, and without this key it
    // re-shaped on every recomposition, including ones that changed nothing it
    // reads.
    return remember(availableWidth, trailingLabel, metrics, spacing, type, density, textMeasurer) {
        val trailingWidth = measureTextWidth(trailingLabel, type.smallLabel, textMeasurer, density)
        val labeledTerminalWidth =
            maxOf(
                metrics.terminalSlotWidth,
                spacing.sm * 2 + RipDpiIconSizes.Small + spacing.xs + trailingWidth,
            )
        val requiredWidth =
            metrics.carriageWidth +
                labeledTerminalWidth +
                spacing.md * EndpointLabelHorizontalGapCount
        val accessibilityLabelsFit =
            density.fontScale < EndpointLabelCollapseFontScale || availableWidth >= WideEndpointLabelWidth
        val showLabels = accessibilityLabelsFit && availableWidth >= requiredWidth
        ActuatorEndpointLayout(
            showLabels = showLabels,
            terminalWidth = if (showLabels) labeledTerminalWidth else metrics.terminalSlotHeight,
        )
    }
}

private fun measureTextWidth(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
    density: Density,
): Dp =
    with(density) {
        textMeasurer
            .measure(AnnotatedString(text), style = style, maxLines = 1)
            .size.width
            .toDp()
    }

private data class ActuatorEndpointLayout(
    val showLabels: Boolean,
    val terminalWidth: Dp,
)

private const val EndpointLabelCollapseFontScale = 1.8f
private val WideEndpointLabelWidth = 480.dp

/** Status headline and route summary. Text only — the rail owns the action. */
@Composable
private fun ActuatorHeadline(
    state: HomeConnectionActuatorUiState,
    stateStyle: RipDpiActuatorStateStyle,
) {
    val type = RipDpiThemeTokens.type

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = state.statusDescription,
            style = type.bodyEmphasisBold,
            color = stateStyle.label,
        )
        Text(
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorRouteLabel),
            text = state.routeLabel,
            style = type.smallLabel,
            color = stateStyle.routeLabel,
        )
    }
}

/**
 * Tap-only replacement for the rail at accessibility font scales, where a
 * horizontal drag target is neither reachable nor discoverable.
 */
@Composable
private fun ActuatorFallbackAction(
    state: HomeConnectionActuatorUiState,
    stateStyle: RipDpiActuatorStateStyle,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.compactCornerRadius)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = RipDpiThemeTokens.components.buttons.minHeight)
                .clip(shape)
                .background(stateStyle.carriage, shape)
                .border(RipDpiStroke.Thin, stateStyle.carriageContent, shape)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = state.status.icon(),
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Default),
            tint = stateStyle.carriageContent,
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = state.actionLabel,
            style = type.button,
            color = stateStyle.carriageContent,
        )
    }
}

/**
 * The one commit path every input shares: tap, keypress and semantics action all
 * come through here, so the arm-then-confirm step cannot be true for one of them
 * and false for another.
 *
 * Returns true whenever the input was handled, arming included — an arming tap
 * is an answer, not a dropped gesture.
 */
@Composable
private fun rememberActuatorCommit(
    state: HomeConnectionActuatorUiState,
    requiresConfirmation: Boolean,
    armed: MutableState<Boolean>,
    carriage: Animatable<Float, AnimationVector1D>,
    travelPx: State<Float>,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
): () -> Boolean {
    val scope = rememberCoroutineScope()
    val nudgeSpec = RipDpiThemeTokens.motion.quickTween<Float>()

    // An armed release expires on its own, so a control left alone goes back to
    // reporting the line instead of sitting on a stale prompt.
    LaunchedEffect(armed.value) {
        if (armed.value) {
            delay(ArmWindowMillis)
            armed.value = false
        }
    }

    return {
        if (requiresConfirmation && !armed.value) {
            armed.value = true
            performHaptic(RipDpiHapticFeedback.Toggle)
            scope.launch {
                // The carriage answers by pulling towards the gesture it wants and
                // settling back, so the first tap teaches the drag rather than
                // looking like the control ignored it.
                carriage.animateTo(-travelPx.value * ArmedNudgeFraction, nudgeSpec)
                carriage.animateTo(0f, nudgeSpec)
            }
            true
        } else {
            armed.value = false
            invokeActuatorClick(state, performHaptic, onActivate, onDeactivate)
        }
    }
}

@Composable
private fun rememberActuatorInteractionModifier(
    state: HomeConnectionActuatorUiState,
    dragEnabled: Boolean,
    nodeTestTag: String?,
    armed: MutableState<Boolean>,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
): ActuatorInteractionModifier {
    // Re-keyed on status: once the new status lands, the base fraction owns the
    // carriage again and this offset starts over at zero.
    val dragOffsetPx = remember(state.status) { Animatable(0f) }
    val travelPx = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val returnSpec = RipDpiThemeTokens.motion.quickTween<Float>()
    val actionEnabled = state.isActivationAvailable || state.isDeactivationAvailable
    // Only a release asks to be confirmed, and only where a rail exists to drag
    // instead. See the commit-model note on [RipDpiConnectionActuator].
    val requiresConfirmation = actionEnabled && dragEnabled && !state.isActivationAvailable
    val focusRequester = remember { FocusRequester() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val commit =
        rememberActuatorCommit(
            state = state,
            requiresConfirmation = requiresConfirmation,
            armed = armed,
            carriage = dragOffsetPx,
            travelPx = travelPx,
            performHaptic = performHaptic,
            onActivate = onActivate,
            onDeactivate = onDeactivate,
        )

    val draggableState =
        rememberDraggableState { delta ->
            val orientedDelta = if (isRtl) -delta else delta
            scope.launch {
                dragOffsetPx.snapTo(
                    (dragOffsetPx.value + orientedDelta)
                        .coerceIn(-travelPx.floatValue, travelPx.floatValue),
                )
            }
        }
    val modifier =
        Modifier
            .actuatorTapCommit(
                enabled = actionEnabled,
                toggleableState = state.status.toToggleableState(),
                onCommit = { commit() },
            ).actuatorSemantics(
                state = state,
                nodeTestTag = nodeTestTag,
                actionEnabled = actionEnabled,
                armed = armed.value,
                focusRequester = focusRequester,
                onCommit = commit,
            ).actuatorKeyCommit(
                enabled = actionEnabled,
                focusRequester = focusRequester,
                onCommit = commit,
            ).draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = actionEnabled,
                onDragStarted = {
                    // A drag is the other way to commit the same action, so it
                    // takes the control back from a pending confirmation.
                    armed.value = false
                },
                onDragStopped = {
                    // The gesture is always consumed so a horizontal swipe never
                    // degrades into a tap, but it only commits where a rail exists.
                    val committed =
                        dragEnabled &&
                            handleActuatorDragStop(
                                state = state,
                                dragDeltaPx = dragOffsetPx.value,
                                travelPx = travelPx.floatValue,
                                performHaptic = performHaptic,
                                onActivate = onActivate,
                                onDeactivate = onDeactivate,
                            )
                    // A committed gesture holds the carriage where the finger left
                    // it and waits for the status to land. Zeroing here instead
                    // rewound the carriage to the start of the drag for as long as
                    // the ViewModel took to answer, so a successful drag visibly
                    // undid itself before the connection began.
                    if (!committed) {
                        dragOffsetPx.animateTo(targetValue = 0f, animationSpec = returnSpec)
                    }
                },
            )

    return ActuatorInteractionModifier(
        modifier = modifier,
        dragDeltaPx = dragOffsetPx.asState(),
        onTravelChanged = { distancePx -> travelPx.floatValue = distancePx },
    )
}

/**
 * The platform tap. A release now routes it through the arm-then-confirm step
 * rather than being denied one, so this is mounted in every state the action is
 * available instead of only where a tap was allowed to commit outright.
 */
private fun Modifier.actuatorTapCommit(
    enabled: Boolean,
    toggleableState: ToggleableState,
    onCommit: () -> Unit,
): Modifier =
    if (enabled) {
        triStateToggleable(
            state = toggleableState,
            enabled = true,
            role = Role.Switch,
            onClick = onCommit,
        )
    } else {
        this
    }

/**
 * Every semantics property for the rail, declared in one block.
 *
 * Two semantics sources on this node produce two platform accessibility nodes:
 * one interactive but nameless, one named but inert, which is how TalkBack
 * ended up announcing an unlabelled switch. Clearing first collapses them, so
 * role and the click action are restated here rather than inherited from
 * `triStateToggleable`.
 */
private fun Modifier.actuatorSemantics(
    state: HomeConnectionActuatorUiState,
    nodeTestTag: String?,
    actionEnabled: Boolean,
    armed: Boolean,
    focusRequester: FocusRequester,
    onCommit: () -> Boolean,
): Modifier =
    clearAndSetSemantics {
        nodeTestTag?.let { testTag = it }
        role = Role.Switch
        toggleableState = state.status.toToggleableState()
        contentDescription = state.actionLabel
        // No stateDescription here: this string is already the visible headline,
        // which is its own node, so carrying it on the switch too made TalkBack
        // read the status twice on every landing and every transition. Role plus
        // toggleableState still announce on/off; the headline owns the prose.
        if (actionEnabled) {
            onClick(label = state.actionLabel) { onCommit() }
        }
        // The live region is scoped to the pending confirmation for the same
        // reason: the headline still reports the line rather than the prompt, so
        // arming is the one change on this node with no other surface to
        // announce it.
        if (armed) {
            liveRegion = LiveRegionMode.Polite
        }
        // The reset above drops the toggleable's own RequestFocus action, so the
        // focus entry point is restated here against the same requester the tap
        // is mounted with. Without it the rail is unreachable by keyboard.
        if (actionEnabled) {
            requestFocus {
                focusRequester.requestFocus()
                true
            }
        }
    }

/**
 * Keyboard and D-pad commit, plus the focus target the semantics reset above
 * points at. `triStateToggleable` carries key activation of its own, but only
 * for a node that is focusable through its own semantics; clearing them leaves
 * this the one handler that fires, which is also what keeps a single keypress
 * from arming and committing in one go.
 */
private fun Modifier.actuatorKeyCommit(
    enabled: Boolean,
    focusRequester: FocusRequester,
    onCommit: () -> Boolean,
): Modifier =
    if (enabled) {
        focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key in ActuatorCommitKeys) {
                    onCommit()
                } else {
                    false
                }
            }.focusable()
    } else {
        this
    }

private fun HomeConnectionActuatorStatus.toToggleableState(): ToggleableState =
    when (this) {
        HomeConnectionActuatorStatus.Open,
        HomeConnectionActuatorStatus.Fault,
        -> ToggleableState.Off

        HomeConnectionActuatorStatus.Locked,
        HomeConnectionActuatorStatus.Degraded,
        -> ToggleableState.On

        HomeConnectionActuatorStatus.Engaging -> ToggleableState.Indeterminate
    }

private fun invokeActuatorClick(
    state: HomeConnectionActuatorUiState,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
): Boolean {
    if (state.isActivationAvailable) {
        performHaptic(RipDpiHapticFeedback.Action)
        onActivate()
    } else {
        performHaptic(RipDpiHapticFeedback.Toggle)
        onDeactivate()
    }
    return true
}

/** Returns true when the gesture committed, so the caller can hold the carriage. */
private fun handleActuatorDragStop(
    state: HomeConnectionActuatorUiState,
    dragDeltaPx: Float,
    travelPx: Float,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
): Boolean {
    if (travelPx <= 0f) return false
    val commitDistance = travelPx * CommitDragThreshold
    val activated = state.isActivationAvailable && dragDeltaPx >= commitDistance
    val deactivated = state.isDeactivationAvailable && dragDeltaPx <= -commitDistance
    return when {
        activated -> {
            performHaptic(RipDpiHapticFeedback.Action)
            onActivate()
            true
        }

        deactivated -> {
            performHaptic(RipDpiHapticFeedback.Toggle)
            onDeactivate()
            true
        }

        else -> {
            false
        }
    }
}

private class ActuatorInteractionModifier(
    val modifier: Modifier,
    val dragDeltaPx: State<Float>,
    val onTravelChanged: (Float) -> Unit,
)

/**
 * Read-only exit indicator at the end of the rail.
 *
 * It is deliberately not clickable, and must not become clickable: it reports
 * which exit is in use, and the only action in this region is the rail's own
 * commit. It borrows chip styling, and its border carries a non-text contrast
 * requirement, so the resemblance to [com.poyka.ripdpi.ui.components.RipDpiChip]
 * is load-bearing rather than accidental. A tap here used to fall through and
 * release a live line; the rail's commit model now withholds that, so the slot
 * no longer has a destructive outcome hiding behind a button-shaped affordance.
 */
@Composable
private fun TerminalSlot(
    label: String?,
    icon: ImageVector,
    width: Dp,
    container: State<Color>,
    content: Color,
    border: Color,
) {
    val type = RipDpiThemeTokens.type
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.extraSmallCornerRadius)
    val metrics = RipDpiThemeTokens.components.actuator

    Row(
        modifier =
            Modifier
                .size(width = width, height = metrics.terminalSlotHeight)
                .clip(shape)
                .drawBehind { drawRect(container.value) }
                .border(RipDpiStroke.Thin, border, shape)
                .padding(horizontal = RipDpiThemeTokens.spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Small),
            tint = content,
        )
        if (label != null) {
            Spacer(modifier = Modifier.width(RipDpiThemeTokens.spacing.xs))
            Text(
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConnectionActuatorTerminalLabel),
                text = label,
                style = type.smallLabel,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActuatorCarriage(
    state: HomeConnectionActuatorUiState,
    carriageColor: State<Color>,
    carriageContentColor: Color,
    modifier: Modifier = Modifier,
) {
    val metrics = RipDpiThemeTokens.components.actuator
    val spacing = RipDpiThemeTokens.spacing
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.compactCornerRadius)

    Row(
        modifier =
            modifier
                .size(width = metrics.carriageWidth, height = metrics.carriageHeight)
                .clip(shape)
                .drawBehind { drawRect(carriageColor.value) }
                .border(RipDpiStroke.Thin, carriageContentColor, shape)
                .padding(horizontal = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            repeat(CarriageGripCount) {
                Box(
                    modifier =
                        Modifier
                            .size(width = metrics.gripWidth, height = metrics.gripHeight)
                            .background(carriageContentColor.copy(alpha = GripAlpha)),
                )
            }
        }
        Icon(
            imageVector = state.carriageIcon(),
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Default),
            tint = carriageContentColor,
        )
    }
}

@Composable
private fun ActuatorPipeline(
    stages: ImmutableList<HomeConnectionActuatorStageUiState>,
    useAccessibilityLayout: Boolean,
) {
    if (useAccessibilityLayout) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            stages.forEach { stage ->
                StageSegment(
                    stage = stage,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            stages.forEach { stage ->
                StageSegment(stage = stage)
            }
        }
    }
}

@Composable
private fun StageSegment(
    stage: HomeConnectionActuatorStageUiState,
    modifier: Modifier = Modifier,
) {
    val motion = RipDpiThemeTokens.motion
    val staticMotion = LocalInspectionMode.current || !motion.allowsInfiniteMotion
    val metrics = RipDpiThemeTokens.components.actuator
    val type = RipDpiThemeTokens.type
    val style =
        RipDpiThemeTokens.state.actuator.resolveStage(
            role = stage.state.toThemeRole(),
        )
    val pulseAlpha =
        if (style.pulsing && !staticMotion) {
            val transition = rememberInfiniteTransition(label = "actuatorStagePulse")
            transition.animateFloat(
                initialValue = 1f,
                targetValue =
                    if (stage.state == HomeConnectionActuatorStageState.Warning) {
                        WarningStagePulseAlpha
                    } else {
                        ActiveStagePulseAlpha
                    },
                // Reverse, not the default Restart: a restart hard-cuts alpha back
                // to full every cycle, which reads as a strobe rather than a pulse.
                animationSpec =
                    infiniteRepeatable(
                        animation = motion.stateTween(),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "actuatorStagePulseAlpha",
            )
        } else {
            rememberUpdatedState(1f)
        }
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.extraSmallCornerRadius)
    val stageStateDescription = stringResource(stage.state.stateDescriptionRes())

    Box(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.homeConnectionStage(stage.stage.stableKey))
                .heightIn(min = metrics.pipelineHeight)
                .clip(shape)
                .drawBehind { drawRect(style.container.copy(alpha = pulseAlpha.value)) }
                .stripedFill(enabled = style.striped, color = style.content.copy(alpha = 0.34f))
                .border(RipDpiStroke.Thin, style.border, shape)
                .semantics {
                    contentDescription = stage.label
                    stateDescription = stageStateDescription
                }.padding(
                    horizontal = metrics.stageHorizontalPadding,
                    vertical = RipDpiThemeTokens.spacing.xs,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stage.state.icon()?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(metrics.stageIconSize),
                    tint = style.content,
                )
                Spacer(modifier = Modifier.width(metrics.stageIconGap))
            }
            Text(
                text = stage.label,
                style = type.caption,
                color = style.content,
            )
        }
    }
}

private fun Modifier.stripedFill(
    enabled: Boolean,
    color: Color,
): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithContent {
            drawContent()
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(
                    color = color,
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = StripeStrokePx,
                )
                x += StripeStepPx
            }
        }
    }

private fun HomeConnectionActuatorStatus.toThemeRole(): RipDpiActuatorStateRole =
    when (this) {
        HomeConnectionActuatorStatus.Open -> RipDpiThemeTokens.stateRoles.actuator.open
        HomeConnectionActuatorStatus.Engaging -> RipDpiThemeTokens.stateRoles.actuator.engaging
        HomeConnectionActuatorStatus.Locked -> RipDpiThemeTokens.stateRoles.actuator.locked
        HomeConnectionActuatorStatus.Degraded -> RipDpiThemeTokens.stateRoles.actuator.degraded
        HomeConnectionActuatorStatus.Fault -> RipDpiThemeTokens.stateRoles.actuator.fault
    }

private fun HomeConnectionActuatorStageState.toThemeRole(): RipDpiActuatorStageRole =
    when (this) {
        HomeConnectionActuatorStageState.Pending -> RipDpiThemeTokens.stateRoles.actuatorStage.pending
        HomeConnectionActuatorStageState.Active -> RipDpiThemeTokens.stateRoles.actuatorStage.active
        HomeConnectionActuatorStageState.Complete -> RipDpiThemeTokens.stateRoles.actuatorStage.complete
        HomeConnectionActuatorStageState.Warning -> RipDpiThemeTokens.stateRoles.actuatorStage.warning
        HomeConnectionActuatorStageState.Failed -> RipDpiThemeTokens.stateRoles.actuatorStage.failed
    }

private fun HomeConnectionActuatorStatus.icon() =
    when (this) {
        HomeConnectionActuatorStatus.Open -> RipDpiIcons.Offline
        HomeConnectionActuatorStatus.Engaging -> RipDpiIcons.Vpn
        HomeConnectionActuatorStatus.Locked -> RipDpiIcons.Lock
        HomeConnectionActuatorStatus.Degraded -> RipDpiIcons.Warning
        HomeConnectionActuatorStatus.Fault -> RipDpiIcons.Error
    }

/**
 * The terminal slot reports whether the line is actually locked. A closed
 * padlock while the headline reads "disengaged" is the contradiction this
 * replaces.
 */
private fun HomeConnectionActuatorStatus.terminalIcon() =
    when (this) {
        HomeConnectionActuatorStatus.Locked,
        HomeConnectionActuatorStatus.Degraded,
        -> RipDpiIcons.Lock

        HomeConnectionActuatorStatus.Open,
        HomeConnectionActuatorStatus.Engaging,
        HomeConnectionActuatorStatus.Fault,
        -> RipDpiIcons.LockOpen
    }

/** Points at the direction the drag has to go, so the gesture is self-describing. */
private fun HomeConnectionActuatorUiState.carriageIcon() =
    when {
        isActivationAvailable -> RipDpiIcons.ChevronRight
        isDeactivationAvailable -> RipDpiIcons.ChevronLeft
        else -> status.icon()
    }

private fun HomeConnectionActuatorStageState.icon() =
    when (this) {
        HomeConnectionActuatorStageState.Complete -> RipDpiIcons.Check

        HomeConnectionActuatorStageState.Warning -> RipDpiIcons.Warning

        HomeConnectionActuatorStageState.Failed -> RipDpiIcons.Error

        HomeConnectionActuatorStageState.Pending,
        HomeConnectionActuatorStageState.Active,
        -> null
    }

private val HomeConnectionActuatorStatus.showsPipeline: Boolean
    get() =
        when (this) {
            HomeConnectionActuatorStatus.Open,
            HomeConnectionActuatorStatus.Locked,
            -> false

            HomeConnectionActuatorStatus.Engaging,
            HomeConnectionActuatorStatus.Degraded,
            HomeConnectionActuatorStatus.Fault,
            -> true
        }

private fun HomeConnectionActuatorStageState.stateDescriptionRes(): Int =
    when (this) {
        HomeConnectionActuatorStageState.Pending -> R.string.home_connection_stage_state_pending
        HomeConnectionActuatorStageState.Active -> R.string.home_connection_stage_state_active
        HomeConnectionActuatorStageState.Complete -> R.string.home_connection_stage_state_complete
        HomeConnectionActuatorStageState.Warning -> R.string.home_connection_stage_state_warning
        HomeConnectionActuatorStageState.Failed -> R.string.home_connection_stage_state_failed
    }
