package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.status.PassageFlightMillis
import com.poyka.ripdpi.ui.screens.status.PassageLane
import com.poyka.ripdpi.ui.screens.status.PassagePieceKind
import com.poyka.ripdpi.ui.screens.status.PassageSettleMillis
import com.poyka.ripdpi.ui.screens.status.SchemeDecoyHost
import com.poyka.ripdpi.ui.screens.status.SchemeGrid
import com.poyka.ripdpi.ui.screens.status.SchemeMark
import com.poyka.ripdpi.ui.screens.status.SchemeRow
import com.poyka.ripdpi.ui.screens.status.SchemeSampleHost
import com.poyka.ripdpi.ui.screens.status.StrategyPassageScene
import com.poyka.ripdpi.ui.screens.status.arrives
import com.poyka.ripdpi.ui.screens.status.passageFlightStartMillis
import com.poyka.ripdpi.ui.screens.status.schemeRows
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import androidx.compose.ui.graphics.lerp as mix

private val MaxCell = 12.dp
private val CellHeight = 16.dp
private val CellInset = 0.5.dp
private val CellRadius = 3.dp
private val HeaderHeight = 15.dp
private val StripGap = 9.dp
private val RowLabelHeight = 14.dp
private val RowGap = 5.dp
private val DisplayPad = 5.dp
private val DisplayLetter = 7.dp
private val DpiMargin = 10.dp
private val DiamondSize = 4.5.dp
private val TickOver = 3.dp
private val CrossSize = 6.dp
private val CrossGap = 4.dp
private val HairStroke = 1.dp
private val MarkStroke = 1.5.dp
private val DotSize = 1.6.dp
private val DotGap = 3.4.dp
private val TextSize = 10.dp
private val SchemePhoneWidth = 7.dp
private val SchemePhoneHeight = 11.dp
private val SchemeGlobeRadius = 5.dp
private val SchemeGlobeInner = 2.2.dp
private val IconGap = 5.dp
private val LaneGap = 10.dp
private val DashOn = 2.dp
private val DashOff = 2.dp
private const val CellCenter = 0.5f
private const val DotSpace = 3
private const val DropShare = 0.3f
private const val EngagingFade = 0.7f
private const val AccentFill = 0.22f
private const val AccentStroke = 0.75f
private const val AccentText = 0.45f
private const val PlainFill = 0.1f
private const val PlainStroke = 0.45f
private const val MarkFill = 0.7f
private const val FaintTone = 0.26f
private const val MutedTone = 0.6f
private const val UnconfirmedAlpha = 0.5f
private const val SpentAlpha = 0.6f
private const val DiamondFill = 0.35f
private const val Smooth3 = 3f
private const val Smooth2 = 2f
private const val LaneTagTcp = "TCP"
private const val LaneTagUdp = "QUIC"
private const val DpiLabel = "DPI"
private const val CopiesPrefix = "+"

/** Fill, outline, and letter color of one kind of cell. */
internal class SchemeTone(
    val fill: Color,
    val stroke: Color,
    val text: Color,
)

internal class SchemeColors(
    val surface: Color,
    val content: Color,
    host: Color,
    decoy: Color,
    reset: Color,
) {
    val plain = SchemeTone(mix(surface, content, PlainFill), mix(surface, content, PlainStroke), content)
    val host = accent(host)
    val decoy = accent(decoy)
    val reset = accent(reset)
    val mark = SchemeTone(mix(surface, content, MarkFill), mix(surface, content, MarkFill), surface)
    val faint = mix(surface, content, FaintTone)
    val muted = mix(surface, content, MutedTone)

    private fun accent(color: Color) =
        SchemeTone(mix(surface, color, AccentFill), mix(surface, color, AccentStroke), mix(color, content, AccentText))
}

/** One moment of the scheme. The rest frame is any time past the end of the scene. */
internal class SchemeFrame(
    val timeMillis: Float,
    val engaging: Boolean,
    val connected: Boolean,
    val unconfirmed: Boolean,
)

/** How far one row is: out of the device packet, along its line, and settled at its end. */
internal class RowMotion(
    val drop: Float,
    val fly: Float,
    val alpha: Float,
    val landed: Float,
)

private fun smooth(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (Smooth3 - Smooth2 * x)
}

internal fun SchemeFrame.motion(index: Int): RowMotion {
    if (timeMillis >= PassageAtRest) {
        val done = if (connected) 1f else 0f
        return RowMotion(done, done, 1f, done)
    }
    val sinceStart = timeMillis - passageFlightStartMillis(index)
    val progress = (sinceStart / PassageFlightMillis).coerceIn(0f, 1f)
    val drop = smooth(progress / DropShare)
    val fly = smooth((progress - DropShare) / (1f - DropShare))
    return if (engaging) {
        RowMotion(drop, fly, 1f - ((fly - EngagingFade) / (1f - EngagingFade)).coerceIn(0f, 1f), 0f)
    } else {
        RowMotion(drop, fly, 1f, ((sinceStart - PassageFlightMillis) / PassageSettleMillis).coerceIn(0f, 1f))
    }
}

private class SchemeStrings(
    val parts: Map<PassagePieceKind, String>,
    val arrives: String,
    val lost: String,
    val early: String,
)

@Composable
private fun schemeStrings() =
    SchemeStrings(
        parts =
            mapOf(
                PassagePieceKind.Real to stringResource(R.string.status_strategy_part_real),
                PassagePieceKind.Decoy to stringResource(R.string.status_strategy_part_decoy),
                PassagePieceKind.ShortTtl to stringResource(R.string.status_strategy_part_short_ttl),
                PassagePieceKind.Retransmit to stringResource(R.string.status_strategy_part_resend),
                PassagePieceKind.IpFragment to stringResource(R.string.status_strategy_part_ip_fragment),
                PassagePieceKind.FakeReset to stringResource(R.string.status_strategy_part_fake_reset),
            ),
        arrives = stringResource(R.string.status_strategy_result_arrives),
        lost = stringResource(R.string.status_strategy_result_lost),
        early = stringResource(R.string.status_strategy_result_early),
    )

/** Measures each letter one time. A frame draws many cells, and most of them repeat a letter. */
private class SchemeGlyphs(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
) {
    private val cache = HashMap<String, TextLayoutResult>()

    fun of(text: String): TextLayoutResult = cache.getOrPut(text) { measurer.measure(text, style, maxLines = 1) }
}

private class SchemeLaneModel(
    val grid: SchemeGrid,
    val rows: List<SchemeRow>,
    val names: List<TextLayoutResult>,
    val results: List<TextLayoutResult>,
    val tag: String?,
) {
    val height: Dp = HeaderHeight + CellHeight + StripGap + (RowLabelHeight + CellHeight + RowGap) * rows.size
}

private fun laneModel(
    lane: PassageLane,
    tag: String?,
    strings: SchemeStrings,
    measurer: TextMeasurer,
    label: TextStyle,
): SchemeLaneModel {
    val grid = SchemeGrid(lane)
    val rows = schemeRows(lane, grid)
    return SchemeLaneModel(
        grid = grid,
        rows = rows,
        names =
            rows.mapIndexed { index, row ->
                measurer.measure("${index + 1}  ${strings.parts.getValue(row.piece.kind)}", label, maxLines = 1)
            },
        results =
            rows.map { row ->
                val text =
                    when {
                        row.piece.arrives -> strings.arrives
                        row.piece.endsBeforeDpi -> strings.early
                        else -> strings.lost
                    }
                measurer.measure(text, label, maxLines = 1)
            },
        tag = tag,
    )
}

/**
 * The plan as a flat scheme: the packet on the device, one line per packet that the chain sends, the filter in
 * the middle, and the packet the server puts together. It keeps its size at every font scale, as a diagram does.
 */
@Composable
internal fun RipDpiStrategyScheme(
    scene: StrategyPassageScene,
    colors: SchemeColors,
    frame: () -> SchemeFrame,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val size = with(LocalDensity.current) { TextSize.toSp() }
    val mono = RipDpiThemeTokens.type.monoSmall.copy(fontSize = size, lineHeight = size, fontWeight = FontWeight.Bold)
    val label = RipDpiThemeTokens.type.smallLabel.copy(fontSize = size, lineHeight = size)
    val strings = schemeStrings()
    val glyphs = remember(measurer, mono) { SchemeGlyphs(measurer, mono) }
    val labels = remember(measurer, label) { SchemeGlyphs(measurer, label) }
    val lanes =
        remember(scene, strings.arrives, label) {
            val both = scene.tcp != null && scene.udp != null
            listOfNotNull(
                scene.tcp?.let { laneModel(it, LaneTagTcp.takeIf { both }, strings, measurer, label) },
                scene.udp?.let { laneModel(it, LaneTagUdp.takeIf { both }, strings, measurer, label) },
            )
        }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LaneGap)) {
        lanes.forEach { lane ->
            Canvas(Modifier.fillMaxWidth().height(lane.height)) {
                SchemePainter(this, lane, frame(), colors, glyphs, labels).draw()
            }
        }
    }
}

/** Where one row is in a frame. */
private class RowPlace(
    val x: Float,
    val y: Float,
    val alpha: Float,
    val motion: RowMotion,
    val items: Int,
)

/** Draws one lane. The sizes come from the width, so the packet on each side has the same cells. */
private class SchemePainter(
    private val scope: DrawScope,
    private val lane: SchemeLaneModel,
    private val frame: SchemeFrame,
    private val colors: SchemeColors,
    private val glyphs: SchemeGlyphs,
    private val labels: SchemeGlyphs,
) {
    private val count = lane.grid.cellCount
    private val letters = SchemeSampleHost.length
    private val displayWidth = with(scope) { DisplayLetter.toPx() * letters + DisplayPad.toPx() * 2 }
    private val cell =
        with(scope) { minOf(MaxCell.toPx(), (size.width - displayWidth - DpiMargin.toPx() * 2) / (2 * count)) }
    private val cellHeight = with(scope) { CellHeight.toPx() }
    private val right = scope.size.width - cell * count
    private val dpi = scope.size.width / 2
    private val stripTop = with(scope) { HeaderHeight.toPx() }
    private val firstRow = with(scope) { stripTop + cellHeight + StripGap.toPx() + RowLabelHeight.toPx() }
    private val pitch = with(scope) { RowLabelHeight.toPx() + cellHeight + RowGap.toPx() }
    private val margin = with(scope) { DpiMargin.toPx() }
    private val hair = with(scope) { HairStroke.toPx() }
    private val places = lane.rows.mapIndexed { index, row -> place(row, index) }

    private fun rowTop(index: Int) = firstRow + index * pitch

    private fun place(
        row: SchemeRow,
        index: Int,
    ): RowPlace {
        val motion = frame.motion(index)
        val items = row.lead.size + (row.to - row.from) + row.trail.size
        val deficit = (row.lead.size - row.from).coerceAtLeast(0)
        val first = row.from - row.lead.size
        val start = (first + deficit * motion.drop) * cell
        val end =
            when {
                frame.engaging || row.piece.endsBeforeDpi -> dpi - margin - items * cell
                !row.piece.arrives -> dpi + margin
                else -> right + minOf(first + deficit, count - items) * cell
            }
        return RowPlace(
            x = lerp(start, end, motion.fly),
            y = if (row.staged) lerp(stripTop, rowTop(index), motion.drop) else rowTop(index),
            alpha = (if (row.staged) 1f else motion.drop) * motion.alpha,
            motion = motion,
            items = items,
        )
    }

    fun draw() {
        drawHeader()
        drawStrips()
        drawDisplay()
        lane.rows.forEachIndexed { index, row -> drawRow(row, index, places[index]) }
    }

    private fun drawHeader() =
        with(scope) {
            val stroke = Stroke(hair)
            val phone = Size(SchemePhoneWidth.toPx(), SchemePhoneHeight.toPx())
            drawRoundRect(colors.muted, Offset(hair, hair), phone, CornerRadius(hair * 2), style = stroke)
            lane.tag?.let { tag ->
                drawText(labels.of(tag), colors.muted, Offset(phone.width + IconGap.toPx(), 0f))
            }
            val radius = SchemeGlobeRadius.toPx()
            val center = Offset(size.width - radius - hair, radius + hair)
            val inner = SchemeGlobeInner.toPx()
            drawCircle(colors.muted, radius, center, style = stroke)
            val oval = Size(inner * 2, radius * 2)
            drawOval(colors.muted, Offset(center.x - inner, center.y - radius), oval, style = stroke)
            drawLine(colors.muted, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), hair)
            val dpiText = labels.of(DpiLabel)
            drawText(dpiText, colors.muted, Offset(dpi - dpiText.size.width / 2f, 0f))
            drawLine(
                color = colors.muted,
                start = Offset(dpi, stripTop + cellHeight + TickOver.toPx()),
                end = Offset(dpi, size.height),
                strokeWidth = hair,
                pathEffect = dash(),
            )
        }

    private fun dash() = with(scope) { PathEffect.dashPathEffect(floatArrayOf(DashOn.toPx(), DashOff.toPx())) }

    /** The packet on the device at the left, and the packet the server has at the right. */
    private fun drawStrips() {
        val sent = BooleanArray(count)
        val got = FloatArray(count)
        lane.rows.forEachIndexed { index, row ->
            val motion = places[index].motion
            for (cellIndex in row.from until row.to) {
                if (row.staged && motion.drop > 0f) sent[cellIndex] = true
                if (row.piece.arrives) got[cellIndex] = maxOf(got[cellIndex], motion.landed)
            }
        }
        val confirmed = if (frame.unconfirmed) UnconfirmedAlpha else 1f
        for (cellIndex in 0 until count) {
            val tone = if (lane.grid.isHost(cellIndex)) colors.host else colors.plain
            val letter = lane.grid.letter(cellIndex, decoy = false)
            drawCell(cellIndex * cell, stripTop, colors.plain, null, 1f, fill = 0f, dashed = true)
            if (!sent[cellIndex]) drawCell(cellIndex * cell, stripTop, tone, letter, 1f)
            drawCell(right + cellIndex * cell, stripTop, colors.plain, null, 1f, fill = 0f)
            val arrived = got[cellIndex] * confirmed
            if (arrived > 0f) drawCell(right + cellIndex * cell, stripTop, tone, letter, arrived)
        }
        drawTicks()
    }

    private fun drawTicks() =
        with(scope) {
            val over = TickOver.toPx()
            val cuts =
                lane.rows
                    .filter { it.staged }
                    .flatMap { listOf(it.from, it.to) }
                    .filter { it in 1 until count }
            cuts.distinct().forEach { border ->
                val x = border * cell
                val bottom = stripTop + cellHeight + over
                drawLine(colors.decoy.stroke, Offset(x, stripTop - over), Offset(x, bottom), MarkStroke.toPx())
            }
            lane.rows.flatMap { it.records }.distinct().forEach { border ->
                val x = border * cell
                drawLine(
                    color = colors.content,
                    start = Offset(x, stripTop - over),
                    end = Offset(x, stripTop + cellHeight + over),
                    strokeWidth = hair,
                    pathEffect = dash(),
                )
            }
        }

    /** The name the filter looks for. A letter lights while a row that the filter reads carries it past the line. */
    private fun drawDisplay() =
        with(scope) {
            val lit = arrayOfNulls<SchemeTone>(letters)
            val shown = SchemeSampleHost.toCharArray()
            lane.rows.forEachIndexed { index, row ->
                val place = places[index]
                val reading = row.inspected && !frame.engaging && place.motion.fly > 0f && place.motion.fly < 1f
                if (!reading) return@forEachIndexed
                for (cellIndex in maxOf(row.from, lane.grid.hostStart) until minOf(row.to, lane.grid.hostEnd)) {
                    val x = place.x + (row.lead.size + cellIndex - row.from + CellCenter) * cell
                    if (x <= dpi) continue
                    val letter = cellIndex - lane.grid.hostStart
                    lit[letter] = if (row.decoy) colors.decoy else colors.host
                    if (row.decoy) shown[letter] = SchemeDecoyHost[letter]
                }
            }
            val top = stripTop - hair
            drawRoundRect(
                color = colors.plain.fill,
                topLeft = Offset(dpi - displayWidth / 2, top),
                size = Size(displayWidth, cellHeight + hair * 2),
                cornerRadius = CornerRadius(CellRadius.toPx()),
            )
            val step = DisplayLetter.toPx()
            shown.forEachIndexed { index, char ->
                val glyph = glyphs.of(char.toString())
                val x = dpi - step * letters / 2 + step * index + (step - glyph.size.width) / 2
                val y = top + (cellHeight - glyph.size.height) / 2 + hair
                drawText(glyph, lit[index]?.stroke ?: colors.muted, Offset(x, y))
            }
        }

    private fun drawRow(
        row: SchemeRow,
        index: Int,
        place: RowPlace,
    ) = with(scope) {
        val top = rowTop(index)
        val middle = top + cellHeight / 2
        val tone = if (row.decoy) colors.decoy else colors.host
        val dots = PathEffect.dashPathEffect(floatArrayOf(hair, hair * DotSpace))
        drawLine(colors.faint, Offset(0f, middle), Offset(size.width, middle), hair, pathEffect = dots)
        drawText(lane.names[index], colors.content, Offset(0f, top - RowLabelHeight.toPx()))
        val done = place.motion.landed
        if (done > 0f) {
            val result = lane.results[index]
            drawText(
                textLayoutResult = result,
                color = if (row.piece.arrives) colors.host.text else colors.muted,
                topLeft = Offset(size.width - result.size.width, top - RowLabelHeight.toPx()),
                alpha = done,
            )
        }
        val startX = (row.from - row.lead.size).coerceAtLeast(0) * cell
        if (place.motion.fly > 0f && place.x > startX) {
            val trail = place.alpha * SpentAlpha
            drawLine(tone.stroke, Offset(startX, middle), Offset(place.x, middle), MarkStroke.toPx(), alpha = trail)
        }
        val crossed = !frame.engaging && !row.piece.endsBeforeDpi && place.x + place.items * cell > dpi
        if (crossed) drawDiamond(middle, if (row.inspected) tone.stroke else colors.muted, row.inspected)
        drawItems(row, place)
        val after = place.x + place.items * cell + CrossGap.toPx()
        if (!row.piece.arrives && done > 0f) drawCross(after, middle, done)
        if (row.piece.extraCopies > 0 && place.alpha > 0f) {
            val copies = labels.of(CopiesPrefix + row.piece.extraCopies)
            val x = after + if (row.piece.arrives) 0f else CrossSize.toPx() + CrossGap.toPx()
            drawText(copies, colors.muted, Offset(x, middle - copies.size.height / 2f), alpha = place.alpha)
        }
    }

    private fun drawItems(
        row: SchemeRow,
        place: RowPlace,
    ) {
        // A row that does not arrive loses its fill at its end, so it reads as spent.
        val fill = if (row.piece.arrives || frame.engaging) 1f else 1f - place.motion.landed
        val extra = place.alpha * (if (row.staged) place.motion.drop else 1f)
        var x = place.x
        row.lead.forEach { mark ->
            drawCell(x, place.y, markTone(mark), markLetter(mark), extra, fill)
            x += cell
        }
        for (cellIndex in row.from until row.to) {
            val tone =
                when {
                    row.decoy -> colors.decoy
                    lane.grid.isHost(cellIndex) -> colors.host
                    else -> colors.plain
                }
            drawCell(x, place.y, tone, lane.grid.letter(cellIndex, row.decoy), place.alpha, fill)
            if (cellIndex in row.records) drawRecord(x, place.y, place.alpha)
            x += cell
        }
        row.trail.forEach { mark ->
            drawCell(x, place.y, markTone(mark), markLetter(mark), extra, fill)
            x += cell
        }
    }

    private fun markTone(mark: SchemeMark) =
        when (mark) {
            SchemeMark.Reset -> colors.reset
            SchemeMark.Prefix -> colors.decoy
            SchemeMark.Syn, SchemeMark.Fragment, SchemeMark.Urgent -> colors.mark
        }

    private fun markLetter(mark: SchemeMark): Char? =
        when (mark) {
            SchemeMark.Reset -> 'R'
            SchemeMark.Syn -> 'S'
            SchemeMark.Fragment -> 'F'
            SchemeMark.Urgent -> '!'
            SchemeMark.Prefix -> null
        }

    private fun drawRecord(
        x: Float,
        y: Float,
        alpha: Float,
    ) = with(scope) {
        val over = TickOver.toPx()
        val bottom = y + cellHeight + over
        drawLine(colors.content, Offset(x, y - over), Offset(x, bottom), hair, pathEffect = dash(), alpha = alpha)
    }

    private fun drawCell(
        x: Float,
        y: Float,
        tone: SchemeTone,
        letter: Char?,
        alpha: Float,
        fill: Float = 1f,
        dashed: Boolean = false,
    ) = with(scope) {
        if (alpha <= 0f) return@with
        val inset = CellInset.toPx()
        val topLeft = Offset(x + inset, y + inset)
        val box = Size(cell - inset * 2 - hair, cellHeight - inset * 2)
        val radius = CornerRadius(CellRadius.toPx())
        if (fill > 0f) drawRoundRect(tone.fill, topLeft, box, radius, alpha = alpha * fill)
        val outline = Stroke(hair, pathEffect = if (dashed || fill < 1f) dash() else null)
        drawRoundRect(tone.stroke, topLeft, box, radius, style = outline, alpha = alpha)
        if (letter != null) {
            val glyph = glyphs.of(letter.toString())
            val place = Offset(x + (cell - hair - glyph.size.width) / 2, y + (cellHeight - glyph.size.height) / 2)
            drawText(glyph, tone.text, place, alpha = alpha)
        } else if (tone === colors.plain && !dashed && fill > 0f) {
            val dot = DotSize.toPx()
            val gap = DotGap.toPx()
            val left = x + (cell - hair - gap - dot) / 2
            val topDot = y + (cellHeight - dot) / 2
            drawRect(colors.muted, Offset(left, topDot), Size(dot, dot), alpha = alpha * fill)
            drawRect(colors.muted, Offset(left + gap, topDot), Size(dot, dot), alpha = alpha * fill)
        }
    }

    private fun drawDiamond(
        y: Float,
        color: Color,
        filled: Boolean,
    ) = with(scope) {
        val half = DiamondSize.toPx()
        val path =
            Path().apply {
                moveTo(dpi, y - half)
                lineTo(dpi + half, y)
                lineTo(dpi, y + half)
                lineTo(dpi - half, y)
                close()
            }
        drawPath(path, colors.surface)
        if (filled) drawPath(path, color, alpha = DiamondFill)
        drawPath(path, color, style = Stroke(MarkStroke.toPx()))
    }

    private fun drawCross(
        x: Float,
        y: Float,
        alpha: Float,
    ) = with(scope) {
        val arm = CrossSize.toPx()
        val width = MarkStroke.toPx()
        drawLine(colors.muted, Offset(x, y - arm / 2), Offset(x + arm, y + arm / 2), width, alpha = alpha)
        drawLine(colors.muted, Offset(x, y + arm / 2), Offset(x + arm, y - arm / 2), width, alpha = alpha)
    }
}
