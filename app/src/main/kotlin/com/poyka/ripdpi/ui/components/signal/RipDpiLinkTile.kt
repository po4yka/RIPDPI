package com.poyka.ripdpi.ui.components.signal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hero link tile: the path as a plug and a socket. With no session the plug lies out of the socket. A live
 * session seats it, and packets travel in the cable: sent packets in the upper lane, received packets in the lower
 * lane. The socket has one lamp for each stage of the path.
 *
 * The parts move only while the app allows infinite motion; otherwise each state shows a still frame. Animated
 * values are read in the draw phase only. The tile is decoration and has no semantics; the caller names the state.
 *
 * @param stages the state of each stage of the path, in order. The tile takes the first five.
 * @param tone the color of the packets. Lamps take the signal, warning, and destructive roles from their stage.
 * @param reserve the share of the width at the start edge that other content takes, from 0 to 1. The figure keeps
 * to the rest, so the caller can put the session clock in the tile.
 * @param press how far the connection action is pressed, from 0 to 1. A loose plug leans toward the socket.
 * @param pressed true while the connection action is pressed, so the tile takes frames for [press].
 * @param sent the measured level of sent traffic from 0 to 1.
 * @param received the measured level of received traffic, as [sent].
 * @param onPlug called when a drag puts the plug into the socket or takes it out. Null turns the drag off.
 */
@Composable
fun RipDpiLinkTile(
    mode: RipDpiLinkMode,
    stages: ImmutableList<RipDpiLinkStage>,
    tone: Color,
    modifier: Modifier = Modifier,
    reserve: () -> Float = { 0f },
    press: () -> Float = { 0f },
    pressed: Boolean = false,
    sent: () -> Float = { 0f },
    received: () -> Float = { 0f },
    onPlug: (() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    // Read in the draw phase, so a change of color does not recompose the tile.
    val container = linkContainer(mode)
    val pulseColor = animateColorAsState(tone, motion.stateTween(), label = "Link tone")
    val palette = remember(colors) { LinkPalette(colors) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lamps = remember(stages) { (stages + List(LinkStageCount) { RipDpiLinkStage.Pending }).take(LinkStageCount) }

    val moves = motion.allowsInfiniteMotion
    val drag = remember { mutableStateOf<Float?>(null) }
    val dragging by remember { derivedStateOf { drag.value != null } }
    val nudge = plugNudge(enabled = onPlug != null && moves && mode == RipDpiLinkMode.Open)
    // The cable in dp: where it starts, and how long it is. The draw phase measures it for the next frame.
    val span = remember { LinkSpan() }
    val frames = remember { mutableIntStateOf(0) }
    val currentPress by rememberUpdatedState(press)
    val currentSent by rememberUpdatedState(sent)
    val currentReceived by rememberUpdatedState(received)
    val currentReserve by rememberUpdatedState(reserve)
    val input = {
        LinkInput(
            mode = mode,
            stages = lamps,
            sent = currentSent(),
            received = currentReceived(),
            press = maxOf(currentPress(), nudge.value.value),
            drag = drag.value,
            length = span.length.floatValue,
            probeFrom = LeadStart - span.from.floatValue,
        )
    }
    // The tile first shows the still frame of its state, so a tile that takes no frames is still correct.
    val figure = remember(motion) { LinkFigure(motion = motion).apply { settle(input()) } }
    if (moves) {
        LaunchedEffect(figure, mode, lamps, pressed, dragging, nudge.active) {
            // A press starts before the plug moves, so the loop also runs while the action is pressed.
            figure.run(input, held = { pressed || nudge.active }) { frames.intValue++ }
        }
    }

    val still = remember { LinkStill() }
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RipDpiLinkTileHeight)
                .clip(RipDpiThemeTokens.shapes.md)
                .then(plugDrag(mode, enabled = onPlug != null && moves, rtl, figure, drag, span, onPlug))
                .drawWithCache {
                    val shapes = LinkShapes(this)
                    val edge = Stroke(1.dp.toPx())
                    val corner = CornerRadius(shapes.unit * TileCorner)
                    onDrawBehind {
                        // Each frame of the figure redraws the tile.
                        frames.intValue
                        val widthDp = size.width / shapes.unit
                        val taken = widthDp * currentReserve().coerceIn(0f, 1f)
                        // The figure sits in the middle of the width that is left.
                        val shift = taken + (widthDp - taken) / 2f - FigureMiddle
                        span.from.floatValue = taken - shift + if (taken > 0f) ReserveGap else EdgeFrom
                        span.shift.floatValue = shift
                        span.length.floatValue = widthDp - shift + EdgeBeyond - span.from.floatValue
                        drawRect(container.value)
                        drawRoundRect(palette.border, style = edge, cornerRadius = corner)
                        // With no motion the tile shows the still frame of its state, and renews it only on a change.
                        if (!moves) still.settle(figure, input())
                        // A right-to-left layout mirrors the figure: the device is at the start edge.
                        scale(if (rtl) -1f else 1f, 1f) {
                            translate(left = shift * shapes.unit) {
                                drawLink(figure, shapes, palette, pulseColor.value, lamps, span)
                            }
                        }
                    }
                },
    )
}

/**
 * Moves the figure frame by frame until nothing moves. A live link with an empty cable at rest takes no frame
 * until its next packet is due.
 */
private suspend fun LinkFigure.run(
    input: () -> LinkInput,
    held: () -> Boolean,
    onFrame: () -> Unit,
) {
    var last = withInfiniteAnimationFrameNanos { it }
    while (held() || moving(input())) {
        val quiet = quiet(input())
        if (quiet > QuietWorth) {
            delay((quiet * MillisPerSecond).toLong())
            skip(quiet)
            last = withInfiniteAnimationFrameNanos { it }
        } else {
            withInfiniteAnimationFrameNanos { now ->
                advance(((now - last) / NanosPerSecond).coerceAtMost(LongestStep), input())
                last = now
                onFrame()
            }
        }
    }
}

private fun RipDpiLinkMode.live(): Boolean = this == RipDpiLinkMode.Live || this == RipDpiLinkMode.Unstable

/** Where the cable is on the tile, in dp of the design: its start, its length, and the shift of the design. */
private class LinkSpan {
    val from = mutableFloatStateOf(EdgeFrom)
    val length = mutableFloatStateOf(LinkDefaultLength)
    val shift = mutableFloatStateOf(0f)
    val to: Float get() = from.floatValue + length.floatValue
}

@Composable
private fun linkContainer(mode: RipDpiLinkMode): State<Color> {
    val colors = RipDpiThemeTokens.colors
    return animateColorAsState(
        when (mode) {
            RipDpiLinkMode.Unstable -> colors.warningContainer
            RipDpiLinkMode.Broken -> colors.destructiveContainer
            else -> colors.card
        },
        RipDpiThemeTokens.motion.stateTween(),
        label = "Link container",
    )
}

/** The hint that the plug can be moved: a loose plug leans toward the socket once, the first time it is shown. */
private class PlugNudge(
    val value: State<Float>,
    val active: Boolean,
)

@Composable
private fun plugNudge(enabled: Boolean): PlugNudge {
    val motion = RipDpiThemeTokens.motion
    var shown by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    val lean = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (enabled && !shown) {
            delay(NudgeDelayMillis)
            active = true
            lean.animateTo(1f, motion.emphasizedTween())
            lean.animateTo(0f, motion.emphasizedTween())
            active = false
            shown = true
        }
    }
    return PlugNudge(lean.asState(), active)
}

/**
 * A drag of the plug: into the socket with no session, out of it with a live session. Only a touch on the plug
 * moves it, so a scroll that starts elsewhere on the tile stays a scroll.
 */
@Composable
private fun plugDrag(
    mode: RipDpiLinkMode,
    enabled: Boolean,
    rtl: Boolean,
    figure: LinkFigure,
    drag: MutableState<Float?>,
    span: LinkSpan,
    onPlug: (() -> Unit)?,
): Modifier {
    val open = mode == RipDpiLinkMode.Open
    val drags = enabled && (open || mode.live())
    val currentOnPlug by rememberUpdatedState(onPlug)
    // A drag that the state ends, or a drag that has acted, lets the plug go.
    DisposableEffect(mode, drags) { onDispose { drag.value = null } }
    val acted = remember { mutableIntStateOf(0) }
    LaunchedEffect(acted.intValue) {
        if (acted.intValue > 0) {
            delay(DragHoldMillis)
            drag.value = null
        }
    }
    if (!drags) return Modifier
    return Modifier.pointerInput(open, rtl) {
        val unit = 1.dp.toPx()
        val side = if (rtl) -1f else 1f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = figure.pull.at.coerceIn(0f, LinkGap)
            val x = (if (rtl) size.width - down.position.x else down.position.x) / unit
            val plugFrom = span.shift.floatValue + PlugNose + start - PlugTouch
            val slop =
                if (x in plugFrom..plugFrom + PlugEnd - PlugNose + PlugTouch * 2f) {
                    awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                } else {
                    null
                }
            if (slop != null) {
                drag.value = start
                val ended =
                    horizontalDrag(slop.id) { change ->
                        val moved = change.positionChange().x / unit * side
                        change.consume()
                        drag.value = ((drag.value ?: start) + moved).coerceIn(0f, LinkGap)
                    }
                val at = drag.value ?: start
                // Into the socket connects; out of it asks to disconnect. Anything less springs back.
                val far = if (open) at < LinkGap * PlugInShare else at > LinkGap * PlugOutShare
                // A plug that has acted stays where the drag left it until the state follows, or for a short time.
                drag.value = if (ended && far) (if (open) 0f else LinkGap) else null
                if (ended && far) {
                    acted.intValue++
                    currentOnPlug?.invoke()
                }
            }
        }
    }
}

/** Keeps the still frame of the figure, and settles the figure again only when the frame would differ. */
private class LinkStill {
    private var mode: RipDpiLinkMode? = null
    private var stages: List<RipDpiLinkStage>? = null
    private var length = 0f
    private var sent = -1
    private var received = -1
    private var pressed = false

    fun settle(
        figure: LinkFigure,
        input: LinkInput,
    ) {
        val nowSent = ceil(input.sent.coerceIn(0f, 1f) * StillSteps).toInt()
        val nowReceived = ceil(input.received.coerceIn(0f, 1f) * StillSteps).toInt()
        val nowPressed = input.press > HalfPress
        val same =
            mode == input.mode && stages == input.stages && length == input.length && sent == nowSent &&
                received == nowReceived && pressed == nowPressed
        if (same) return
        mode = input.mode
        stages = input.stages
        length = input.length
        sent = nowSent
        received = nowReceived
        pressed = nowPressed
        figure.settle(input)
    }
}

/**
 * The tone between [a] and [b] that has the same contrast to both. The socket takes it between the plug and the
 * tile, so the three stay apart at every contrast level of the theme.
 */
internal fun linkMidTone(
    a: Color,
    b: Color,
): Color {
    var low = 0f
    var high = 1f
    repeat(MidToneSteps) {
        val t = (low + high) / 2f
        val tone = lerp(a, b, t)
        if (linkContrast(tone, a) < linkContrast(tone, b)) low = t else high = t
    }
    return lerp(a, b, (low + high) / 2f)
}

internal fun linkContrast(
    a: Color,
    b: Color,
): Float {
    val one = a.luminance()
    val two = b.luminance()
    return (maxOf(one, two) + ContrastOffset) / (minOf(one, two) + ContrastOffset)
}

/** The colors of the figure. Every one is a role of the theme, or a mix of two roles. */
private class LinkPalette(
    colors: RipDpiExtendedColors,
) {
    // The plug is the strongest shape, a step calmer than the primary action so it does not outweigh the headline.
    val plug = lerp(colors.primaryAction, colors.card, PlugMix)
    val plugTop = lerp(plug, colors.card, TopMix)
    val socket = linkMidTone(plug, colors.card)
    val socketTop = lerp(socket, colors.card, TopMix)
    val socketSide = lerp(socket, plug, SideMix)
    val port = lerp(socket, Color.Black, PortMix)
    val jacket = socket
    val track = colors.card
    val border = colors.cardBorder
    val lampOff = lerp(colors.card, socket, LampOffMix)
    val signal = colors.signal
    val warning = colors.warning
    val destructive = colors.destructive

    fun lamp(stage: RipDpiLinkStage): Color =
        when (stage) {
            RipDpiLinkStage.Pending -> lampOff
            RipDpiLinkStage.Active, RipDpiLinkStage.Complete, RipDpiLinkStage.Held -> signal
            RipDpiLinkStage.Warning -> warning
            RipDpiLinkStage.Failed -> destructive
        }
}

/** One face of a box, and whether it is a top face. */
private class LinkFace(
    val path: Path,
    val top: Boolean,
)

/** The fixed shapes of the figure, in pixels of the design. The draw phase moves the design to its place. */
private class LinkShapes(
    density: Density,
) {
    val unit = with(density) { 1.dp.toPx() }

    /** One oblique view: x to the right, y down, z into the page and up to the right. */
    fun at(
        x: Float,
        y: Float,
        z: Float = CableDepth,
    ) = Offset((x + z * DepthX) * unit, (y - z * DepthY) * unit)

    private fun quad(
        a: Offset,
        b: Offset,
        c: Offset,
        d: Offset,
    ) = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }

    private fun end(
        x: Float,
        above: Float,
        below: Float,
        z0: Float,
        z1: Float,
    ) = quad(at(x, CableY - above, z0), at(x, CableY - above, z1), at(x, CableY + below, z1), at(x, CableY + below, z0))

    private fun top(
        x0: Float,
        x1: Float,
        above: Float,
        z0: Float,
        z1: Float,
    ): Path {
        val y = CableY - above
        return quad(at(x0, y, z0), at(x1, y, z0), at(x1, y, z1), at(x0, y, z1))
    }

    private fun front(
        x0: Float,
        x1: Float,
        above: Float,
        below: Float,
        z: Float,
    ) = quad(at(x0, CableY - above, z), at(x1, CableY - above, z), at(x1, CableY + below, z), at(x0, CableY + below, z))

    /**
     * The three faces of a box that this view shows: the end that looks right, the top, and the front. [above] and
     * [below] are its extent from the middle of the cable.
     */
    private fun box(
        x0: Float,
        x1: Float,
        above: Float,
        below: Float,
        z0: Float,
        z1: Float,
    ) = listOf(
        LinkFace(end(x1, above, below, z0, z1), top = false),
        LinkFace(top(x0, x1, above, z0, z1), top = true),
        LinkFace(front(x0, x1, above, below, z0), top = false),
    )

    val socketSide = end(SocketEnd, SocketHalf, SocketHalf, 0f, SocketDepth)
    val port = end(SocketEnd, PortHalf, PortHalf, PortNear, PortFar)
    val socketTop = top(SocketStart, SocketEnd, SocketHalf, 0f, SocketDepth)
    val socketFront = front(SocketStart, SocketEnd, SocketHalf, SocketHalf, 0f)

    // The nose, the boot, and the body of the plug, from the back to the front.
    val plug =
        box(PlugNose, 150f, 9f, 9f, 5f, 13f) + box(186f, PlugEnd, 11f, 11f, 4f, 14f) +
            box(148f, 186f, 15f, 15f, 2f, 16f)
    val latch = box(150f, 172f, 19f, -15f, 6f, 12f)
    val pivot = Offset(PlugPivot * unit, CableY * unit)
    val seat = at(SocketEnd, CableY)
    val windowAt = Offset(lamp(0).x - WindowInset * unit, (CableY - WindowInset) * unit)
    val windowSize = Size((LampStep * (LinkStageCount - 1) + WindowInset * 2f) * unit, WindowInset * 2f * unit)
    val lampPath = Path()
    val leadPath = Path()
    val jacket = Stroke(JacketWidth * unit, cap = StrokeCap.Round)
    val track = Stroke(TrackWidth * unit, cap = StrokeCap.Round)
    val edge = Stroke(FaceStroke * unit, join = StrokeJoin.Round)
    val ring = Stroke(RingWidth * unit)

    fun lamp(index: Int) = at(FirstLamp + LampStep * index, CableY, 0f)
}

private fun DrawScope.drawLink(
    figure: LinkFigure,
    shapes: LinkShapes,
    palette: LinkPalette,
    tone: Color,
    stages: List<RipDpiLinkStage>,
    span: LinkSpan,
) {
    val unit = shapes.unit
    val angle = figure.loose * LooseTurn
    val drop = figure.loose * LooseDrop
    val shift = figure.out + figure.shakeBy
    // Where the cable leaves the plug: the plug turns about a point on its cable.
    val radians = angle * PI.toFloat() / HalfTurn
    val dx = LeadStart - PlugPivot
    val leadX = PlugPivot + dx * cos(radians) + shift
    val leadY = CableY + dx * sin(radians) + drop
    val from = span.from.floatValue
    val lead = LinkLead(shapes, leadX, leadY, (leadY + CableY) / 2f + figure.sag.at, span.to)

    val deviceFrom = shapes.at(from, CableY)
    val deviceTo = shapes.at(DeviceEnd, CableY)
    drawLine(palette.jacket, deviceFrom, deviceTo, shapes.jacket.width, StrokeCap.Round)
    drawLine(palette.track, deviceFrom, deviceTo, shapes.track.width, StrokeCap.Round)
    lead.trace(shapes.leadPath)
    drawPath(shapes.leadPath, palette.jacket, style = shapes.jacket)
    drawPath(shapes.leadPath, palette.track, style = shapes.track)
    drawPulses(figure.sent, forward = true, shapes, lead, tone, from)
    drawPulses(figure.received, forward = false, shapes, lead, tone, from)

    translate(left = figure.recoil.at * unit) {
        drawFace(shapes.socketSide, palette.socketSide, shapes)
        drawFace(shapes.port, palette.port, shapes)
    }
    if (figure.ring < 1f) {
        val radius = (RingFrom + RingGrow * figure.ring) * unit
        drawCircle(tone, radius, shapes.seat, alpha = (1f - figure.ring) * RingAlpha, style = shapes.ring)
    }
    translate(left = shift * unit, top = drop * unit) {
        rotate(angle, shapes.pivot) {
            drawFaces(shapes.plug, palette, shapes)
            translate(top = figure.click.at.coerceAtLeast(0f) * unit) { drawFaces(shapes.latch, palette, shapes) }
        }
    }
    translate(left = figure.recoil.at * unit) {
        drawFace(shapes.socketTop, palette.socketTop, shapes)
        drawFace(shapes.socketFront, palette.socket, shapes)
        drawRoundRect(palette.track, shapes.windowAt, shapes.windowSize, CornerRadius(WindowInset * unit))
        for (index in stages.indices) drawLamp(figure.lamps[index], shapes, index, palette.lamp(stages[index]))
    }
}

/** A face has a stroke of its own color, which rounds its corners. */
private fun DrawScope.drawFace(
    path: Path,
    color: Color,
    shapes: LinkShapes,
) {
    drawPath(path, color)
    drawPath(path, color, style = shapes.edge)
}

private fun DrawScope.drawFaces(
    faces: List<LinkFace>,
    palette: LinkPalette,
    shapes: LinkShapes,
) {
    // Indexed, so a frame allocates no iterator.
    for (index in faces.indices) {
        drawFace(faces[index].path, if (faces[index].top) palette.plugTop else palette.plug, shapes)
    }
}

/** A lamp is a circle. A warning gives it four points, and a failure turns the points to a cross. */
private fun DrawScope.drawLamp(
    lamp: LinkLamp,
    shapes: LinkShapes,
    index: Int,
    color: Color,
) {
    val center = shapes.lamp(index)
    val path = shapes.lampPath
    path.rewind()
    for (step in 0 until LampSteps) {
        val angle = LinkTau * step / LampSteps
        val radius = LampRadius * shapes.unit * (1f + lamp.notch * cos(LampPoints * angle + lamp.turn))
        val x = center.x + radius * cos(angle)
        val y = center.y + radius * sin(angle)
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, alpha = lamp.glow.coerceIn(0f, 1f))
}

/** The cable behind the plug. It hangs between the plug and the edge of the tile. */
private class LinkLead(
    private val shapes: LinkShapes,
    val startX: Float,
    private val startY: Float,
    private val middleY: Float,
    val to: Float,
) {
    fun trace(path: Path) {
        val start = shapes.at(startX, startY)
        val middle = shapes.at((startX + to) / 2f, middleY)
        val end = shapes.at(to, CableY)
        path.rewind()
        path.moveTo(start.x, start.y)
        path.quadraticTo(middle.x, middle.y, end.x, end.y)
    }

    /** The point of the cable at [x], moved by [lane] across it. */
    fun at(
        x: Float,
        lane: Float,
    ): Offset {
        val t = ((x - startX) / (to - startX)).coerceIn(0f, 1f)
        val u = 1f - t
        val px = u * u * startX + 2f * u * t * (startX + to) / 2f + t * t * to
        val py = u * u * startY + 2f * u * t * middleY + t * t * CableY
        return shapes.at(px, py + lane)
    }
}

private fun DrawScope.drawPulses(
    lane: LinkLane,
    forward: Boolean,
    shapes: LinkShapes,
    lead: LinkLead,
    color: Color,
    from: Float,
) {
    if (lane.alpha <= 0f) return
    val width = PulseWidth * shapes.unit
    val side = if (forward) -LaneOffset else LaneOffset
    val tone = color.copy(alpha = color.alpha * lane.alpha.coerceIn(0f, 1f))
    // Indexed, so a frame allocates no iterator.
    for (index in lane.pulses.indices) {
        // The distance that a packet has travelled becomes a place on the cable: sent packets start at the device.
        val travelled = lane.pulses[index].at
        val a = if (forward) from + travelled - LinkPulseLength else lead.to - travelled
        val b = a + LinkPulseLength
        // The socket and the plug hide the middle of the cable.
        val deviceTo = minOf(b, DeviceEnd - CableCover)
        val deviceFrom = maxOf(a, from)
        if (deviceTo - deviceFrom > MinPulse) {
            val start = shapes.at(deviceFrom, CableY + side)
            drawLine(tone, start, shapes.at(deviceTo, CableY + side), width, StrokeCap.Round)
        }
        val leadFrom = maxOf(a, lead.startX + CableCover)
        val leadTo = minOf(b, lead.to)
        if (leadTo - leadFrom > MinPulse) {
            drawLine(tone, lead.at(leadFrom, side), lead.at(leadTo, side), width, StrokeCap.Round)
        }
    }
}

/** Height of the link tile. */
val RipDpiLinkTileHeight = 80.dp

// The design, in dp. The cable runs along CableY, and every part is placed from it.
private const val FigureMiddle = 140f
private const val EdgeFrom = -24f
private const val EdgeBeyond = 44f
private const val ReserveGap = 6f
private const val TileCorner = 12f
private const val DepthX = 0.55f
private const val DepthY = 0.4f
private const val CableY = 44f
private const val CableDepth = 9f
private const val SocketStart = 74f
private const val SocketEnd = 140f
private const val SocketHalf = 24f
private const val SocketDepth = 18f
private const val PortHalf = 11f
private const val PortNear = 4f
private const val PortFar = 14f
private const val PlugNose = 132f
private const val PlugEnd = 206f
private const val PlugPivot = 266f
private const val DeviceEnd = 78f
private const val LeadStart = 202f
private const val CableCover = 3f
private const val FirstLamp = 83f
private const val LampStep = 12f
private const val LampRadius = 4.5f
private const val LampSteps = 32
private const val LampPoints = 4f
private const val WindowInset = 6.5f
private const val FaceStroke = 3f

// The cable carries two lanes of packets, each 4 dp wide.
private const val JacketWidth = 16f
private const val TrackWidth = 12f
private const val PulseWidth = 4f
private const val LaneOffset = 3f
private const val MinPulse = 0.4f
private const val RingFrom = 6f
private const val RingGrow = 22f
private const val RingWidth = 2f
private const val RingAlpha = 0.6f

// A loose plug lies a little lower and turns.
private const val LooseDrop = 5f
private const val LooseTurn = 5f
private const val HalfTurn = 180f

// A drag: how far in connects, how far out disconnects, the reach of a touch around the plug, and how long a plug
// that has acted waits for the state to follow.
private const val PlugInShare = 0.55f
private const val PlugOutShare = 0.6f
private const val PlugTouch = 20f
private const val DragHoldMillis = 1_500L
private const val NudgeDelayMillis = 700L
private const val StillSteps = 4f
private const val HalfPress = 0.5f

private const val PlugMix = 0.14f
private const val TopMix = 0.2f
private const val SideMix = 0.3f
private const val PortMix = 0.75f
private const val LampOffMix = 0.3f
private const val MidToneSteps = 14
private const val ContrastOffset = 0.05f

private const val NanosPerSecond = 1_000_000_000f
private const val MillisPerSecond = 1_000f

// A frame that comes late moves the figure by no more than this, so it does not jump.
private const val LongestStep = 0.05f

// A wait shorter than this is not worth a pause of the frame loop.
private const val QuietWorth = 0.1f

@Preview(name = "LinkTile light", showBackground = true, widthDp = 360)
@Composable
private fun RipDpiLinkTileLightPreview() {
    LinkTileModes(theme = "light")
}

@Preview(name = "LinkTile dark", showBackground = true, widthDp = 360)
@Composable
private fun RipDpiLinkTileDarkPreview() {
    LinkTileModes(theme = "dark")
}

@Composable
private fun LinkTileModes(theme: String) {
    RipDpiComponentPreview(themePreference = theme) {
        val colors = RipDpiThemeTokens.colors

        fun stages(stage: (Int) -> RipDpiLinkStage) = List(LinkStageCount, stage).toImmutableList()
        Column {
            RipDpiLinkTile(RipDpiLinkMode.Open, stages { RipDpiLinkStage.Pending }, colors.outline)
            RipDpiLinkTile(RipDpiLinkMode.Seeking, stages { RipDpiLinkStage.Active }, colors.signal)
            RipDpiLinkTile(
                RipDpiLinkMode.Live,
                stages { RipDpiLinkStage.Complete },
                colors.signal,
                reserve = { PreviewReserve },
                sent = { PreviewSent },
                received = { PreviewReceived },
            )
            RipDpiLinkTile(
                RipDpiLinkMode.Unstable,
                stages { if (it == PreviewWarningAt) RipDpiLinkStage.Warning else RipDpiLinkStage.Complete },
                colors.warning,
                sent = { PreviewSent },
                received = { PreviewSent },
            )
            RipDpiLinkTile(
                RipDpiLinkMode.Broken,
                stages { if (it == PreviewFailedAt) RipDpiLinkStage.Failed else RipDpiLinkStage.Pending },
                colors.destructive,
            )
            RipDpiLinkTile(RipDpiLinkMode.Held, stages { RipDpiLinkStage.Held }, colors.signal)
        }
    }
}

private const val PreviewSent = 0.4f
private const val PreviewReceived = 0.8f
private const val PreviewReserve = 0.4f
private const val PreviewWarningAt = 1
private const val PreviewFailedAt = 3
