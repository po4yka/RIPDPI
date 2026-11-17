package com.poyka.ripdpi.ui.components.signal

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import kotlin.math.cos
import kotlin.math.sin

/** The colors of the figure. Every one is a role of the theme, or a mix of two roles. */
internal class LinkPalette(
    colors: RipDpiExtendedColors,
) {
    // The plug is the strongest shape, a step calmer than the primary action so it does not outweigh the headline.
    val plug = lerp(colors.primaryAction, colors.card, PlugMix)
    val plugTop = lerp(plug, colors.card, TopMix)
    val contact = lerp(plug, colors.card, ContactMix)
    val socket = linkMidTone(plug, colors.card)
    val socketTop = lerp(socket, colors.card, TopMix)
    val socketSide = lerp(socket, plug, SideMix)
    val port = lerp(socket, Color.Black, PortMix)
    val socketBase = lerp(socket, plug, BaseMix)
    val sideBase = lerp(socketSide, plug, BaseMix)
    val glint = Color.White
    val jacket = socket

    // The cable is a solid tube: a tone of its own between the tile and its jacket.
    val track = lerp(colors.card, socket, TrackMix)
    val window = colors.card
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
internal class LinkFace(
    val path: Path,
    val top: Boolean,
)

/** The fixed shapes of the figure, in pixels of the design. The draw phase moves the design to its place. */
internal class LinkShapes(
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

    // The base of the socket, and the gland where the device cable goes in.
    val frontBase = front(SocketStart, SocketEnd, BaseHeight - SocketHalf, SocketHalf, 0f)
    val sideBase = end(SocketEnd, BaseHeight - SocketHalf, SocketHalf, 0f, SocketDepth)
    val gland = box(GlandStart, SocketStart + 1f, GlandHalf, GlandHalf, CollarNear, CollarFar)

    // The parts of the plug, from the back to the front: the nose, the boot, the collar, and the body.
    val nose = box(PlugNose, NoseEnd, NoseHalf, NoseHalf, NoseNear, NoseFar)
    val collar = box(CollarStart, BodyStart + 1f, CollarHalf, CollarHalf, CollarNear, CollarFar)
    val body = box(BodyStart, BootStart, BodyHalf, BodyHalf, BodyNear, BodyFar)

    /** The boot narrows from the body to the cable. */
    val boot =
        listOf(
            LinkFace(
                end(PlugEnd, BootEndHalf, BootEndHalf, CollarNear + BootInset, CollarFar - BootInset),
                top = false,
            ),
            LinkFace(
                quad(
                    at(BootStart, CableY - CollarHalf, CollarNear),
                    at(PlugEnd, CableY - BootEndHalf, CollarNear + BootInset),
                    at(PlugEnd, CableY - BootEndHalf, CollarFar - BootInset),
                    at(BootStart, CableY - CollarHalf, CollarFar),
                ),
                top = true,
            ),
            LinkFace(
                quad(
                    at(BootStart, CableY - CollarHalf, CollarNear),
                    at(PlugEnd, CableY - BootEndHalf, CollarNear + BootInset),
                    at(PlugEnd, CableY + BootEndHalf, CollarNear + BootInset),
                    at(BootStart, CableY + CollarHalf, CollarNear),
                ),
                top = false,
            ),
        )

    /** The latch: a lever that rises from the nose to above the body. The socket holds it when the plug is in. */
    val latch =
        listOf(
            LinkFace(end(LeverEnd, LeverTop, LeverThick - LeverTop, LeverNear, LeverFar), top = false),
            LinkFace(
                quad(
                    at(LeverStart, CableY - NoseHalf, LeverNear),
                    at(LeverEnd, CableY - LeverTop, LeverNear),
                    at(LeverEnd, CableY - LeverTop, LeverFar),
                    at(LeverStart, CableY - NoseHalf, LeverFar),
                ),
                top = true,
            ),
            LinkFace(
                quad(
                    at(LeverStart, CableY - NoseHalf, LeverNear),
                    at(LeverEnd, CableY - LeverTop, LeverNear),
                    at(LeverEnd, CableY - LeverTop + LeverThick, LeverNear),
                    at(LeverStart, CableY - NoseHalf + LeverThick, LeverNear),
                ),
                top = false,
            ),
        )

    /** The contacts of the plug: one line across the top of the nose for each stage of the path. */
    val contacts =
        Path().apply {
            repeat(LinkStageCount) { index ->
                val x = FirstContact + ContactStep * index
                val near = at(x, CableY - NoseHalf, NoseNear + ContactInset)
                val far = at(x, CableY - NoseHalf, NoseFar - ContactInset)
                moveTo(near.x, near.y)
                lineTo(far.x, far.y)
            }
        }

    /** The grooves of the boot, where the cable bends. They follow its taper. */
    val ribs =
        Path().apply {
            repeat(RibCount) { index ->
                val share = (index + 1f) / (RibCount + 1f)
                val x = BootStart + (PlugEnd - BootStart) * share
                val half = CollarHalf + (BootEndHalf - CollarHalf) * share - RibInset
                val high = at(x, CableY - half, CollarNear + BootInset * share)
                val low = at(x, CableY + half, CollarNear + BootInset * share)
                moveTo(high.x, high.y)
                lineTo(low.x, low.y)
            }
        }

    /** The grip of the body. */
    val grip =
        Path().apply {
            repeat(RibCount) { index ->
                val x = FirstGrip + GripStep * index
                val high = at(x, CableY - GripHalf, BodyNear)
                val low = at(x, CableY + GripHalf, BodyNear)
                moveTo(high.x, high.y)
                lineTo(low.x, low.y)
            }
        }
    val pivot = Offset(PlugPivot * unit, CableY * unit)
    val seat = at(SocketEnd, CableY)
    val windowAt = Offset(lamp(0).x - WindowInset * unit, (CableY - WindowInset) * unit)
    val windowSize = Size((LampStep * (LinkStageCount - 1) + WindowInset * 2f) * unit, WindowInset * 2f * unit)
    val lampPath = Path()
    val leadPath = Path()
    val litPath = Path()
    val jacket = Stroke(JacketWidth * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val track = Stroke(TrackWidth * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val lane = Stroke(PulseWidth * unit, join = StrokeJoin.Round)
    val rim = Stroke(RimWidth * unit, join = StrokeJoin.Round)
    val edge = Stroke(FaceStroke * unit, join = StrokeJoin.Round)
    val hair = Stroke(HairWidth * unit, cap = StrokeCap.Round)
    val ring = Stroke(RingWidth * unit)
    val fade = LinkFade()

    fun lamp(index: Int) = at(FirstLamp + LampStep * index, CableY, 0f)

    /** Traces the cable behind the plug into [path], as far as the share [upTo] of its length. */
    fun trace(
        path: Path,
        rope: LinkRope,
        upTo: Float,
    ) {
        path.rewind()
        for (step in 0..RopeSamples) {
            val t = upTo * step / RopeSamples
            val point = at(rope.x(t), rope.y(t))
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
    }

    /** The point of the cable behind the plug at [x], moved by [lane] across it. */
    fun onRope(
        rope: LinkRope,
        x: Float,
        lane: Float,
    ): Offset {
        val t = ((x - rope.startX) / (rope.endX - rope.startX)).coerceIn(0f, 1f)
        return at(rope.x(t), rope.y(t) + lane)
    }
}

/**
 * The parts of the figure that never change shape, each recorded once. A frame draws a part as one operation and
 * only moves it. They are in the order of the picture, from the back to the front.
 */
internal class LinkParts(
    val socketBack: GraphicsLayer,
    val plug: GraphicsLayer,
    val socketTop: GraphicsLayer,
    val socketFront: GraphicsLayer,
)

/** The fade of the device cable where other content takes the start of the tile. It is made again only on a change. */
internal class LinkFade {
    private var color = Color.Unspecified
    private var from = Float.NaN
    private var to = Float.NaN
    private var brush: Brush? = null

    fun brush(
        color: Color,
        from: Float,
        to: Float,
    ): Brush {
        val kept = brush
        val same = color == this.color && from == this.from && to == this.to
        if (kept != null && same) return kept
        this.color = color
        this.from = from
        this.to = to
        return Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)), from, to).also { brush = it }
    }
}

/**
 * Draws the figure. [cover] is the color of the tile where other content takes its start: the device cable fades
 * into it. It is unspecified when the cable runs to the edge of the tile.
 */
internal fun DrawScope.drawLink(
    figure: LinkFigure,
    shapes: LinkShapes,
    parts: LinkParts,
    palette: LinkPalette,
    tone: Color,
    span: LinkSpan,
    cover: Color,
) {
    val unit = shapes.unit
    val angle = figure.tilt.at * LooseTurn
    val drop = figure.lie.at * LooseDrop
    val shift = figure.out + figure.shakeBy
    val from = span.from.floatValue

    drawCables(figure, shapes, palette, tone, from)
    if (cover.isSpecified) {
        val start = shapes.at(from - FadeLead, CableY - FadeHalf)
        val brush = shapes.fade.brush(cover, shapes.at(from, CableY).x, shapes.at(from + FadeLength, CableY).x)
        drawRect(brush, start, Size((FadeLead + FadeLength) * unit, FadeHalf * 2f * unit))
    }
    translate(left = figure.recoil.at * unit) { drawLayer(parts.socketBack) }
    if (figure.ring < 1f) {
        val radius = (RingFrom + RingGrow * figure.ring) * unit
        drawCircle(tone, radius, shapes.seat, alpha = (1f - figure.ring) * RingAlpha, style = shapes.ring)
    }
    translate(left = shift * unit, top = drop * unit) {
        rotate(angle, shapes.pivot) {
            drawLayer(parts.plug)
            // The latch goes down when a contact makes.
            translate(top = figure.click.at.coerceAtLeast(0f) * unit) {
                drawFaces(shapes.latch, palette.plug, palette.plugTop, shapes)
            }
        }
    }
    translate(left = figure.recoil.at * unit) { drawSocket(figure, shapes, parts, palette, tone) }
}

/** The end of the socket that the plug goes into. It is behind the plug. */
internal fun DrawScope.drawSocketBack(
    shapes: LinkShapes,
    palette: LinkPalette,
) {
    drawFace(shapes.socketSide, palette.socketSide, shapes)
    drawFace(shapes.sideBase, palette.sideBase, shapes)
    // The port has a rim, a step lighter than the face that it is in.
    drawPath(shapes.port, palette.socketTop, style = shapes.rim)
    drawFace(shapes.port, palette.port, shapes)
}

private fun DrawScope.drawCables(
    figure: LinkFigure,
    shapes: LinkShapes,
    palette: LinkPalette,
    tone: Color,
    from: Float,
) {
    val rope = figure.rope
    val deviceFrom = shapes.at(from, CableY)
    val deviceTo = shapes.at(DeviceEnd, CableY)
    drawLine(palette.jacket, deviceFrom, deviceTo, shapes.jacket.width, StrokeCap.Round)
    drawLine(palette.track, deviceFrom, deviceTo, shapes.track.width, StrokeCap.Round)
    shapes.trace(shapes.leadPath, rope, upTo = 1f)
    drawPath(shapes.leadPath, palette.jacket, style = shapes.jacket)
    drawPath(shapes.leadPath, palette.track, style = shapes.track)
    // A live link lights the cable. The light spreads from the socket to both ends.
    if (figure.charge > 0f) {
        val lit = tone.copy(alpha = tone.alpha * ChargeAlpha)
        val reach = DeviceEnd - (DeviceEnd - from) * figure.charge
        drawLine(lit, shapes.at(reach, CableY), deviceTo, shapes.track.width, StrokeCap.Round)
        // A full light is on the whole cable, which this frame has traced.
        if (figure.charge < 1f) shapes.trace(shapes.litPath, rope, upTo = figure.charge)
        drawPath(if (figure.charge < 1f) shapes.litPath else shapes.leadPath, lit, style = shapes.track)
    }
    drawLane(figure.sent, forward = true, figure, shapes, tone, from)
    drawLane(figure.received, forward = false, figure, shapes, tone, from)
}

/** One lane of the cable: a stream as strong as the traffic of the lane, and the packets on it. */
private fun DrawScope.drawLane(
    lane: LinkLane,
    forward: Boolean,
    figure: LinkFigure,
    shapes: LinkShapes,
    color: Color,
    from: Float,
) {
    if (lane.alpha <= 0f) return
    val rope = figure.rope
    val side = if (forward) -LaneOffset else LaneOffset
    val alpha = color.alpha * lane.alpha.coerceIn(0f, 1f)
    val stream = alpha * lane.level.coerceIn(0f, 1f) * figure.charge * StreamAlpha
    if (stream > MinAlpha) {
        val tint = color.copy(alpha = stream)
        drawLine(tint, shapes.at(from, CableY + side), shapes.at(DeviceEnd, CableY + side), shapes.lane.width)
        // A lane is the cable of this frame, moved across it.
        translate(top = side * shapes.unit) { drawPath(shapes.leadPath, tint, style = shapes.lane) }
    }
    val head = color.copy(alpha = alpha)
    val middle = color.copy(alpha = alpha * MiddleAlpha)
    val tail = color.copy(alpha = alpha * TailAlpha)
    // Indexed, so a frame allocates no iterator.
    for (index in lane.pulses.indices) {
        // The distance that a packet has travelled becomes a place on the cable: sent packets start at the device.
        val pulse = lane.pulses[index]
        val a = if (forward) from + pulse.at - pulse.length else rope.endX - pulse.at
        val b = a + pulse.length
        val half = pulse.length / 2f
        // A packet has a bright head, and a tail behind it that fades in two steps.
        drawPulse(a, b, side, tail, shapes, rope, from)
        drawPulse(if (forward) b - half else a, if (forward) b else a + half, side, middle, shapes, rope, from)
        drawPulse(if (forward) b - PulseHead else a, if (forward) b else a + PulseHead, side, head, shapes, rope, from)
    }
}

/** Draws the part of a packet from [a] to [b] that is not behind the socket or the plug. */
private fun DrawScope.drawPulse(
    a: Float,
    b: Float,
    side: Float,
    color: Color,
    shapes: LinkShapes,
    rope: LinkRope,
    from: Float,
) {
    val width = PulseWidth * shapes.unit
    val deviceTo = minOf(b, DeviceEnd - CableCover)
    val deviceFrom = maxOf(a, from)
    if (deviceTo - deviceFrom > MinPulse) {
        val start = shapes.at(deviceFrom, CableY + side)
        drawLine(color, start, shapes.at(deviceTo, CableY + side), width, StrokeCap.Round)
    }
    val leadFrom = maxOf(a, rope.startX + CableCover)
    val leadTo = minOf(b, rope.endX)
    if (leadTo - leadFrom > MinPulse) {
        drawLine(color, shapes.onRope(rope, leadFrom, side), shapes.onRope(rope, leadTo, side), width, StrokeCap.Round)
    }
}

/** The plug without its latch, which moves on it. */
internal fun DrawScope.drawPlug(
    shapes: LinkShapes,
    palette: LinkPalette,
) {
    drawFaces(shapes.nose, palette.plug, palette.plugTop, shapes)
    drawPath(shapes.contacts, palette.contact, style = shapes.hair)
    drawFaces(shapes.boot, palette.plug, palette.plugTop, shapes)
    drawPath(shapes.ribs, palette.plugTop, style = shapes.hair)
    drawFaces(shapes.collar, palette.plug, palette.plugTop, shapes)
    drawFaces(shapes.body, palette.plug, palette.plugTop, shapes)
    drawPath(shapes.grip, palette.plugTop, style = shapes.hair)
}

/** The gland and the top of the socket. The light of the lamps falls on the top, under the front. */
internal fun DrawScope.drawSocketTop(
    shapes: LinkShapes,
    palette: LinkPalette,
) {
    drawFaces(shapes.gland, palette.socketSide, palette.socket, shapes)
    drawFace(shapes.socketTop, palette.socketTop, shapes)
}

/** The front of the socket, with the window of the lamps. */
internal fun DrawScope.drawSocketFront(
    shapes: LinkShapes,
    palette: LinkPalette,
) {
    drawFace(shapes.socketFront, palette.socket, shapes)
    drawFace(shapes.frontBase, palette.socketBase, shapes)
    drawRoundRect(palette.window, shapes.windowAt, shapes.windowSize, CornerRadius(WindowInset * shapes.unit))
}

private fun DrawScope.drawSocket(
    figure: LinkFigure,
    shapes: LinkShapes,
    parts: LinkParts,
    palette: LinkPalette,
    tone: Color,
) {
    drawLayer(parts.socketTop)
    // The lamps light the top of the socket by one step of tone.
    var light = 0f
    for (index in figure.lamps.indices) {
        val lamp = figure.lamps[index]
        if (lamp.stage != RipDpiLinkStage.Pending) light += lamp.glow / LinkStageCount
    }
    if (light > 0f) drawPath(shapes.socketTop, tone, alpha = light.coerceAtMost(1f) * CastAlpha)
    drawLayer(parts.socketFront)
    for (index in figure.lamps.indices) drawLamp(figure.lamps[index], shapes, index, palette)
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
    side: Color,
    top: Color,
    shapes: LinkShapes,
) {
    // Indexed, so a frame allocates no iterator.
    for (index in faces.indices) drawFace(faces[index].path, if (faces[index].top) top else side, shapes)
}

/** A lamp is a circle. A warning gives it four points, and a failure turns the points to a cross. */
private fun DrawScope.drawLamp(
    lamp: LinkLamp,
    shapes: LinkShapes,
    index: Int,
    palette: LinkPalette,
) {
    val center = shapes.lamp(index)
    val size = LampRadius * shapes.unit * lamp.size.at.coerceAtLeast(0f)
    val now = palette.lamp(lamp.stage)
    val color = if (lamp.mix < 1f) lerp(palette.lamp(lamp.before), now, lamp.mix) else now
    val glow = lamp.glow.coerceIn(0f, 1f)
    if (lamp.notch < RoundWithin) {
        // A lamp with no points is a circle, so the steady frame traces no outline.
        drawCircle(color, size, center, alpha = glow)
    } else {
        val path = shapes.lampPath
        path.rewind()
        for (step in 0 until LampSteps) {
            val angle = LinkTau * step / LampSteps
            val radius = size * (1f + lamp.notch * cos(LampPoints * angle + lamp.turn))
            val x = center.x + radius * cos(angle)
            val y = center.y + radius * sin(angle)
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color, alpha = glow)
    }
    // A round lamp that is on has a glint, as a lens has.
    if (lamp.stage != RipDpiLinkStage.Pending && lamp.notch < GlintWithin) {
        val at = Offset(center.x - size * GlintOffset, center.y - size * GlintOffset)
        drawCircle(palette.glint, size * GlintSize, at, alpha = glow * GlintAlpha)
    }
}

// The design, in dp. The cable runs along CableY, and every part is placed from it. The figure is 162 dp wide with
// a loose plug, and FigureMiddle puts it between the middle of that and the middle of the seated figure.
internal const val FigureMiddle = 146f
internal const val EdgeFrom = -24f
internal const val EdgeBeyond = 44f
internal const val PlugNose = 124f
internal const val PlugEnd = 196f
private const val DepthX = 0.55f
private const val DepthY = 0.4f
private const val CableDepth = 7f
private const val SocketStart = 74f
private const val SocketEnd = 136f
private const val SocketHalf = 19f
private const val SocketDepth = 14f
private const val PortHalf = 8f
private const val PortNear = 3f
private const val PortFar = 11f
private const val NoseEnd = 146f
private const val NoseHalf = 6.5f
private const val NoseNear = 4f
private const val NoseFar = 10f
private const val BodyStart = 144f
private const val BodyHalf = 11f
private const val BodyNear = 1.5f
private const val BodyFar = 12.5f
private const val BootStart = 178f
private const val BootEndHalf = 6.5f
private const val BootInset = 1f
private const val CollarStart = 143f
private const val CollarHalf = 9f
private const val CollarNear = 2.5f
private const val CollarFar = 11.5f
private const val LeverStart = 128f
private const val LeverEnd = 162f
private const val LeverTop = 15.5f
private const val LeverThick = 1.5f
private const val LeverNear = 5f
private const val LeverFar = 9f
private const val FirstGrip = 157f
private const val GripStep = 4f
private const val GripHalf = 5f
private const val GlandStart = 67f
private const val GlandHalf = 8.5f
private const val BaseHeight = 4f
private const val RimWidth = 5f

// The contacts are all in the socket when the plug is seated.
private const val FirstContact = 126f
private const val ContactStep = 2.2f
private const val ContactInset = 1f
private const val RibCount = 3
private const val RibInset = 2.5f
private const val HairWidth = 1f
private const val FirstLamp = 83f
private const val LampStep = 11f
private const val LampRadius = 4.25f
private const val LampSteps = 32
private const val LampPoints = 4f
private const val WindowInset = 6f
private const val FaceStroke = 2.5f

// The cable carries two lanes of packets, each 4 dp wide.
private const val JacketWidth = 12f
private const val TrackWidth = 10f
private const val PulseWidth = 4f
private const val PulseHead = 2f
private const val TailAlpha = 0.3f
private const val MiddleAlpha = 0.6f
private const val StreamAlpha = 0.22f
private const val MinAlpha = 0.01f
private const val RopeSamples = 28
private const val GlintWithin = 0.05f

// Points smaller than this share of the radius do not show.
private const val RoundWithin = 0.005f
private const val GlintOffset = 0.3f
private const val GlintSize = 0.24f
private const val GlintAlpha = 0.5f
private const val LaneOffset = 2.5f
private const val MinPulse = 0.4f
private const val ChargeAlpha = 0.16f
private const val CastAlpha = 0.2f
private const val FadeLead = 8f
private const val FadeLength = 28f
private const val FadeHalf = 10f
private const val RingFrom = 6f
private const val RingGrow = 22f
private const val RingWidth = 2f
private const val RingAlpha = 0.6f

private const val PlugMix = 0.14f
private const val TopMix = 0.2f
private const val ContactMix = 0.6f
private const val SideMix = 0.3f
private const val PortMix = 0.75f
private const val BaseMix = 0.18f
private const val TrackMix = 0.22f
private const val LampOffMix = 0.3f
