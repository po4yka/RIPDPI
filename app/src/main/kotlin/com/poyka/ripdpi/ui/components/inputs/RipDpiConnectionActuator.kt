package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
private val StageIconSize = 12.dp

@Composable
fun RipDpiConnectionActuator(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val motion = RipDpiThemeTokens.motion
    val metrics = RipDpiThemeTokens.components.actuator
    val density = LocalDensity.current
    val performHaptic = rememberRipDpiHapticPerformer()
    val stateStyle =
        RipDpiThemeTokens.state.actuator.resolve(
            role = state.status.toThemeRole(),
        )
    val railColor by animateColorAsState(stateStyle.rail, motion.stateTween(), label = "actuatorRail")
    val carriageColor by animateColorAsState(stateStyle.carriage, motion.stateTween(), label = "actuatorCarriage")
    val terminalColor by animateColorAsState(stateStyle.terminal, motion.stateTween(), label = "actuatorTerminal")
    val baseFraction by animateFloatAsState(
        targetValue = state.carriageFraction.coerceIn(0f, 1f),
        animationSpec = motion.stateTween(),
        label = "actuatorCarriageFraction",
    )
    val interactionModifier =
        rememberActuatorInteractionModifier(
            state = state,
            onActivate = onActivate,
            onDeactivate = onDeactivate,
            performHaptic = performHaptic,
        )

    Column(
        modifier =
            modifier
                .ripDpiTestTag(testTag)
                .then(interactionModifier.modifier),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(metrics.height)
                    .onSizeChanged { interactionModifier.onRailWidthChanged(it.width.toFloat()) },
        ) {
            val carriageWidthPx = with(density) { metrics.carriageWidth.toPx() }
            val travelPx = (constraints.maxWidth - carriageWidthPx).coerceAtLeast(0f)
            val dragFraction = if (travelPx > 0f) interactionModifier.dragDeltaPx / travelPx else 0f
            val effectiveFraction = (baseFraction + dragFraction).coerceIn(0f, 1f)
            val carriageOffset = (effectiveFraction * travelPx).roundToInt()

            ActuatorRail(
                modifier = Modifier.align(Alignment.Center),
                state = state,
                railColor = railColor,
                terminalColor = terminalColor,
                stateStyle = stateStyle,
            )
            ActuatorCarriage(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(x = carriageOffset, y = 0) },
                state = state,
                carriageColor = carriageColor,
                carriageContentColor = stateStyle.carriageContent,
            )
        }
        ActuatorPipeline(stages = state.stages)
    }
}

@Composable
private fun rememberActuatorInteractionModifier(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
): ActuatorInteractionModifier {
    var dragDeltaPx by remember(state.status) { mutableFloatStateOf(0f) }
    var railWidthPx by remember { mutableFloatStateOf(0f) }
    val dragEnabled = state.isActivationAvailable || state.isDeactivationAvailable
    val draggableState =
        rememberDraggableState { delta ->
            dragDeltaPx = (dragDeltaPx + delta).coerceIn(-railWidthPx, railWidthPx)
        }
    val modifier =
        Modifier
            .semantics {
                role = Role.Switch
                contentDescription = state.statusDescription
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
                        dragDeltaPx = dragDeltaPx,
                        railWidthPx = railWidthPx,
                        performHaptic = performHaptic,
                        onActivate = onActivate,
                        onDeactivate = onDeactivate,
                    )
                    dragDeltaPx = 0f
                },
            )

    return ActuatorInteractionModifier(
        modifier = modifier,
        dragDeltaPx = dragDeltaPx,
        onRailWidthChanged = { widthPx -> railWidthPx = widthPx },
    )
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
    val dragDeltaPx: Float,
    val onRailWidthChanged: (Float) -> Unit,
)

@Composable
private fun ActuatorRail(
    state: HomeConnectionActuatorUiState,
    railColor: Color,
    terminalColor: Color,
    stateStyle: com.poyka.ripdpi.ui.theme.RipDpiActuatorStateStyle,
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
                .background(railColor)
                .border(RipDpiStroke.Thin, stateStyle.railBorder, shape)
                .padding(horizontal = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.leadingLabel,
                style = type.caption,
                color = stateStyle.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                modifier =
                    Modifier
                        .ripDpiTestTag(RipDpiTestTags.HomeConnectionRouteLabel)
                        .padding(horizontal = spacing.md),
                text = state.routeLabel,
                style = type.smallLabel,
                color = stateStyle.routeLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TerminalSlot(
                label = state.trailingLabel,
                container = terminalColor,
                content = stateStyle.slotContent,
                border = stateStyle.terminalBorder,
            )
        }
    }
}

@Composable
private fun TerminalSlot(
    label: String,
    container: Color,
    content: Color,
    border: Color,
) {
    val metrics = RipDpiThemeTokens.components.actuator
    val type = RipDpiThemeTokens.type
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.extraSmallCornerRadius)

    Row(
        modifier =
            Modifier
                .size(width = metrics.terminalSlotWidth, height = metrics.terminalSlotHeight)
                .clip(shape)
                .background(container)
                .border(RipDpiStroke.Thin, border, shape)
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = RipDpiIcons.Lock,
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Small),
            tint = content,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = type.smallLabel,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActuatorCarriage(
    state: HomeConnectionActuatorUiState,
    carriageColor: Color,
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
                .background(carriageColor)
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
private fun ActuatorPipeline(stages: List<HomeConnectionActuatorStageUiState>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        stages.forEach { stage ->
            StageSegment(
                stage = stage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StageSegment(
    stage: HomeConnectionActuatorStageUiState,
    modifier: Modifier = Modifier,
) {
    val motion = RipDpiThemeTokens.motion
    val metrics = RipDpiThemeTokens.components.actuator
    val type = RipDpiThemeTokens.type
    val style =
        RipDpiThemeTokens.state.actuator.resolveStage(
            role = stage.state.toThemeRole(),
        )
    val pulse =
        if (style.pulsing && motion.allowsInfiniteMotion) {
            val transition = rememberInfiniteTransition(label = "actuatorStagePulse")
            val pulseAlpha =
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
            pulseAlpha.value
        } else {
            1f
        }
    val shape = RoundedCornerShape(RipDpiThemeTokens.components.shapes.extraSmallCornerRadius)

    Box(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.homeConnectionStage(stage.stage.stableKey))
                .height(metrics.pipelineHeight)
                .clip(shape)
                .background(style.container.copy(alpha = pulse))
                .stripedFill(enabled = style.striped, color = style.content.copy(alpha = 0.34f))
                .border(RipDpiStroke.Thin, style.border, shape)
                .semantics {
                    contentDescription = stage.label
                    stateDescription = stage.state.name.lowercase()
                }.padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stage.state.icon()?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(StageIconSize),
                    tint = style.content,
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = stage.label,
                style = type.caption,
                color = style.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
