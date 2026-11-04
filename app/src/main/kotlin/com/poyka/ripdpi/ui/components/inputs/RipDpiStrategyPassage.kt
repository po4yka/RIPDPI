package com.poyka.ripdpi.ui.components.inputs

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.ui.screens.status.PassageFamily
import com.poyka.ripdpi.ui.screens.status.PassageFlightMillis
import com.poyka.ripdpi.ui.screens.status.PassageHostEnd
import com.poyka.ripdpi.ui.screens.status.PassageHostStart
import com.poyka.ripdpi.ui.screens.status.PassageLane
import com.poyka.ripdpi.ui.screens.status.PassagePayloadEnd
import com.poyka.ripdpi.ui.screens.status.PassagePiece
import com.poyka.ripdpi.ui.screens.status.PassagePieceKind
import com.poyka.ripdpi.ui.screens.status.PassageSettleMillis
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StrategyPassageScene
import com.poyka.ripdpi.ui.screens.status.arrives
import com.poyka.ripdpi.ui.screens.status.durationMillis
import com.poyka.ripdpi.ui.screens.status.landedPieces
import com.poyka.ripdpi.ui.screens.status.passageFlightStartMillis
import com.poyka.ripdpi.ui.screens.status.settleStartMillis
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val SingleLaneHeight = 44.dp
private val DoubleLaneHeight = 22.dp
private val IconColumn = 20.dp
private val ColumnGap = 8.dp
private val PieceGap = 3.dp
private val FlashStroke = 2.dp
private val FlashRange = 10.dp
private val CopyLabelSize = 11.dp
private val PieceRadius = 2.dp
private val ThinStroke = 1.2.dp
private val DashOn = 2.6.dp
private val DashOff = 2.dp
private val MarkWidth = 2.5.dp
private val UrgentByteRadius = 2.4.dp
private val SynMarkSize = 6.dp
private val ResetWidth = 3.dp
private val MarkLift = 4.dp
private val PhoneWidth = 10.dp
private val PhoneHeight = 18.dp
private val GlobeRadius = 7.5.dp
private val GlobeInnerRadius = 3.2.dp
private const val DpiLineFraction = 0.52f
private const val BlockHeightFraction = 0.34f
private const val RealTone = 0.56f
private const val TrackAlpha = 0.28f
private const val ShortTtlAlpha = 0.62f
private const val DecoyHostAlpha = 0.28f
private const val DecoyHostStart = 0.3f
private const val DecoyHostWidth = 0.18f
private const val SynMarkHeightRatio = 2f / 3f
private const val ResetUnits = 5f
private const val PassageAtRest = Float.MAX_VALUE
private const val LaunchedAlpha = 0.26f
private const val DecoyDeathFraction = 0.11f
private const val DecoyFadeStart = 0.65f
private const val EngagingFadeStart = 0.7f
private const val SettleGrowth = 0.04f
private const val FakePrefixUnits = 12f

private enum class PassageWhole { Assembled, Unconfirmed, Empty }

private class PassageColors(
    val cut: Color,
    val real: Color,
    val track: Color,
    val content: Color,
    val host: Color,
    val decoy: Color,
) {
    fun fadedTo(alpha: Float): PassageColors =
        if (alpha >= 1f) {
            this
        } else {
            PassageColors(
                cut.copy(alpha = alpha),
                real.copy(alpha = alpha),
                track.copy(alpha = track.alpha * alpha),
                content.copy(alpha = alpha),
                host.copy(alpha = alpha),
                decoy.copy(alpha = alpha),
            )
        }
}

/** One moment of the playback. The rest frame is any time past the end of the scene. */
private class PassageFrame(
    val timeMillis: Float,
    val engaging: Boolean,
) {
    fun settle(lane: PassageLane): Float =
        if (engaging && timeMillis < PassageAtRest) {
            0f
        } else {
            ((timeMillis - lane.settleStartMillis) / PassageSettleMillis).coerceIn(0f, 1f)
        }

    fun restingAlpha(
        pieceIndex: Int,
        settle: Float,
    ): Float {
        val launched = timeMillis >= passageFlightStartMillis(pieceIndex)
        return if (launched) lerp(LaunchedAlpha, 1f, settle) else 1f
    }
}

/**
 * What the panel remembers about playback. It outlives the scene, which leaves composition while
 * saved edits wait for a reconnect, so a scene that returns neither repeats a tap nor misses a change.
 * Leaving the screen discards it: the next opening is a new baseline and stays at rest.
 */
internal class PassagePlaybackMemory {
    var replayRequests by mutableIntStateOf(0)
    var handledReplays = 0
    var seenKey: Int? = null
}

/**
 * Plays one flight when the session locks, the strategy changes, or the panel is tapped, and loops up to
 * the DPI line while connecting. Reduced motion stays at rest.
 */
@Composable
internal fun rememberPassagePlayback(
    scene: StrategyPassageScene,
    status: StatusConnectionActuatorStatus,
    memory: PassagePlaybackMemory,
    motion: RipDpiMotion,
): Animatable<Float, *> {
    val clock = remember { Animatable(PassageAtRest) }
    val canPlay = motion.allowsInfiniteMotion && !LocalInspectionMode.current
    val duration = scene.durationMillis
    val flight = motion.durationTween<Float>(duration, LinearEasing)
    val key = "$status|$scene".hashCode()
    val locked = status == StatusConnectionActuatorStatus.Locked
    LaunchedEffect(key, canPlay) {
        val changed = memory.seenKey != null && memory.seenKey != key
        memory.seenKey = key
        when {
            canPlay && status == StatusConnectionActuatorStatus.Engaging -> {
                clock.snapTo(0f)
                clock.animateTo(duration.toFloat(), infiniteRepeatable(flight, RepeatMode.Restart))
            }

            canPlay && locked && changed -> {
                clock.snapTo(0f)
                clock.animateTo(duration.toFloat(), flight)
            }

            else -> {
                clock.snapTo(PassageAtRest)
            }
        }
    }
    LaunchedEffect(memory.replayRequests) {
        val requested = memory.replayRequests != memory.handledReplays
        memory.handledReplays = memory.replayRequests
        if (requested && canPlay && locked) {
            clock.snapTo(0f)
            clock.animateTo(duration.toFloat(), flight)
        }
    }
    return clock
}

private class QuicDecoyShape(
    val units: Float,
    val tail: Float = 0f,
    val mark: Float? = null,
)

// shortcut: only the copy variants a reader can tell apart are drawn, add one if a new QUIC step needs its own mark.
@Suppress("MagicNumber")
private fun quicDecoyShape(
    step: UdpChainStepKind,
    index: Int,
): QuicDecoyShape =
    when (step) {
        UdpChainStepKind.FakeBurst -> {
            QuicDecoyShape(34f)
        }

        UdpChainStepKind.DummyPrepend -> {
            QuicDecoyShape(18f + index * 8)
        }

        UdpChainStepKind.QuicPaddingLadder -> {
            QuicDecoyShape(34f, tail = 4f + index * 5)
        }

        UdpChainStepKind.QuicMultiInitialRealistic -> {
            QuicDecoyShape(30f + index * 6, tail = index * 5f)
        }

        UdpChainStepKind.QuicCidChurn -> {
            QuicDecoyShape(34f, mark = 0.08f + index * 0.12f)
        }

        UdpChainStepKind.QuicPacketNumberGap -> {
            QuicDecoyShape(34f, mark = 0.32f + index * 0.1f)
        }

        UdpChainStepKind.QuicFakeVersion, UdpChainStepKind.QuicVersionNegotiationDecoy -> {
            QuicDecoyShape(
                44f,
                mark = 0.06f,
            )
        }

        UdpChainStepKind.QuicSniSplit -> {
            QuicDecoyShape(72f, mark = 0.39f)
        }

        UdpChainStepKind.QuicCryptoSplit -> {
            QuicDecoyShape(72f, mark = 0.22f)
        }

        UdpChainStepKind.IpFrag2Udp -> {
            QuicDecoyShape(34f)
        }
    }

private fun PassagePiece.units(): Float {
    val quic = quicStep?.let { quicDecoyShape(it, copyIndex) }
    return when {
        kind == PassagePieceKind.FakeReset -> ResetUnits
        quic != null -> quic.units + quic.tail
        hasFakePrefix -> (end - start) + FakePrefixUnits
        else -> (end - start).toFloat()
    }
}

/**
 * The packet as DPI sees it on the left, and as the server reassembles it on the right. It draws the
 * configured chain only: the whole packet appears for a locked session and never proves a bypass.
 */
@Composable
internal fun RipDpiStrategyPassage(
    scene: StrategyPassageScene,
    status: StatusConnectionActuatorStatus,
    playback: PassagePlaybackMemory,
    surface: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = RipDpiThemeTokens.colors
    val colors =
        PassageColors(
            cut = surface,
            real = lerp(surface, content, RealTone),
            track = content.copy(alpha = TrackAlpha),
            content = content,
            host = tokens.info,
            decoy = tokens.warning,
        )
    val clock = rememberPassagePlayback(scene, status, playback, RipDpiThemeTokens.motion)
    val engaging = status == StatusConnectionActuatorStatus.Engaging
    val whole =
        when (status) {
            StatusConnectionActuatorStatus.Locked -> PassageWhole.Assembled
            StatusConnectionActuatorStatus.Degraded -> PassageWhole.Unconfirmed
            else -> PassageWhole.Empty
        }
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measurer = rememberTextMeasurer()
    // The scene keeps its size at every font scale, so its label does too.
    val labelSize = with(LocalDensity.current) { CopyLabelSize.toSp() }
    val countStyle =
        RipDpiThemeTokens.type.monoSmall.copy(
            color = content,
            fontSize = labelSize,
            lineHeight = labelSize,
        )
    val description = strategyPassageDescription(scene)
    val lanes = listOfNotNull(scene.tcp, scene.udp)
    val laneHeight = if (lanes.size > 1) DoubleLaneHeight else SingleLaneHeight
    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = description }) {
        lanes.forEach { lane ->
            Canvas(Modifier.fillMaxWidth().height(laneHeight)) {
                drawPassageLane(
                    lane,
                    whole,
                    PassageFrame(clock.value, engaging),
                    colors,
                    mirrored,
                    measurer,
                    countStyle,
                )
            }
        }
    }
}

/** The left side of a lane: where each piece rests, with the labels that collapse its copies. */
private class LaneLayout(
    val pieceAreas: List<Rect>,
    val labels: List<Pair<TextLayoutResult, Float>>,
    val dpiX: Float,
    val wholeArea: Rect,
)

private fun DrawScope.layoutLane(
    lane: PassageLane,
    measurer: TextMeasurer,
    countStyle: TextStyle,
): LaneLayout {
    val height = size.height * BlockHeightFraction
    val top = (size.height - height) / 2
    val dpiX = size.width * DpiLineFraction
    val leftStart = IconColumn.toPx()
    val wholeArea = Rect(dpiX + ColumnGap.toPx(), top, size.width - IconColumn.toPx(), top + height)
    val resting = lane.pieces.filter { it.kind != PassagePieceKind.Retransmit }
    val copyLabels =
        resting.map { piece ->
            piece.takeIf { it.extraCopies > 0 }?.let {
                measurer.measure("×${it.copyIndex + 1 + it.extraCopies}", countStyle)
            }
        }
    val room =
        dpiX - ColumnGap.toPx() - leftStart -
            PieceGap.toPx() * (resting.size - 1).coerceAtLeast(0) -
            copyLabels.filterNotNull().sumOf { it.size.width + PieceGap.roundToPx() }
    val unit =
        minOf(
            room / resting.sumOf { it.units().toDouble() }.toFloat().coerceAtLeast(1f),
            wholeArea.width / PassagePayloadEnd,
        ).coerceAtLeast(0f)
    var x = leftStart
    var restingIndex = 0
    val labels = mutableListOf<Pair<TextLayoutResult, Float>>()
    val areas =
        lane.pieces.map { piece ->
            val width = piece.units() * unit
            if (piece.kind == PassagePieceKind.Retransmit) {
                // A resend is not on the left at rest: it leaves the device only during the flight.
                return@map Rect(leftStart, top, leftStart + width, top + height)
            }
            val area = Rect(x, top, x + width, top + height)
            x += width + PieceGap.toPx()
            copyLabels[restingIndex++]?.let { label ->
                labels += label to x
                x += label.size.width + PieceGap.toPx()
            }
            area
        }
    return LaneLayout(areas, labels, dpiX, wholeArea)
}

private fun DrawScope.drawPassageLane(
    lane: PassageLane,
    whole: PassageWhole,
    frame: PassageFrame,
    colors: PassageColors,
    mirrored: Boolean,
    measurer: TextMeasurer,
    countStyle: TextStyle,
) {
    val layout = layoutLane(lane, measurer, countStyle)
    val settle = frame.settle(lane)
    scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f) {
        drawEndpoints(colors)
        lane.pieces.forEachIndexed { index, piece ->
            if (piece.kind != PassagePieceKind.Retransmit) {
                val tone = colors.fadedTo(frame.restingAlpha(index, settle))
                drawPiece(piece, layout.pieceAreas[index], lane.recordBoundaries, tone)
            }
        }
        drawWhole(lane, whole, frame, settle, layout.wholeArea, colors)
        val flash = if (settle < 1f) drawFlights(lane, frame, settle, layout, colors) else false
        drawLine(
            color = if (flash) colors.host else colors.content,
            start = Offset(layout.dpiX, 0f),
            end = Offset(layout.dpiX, size.height),
            strokeWidth = if (flash) FlashStroke.toPx() else ThinStroke.toPx(),
            pathEffect = if (flash) null else dash(),
        )
    }
    layout.labels.forEach { (label, labelStart) ->
        val labelX = if (mirrored) size.width - labelStart - label.size.width else labelStart
        drawText(label, topLeft = Offset(labelX, (size.height - label.size.height) / 2))
    }
}

/** Draws the pieces in the air and tells whether one of them is crossing the DPI line. */
private fun DrawScope.drawFlights(
    lane: PassageLane,
    frame: PassageFrame,
    settle: Float,
    layout: LaneLayout,
    colors: PassageColors,
): Boolean {
    var crossing = false
    val wholeUnit = layout.wholeArea.width / PassagePayloadEnd
    if (frame.engaging || settle <= 0f) {
        lane.pieces.forEachIndexed { index, piece ->
            val progress = (frame.timeMillis - passageFlightStartMillis(index)) / PassageFlightMillis
            if (progress in 0f..1f) {
                val from = layout.pieceAreas[index]
                val staysInAir = frame.engaging || !piece.arrives
                val targetWidth = if (staysInAir) from.width else (piece.end - piece.start) * wholeUnit
                val targetX =
                    when {
                        frame.engaging -> layout.dpiX - from.width - PieceGap.toPx()
                        piece.endsBeforeDpi -> layout.dpiX - from.width - PieceGap.toPx()
                        !piece.arrives -> layout.dpiX + size.width * DecoyDeathFraction
                        else -> layout.wholeArea.left + piece.start * wholeUnit
                    }
                val eased = RipDpiMotion.StandardEasing.transform(progress)
                val x = lerp(from.left, targetX, eased)
                val width = lerp(from.width, targetWidth, eased)
                val fadeStart = if (frame.engaging) EngagingFadeStart else DecoyFadeStart
                val alpha = if (staysInAir) 1f - ((progress - fadeStart) / (1f - fadeStart)).coerceIn(0f, 1f) else 1f
                drawPiece(
                    piece,
                    Rect(x, from.top, x + width, from.bottom),
                    lane.recordBoundaries,
                    colors.fadedTo(alpha),
                )
                crossing = crossing || abs(x + width - layout.dpiX) < FlashRange.toPx()
            }
        }
    }
    return crossing
}

private fun DrawScope.dash(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(DashOn.toPx(), DashOff.toPx()))

private fun DrawScope.drawEndpoints(colors: PassageColors) {
    val stroke = Stroke(ThinStroke.toPx())
    val centerY = size.height / 2
    val phone =
        Size(
            PhoneWidth.toPx(),
            PhoneHeight.toPx().coerceAtMost(size.height - stroke.width),
        )
    drawRoundRect(
        color = colors.content,
        topLeft = Offset(stroke.width, centerY - phone.height / 2),
        size = phone,
        cornerRadius = CornerRadius(PieceRadius.toPx()),
        style = stroke,
    )
    val radius = GlobeRadius.toPx().coerceAtMost(centerY - stroke.width)
    val center = Offset(size.width - radius - stroke.width, centerY)
    drawCircle(colors.content, radius, center, style = stroke)
    drawOval(
        color = colors.content,
        topLeft = Offset(center.x - GlobeInnerRadius.toPx(), center.y - radius),
        size = Size(GlobeInnerRadius.toPx() * 2, radius * 2),
        style = stroke,
    )
    drawLine(
        color = colors.content,
        start = Offset(center.x - radius, centerY),
        end = Offset(center.x + radius, centerY),
        strokeWidth = stroke.width,
    )
}

private fun DrawScope.drawWhole(
    lane: PassageLane,
    whole: PassageWhole,
    frame: PassageFrame,
    settle: Float,
    area: Rect,
    colors: PassageColors,
) {
    if (whole == PassageWhole.Assembled && settle > 0f) {
        val growth = if (settle < 1f) 1f + SettleGrowth * sin(PI.toFloat() * settle) else 1f
        scale(scale = growth, pivot = area.center) {
            drawPiece(PassagePiece(PassagePieceKind.Real, 0, PassagePayloadEnd), area, lane.recordBoundaries, colors)
        }
        return
    }
    val unconfirmed = whole == PassageWhole.Unconfirmed
    drawRoundRect(
        color = if (unconfirmed) colors.decoy else colors.track,
        topLeft = area.topLeft,
        size = area.size,
        cornerRadius = CornerRadius(PieceRadius.toPx()),
        style = Stroke(ThinStroke.toPx(), pathEffect = if (unconfirmed) dash() else null),
    )
    if (whole == PassageWhole.Assembled) {
        val unit = area.width / PassagePayloadEnd
        lane.landedPieces(frame.timeMillis).forEach { piece ->
            val slot = piece.copy(hasUrgentByte = false, inSyn = false, hasFakePrefix = false)
            val left = area.left + piece.start * unit
            drawPiece(
                slot,
                Rect(left, area.top, left + (piece.end - piece.start) * unit, area.bottom),
                lane.recordBoundaries,
                colors,
            )
        }
    }
}

private fun DrawScope.drawPiece(
    piece: PassagePiece,
    area: Rect,
    boundaries: List<Int>,
    colors: PassageColors,
) {
    if (piece.kind == PassagePieceKind.FakeReset) {
        val width = maxOf(area.width, ResetWidth.toPx())
        drawRect(colors.decoy, Offset(area.left, area.top), Size(width, area.height))
        return
    }
    val prefixWidth = if (piece.hasFakePrefix) area.width * FakePrefixUnits / piece.units() else 0f
    if (prefixWidth > 0f) {
        drawDashedBlock(Rect(area.left, area.top, area.left + prefixWidth - 1f, area.bottom), colors.decoy)
    }
    val quic = piece.quicStep?.let { quicDecoyShape(it, piece.copyIndex) }
    val tail = quic?.let { area.width * it.tail / (it.units + it.tail) } ?: 0f
    val block = Rect(area.left + prefixWidth, area.top, area.right - tail, area.bottom)
    if (piece.kind == PassagePieceKind.Decoy) {
        drawDecoy(piece, block, tail, quic, colors)
    } else {
        drawRealBytes(piece, block, boundaries, colors)
    }
    if (piece.hasUrgentByte) {
        drawCircle(colors.decoy, UrgentByteRadius.toPx(), Offset(area.right, area.top - MarkLift.toPx()))
    }
    if (piece.inSyn) {
        val markSize = SynMarkSize.toPx()
        val path =
            Path().apply {
                moveTo(area.left, area.top - MarkLift.toPx() - markSize * SynMarkHeightRatio)
                lineTo(area.left + markSize, area.top - MarkLift.toPx() - markSize * SynMarkHeightRatio)
                lineTo(area.left + markSize / 2, area.top - MarkLift.toPx())
                close()
            }
        drawPath(path, colors.decoy)
    }
}

private fun DrawScope.drawDecoy(
    piece: PassagePiece,
    block: Rect,
    tail: Float,
    quic: QuicDecoyShape?,
    colors: PassageColors,
) {
    drawDashedBlock(block, colors.decoy)
    val host = if (quic != null) DecoyHostStart to DecoyHostWidth else hostFraction(piece)
    if (host != null) {
        drawRect(
            color = colors.decoy.copy(alpha = colors.decoy.alpha * DecoyHostAlpha),
            topLeft = Offset(block.left + block.width * host.first, block.top),
            size = Size(block.width * host.second, block.height),
        )
    }
    quic?.mark?.let { mark ->
        drawRect(
            color = colors.decoy,
            topLeft = Offset(block.left + block.width * mark, block.top),
            size = Size(MarkWidth.toPx(), block.height),
        )
    }
    if (tail > 0f) {
        drawLine(
            color = colors.decoy,
            start = Offset(block.right, block.center.y),
            end = Offset(block.right + tail, block.center.y),
            strokeWidth = ThinStroke.toPx(),
            pathEffect = dash(),
        )
    }
}

private fun DrawScope.drawRealBytes(
    piece: PassagePiece,
    block: Rect,
    boundaries: List<Int>,
    colors: PassageColors,
) {
    val alpha = if (piece.kind == PassagePieceKind.ShortTtl) ShortTtlAlpha else 1f
    val radius = if (piece.kind == PassagePieceKind.IpFragment) CornerRadius.Zero else CornerRadius(PieceRadius.toPx())
    drawRoundRect(colors.real.copy(alpha = colors.real.alpha * alpha), block.topLeft, block.size, radius)
    hostFraction(piece)?.let { (start, width) ->
        val hostStart = block.left + block.width * start
        drawRect(
            color = colors.host.copy(alpha = colors.host.alpha * alpha),
            topLeft = Offset(hostStart, block.top),
            size = Size(block.width * width, block.height),
        )
        // Host and real bytes are too close in contrast to meet directly, so a cut keeps them apart.
        if (start > 0f) drawCut(hostStart, block, colors)
        if (start + width < 1f) drawCut(hostStart + block.width * width, block, colors)
    }
    if (piece.kind == PassagePieceKind.ShortTtl) {
        drawDashedBlock(block, colors.decoy)
    }
    val span = (piece.end - piece.start).coerceAtLeast(1)
    boundaries.filter { it > piece.start && it < piece.end }.forEach { boundary ->
        drawCut(block.left + block.width * (boundary - piece.start) / span, block, colors)
    }
}

private fun DrawScope.drawCut(
    x: Float,
    block: Rect,
    colors: PassageColors,
) {
    drawLine(colors.cut, Offset(x, block.top), Offset(x, block.bottom), ThinStroke.toPx())
}

private fun DrawScope.drawDashedBlock(
    block: Rect,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = block.topLeft,
        size = Size(block.width.coerceAtLeast(0f), block.height),
        cornerRadius = CornerRadius(PieceRadius.toPx()),
        style = Stroke(ThinStroke.toPx(), pathEffect = dash()),
    )
}

/** Where the host lies inside the piece, as fractions of its width. */
private fun hostFraction(piece: PassagePiece): Pair<Float, Float>? {
    val start = maxOf(piece.start, PassageHostStart)
    val end = minOf(piece.end, PassageHostEnd)
    if (end <= start || piece.end <= piece.start) return null
    val span = (piece.end - piece.start).toFloat()
    return (start - piece.start) / span to (end - start) / span
}

@StringRes
private fun PassageFamily.nameRes(): Int =
    when (this) {
        PassageFamily.Cut -> R.string.status_strategy_family_cut
        PassageFamily.LaggingPart -> R.string.status_strategy_family_lagging_part
        PassageFamily.Decoy -> R.string.status_strategy_family_decoy
        PassageFamily.HostMask -> R.string.status_strategy_family_host_mask
        PassageFamily.TlsRecords -> R.string.status_strategy_family_tls_records
        PassageFamily.UrgentByte -> R.string.status_strategy_family_urgent_byte
        PassageFamily.IpFragments -> R.string.status_strategy_family_ip_fragments
        PassageFamily.Overlap -> R.string.status_strategy_family_overlap
        PassageFamily.FakeReset -> R.string.status_strategy_family_fake_reset
        PassageFamily.QuicDecoyBurst -> R.string.status_strategy_family_quic_decoys
        PassageFamily.QuicSplitCopy -> R.string.status_strategy_family_quic_split_copy
        PassageFamily.QuicVersion -> R.string.status_strategy_family_quic_version
    }

@StringRes
private fun PassageFamily.descriptionRes(): Int =
    when (this) {
        PassageFamily.Cut -> R.string.status_strategy_family_cut_description
        PassageFamily.LaggingPart -> R.string.status_strategy_family_lagging_part_description
        PassageFamily.Decoy -> R.string.status_strategy_family_decoy_description
        PassageFamily.HostMask -> R.string.status_strategy_family_host_mask_description
        PassageFamily.TlsRecords -> R.string.status_strategy_family_tls_records_description
        PassageFamily.UrgentByte -> R.string.status_strategy_family_urgent_byte_description
        PassageFamily.IpFragments -> R.string.status_strategy_family_ip_fragments_description
        PassageFamily.Overlap -> R.string.status_strategy_family_overlap_description
        PassageFamily.FakeReset -> R.string.status_strategy_family_fake_reset_description
        PassageFamily.QuicDecoyBurst -> R.string.status_strategy_family_quic_decoys_description
        PassageFamily.QuicSplitCopy -> R.string.status_strategy_family_quic_split_copy_description
        PassageFamily.QuicVersion -> R.string.status_strategy_family_quic_version_description
    }

@Composable
internal fun strategyPassageName(scene: StrategyPassageScene): String =
    when (scene.families.size) {
        0 -> {
            stringResource(R.string.status_strategy_family_none)
        }

        1 -> {
            stringResource(scene.families[0].nameRes())
        }

        2 -> {
            stringResource(
                R.string.status_strategy_family_pair,
                stringResource(scene.families[0].nameRes()),
                stringResource(scene.families[1].nameRes()),
            )
        }

        else -> {
            stringResource(R.string.status_strategy_family_combined)
        }
    }

@Composable
private fun strategyPassageDescription(scene: StrategyPassageScene): String {
    // The visible panel has no "Bypass strategy" caption, so TalkBack keeps that name here.
    val title = stringResource(R.string.config_local_bypass_strategy_title)
    val details =
        if (scene.families.isEmpty()) {
            listOf(stringResource(R.string.status_strategy_family_none_description))
        } else {
            scene.families.map { stringResource(it.descriptionRes()) }
        }
    return stringResource(R.string.status_strategy_passage_description, title, details.joinToString(separator = " "))
}
