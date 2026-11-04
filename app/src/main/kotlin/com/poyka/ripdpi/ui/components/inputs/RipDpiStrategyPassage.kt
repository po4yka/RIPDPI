package com.poyka.ripdpi.ui.components.inputs

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.ui.screens.status.PassageFamily
import com.poyka.ripdpi.ui.screens.status.PassageHostEnd
import com.poyka.ripdpi.ui.screens.status.PassageHostStart
import com.poyka.ripdpi.ui.screens.status.PassageLane
import com.poyka.ripdpi.ui.screens.status.PassagePayloadEnd
import com.poyka.ripdpi.ui.screens.status.PassagePiece
import com.poyka.ripdpi.ui.screens.status.PassagePieceKind
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StrategyPassageScene
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val SingleLaneHeight = 44.dp
private val DoubleLaneHeight = 22.dp
private val IconColumn = 20.dp
private val ColumnGap = 8.dp
private val PieceGap = 3.dp
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
private const val FakePrefixUnits = 12f

private enum class PassageWhole { Assembled, Unconfirmed, Empty }

private class PassageColors(
    surface: Color,
    val content: Color,
    val host: Color,
    val decoy: Color,
) {
    val cut = surface
    val real = lerp(surface, content, RealTone)
    val track = content.copy(alpha = TrackAlpha)
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
    surface: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = RipDpiThemeTokens.colors
    val colors =
        PassageColors(
            surface = surface,
            content = content,
            host = tokens.info,
            decoy = tokens.warning,
        )
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
                drawPassageLane(lane, whole, colors, mirrored, measurer, countStyle)
            }
        }
    }
}

private fun DrawScope.drawPassageLane(
    lane: PassageLane,
    whole: PassageWhole,
    colors: PassageColors,
    mirrored: Boolean,
    measurer: TextMeasurer,
    countStyle: TextStyle,
) {
    val height = size.height * BlockHeightFraction
    val top = (size.height - height) / 2
    val dpiX = size.width * DpiLineFraction
    val leftStart = IconColumn.toPx()
    val wholeStart = dpiX + ColumnGap.toPx()
    val wholeEnd = size.width - IconColumn.toPx()
    val wholeUnit = (wholeEnd - wholeStart) / PassagePayloadEnd
    val pieces = lane.pieces.filter { it.kind != PassagePieceKind.Retransmit }
    val copyLabels =
        pieces.map { piece ->
            piece.takeIf { it.extraCopies > 0 }?.let {
                measurer.measure(
                    "×${it.copyIndex + 1 + it.extraCopies}",
                    countStyle,
                )
            }
        }
    val room =
        dpiX - ColumnGap.toPx() - leftStart -
            PieceGap.toPx() * (pieces.size - 1).coerceAtLeast(0) -
            copyLabels.filterNotNull().sumOf { it.size.width + PieceGap.roundToPx() }
    val unit =
        minOf(room / pieces.sumOf { it.units().toDouble() }.toFloat().coerceAtLeast(1f), wholeUnit).coerceAtLeast(0f)
    var x = leftStart
    val placedLabels = mutableListOf<Pair<TextLayoutResult, Float>>()
    val mirror = if (mirrored) -1f else 1f
    scale(scaleX = mirror, scaleY = 1f) {
        drawEndpoints(colors)
        pieces.forEachIndexed { index, piece ->
            val width = piece.units() * unit
            drawPiece(piece, Rect(x, top, x + width, top + height), lane.recordBoundaries, colors)
            x += width + PieceGap.toPx()
            copyLabels[index]?.let { label ->
                placedLabels += label to x
                x += label.size.width + PieceGap.toPx()
            }
        }
        drawWhole(lane, whole, Rect(wholeStart, top, wholeEnd, top + height), colors)
        drawLine(
            color = colors.content,
            start = Offset(dpiX, 0f),
            end = Offset(dpiX, size.height),
            strokeWidth = ThinStroke.toPx(),
            pathEffect = dash(),
        )
    }
    placedLabels.forEach { (label, labelStart) ->
        val labelX = if (mirrored) size.width - labelStart - label.size.width else labelStart
        drawText(label, topLeft = Offset(labelX, (size.height - label.size.height) / 2))
    }
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
    area: Rect,
    colors: PassageColors,
) {
    if (whole == PassageWhole.Assembled) {
        val packet = PassagePiece(PassagePieceKind.Real, 0, PassagePayloadEnd)
        drawPiece(packet, area, lane.recordBoundaries, colors)
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
            color = colors.decoy.copy(alpha = DecoyHostAlpha),
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
    drawRoundRect(colors.real.copy(alpha = alpha), block.topLeft, block.size, radius)
    hostFraction(piece)?.let { (start, width) ->
        val hostStart = block.left + block.width * start
        drawRect(
            color = colors.host.copy(alpha = alpha),
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
