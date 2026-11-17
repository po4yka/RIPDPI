package com.poyka.ripdpi.ui.screens.status

import kotlin.math.ceil
import kotlin.math.floor

/** The name the scheme draws in the packet. `.test` is reserved, so it is never a real host. */
internal const val SchemeSampleHost = "site.test"

/** The name a decoy carries in the scheme. It has the length of [SchemeSampleHost]. */
internal const val SchemeDecoyHost = "good.test"

private const val RecordBorder = '|'
private const val ResetText = "RST"

/**
 * The cells of one lane: one per letter of the sample host, and one per span that the pieces cut outside the
 * host. A schematic position becomes a border between two cells.
 */
internal class SchemeGrid(
    lane: PassageLane,
) {
    // A record border does not make a cell: outside the host it has no letter to divide.
    private val marks = lane.pieces.flatMap { listOf(it.start, it.end) }.toSet()
    private val headCuts = marks.filter { it in 1 until PassageHostStart }.sorted()
    private val tailCuts = marks.filter { it in PassageHostEnd + 1 until PassagePayloadEnd }.sorted()
    val hostStart = headCuts.size + 1
    val hostEnd = hostStart + SchemeSampleHost.length
    val cellCount = hostEnd + tailCuts.size + 1

    fun cellAt(position: Int): Int =
        when {
            position <= 0 -> 0
            position < PassageHostStart -> headCuts.count { it <= position }
            position <= PassageHostEnd -> hostStart + hostCell(position)
            position >= PassagePayloadEnd -> cellCount
            else -> hostEnd + tailCuts.count { it <= position }
        }

    fun isHost(cell: Int): Boolean = cell in hostStart until hostEnd

    fun letter(
        cell: Int,
        decoy: Boolean,
    ): Char? = (if (decoy) SchemeDecoyHost else SchemeSampleHost).getOrNull(cell - hostStart)

    // A position inside the host never lands on the host border, so a cut there always divides the name.
    private fun hostCell(position: Int): Int {
        val letters = SchemeSampleHost.length
        val span = PassageHostEnd - PassageHostStart
        val offset = (position - PassageHostStart).coerceIn(0, span)
        val exact = offset * letters / span.toFloat()
        val inside = (if (offset * 2 < span) floor(exact) else ceil(exact)).toInt().coerceIn(1, letters - 1)
        return when (offset) {
            0 -> 0
            span -> letters
            else -> inside
        }
    }
}

/** A cell that a piece carries in addition to its payload. */
internal enum class SchemeMark { Syn, Fragment, Prefix, Urgent, Reset }

/**
 * One packet of the plan as a row of the scheme. [from] and [to] are cell borders. A [staged] row takes its
 * cells from the packet on the device; another row appears in its own line.
 */
internal class SchemeRow(
    val piece: PassagePiece,
    val from: Int,
    val to: Int,
    val lead: List<SchemeMark>,
    val trail: List<SchemeMark>,
    val records: List<Int>,
    val staged: Boolean,
    val inspected: Boolean,
) {
    val decoy: Boolean get() = piece.kind == PassagePieceKind.Decoy

    /** A copy that the filter reads in place of the flow, so it stops its inspection after this row. */
    val endsInspection: Boolean
        get() = !piece.endsBeforeDpi && (decoy || piece.kind == PassagePieceKind.FakeReset)
}

private val PassagePiece.takesDeviceBytes: Boolean
    get() =
        kind == PassagePieceKind.Real ||
            kind == PassagePieceKind.ShortTtl ||
            kind == PassagePieceKind.IpFragment ||
            kind == PassagePieceKind.Retransmit

private fun PassagePiece.leadMarks(): List<SchemeMark> =
    buildList {
        if (kind == PassagePieceKind.FakeReset) add(SchemeMark.Reset)
        if (inSyn) add(SchemeMark.Syn)
        if (kind == PassagePieceKind.IpFragment) add(SchemeMark.Fragment)
        if (hasFakePrefix) {
            add(SchemeMark.Prefix)
            add(SchemeMark.Prefix)
        }
    }

internal fun schemeRows(
    lane: PassageLane,
    grid: SchemeGrid,
): List<SchemeRow> {
    val taken = BooleanArray(grid.cellCount)
    var inspected = true
    return lane.pieces.map { piece ->
        val from = grid.cellAt(piece.start)
        val to = grid.cellAt(piece.end)
        val staged = piece.takesDeviceBytes && (from until to).none { taken[it] }
        if (staged) taken.fill(true, from, to)
        val row =
            SchemeRow(
                piece = piece,
                from = from,
                to = to,
                lead = piece.leadMarks(),
                trail = if (piece.hasUrgentByte) listOf(SchemeMark.Urgent) else emptyList(),
                records = lane.recordBoundaries.map(grid::cellAt).filter { it in from + 1 until to },
                staged = staged,
                inspected = inspected && !piece.endsBeforeDpi,
            )
        if (row.endsInspection) inspected = false
        row
    }
}

internal class SchemeChunk(
    val text: String,
    val decoy: Boolean,
)

internal enum class SchemeVerdict { NoMatch, StopsLooking, WholeName }

/** What the filter reads from the rows it inspects, in send order. */
internal class SchemeReadout(
    val chunks: List<SchemeChunk>,
    val verdict: SchemeVerdict,
)

private fun SchemeRow.readText(grid: SchemeGrid): String =
    if (piece.kind == PassagePieceKind.FakeReset) {
        ResetText
    } else {
        buildString {
            for (cell in from until to) {
                val letter = grid.letter(cell, decoy) ?: continue
                if (cell in records && isNotEmpty()) append(RecordBorder)
                append(letter)
            }
        }
    }

internal fun schemeReadout(lane: PassageLane): SchemeReadout {
    val grid = SchemeGrid(lane)
    val rows = schemeRows(lane, grid).filter { it.inspected }
    val chunks =
        rows
            .map { SchemeChunk(it.readText(grid), it.decoy || it.endsInspection) }
            .filter { it.text.isNotEmpty() }
    val verdict =
        when {
            rows.any { it.endsInspection } -> SchemeVerdict.StopsLooking
            chunks.any { !it.decoy && it.text == SchemeSampleHost } -> SchemeVerdict.WholeName
            else -> SchemeVerdict.NoMatch
        }
    return SchemeReadout(chunks, verdict)
}
