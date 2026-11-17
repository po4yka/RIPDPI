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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import kotlin.math.PI
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

    // The parts of the plug, from the back to the front: the nose, the boot, and the body with its latch.
    val nose = box(PlugNose, NoseEnd, NoseHalf, NoseHalf, NoseNear, NoseFar)
    val boot = box(BootStart, PlugEnd, BootHalf, BootHalf, BootNear, BootFar)
    val body = box(BodyStart, BootStart, BodyHalf, BodyHalf, BodyNear, BodyFar)
    val latch = box(LatchStart, LatchEnd, LatchTop, -BodyHalf, LatchNear, LatchFar)

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

    /** The grooves of the boot, where the cable bends. */
    val ribs =
        Path().apply {
            repeat(RibCount) { index ->
                val x = BootStart + RibStep * (index + 1)
                val high = at(x, CableY - RibHalf, BootNear)
                val low = at(x, CableY + RibHalf, BootNear)
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
    val jacket = Stroke(JacketWidth * unit, cap = StrokeCap.Round)
    val track = Stroke(TrackWidth * unit, cap = StrokeCap.Round)
    val edge = Stroke(FaceStroke * unit, join = StrokeJoin.Round)
    val hair = Stroke(HairWidth * unit, cap = StrokeCap.Round)
    val ring = Stroke(RingWidth * unit)
    val fade = LinkFade()

    fun lamp(index: Int) = at(FirstLamp + LampStep * index, CableY, 0f)
}

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
    palette: LinkPalette,
    tone: Color,
    span: LinkSpan,
    cover: Color,
) {
    val unit = shapes.unit
    val angle = figure.lie.at * LooseTurn
    val drop = figure.lie.at * LooseDrop
    val shift = figure.out + figure.shakeBy
    // Where the cable leaves the plug: the plug turns about a point on its cable.
    val radians = angle * PI.toFloat() / HalfTurn
    val reach = LeadStart - PlugPivot
    val leadX = PlugPivot + reach * cos(radians) + shift
    val leadY = CableY + reach * sin(radians) + drop
    val from = span.from.floatValue
    val lead = LinkLead(shapes, leadX, leadY, (leadY + CableY) / 2f + figure.sag.at, span.to)

    drawCables(figure, shapes, palette, tone, lead, from)
    if (cover.isSpecified) {
        val start = shapes.at(from - FadeLead, CableY - FadeHalf)
        val brush = shapes.fade.brush(cover, shapes.at(from, CableY).x, shapes.at(from + FadeLength, CableY).x)
        drawRect(brush, start, Size((FadeLead + FadeLength) * unit, FadeHalf * 2f * unit))
    }
    translate(left = figure.recoil.at * unit) {
        drawFace(shapes.socketSide, palette.socketSide, shapes)
        drawFace(shapes.port, palette.port, shapes)
    }
    if (figure.ring < 1f) {
        val radius = (RingFrom + RingGrow * figure.ring) * unit
        drawCircle(tone, radius, shapes.seat, alpha = (1f - figure.ring) * RingAlpha, style = shapes.ring)
    }
    translate(left = shift * unit, top = drop * unit) {
        rotate(angle, shapes.pivot) { drawPlug(figure, shapes, palette) }
    }
    translate(left = figure.recoil.at * unit) { drawSocket(figure, shapes, palette, tone) }
}

private fun DrawScope.drawCables(
    figure: LinkFigure,
    shapes: LinkShapes,
    palette: LinkPalette,
    tone: Color,
    lead: LinkLead,
    from: Float,
) {
    val deviceFrom = shapes.at(from, CableY)
    val deviceTo = shapes.at(DeviceEnd, CableY)
    drawLine(palette.jacket, deviceFrom, deviceTo, shapes.jacket.width, StrokeCap.Round)
    drawLine(palette.track, deviceFrom, deviceTo, shapes.track.width, StrokeCap.Round)
    lead.trace(shapes.leadPath, upTo = 1f)
    drawPath(shapes.leadPath, palette.jacket, style = shapes.jacket)
    drawPath(shapes.leadPath, palette.track, style = shapes.track)
    // A live link lights the cable. The light spreads from the socket to both ends.
    if (figure.charge > 0f) {
        val lit = tone.copy(alpha = tone.alpha * ChargeAlpha)
        val reach = DeviceEnd - (DeviceEnd - from) * figure.charge
        drawLine(lit, shapes.at(reach, CableY), deviceTo, shapes.track.width, StrokeCap.Round)
        lead.trace(shapes.litPath, upTo = figure.charge)
        drawPath(shapes.litPath, lit, style = shapes.track)
    }
    drawPulses(figure.sent, forward = true, shapes, lead, tone, from)
    drawPulses(figure.received, forward = false, shapes, lead, tone, from)
}

private fun DrawScope.drawPlug(
    figure: LinkFigure,
    shapes: LinkShapes,
    palette: LinkPalette,
) {
    drawFaces(shapes.nose, palette, shapes)
    drawPath(shapes.contacts, palette.contact, style = shapes.hair)
    drawFaces(shapes.boot, palette, shapes)
    drawPath(shapes.ribs, palette.plugTop, style = shapes.hair)
    drawFaces(shapes.body, palette, shapes)
    translate(top = figure.click.at.coerceAtLeast(0f) * shapes.unit) { drawFaces(shapes.latch, palette, shapes) }
}

private fun DrawScope.drawSocket(
    figure: LinkFigure,
    shapes: LinkShapes,
    palette: LinkPalette,
    tone: Color,
) {
    drawFace(shapes.socketTop, palette.socketTop, shapes)
    // The lamps light the top of the socket by one step of tone.
    var light = 0f
    for (index in figure.lamps.indices) {
        val lamp = figure.lamps[index]
        if (lamp.stage != RipDpiLinkStage.Pending) light += lamp.glow / LinkStageCount
    }
    if (light > 0f) drawPath(shapes.socketTop, tone, alpha = light.coerceAtMost(1f) * CastAlpha)
    drawFace(shapes.socketFront, palette.socket, shapes)
    drawRoundRect(palette.window, shapes.windowAt, shapes.windowSize, CornerRadius(WindowInset * shapes.unit))
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
    palette: LinkPalette,
) {
    val center = shapes.lamp(index)
    val path = shapes.lampPath
    val size = LampRadius * shapes.unit * lamp.size.at.coerceAtLeast(0f)
    path.rewind()
    for (step in 0 until LampSteps) {
        val angle = LinkTau * step / LampSteps
        val radius = size * (1f + lamp.notch * cos(LampPoints * angle + lamp.turn))
        val x = center.x + radius * cos(angle)
        val y = center.y + radius * sin(angle)
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    val now = palette.lamp(lamp.stage)
    val color = if (lamp.mix < 1f) lerp(palette.lamp(lamp.before), now, lamp.mix) else now
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
    /** Traces the cable from the plug as far as the share [upTo] of its length. */
    fun trace(
        path: Path,
        upTo: Float,
    ) {
        val start = shapes.at(startX, startY)
        val middleX = (startX + to) / 2f
        // The first part of a curve is a curve: its control point and its end move toward the start.
        val control = shapes.at(startX + (middleX - startX) * upTo, startY + (middleY - startY) * upTo)
        val end = at(startX + (to - startX) * upTo, lane = 0f)
        path.rewind()
        path.moveTo(start.x, start.y)
        path.quadraticTo(control.x, control.y, end.x, end.y)
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
    val side = if (forward) -LaneOffset else LaneOffset
    val alpha = color.alpha * lane.alpha.coerceIn(0f, 1f)
    val head = color.copy(alpha = alpha)
    val tail = color.copy(alpha = alpha * TailAlpha)
    // Indexed, so a frame allocates no iterator.
    for (index in lane.pulses.indices) {
        // The distance that a packet has travelled becomes a place on the cable: sent packets start at the device.
        val travelled = lane.pulses[index].at
        val a = if (forward) from + travelled - LinkPulseLength else lead.to - travelled
        val b = a + LinkPulseLength
        // A packet has a bright head and a faint tail behind it.
        drawPulse(a, b, side, tail, shapes, lead, from)
        if (forward) drawPulse(b - PulseHead, b, side, head, shapes, lead, from)
        if (!forward) drawPulse(a, a + PulseHead, side, head, shapes, lead, from)
    }
}

/** Draws the part of a packet from [a] to [b] that is not behind the socket or the plug. */
private fun DrawScope.drawPulse(
    a: Float,
    b: Float,
    side: Float,
    color: Color,
    shapes: LinkShapes,
    lead: LinkLead,
    from: Float,
) {
    val width = PulseWidth * shapes.unit
    val deviceTo = minOf(b, DeviceEnd - CableCover)
    val deviceFrom = maxOf(a, from)
    if (deviceTo - deviceFrom > MinPulse) {
        val start = shapes.at(deviceFrom, CableY + side)
        drawLine(color, start, shapes.at(deviceTo, CableY + side), width, StrokeCap.Round)
    }
    val leadFrom = maxOf(a, lead.startX + CableCover)
    val leadTo = minOf(b, lead.to)
    if (leadTo - leadFrom > MinPulse) {
        drawLine(color, lead.at(leadFrom, side), lead.at(leadTo, side), width, StrokeCap.Round)
    }
}

// The design, in dp. The cable runs along CableY, and every part is placed from it. The figure is 162 dp wide with
// a loose plug, and FigureMiddle puts it between the middle of that and the middle of the seated figure.
internal const val FigureMiddle = 146f
internal const val EdgeFrom = -24f
internal const val EdgeBeyond = 44f
internal const val PlugNose = 124f
internal const val PlugEnd = 196f
internal const val LeadStart = 192f
private const val DepthX = 0.55f
private const val DepthY = 0.4f
private const val CableY = 42f
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
private const val BootHalf = 8f
private const val BootNear = 3f
private const val BootFar = 11f
private const val LatchStart = 146f
private const val LatchEnd = 166f
private const val LatchTop = 14.5f
private const val LatchNear = 5f
private const val LatchFar = 9f

// The contacts are all in the socket when the plug is seated.
private const val FirstContact = 126f
private const val ContactStep = 2.2f
private const val ContactInset = 1f
private const val RibCount = 3
private const val RibStep = 4.5f
private const val RibHalf = 5f
private const val HairWidth = 1f
private const val PlugPivot = 256f
private const val DeviceEnd = 78f
private const val CableCover = 3f
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
private const val TailAlpha = 0.4f
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

// A loose plug lies a little lower and turns.
private const val LooseDrop = 5f
private const val LooseTurn = 5f
private const val HalfTurn = 180f

private const val PlugMix = 0.14f
private const val TopMix = 0.2f
private const val ContactMix = 0.6f
private const val SideMix = 0.3f
private const val PortMix = 0.75f
private const val TrackMix = 0.22f
private const val LampOffMix = 0.3f
