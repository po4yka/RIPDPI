package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What the route diagram shows: the plug sits in the socket of [path], and power runs while [powered]. */
@Immutable
data class RouteDiagramUiState(
    val scope: Mode = Mode.VPN,
    val path: ConnectionPath = ConnectionPath.Direct,
    val powered: Boolean = false,
    /** A reconnect is in progress, so the route is not settled. */
    val busy: Boolean = false,
    /** The last strategy test on this network found no strategy that passes. */
    val directBlocked: Boolean = false,
    /** A relay is selected, so the Relay socket leads somewhere. */
    val relayReady: Boolean = false,
)

internal fun ConnectionUiState.toRouteDiagram(path: ConnectionPath): RouteDiagramUiState =
    RouteDiagramUiState(
        scope = activeMode,
        path = path,
        powered = runningMode != null,
        busy = runtimeOperation != null,
        directBlocked =
            strategyLanes?.let { test ->
                test.progress == null &&
                    test.lanes.isNotEmpty() &&
                    !test.otherNetwork &&
                    test.lanes.none {
                        it.outcome == StrategyLaneOutcome.Passed || it.outcome == StrategyLaneOutcome.Partial
                    }
            } == true,
        relayReady = usesRelayPath,
    )

// The diagram is drawn in these units and scaled to the width it gets.
private const val ViewWidth = 360f
private const val LabelTop = 82f
private const val DirectY = 60f
private const val RelayY = 160f
private const val MidY = (DirectY + RelayY) / 2
private const val DeviceX = 36f
private const val DnsX = 110f
private const val ExitX = 272f
private const val NetX = 334f
private const val PlugSeatX = 176f
private const val PlugPullOut = -15f
private const val SocketLeft = 200f
private const val NodeRadius = 17f
private const val LabelWidth = 72f
private const val MaxFrameSeconds = 0.032f
private const val NanosPerSecond = 1e9f
private const val Settled = 0.05f

// Share of the power-up at which each lamp lights, in route order.
private const val LitDevice = 0.04f
private const val LitDns = 0.24f
private const val LitSocket = 0.48f
private const val LitExit = 0.7f
private const val LitNet = 0.93f
private const val PowerRelayMissing = 0.55f
private const val PowerDirectBlocked = 0.8f

private fun pathY(path: ConnectionPath) = if (path == ConnectionPath.Direct) DirectY else RelayY

private fun RouteDiagramUiState.wantedPower(): Float =
    when {
        !powered || busy -> 0f
        path == ConnectionPath.Relay && !relayReady -> PowerRelayMissing
        path == ConnectionPath.Direct && directBlocked -> PowerDirectBlocked
        else -> 1f
    }

/** Spring state of the plug and cable. Plain fields: only the draw phase reads them, once per frame. */
@Suppress("MagicNumber") // Spring constants tuned in the web prototype; they have no meaning apart from each other.
internal class RouteMotion(
    target: Float,
    power: Float,
) {
    var plugY = target
    var cableY = target
    var plugX = 0f
    var power = power
    var sag = 18f
    var seatPulse = 1f
    var dragging = false
    private var plugVelocity = 0f
    private var cableVelocity = 0f
    private var pullVelocity = 0f
    private var pulledOut = false

    fun snap(
        target: Float,
        wantedPower: Float,
    ) {
        plugY = target
        cableY = target
        plugX = 0f
        power = wantedPower
        sag = 18f
        seatPulse = 1f
        plugVelocity = 0f
        cableVelocity = 0f
        pullVelocity = 0f
        pulledOut = false
    }

    /** Advances by [dt] seconds and returns true when nothing moves any more. */
    fun step(
        dt: Float,
        target: Float,
        wantedPower: Float,
    ): Boolean {
        if (!dragging) {
            plugVelocity += (260f * (target - plugY) - 21f * plugVelocity) * dt
            plugY += plugVelocity * dt
        }
        cableVelocity += (95f * (plugY - cableY) - 11f * cableVelocity) * dt
        cableY += cableVelocity * dt
        // The plug leaves its socket for the whole trip and seats again at the other one.
        val pull = if (dragging || abs(plugY - target) > 5f) PlugPullOut else 0f
        pullVelocity += (480f * (pull - plugX) - 28f * pullVelocity) * dt
        plugX += pullVelocity * dt
        power += (wantedPower - power).coerceIn(-dt / 0.4f, dt / 1.25f)
        if (plugX < -9f) pulledOut = true
        if (pulledOut && plugX > -1.5f && !dragging) {
            pulledOut = false
            seatPulse = 0f
        }
        if (seatPulse < 1f) seatPulse = min(1f, seatPulse + dt / 0.5f)
        val wantedSag = 18f + min(20f, abs(plugVelocity) * 0.035f)
        sag += (wantedSag - sag) * min(1f, dt * 9f)
        return !dragging &&
            abs(target - plugY) < Settled &&
            abs(plugVelocity) < 1f &&
            abs(plugY - cableY) < Settled &&
            abs(cableVelocity) < 1f &&
            abs(plugX - pull) < Settled &&
            abs(pullVelocity) < 1f &&
            power == wantedPower &&
            seatPulse >= 1f &&
            abs(sag - wantedSag) < Settled
    }
}

@Immutable
private data class RoutePalette(
    val line: Color,
    val surface: Color,
    val socket: Color,
    val well: Color,
    val metal: Color,
    val muted: Color,
    val route: Color,
    val on: Color,
    val warning: Color,
)

private class RouteLabels(
    val device: TextLayoutResult,
    val dns: TextLayoutResult,
    val direct: TextLayoutResult,
    val relay: TextLayoutResult,
) {
    val height = maxOf(device.size.height, dns.size.height, direct.size.height, relay.size.height)
}

/**
 * The route from the device to the internet. The plug sits in the Direct or the Relay socket, so the
 * diagram is also the control that selects the path: tap a socket, or drag the plug to it.
 */
@Composable
internal fun ConnectionRouteDiagram(
    state: RouteDiagramUiState,
    onPathSelected: (ConnectionPath) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionTokens = RipDpiThemeTokens.motion
    val colors = RipDpiThemeTokens.colors
    val palette =
        RoutePalette(
            line = colors.foreground,
            surface = colors.background,
            socket = colors.card,
            well = colors.outlineVariant,
            metal = colors.mutedForeground,
            muted = colors.mutedForeground,
            route = colors.signal,
            on = colors.success,
            warning = colors.warning,
        )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val motion = remember { RouteMotion(pathY(state.path), state.wantedPower()) }
    val frame = remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val selectPath by rememberUpdatedState(onPathSelected)

    RouteMotionEffect(state, motion, dragging) { frame.longValue = it }

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width = min(maxWidth.value, MaxDiagramWidth).dp
        val unit = width / ViewWidth
        val labels = rememberRouteLabels(state, unit.value)
        val labelHeight = with(LocalDensity.current) { labels.height.toDp() }
        val height = unit * (RelayY + LabelTop - DirectY) + labelHeight

        Box(
            Modifier
                .widthIn(max = width)
                .fillMaxWidth()
                .height(height)
                .selectableGroup(),
        ) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {}
                    // With animations off no frame loop runs, so each move of the finger asks for a redraw.
                    .plugDrag(
                        rtl,
                        motion,
                        onDragging = { dragging = it },
                        onMove = { frame.longValue++ },
                    ) { selectPath(it) },
            ) {
                // Reading the frame here redraws the canvas each frame without a recomposition.
                val now = frame.longValue
                val seconds = (now / NanosPerSecond).takeIf { motionTokens.allowsInfiniteMotion }
                val scale = size.width / ViewWidth
                scale(scale, scale, pivot = Offset.Zero) {
                    scale(if (rtl) -1f else 1f, 1f, pivot = Offset(ViewWidth / 2, 0f)) {
                        drawRoute(state, motion, palette, seconds)
                    }
                    drawNodes(state, motion, palette, labels, seconds)
                }
            }
            ConnectionPath.entries.forEach { path ->
                RoutePathTarget(
                    path = path,
                    selected = state.path == path,
                    onClick = { onPathSelected(path) },
                    modifier =
                        Modifier
                            .align(if (rtl) Alignment.TopStart else Alignment.TopEnd)
                            .offset(y = unit * (pathY(path) - TargetHalfHeight))
                            // A tall label must not push one target over the other.
                            .size(
                                unit * TargetWidth,
                                minOf(
                                    unit * TargetHalfHeight * 2 + labelHeight,
                                    unit * (RelayY - DirectY),
                                ),
                            ),
                )
            }
        }
    }
}

/** Runs the springs while something moves, and without end only while traffic or a reconnect shows. */
@Composable
private fun RouteMotionEffect(
    state: RouteDiagramUiState,
    motion: RouteMotion,
    dragging: Boolean,
    onFrame: (Long) -> Unit,
) {
    val motionTokens = RipDpiThemeTokens.motion
    val frame by rememberUpdatedState(onFrame)
    LaunchedEffect(state, dragging, motionTokens.animationsEnabled, motionTokens.allowsInfiniteMotion) {
        val target = pathY(state.path)
        val power = state.wantedPower()
        if (!motionTokens.animationsEnabled) {
            if (!dragging) motion.snap(target, power)
            frame(System.nanoTime())
            return@LaunchedEffect
        }
        // The traffic dots and the dashes of a reconnect keep moving; everything else comes to rest.
        val endless = motionTokens.allowsInfiniteMotion && (power == 1f || state.busy)
        var last = 0L
        var settled = false
        while (!settled || endless) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min(MaxFrameSeconds, (now - last) / NanosPerSecond)
                last = now
                settled = motion.step(dt, target, power) && dt > 0f
                frame(now)
            }
        }
    }
}

/** The plug follows a finger that starts on it, and drops into the nearer socket. */
private fun Modifier.plugDrag(
    rtl: Boolean,
    motion: RouteMotion,
    onDragging: (Boolean) -> Unit,
    onMove: () -> Unit,
    onDrop: (ConnectionPath) -> Unit,
): Modifier =
    this
        .pointerInput(rtl) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val scale = size.width / ViewWidth
                val x = (if (rtl) size.width - down.position.x else down.position.x) / scale
                val onPlug =
                    x in PlugGrabLeft..PlugGrabRight &&
                        abs(down.position.y / scale - motion.plugY) < PlugGrabReach
                if (!onPlug) return@awaitEachGesture
                motion.dragging = true
                onDragging(true)
                drag(down.id) { change ->
                    change.consume()
                    motion.plugY = (change.position.y / scale).coerceIn(DirectY - PlugDragSlack, RelayY + PlugDragSlack)
                    onMove()
                }
                motion.dragging = false
                onDragging(false)
                onDrop(if (motion.plugY < MidY) ConnectionPath.Direct else ConnectionPath.Relay)
            }
        }

private const val MaxDiagramWidth = 420f
private const val PlugDragSlack = 8f
private const val PlugGrabLeft = 150f
private const val PlugGrabRight = SocketLeft
private const val PlugGrabReach = 28f
private const val TargetWidth = ViewWidth - SocketLeft
private const val TargetHalfHeight = 30f

/** One socket with its node: the whole control for a path, so name, role, state, and action sit in one block. */
@Composable
private fun RoutePathTarget(
    path: ConnectionPath,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(pathTitleRes(path))
    val tag = RipDpiTestTags.connectionPath(path.stableKey)
    Box(
        modifier
            .ripDpiClickable(role = Role.Tab, onClick = onClick)
            // One semantics block: a second source on this node would split it into two accessibility nodes.
            .clearAndSetSemantics {
                contentDescription = name
                // The sockets switch the section under the diagram, as the tabs they replace did.
                role = Role.Tab
                this.selected = selected
                testTag = tag
                onClick {
                    onClick()
                    true
                }
            },
    )
}

@Composable
private fun rememberRouteLabels(
    state: RouteDiagramUiState,
    unitDp: Float,
): RouteLabels {
    val measurer = rememberTextMeasurer()
    val style = RipDpiThemeTokens.type.caption.copy(textAlign = TextAlign.Center)
    val density = LocalDensity.current
    val widthPx = with(density) { (LabelWidth * unitDp).dp.roundToPx() }
    val device = stringResource(modeLabelRes(state.scope))
    val dns = stringResource(R.string.dbs_ip_setting)
    val direct = stringResource(R.string.config_path_direct)
    val relay = stringResource(R.string.config_path_relay)
    return remember(measurer, style, widthPx, device, dns, direct, relay) {
        fun measure(text: String) =
            measurer.measure(
                text = text,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints.fixedWidth(widthPx),
            )
        RouteLabels(measure(device), measure(dns), measure(direct), measure(relay))
    }
}

@Suppress("MagicNumber")
private fun lit(
    power: Float,
    threshold: Float,
) = ((power - threshold) * 7f).coerceIn(0f, 1f)

@Suppress("MagicNumber")
private fun exitTail(y: Float): Path =
    Path().apply {
        val up = y < MidY
        moveTo(SocketLeft + 32f, y)
        lineTo(ExitX - NodeRadius, y)
        moveTo(ExitX + NodeRadius, y)
        cubicTo(310f, y, 316f, if (up) 80f else 140f, 322f, if (up) 97f else 123f)
    }

/** Where the plug and the end of its cable are in this frame. */
@Suppress("MagicNumber")
private class PlugPose(
    motion: RouteMotion,
) {
    val y = motion.plugY
    val x = PlugSeatX + motion.plugX
    val free = (motion.plugX / PlugPullOut).coerceIn(0f, 1f)
    val angle = atan2(-(motion.cableY - y) * 0.7f * free, 34f)
    val cableEnd = Offset(x - 10f * cos(angle), y - 10f * sin(angle))
    val sag = motion.sag

    /** The share of the way from the Relay socket to the Direct socket. */
    val direct = 1f - ((y - DirectY) / (RelayY - DirectY)).coerceIn(0f, 1f)

    /** Continues [path] from the DNS stub along the hanging cable to the plug. */
    fun cableTo(path: Path) =
        path.cubicTo(
            152f,
            DirectY + sag * 1.6f,
            cableEnd.x - 30f * cos(angle),
            cableEnd.y - 30f * sin(angle) + sag * 0.35f,
            cableEnd.x,
            cableEnd.y,
        )
}

/**
 * Cables, sockets, and the plug. Mirrored as a whole in right-to-left layouts.
 * shortcut: each frame builds its paths anew; cache them in [RouteMotion] if a frame trace shows the cost.
 */
private fun DrawScope.drawRoute(
    state: RouteDiagramUiState,
    motion: RouteMotion,
    palette: RoutePalette,
    seconds: Float?,
) {
    val pose = PlugPose(motion)
    drawCables(state, pose, palette, seconds ?: 0f)
    drawPower(motion.power, pose, palette, seconds)
    ConnectionPath.entries.forEach { path -> drawSocketState(path, state, motion, pose.free, palette) }
    translate(pose.x, pose.y) {
        rotate(Math.toDegrees(pose.angle.toDouble()).toFloat(), pivot = Offset.Zero) { drawPlug(palette) }
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawCables(
    state: RouteDiagramUiState,
    pose: PlugPose,
    palette: RoutePalette,
    seconds: Float,
) {
    // A reconnect has no settled route, so its cables are dashed.
    val dash = if (state.busy) PathEffect.dashPathEffect(floatArrayOf(2f, 7f), -seconds * 22f) else null
    drawLine(palette.line, Offset(53f, DirectY), Offset(94f, DirectY), 3.2f, StrokeCap.Round, dash)
    drawRoundRect(palette.line, Offset(125f, 56f), Size(5f, 8f), CornerRadius(1.5f))
    val lead = Path().apply { moveTo(128f, DirectY) }.also(pose::cableTo)
    drawPath(lead, palette.line, style = Stroke(width = 3.2f, cap = StrokeCap.Round, pathEffect = dash))
    listOf(DirectY to pose.direct, RelayY to 1f - pose.direct).forEach { (tailY, share) ->
        drawPath(
            exitTail(tailY),
            palette.line,
            alpha = 0.3f + 0.7f * share,
            style = Stroke(1.1f + 2.1f * share, cap = StrokeCap.Round, pathEffect = dash),
        )
    }
}

/** The power that sweeps along the route at start, and the traffic that runs on it after. */
@Suppress("MagicNumber")
private fun DrawScope.drawPower(
    power: Float,
    pose: PlugPose,
    palette: RoutePalette,
    seconds: Float?,
) {
    if (power <= 0f) return
    val up = pose.y < MidY
    val route =
        Path().apply {
            moveTo(53f, DirectY)
            lineTo(128f, DirectY)
            pose.cableTo(this)
            lineTo(pose.x, pose.y)
            lineTo(288f, pose.y)
            cubicTo(310f, pose.y, 316f, if (up) 80f else 140f, 322f, if (up) 97f else 123f)
        }
    val measure = PathMeasure().apply { setPath(route, false) }
    val length = measure.length
    if (power < 1f) {
        val sweep = Path()
        measure.getSegment(0f, length * power, sweep, true)
        drawPath(sweep, palette.route, alpha = 0.9f, style = Stroke(3.6f, cap = StrokeCap.Round))
    }
    val flow = ((power - LitNet) * 14f).coerceIn(0f, 1f)
    if (seconds == null || flow <= 0f) return
    repeat(6) { index ->
        val outbound = index < 3
        val phase = (seconds / (if (outbound) 2.6f else 3.3f) + (index % 3) / 3f) % 1f
        val at = (if (outbound) phase else 1f - phase) * length
        val position = measure.getPosition(at)
        val tangent = measure.getTangent(at)
        val side = if (outbound) -2.7f else 2.7f
        // The plug and the socket cover the cable, so no dot shows inside them.
        if (position.x < pose.x - 2f || position.x > SocketLeft + 34f) {
            drawCircle(
                color = if (outbound) palette.route else palette.on,
                radius = 2.6f,
                center = Offset(position.x - tangent.y * side, position.y + tangent.x * side),
                alpha = flow * (min(phase, 1f - phase) * 9f).coerceIn(0f, 1f),
            )
        }
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawSocketState(
    path: ConnectionPath,
    state: RouteDiagramUiState,
    motion: RouteMotion,
    free: Float,
    palette: RoutePalette,
) {
    val y = pathY(path)
    val active = state.path == path
    // A Relay socket with no relay behind it warns; it cannot carry power.
    val deadEnd = path == ConnectionPath.Relay && !state.relayReady
    val color = if (deadEnd) palette.warning else palette.on
    if (active && motion.seatPulse < 1f) {
        drawCircle(
            color = color,
            radius = 16f + 16f * motion.seatPulse,
            center = Offset(SocketLeft + 16f, y),
            alpha = 0.7f * (1f - motion.seatPulse),
            style = Stroke(2f),
        )
    }
    drawSocket(y, palette, holeAlpha = if (active) 0.25f + 0.75f * free else 1f)
    val seated = if (motion.plugX > -3f) 1f else 0f
    val lamp =
        when {
            !active -> 0f
            deadEnd -> seated * if (state.powered) 1f else 0.55f
            else -> lit(motion.power, LitSocket) * seated
        }
    drawLamp(Offset(SocketLeft + 25f, y - 18f), lamp, color, palette)
}

@Suppress("MagicNumber")
private fun DrawScope.drawSocket(
    y: Float,
    palette: RoutePalette,
    holeAlpha: Float,
) {
    val outline = Stroke(1.3f)
    drawRoundRect(palette.socket, Offset(219f, y - 22f), Size(12f, 9f), CornerRadius(3f))
    drawRoundRect(palette.line, Offset(219f, y - 22f), Size(12f, 9f), CornerRadius(3f), style = Stroke(1.1f))
    drawRoundRect(palette.socket, Offset(SocketLeft, y - 15f), Size(32f, 30f), CornerRadius(8f))
    drawRoundRect(palette.line, Offset(SocketLeft, y - 15f), Size(32f, 30f), CornerRadius(8f), style = outline)
    drawRoundRect(palette.well, Offset(203f, y - 11f), Size(12f, 22f), CornerRadius(3.5f))
    listOf(-6.5f, 3.5f).forEach { hole ->
        drawRoundRect(palette.line, Offset(205f, y + hole), Size(8f, 3f), CornerRadius(1.5f), alpha = holeAlpha)
    }
    drawLine(palette.line, Offset(219f, y - 8f), Offset(219f, y + 8f), 0.8f, alpha = 0.3f)
    drawCircle(palette.line, 2f, Offset(225.5f, y), alpha = 0.5f, style = Stroke(0.9f))
}

@Suppress("MagicNumber")
private fun DrawScope.drawPlug(palette: RoutePalette) {
    listOf(-6.5f, 3.5f).forEach { prong ->
        drawRoundRect(palette.metal, Offset(24f, prong), Size(10f, 3f), CornerRadius(1.2f))
    }
    drawPath(PlugRelief, palette.line)
    drawRoundRect(palette.line, Offset(0f, -10.5f), Size(21f, 21f), CornerRadius(5.5f))
    drawRoundRect(palette.metal, Offset(19.5f, -8.5f), Size(5f, 17f), CornerRadius(1.6f))
    listOf(6f, 9.5f, 13f).forEach { grip ->
        drawLine(palette.surface, Offset(grip, -4f), Offset(grip, 4f), 1.5f, StrokeCap.Round, alpha = 0.5f)
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawLamp(
    center: Offset,
    level: Float,
    color: Color,
    palette: RoutePalette,
) {
    if (level > 0f) drawCircle(color, 7f, center, alpha = 0.22f * level)
    drawCircle(palette.surface, 3.6f, center)
    drawCircle(palette.line, 3.6f, center, style = Stroke(0.8f))
    drawCircle(palette.muted, 2.2f, center, alpha = 0.7f)
    if (level > 0f) drawCircle(color, 2.2f, center, alpha = level)
}

private fun svgPath(data: String): Path = PathParser().parsePathString(data).toPath()

private val PlugRelief = svgPath("M-10,-2.6 Q-5,-3.4 0,-6.5 V6.5 Q-5,3.4 -10,2.6 Z")
private val ShieldIcon = svgPath("M0,-9 L8,-6 V1 C8,6 4,8 0,10 C-4,8 -8,6 -8,1 V-6 Z M-3,0 L-1,2.5 L3.5,-2.5")
private val GlobeIcon = svgPath("M-9,0 H9 M0,-9 C5,-4 5,4 0,9 M0,-9 C-5,-4 -5,4 0,9")

/** Nodes with their icons, lamps, and labels. Not mirrored, so icons and text keep their direction. */
private fun DrawScope.drawNodes(
    state: RouteDiagramUiState,
    motion: RouteMotion,
    palette: RoutePalette,
    labels: RouteLabels,
    seconds: Float?,
) {
    val rtl = layoutDirection == LayoutDirection.Rtl

    fun at(
        x: Float,
        y: Float,
    ) = Offset(if (rtl) ViewWidth - x else x, y)
    drawEntryNodes(state, motion.power, palette, labels, ::at)
    drawExitNodes(state, motion, palette, labels, ::at)

    val net = at(NetX, MidY)
    val netLit = lit(motion.power, LitNet)
    if (seconds != null && netLit > 0f) {
        val breath = 1f + sin(seconds * NetBreathRate)
        drawCircle(palette.on, NodeRadius + NetBreathReach * breath * netLit, net, alpha = NetBreathAlpha * netLit)
    }
    drawNode(net, 1f, null, palette) {
        drawCircle(palette.line, GlobeRadius, Offset.Zero, style = IconStroke)
        drawPath(GlobeIcon, palette.line, style = IconStroke)
    }
    drawNodeLamp(net, netLit, palette.on, palette)
}

private const val NetBreathRate = 1.7f
private const val NetBreathReach = 5f
private const val NetBreathAlpha = 0.16f
private const val GlobeRadius = 9f
private val IconStroke = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** The device and the DNS node: the part of the route both paths share. */
@Suppress("MagicNumber")
private fun DrawScope.drawEntryNodes(
    state: RouteDiagramUiState,
    power: Float,
    palette: RoutePalette,
    labels: RouteLabels,
    at: (Float, Float) -> Offset,
) {
    val device = at(DeviceX, DirectY)
    drawNode(device, 1f, labels.device, palette) {
        if (state.scope == Mode.VPN) {
            drawRoundRect(palette.line, Offset(-6f, -9f), Size(12f, 18f), CornerRadius(2.5f), style = IconStroke)
            drawLine(palette.line, Offset(-2f, 6f), Offset(2f, 6f), 1.5f, StrokeCap.Round)
        } else {
            drawRoundRect(palette.line, Offset(-8f, -8f), Size(16f, 16f), CornerRadius(3f), style = IconStroke)
            drawCircle(palette.line, 3f, Offset.Zero, style = IconStroke)
        }
    }
    drawNodeLamp(device, lit(power, LitDevice), palette.on, palette)

    val dns = at(DnsX, DirectY)
    drawNode(dns, 1f, labels.dns, palette) {
        listOf(-8f, 2f).forEach { top ->
            drawRoundRect(palette.line, Offset(-8f, top), Size(16f, 6f), CornerRadius(1.5f), style = IconStroke)
            drawCircle(palette.line, 0.9f, Offset(-5f, top + 3f))
        }
    }
    drawNodeLamp(dns, lit(power, LitDns), palette.on, palette)
}

/** The Direct and the Relay node. The one the plug is not at fades. */
@Suppress("MagicNumber")
private fun DrawScope.drawExitNodes(
    state: RouteDiagramUiState,
    motion: RouteMotion,
    palette: RoutePalette,
    labels: RouteLabels,
    at: (Float, Float) -> Offset,
) {
    val direct = 1f - ((motion.plugY - DirectY) / (RelayY - DirectY)).coerceIn(0f, 1f)
    val onDirect = state.path == ConnectionPath.Direct

    val directExit = at(ExitX, DirectY)
    val directAlpha = 0.35f + 0.65f * direct
    drawNode(directExit, directAlpha, labels.direct, palette) {
        drawPath(ShieldIcon, palette.line, alpha = directAlpha, style = IconStroke)
    }
    // No strategy passes here: the Direct exit warns in place of going green.
    val blocked = onDirect && state.directBlocked
    drawNodeLamp(
        directExit,
        when {
            !onDirect -> 0f
            blocked -> if (state.powered) 1f else 0.55f
            else -> lit(motion.power, LitExit)
        },
        if (blocked) palette.warning else palette.on,
        palette,
    )

    val relayExit = at(ExitX, RelayY)
    val relayAlpha = 1f - 0.65f * direct
    drawNode(relayExit, relayAlpha, labels.relay, palette) {
        drawRoundRect(
            palette.line,
            Offset(-8f, -6f),
            Size(16f, 12f),
            CornerRadius(2f),
            alpha = relayAlpha,
            style = IconStroke,
        )
        drawLine(palette.line, Offset(-4f, 0f), Offset(4f, 0f), 1.5f, StrokeCap.Round, alpha = relayAlpha)
    }
    drawNodeLamp(relayExit, if (onDirect) 0f else lit(motion.power, LitExit), palette.on, palette)
}

@Suppress("MagicNumber")
private fun DrawScope.drawNode(
    center: Offset,
    alpha: Float,
    label: TextLayoutResult?,
    palette: RoutePalette,
    drawIcon: DrawScope.() -> Unit,
) {
    drawCircle(palette.surface, NodeRadius, center)
    drawCircle(palette.line, NodeRadius, center, alpha = alpha, style = Stroke(1.3f))
    drawCircle(palette.well, 13.5f, center, alpha = 0.55f * alpha)
    translate(center.x, center.y) { drawIcon() }
    if (label != null) {
        // Text is measured in pixels, so it is drawn outside the view-box scale.
        val scale = size.width / ViewWidth
        scale(1f / scale, 1f / scale, pivot = Offset.Zero) {
            drawText(
                label,
                color = palette.muted,
                topLeft = Offset(center.x * scale - label.size.width / 2f, (center.y + 22f) * scale),
                alpha = alpha,
            )
        }
    }
}

/** The lamp sits on the outer shoulder of its node. */
@Suppress("MagicNumber")
private fun DrawScope.drawNodeLamp(
    center: Offset,
    level: Float,
    color: Color,
    palette: RoutePalette,
) {
    val side = if (layoutDirection == LayoutDirection.Rtl) -13f else 13f
    drawLamp(center + Offset(side, -13f), level, color, palette)
}
