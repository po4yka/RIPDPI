package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlin.math.roundToInt

private const val ActivateDragThreshold = 0.72f
private const val DeactivateDragThreshold = 0.28f
private const val ActiveStagePulseAlpha = 0.72f
private const val WarningStagePulseAlpha = 0.82f
private const val StripeStepPx = 10f
private const val StripeStrokePx = 2f
private const val CarriageGripCount = 4
private const val EndpointLabelHorizontalGapCount = 4
private const val AccessibilityLayoutFontScale = 1.5f

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
            onActivate = onActivate,
            onDeactivate = onDeactivate,
            performHaptic = performHaptic,
        )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(interactionModifier.modifier)
                    .ripDpiTestTag(testTag),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            ActuatorStateAction(
                state = state,
                stateStyle = stateStyle,
            )
            if (!useAccessibilityLayout) {
                ActuatorRailLayout(
                    state = state,
                    stateStyle = stateStyle,
                    railColor = railColor,
                    terminalColor = terminalColor,
                    carriageColor = carriageColor,
                    baseFraction = baseFraction,
                    interactionModifier = interactionModifier,
                )
            }
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
) {
    val metrics = RipDpiThemeTokens.components.actuator
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(metrics.height)
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorRail)
                .onSizeChanged { interactionModifier.onRailWidthChanged(it.width.toFloat()) },
    ) {
        val travelPx =
            (constraints.maxWidth - with(density) { metrics.carriageWidth.toPx() }).coerceAtLeast(0f)
        val endpointLayout =
            rememberActuatorEndpointLayout(
                availableWidth = maxWidth,
                leadingLabel = state.leadingLabel,
                trailingLabel = state.trailingLabel,
            )

        ActuatorRail(
            modifier = Modifier.align(Alignment.Center),
            state = state,
            railColor = railColor,
            terminalColor = terminalColor,
            stateStyle = stateStyle,
            endpointLayout = endpointLayout,
        )
        ActuatorCarriage(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val dragFraction =
                            if (travelPx > 0f) interactionModifier.dragDeltaPx.value / travelPx else 0f
                        val effectiveFraction = (baseFraction.value + dragFraction).coerceIn(0f, 1f)
                        IntOffset(x = (effectiveFraction * travelPx).roundToInt(), y = 0)
                    },
            state = state,
            carriageColor = carriageColor,
            carriageContentColor = stateStyle.carriageContent,
        )
    }
}

@Composable
private fun actuatorStateStyle(state: HomeConnectionActuatorUiState): RipDpiActuatorStateStyle =
    RipDpiThemeTokens.state.actuator.resolve(role = state.status.toThemeRole())

@Composable
private fun rememberActuatorEndpointLayout(
    availableWidth: Dp,
    leadingLabel: String,
    trailingLabel: String,
): ActuatorEndpointLayout {
    val metrics = RipDpiThemeTokens.components.actuator
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val leadingWidth = measureTextWidth(leadingLabel, type.caption, textMeasurer, density)
    val trailingWidth = measureTextWidth(trailingLabel, type.smallLabel, textMeasurer, density)
    val labeledTerminalWidth =
        maxOf(
            metrics.terminalSlotWidth,
            spacing.sm * 2 + RipDpiIconSizes.Small + spacing.xs + trailingWidth,
        )
    val requiredWidth =
        metrics.carriageWidth +
            leadingWidth +
            labeledTerminalWidth +
            spacing.md * EndpointLabelHorizontalGapCount
    val accessibilityLabelsFit =
        density.fontScale < EndpointLabelCollapseFontScale || availableWidth >= WideEndpointLabelWidth
    val showLabels = accessibilityLabelsFit && availableWidth >= requiredWidth
    return ActuatorEndpointLayout(
        showLabels = showLabels,
        terminalWidth = if (showLabels) labeledTerminalWidth else metrics.terminalSlotHeight,
    )
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

@Composable
private fun ActuatorStateAction(
    state: HomeConnectionActuatorUiState,
    stateStyle: com.poyka.ripdpi.ui.theme.RipDpiActuatorStateStyle,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.compactCornerRadius)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
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
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RipDpiThemeTokens.components.buttons.minHeight)
                    .clip(shape)
                    .background(stateStyle.carriage, shape)
                    .border(RipDpiStroke.Thin, stateStyle.carriageContent.copy(alpha = 0.38f), shape)
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
}

@Composable
private fun rememberActuatorInteractionModifier(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
): ActuatorInteractionModifier {
    val dragDeltaPx = remember(state.status) { mutableFloatStateOf(0f) }
    val railWidthPx = remember { mutableFloatStateOf(0f) }
    val dragEnabled = state.isActivationAvailable || state.isDeactivationAvailable
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val draggableState =
        rememberDraggableState { delta ->
            val orientedDelta = if (isRtl) -delta else delta
            dragDeltaPx.floatValue =
                (dragDeltaPx.floatValue + orientedDelta).coerceIn(-railWidthPx.floatValue, railWidthPx.floatValue)
        }
    val modifier =
        Modifier
            .then(
                if (dragEnabled) {
                    Modifier.clickable(
                        role = Role.Switch,
                        onClick = {
                            invokeActuatorClick(state, performHaptic, onActivate, onDeactivate)
                        },
                    )
                } else {
                    Modifier
                },
            ).semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = state.status.toToggleableState()
                contentDescription = state.routeLabel
                stateDescription = state.statusDescription
                liveRegion = LiveRegionMode.Polite
                if (dragEnabled) {
                    onClick(label = state.actionLabel) {
                        invokeActuatorClick(state, performHaptic, onActivate, onDeactivate)
                    }
                }
            }.draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = dragEnabled,
                onDragStopped = {
                    handleActuatorDragStop(
                        state = state,
                        dragDeltaPx = dragDeltaPx.floatValue,
                        railWidthPx = railWidthPx.floatValue,
                        performHaptic = performHaptic,
                        onActivate = onActivate,
                        onDeactivate = onDeactivate,
                    )
                    dragDeltaPx.floatValue = 0f
                },
            )

    return ActuatorInteractionModifier(
        modifier = modifier,
        dragDeltaPx = dragDeltaPx,
        onRailWidthChanged = { widthPx -> railWidthPx.floatValue = widthPx },
    )
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

private fun handleActuatorDragStop(
    state: HomeConnectionActuatorUiState,
    dragDeltaPx: Float,
    railWidthPx: Float,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
) {
    if (railWidthPx <= 0f) return
    val activated = state.isActivationAvailable && dragDeltaPx >= railWidthPx * ActivateDragThreshold
    val deactivated = state.isDeactivationAvailable && dragDeltaPx <= -railWidthPx * DeactivateDragThreshold
    when {
        activated -> {
            performHaptic(RipDpiHapticFeedback.Action)
            onActivate()
        }

        deactivated -> {
            performHaptic(RipDpiHapticFeedback.Toggle)
            onDeactivate()
        }
    }
}

private class ActuatorInteractionModifier(
    val modifier: Modifier,
    val dragDeltaPx: State<Float>,
    val onRailWidthChanged: (Float) -> Unit,
)

@Composable
private fun ActuatorRail(
    state: HomeConnectionActuatorUiState,
    railColor: State<Color>,
    terminalColor: State<Color>,
    stateStyle: com.poyka.ripdpi.ui.theme.RipDpiActuatorStateStyle,
    endpointLayout: ActuatorEndpointLayout,
    modifier: Modifier = Modifier,
) {
    val metrics = RipDpiThemeTokens.components.actuator
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.compactCornerRadius)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(metrics.railHeight)
                .clip(shape)
                .drawBehind { drawRect(railColor.value) }
                .border(RipDpiStroke.Thin, stateStyle.railBorder, shape)
                .padding(horizontal = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (endpointLayout.showLabels) {
                Text(
                    text = state.leadingLabel,
                    style = type.caption,
                    color = stateStyle.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TerminalSlot(
                label = state.trailingLabel.takeIf { endpointLayout.showLabels },
                width = endpointLayout.terminalWidth,
                container = terminalColor,
                content = stateStyle.slotContent,
                border = stateStyle.terminalBorder,
            )
        }
    }
}

@Composable
private fun TerminalSlot(
    label: String?,
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
            imageVector = RipDpiIcons.Lock,
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
                .border(RipDpiStroke.Thin, carriageContentColor.copy(alpha = 0.38f), shape)
                .padding(horizontal = spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(CarriageGripCount) {
            Box(
                modifier =
                    Modifier
                        .size(width = metrics.gripWidth, height = metrics.gripHeight)
                        .background(carriageContentColor.copy(alpha = 0.42f)),
            )
        }
        Icon(
            imageVector = state.status.icon(),
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Default),
            tint = carriageContentColor,
        )
    }
}

@Composable
private fun ActuatorPipeline(
    stages: List<HomeConnectionActuatorStageUiState>,
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
                animationSpec = infiniteRepeatable(animation = motion.stateTween()),
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
