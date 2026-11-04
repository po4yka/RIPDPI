package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.adaptiveOffsetPreset
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** One name per group of engine steps that the strategy panel draws the same way. */
enum class PassageFamily {
    Cut,
    LaggingPart,
    Decoy,
    HostMask,
    TlsRecords,
    UrgentByte,
    IpFragments,
    Overlap,
    FakeReset,
    QuicDecoyBurst,
    QuicSplitCopy,
    QuicVersion,
}

enum class PassagePieceKind {
    Real,
    Decoy,
    ShortTtl,
    Retransmit,
    IpFragment,
    FakeReset,
}

/**
 * One packet in send order. [start] and [end] are schematic positions from 0 to 100, never real
 * offsets, so a scene cannot reveal a host or a payload size.
 */
@Immutable
data class PassagePiece(
    val kind: PassagePieceKind,
    val start: Int,
    val end: Int,
    val hasUrgentByte: Boolean = false,
    val inSyn: Boolean = false,
    val hasFakePrefix: Boolean = false,
    val quicStep: UdpChainStepKind? = null,
    val copyIndex: Int = 0,
    val extraCopies: Int = 0,
)

@Immutable
data class PassageLane(
    val pieces: ImmutableList<PassagePiece>,
    val recordBoundaries: ImmutableList<Int>,
)

/**
 * What the configured chain does to a packet, in the order of the native planner. It describes the
 * settings, not captured traffic: the engine can still fall back at runtime, and the kernel decides
 * when a resend really happens.
 */
@Immutable
data class StrategyPassageScene(
    val families: ImmutableList<PassageFamily>,
    val tcp: PassageLane?,
    val udp: PassageLane?,
)

private const val PayloadEnd = 100
private const val HostStart = 30
private const val HostEnd = 48
private const val MarkerDeltaStep = 3
private const val NumericMarkerBase = 4
private const val NumericMarkerScale = 4
private const val NumericMarkerMax = HostStart - NumericMarkerBase
private const val NegativeMarkerBase = PayloadEnd - NumericMarkerBase
private const val NegativeMarkerMin = HostEnd + NumericMarkerBase
private const val QuicFragmentCut = 39
private const val MaxDrawnCopies = 4
private const val MinRealisticInitialCopies = 2

private val MarkerPattern = Regex("^([a-z]+)?([+-]?\\d+)?$")

// Follows the ClientHello layout, so the markers of one chain keep their real order.
private val NamedMarkerPositions =
    mapOf(
        "method" to 2,
        "extlen" to 14,
        "echext" to 20,
        "sniext" to 24,
        "host" to HostStart,
        "sld" to 34,
        "midsld" to QuicFragmentCut,
        "endsld" to 44,
        "endhost" to HostEnd,
    )

// shortcut: `:repeats:skip` is dropped from a marker, draw every repeat if a preset starts to use them.
private fun schematicPosition(marker: String): Int {
    val expression = marker.trim().substringBefore(':').lowercase()
    val preset = adaptiveOffsetPreset(expression)
    if (preset != null) {
        return NamedMarkerPositions[preset] ?: HostStart
    }
    val groups = MarkerPattern.matchEntire(expression)?.groupValues
    val name = groups?.get(1).orEmpty()
    val delta = groups?.get(2)?.toIntOrNull() ?: 0
    val base = NamedMarkerPositions[name]
    return when {
        base != null -> base + Integer.signum(delta) * MarkerDeltaStep
        delta > 0 -> (NumericMarkerBase + delta / NumericMarkerScale).coerceAtMost(NumericMarkerMax)
        delta < 0 -> (NegativeMarkerBase + delta / NumericMarkerScale).coerceAtLeast(NegativeMarkerMin)
        else -> 0
    }.coerceIn(0, PayloadEnd)
}

private val TcpChainStepKind.family: PassageFamily
    get() =
        when (this) {
            TcpChainStepKind.Split, TcpChainStepKind.SynData -> PassageFamily.Cut
            TcpChainStepKind.Disorder, TcpChainStepKind.MultiDisorder -> PassageFamily.LaggingPart
            TcpChainStepKind.Fake, TcpChainStepKind.FakeSplit, TcpChainStepKind.FakeDisorder -> PassageFamily.Decoy
            TcpChainStepKind.HostFake -> PassageFamily.HostMask
            TcpChainStepKind.TlsRec, TcpChainStepKind.TlsRandRec -> PassageFamily.TlsRecords
            TcpChainStepKind.Oob, TcpChainStepKind.Disoob -> PassageFamily.UrgentByte
            TcpChainStepKind.IpFrag2 -> PassageFamily.IpFragments
            TcpChainStepKind.SeqOverlap -> PassageFamily.Overlap
            TcpChainStepKind.FakeRst -> PassageFamily.FakeReset
        }

private val UdpChainStepKind.family: PassageFamily
    get() =
        when (this) {
            UdpChainStepKind.FakeBurst,
            UdpChainStepKind.DummyPrepend,
            UdpChainStepKind.QuicPaddingLadder,
            UdpChainStepKind.QuicCidChurn,
            UdpChainStepKind.QuicPacketNumberGap,
            UdpChainStepKind.QuicMultiInitialRealistic,
            -> PassageFamily.QuicDecoyBurst

            UdpChainStepKind.QuicSniSplit, UdpChainStepKind.QuicCryptoSplit -> PassageFamily.QuicSplitCopy

            UdpChainStepKind.QuicFakeVersion,
            UdpChainStepKind.QuicVersionNegotiationDecoy,
            -> PassageFamily.QuicVersion

            UdpChainStepKind.IpFrag2Udp -> PassageFamily.IpFragments
        }

/** Mirrors the planner's cursor: each step cuts what is left, and lost bytes are resent after the plan. */
private class TcpPieceWriter {
    private val pieces = mutableListOf<PassagePiece>()
    private val resends = mutableListOf<PassagePiece>()
    private var cursor = 0

    fun write(
        step: TcpChainStepModel,
        position: Int,
    ) {
        val start = cursor
        when (step.kind) {
            TcpChainStepKind.Split -> {
                pieces += real(start, position)
            }

            TcpChainStepKind.SynData -> {
                pieces += real(start, position).copy(inSyn = true)
            }

            TcpChainStepKind.Oob -> {
                pieces += real(start, position).copy(hasUrgentByte = true)
            }

            TcpChainStepKind.Disorder -> {
                lose(PassagePieceKind.ShortTtl, start, position)
            }

            TcpChainStepKind.Disoob -> {
                lose(PassagePieceKind.ShortTtl, start, position, hasUrgentByte = true)
            }

            TcpChainStepKind.Fake -> {
                lose(PassagePieceKind.Decoy, start, position)
            }

            TcpChainStepKind.FakeSplit -> {
                lose(PassagePieceKind.Decoy, start, position)
                lose(PassagePieceKind.Decoy, position, PayloadEnd)
            }

            TcpChainStepKind.FakeDisorder -> {
                pieces += PassagePiece(PassagePieceKind.Decoy, start, position)
                pieces += PassagePiece(PassagePieceKind.Decoy, position, PayloadEnd)
                resends += PassagePiece(PassagePieceKind.Retransmit, position, PayloadEnd)
                resends += PassagePiece(PassagePieceKind.Retransmit, start, position)
            }

            TcpChainStepKind.HostFake -> {
                writeHostFake(start, position, step)
            }

            TcpChainStepKind.FakeRst -> {
                pieces += PassagePiece(PassagePieceKind.FakeReset, start, start)
                pieces += real(start, position)
            }

            TcpChainStepKind.SeqOverlap -> {
                pieces += real(start, position).copy(hasFakePrefix = true)
                pieces += real(position, PayloadEnd)
            }

            TcpChainStepKind.IpFrag2 -> {
                pieces += PassagePiece(PassagePieceKind.IpFragment, start, position)
                pieces += PassagePiece(PassagePieceKind.IpFragment, position, PayloadEnd)
            }

            // Prelude steps never reach the writer, and the writer never sees a multidisorder chain.
            TcpChainStepKind.TlsRec, TcpChainStepKind.TlsRandRec, TcpChainStepKind.MultiDisorder -> {}
        }
        cursor =
            when (step.kind) {
                TcpChainStepKind.FakeSplit,
                TcpChainStepKind.FakeDisorder,
                TcpChainStepKind.SeqOverlap,
                TcpChainStepKind.IpFrag2,
                -> PayloadEnd

                else -> position
            }
    }

    fun position(step: TcpChainStepModel): Int? =
        schematicPosition(normalizeTcpChainStepModel(step).marker).takeIf { it > cursor && it <= PayloadEnd }

    fun finish(): List<PassagePiece> {
        if (cursor < PayloadEnd) {
            pieces += real(cursor, PayloadEnd)
        }
        return pieces + resends
    }

    private fun lose(
        kind: PassagePieceKind,
        start: Int,
        end: Int,
        hasUrgentByte: Boolean = false,
    ) {
        pieces += PassagePiece(kind, start, end, hasUrgentByte = hasUrgentByte)
        resends += PassagePiece(PassagePieceKind.Retransmit, start, end)
    }

    private fun writeHostFake(
        start: Int,
        end: Int,
        step: TcpChainStepModel,
    ) {
        // resolve_hostfake_span: without the whole host in this step's range the engine plans a split.
        if (start > HostStart || end < HostEnd) {
            pieces += real(start, end)
            return
        }
        if (start < HostStart) {
            pieces += real(start, HostStart)
        }
        pieces += PassagePiece(PassagePieceKind.Decoy, HostStart, HostEnd)
        val middle = step.midhostMarker.takeIf { it.isNotBlank() }?.let(::schematicPosition)
        if (middle != null && middle > HostStart && middle < HostEnd) {
            pieces += real(HostStart, middle)
            pieces += real(middle, HostEnd)
        } else {
            pieces += real(HostStart, HostEnd)
        }
        pieces += PassagePiece(PassagePieceKind.Decoy, HostStart, HostEnd)
        if (HostEnd < end) {
            pieces += real(HostEnd, end)
        }
    }

    private fun real(
        start: Int,
        end: Int,
    ) = PassagePiece(PassagePieceKind.Real, start, end)
}

private fun recordBoundaries(preludeSteps: List<TcpChainStepModel>): List<Int> =
    preludeSteps
        .flatMap { step ->
            val first = schematicPosition(normalizeTcpChainStepModel(step).marker)
            if (step.kind == TcpChainStepKind.TlsRec) {
                listOf(first)
            } else {
                val count = step.fragmentCount.takeIf { it > 0 } ?: DefaultTlsRandRecFragmentCount
                List(count) { index -> first + (PayloadEnd - first) * index / count }
            }
        }.distinct()
        .sorted()

private fun compileTcpPieces(sendSteps: List<TcpChainStepModel>): List<PassagePiece> {
    // send_multi_disorder_tcp emits every segment at the normal TTL, last segment first.
    if (sendSteps.any { it.kind == TcpChainStepKind.MultiDisorder }) {
        val cuts = sendSteps.map { schematicPosition(normalizeTcpChainStepModel(it).marker) }
        return (listOf(0) + cuts + PayloadEnd)
            .distinct()
            .sorted()
            .zipWithNext { start, end -> PassagePiece(PassagePieceKind.Real, start, end) }
            .reversed()
    }
    val writer = TcpPieceWriter()
    sendSteps.forEach { step -> writer.position(step)?.let { writer.write(step, it) } }
    return writer.finish()
}

private fun udpCopies(step: UdpChainStepModel): List<PassagePiece> {
    val count =
        if (step.kind == UdpChainStepKind.QuicMultiInitialRealistic) {
            step.count.coerceAtLeast(MinRealisticInitialCopies)
        } else {
            step.count
        }
    val drawn = count.coerceAtMost(MaxDrawnCopies)
    return List(drawn.coerceAtLeast(0)) { index ->
        PassagePiece(
            kind = PassagePieceKind.Decoy,
            start = 0,
            end = PayloadEnd,
            quicStep = step.kind,
            copyIndex = index,
            extraCopies = if (index == drawn - 1) count - drawn else 0,
        )
    }
}

private fun compileUdpPieces(steps: List<UdpChainStepModel>): List<PassagePiece> {
    // plan_udp: a fragmented datagram already carries the payload, so no plain write follows it.
    val fragmented = steps.any { it.kind == UdpChainStepKind.IpFrag2Udp }
    val pieces =
        steps.flatMap { step ->
            if (step.kind == UdpChainStepKind.IpFrag2Udp) {
                listOf(
                    PassagePiece(PassagePieceKind.IpFragment, 0, QuicFragmentCut),
                    PassagePiece(PassagePieceKind.IpFragment, QuicFragmentCut, PayloadEnd),
                )
            } else {
                udpCopies(step)
            }
        }
    return if (fragmented) pieces else pieces + PassagePiece(PassagePieceKind.Real, 0, PayloadEnd)
}

/** Builds the panel's scene from the configured chain. Chain order is the order the panel names. */
fun compileStrategyPassageScene(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): StrategyPassageScene {
    val (preludeSteps, sendSteps) = tcpSteps.partition { it.kind.isTlsPrelude }
    val tcp =
        if (tcpSteps.isEmpty() && udpSteps.isNotEmpty()) {
            null
        } else {
            PassageLane(
                pieces = compileTcpPieces(sendSteps).toImmutableList(),
                recordBoundaries = recordBoundaries(preludeSteps).toImmutableList(),
            )
        }
    val udp =
        udpSteps.takeIf { it.isNotEmpty() }?.let { steps ->
            PassageLane(pieces = compileUdpPieces(steps).toImmutableList(), recordBoundaries = persistentListOf())
        }
    val families = (tcpSteps.map { it.kind.family } + udpSteps.map { it.kind.family }).distinct()
    return StrategyPassageScene(families = families.toImmutableList(), tcp = tcp, udp = udp)
}
