package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.adaptiveOffsetPreset
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.proto.AppSettings
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
    val endsBeforeDpi: Boolean = false,
    val quicStep: UdpChainStepKind? = null,
    val copyIndex: Int = 0,
    val extraCopies: Int = 0,
)

@Immutable
data class PassageLane(
    val pieces: ImmutableList<PassagePiece>,
    val recordBoundaries: ImmutableList<Int>,
)

/** Reaches the server. Other pieces end on the path, or never carry payload. */
val PassagePiece.arrives: Boolean
    get() =
        kind == PassagePieceKind.Real ||
            kind == PassagePieceKind.Retransmit ||
            kind == PassagePieceKind.IpFragment

/**
 * What the configured chain does to a packet, in the order of the native planner. It describes the
 * settings, not captured traffic: the engine can still fall back at runtime, and the kernel decides
 * when a resend really happens. No live signal reports a fallback to the app yet.
 *
 * shortcut: custom `altorder` and `seqmode` values on fake steps draw the default order, model them if
 * presets start to use them.
 */
@Immutable
data class StrategyPassageScene(
    val families: ImmutableList<PassageFamily>,
    val stepCount: Int,
    val tcp: PassageLane?,
    val udp: PassageLane?,
)

internal const val PassageStartDelayMillis = 120
internal const val PassageStaggerMillis = 220
internal const val PassageFlightMillis = 480
internal const val PassageSettleMillis = 260
internal const val PassagePayloadEnd = 100
internal const val PassageHostStart = 30
internal const val PassageHostEnd = 48
private const val MarkerDeltaStep = 3
private const val NumericMarkerBase = 4
private const val NumericMarkerScale = 4
private const val NumericMarkerMax = PassageHostStart - NumericMarkerBase
private const val NegativeMarkerBase = PassagePayloadEnd - NumericMarkerBase
private const val NegativeMarkerMin = PassageHostEnd + NumericMarkerBase
private const val QuicFragmentCut = 39
private const val MaxDrawnCopies = 4
private const val MinRealisticInitialCopies = 2
private const val EchMarkerName = "echext"
private const val HostFakeDecoys = 2

private val MarkerPattern = Regex("^([a-z]+)?([+-]?\\d+)?$")

// Follows the ClientHello layout, so the markers of one chain keep their real order.
private val NamedMarkerPositions =
    mapOf(
        "method" to 2,
        "extlen" to 14,
        "echext" to 20,
        "sniext" to 24,
        "host" to PassageHostStart,
        "sld" to 34,
        "midsld" to QuicFragmentCut,
        "endsld" to 44,
        "endhost" to PassageHostEnd,
    )

// shortcut: `:repeats:skip` is dropped from a marker, draw every repeat if a preset starts to use them.
private fun schematicPosition(marker: String): Int {
    val expression = marker.trim().substringBefore(':').lowercase()
    val preset = adaptiveOffsetPreset(expression)
    if (preset != null) {
        return NamedMarkerPositions[preset] ?: PassageHostStart
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
    }.coerceIn(0, PassagePayloadEnd)
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

// A hostfake range without the whole host plans a split, so it must not be named a mask.
private fun TcpChainStepModel.family(
    rawPackets: Boolean,
    hostMasked: Boolean,
): PassageFamily =
    when {
        kind == TcpChainStepKind.HostFake && !hostMasked -> PassageFamily.Cut
        !rawPackets && kind in LoweredWithoutRawPackets -> PassageFamily.Cut
        else -> kind.family
    }

// The engine refuses a raw send before any packet leaves and writes these as plain cuts.
private val LoweredWithoutRawPackets = setOf(TcpChainStepKind.SeqOverlap, TcpChainStepKind.FakeRst)

// The scene shows the first packet of a flow, and the engine skips a filter that excludes round 1.
// shortcut: payload, stream, and TCP state conditions are not read, model them if a preset starts to use them.
private val ActivationFilterModel.skipsFirstRound: Boolean
    get() = (round.start ?: 1L) > 1L || (round.end ?: 1L) < 1L

// plan_tcp fails on a cut behind the previous one and sends the whole payload unsplit. Seqovl moves
// the cursor to the end of the payload, so any step after it is behind.
private fun cutsRunBackward(steps: List<TcpChainStepModel>): Boolean {
    val sendSteps = steps.filter { !it.kind.isTlsPrelude && it.kind != TcpChainStepKind.MultiDisorder }
    return sendSteps.dropLast(1).any { it.kind == TcpChainStepKind.SeqOverlap } ||
        sendSteps
            .map { normalizeTcpChainStepModel(it).marker }
            .zipWithNext(::markerRunsBackward)
            .any { it }
}

// shortcut: a numeric or echext marker has no known real order against a named one, so this pair counts as forward.
private fun markerRunsBackward(
    first: String,
    second: String,
): Boolean {
    val firstParts = markerParts(first)
    val secondParts = markerParts(second)
    if (firstParts == null || secondParts == null) return false
    val (firstName, firstDelta) = firstParts
    val (secondName, secondDelta) = secondParts
    val firstBase = NamedMarkerPositions[firstName].takeUnless { firstName == EchMarkerName }
    val secondBase = NamedMarkerPositions[secondName].takeUnless { secondName == EchMarkerName }
    return when {
        // A negative numeric marker counts from the payload end, so it has no known order against a positive one.
        firstName.isEmpty() && secondName.isEmpty() -> firstDelta >= 0 == secondDelta >= 0 && secondDelta < firstDelta

        firstName == secondName -> secondDelta < firstDelta

        firstBase == null || secondBase == null -> false

        else -> secondBase < firstBase
    }
}

private fun markerParts(marker: String): Pair<String, Int>? =
    MarkerPattern.matchEntire(marker.trim().substringBefore(':').lowercase())?.groupValues?.let { groups ->
        groups[1].removePrefix("abs") to (groups[2].toIntOrNull() ?: 0)
    }

// validate_ip_fragmentation_capabilities refuses to start the proxy for these chains.
private fun blockedWithoutRawPackets(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): Boolean =
    tcpSteps.any { it.kind == TcpChainStepKind.MultiDisorder || it.kind == TcpChainStepKind.IpFrag2 } ||
        udpSteps.any { it.kind == UdpChainStepKind.IpFrag2Udp }

/** Mirrors the planner's cursor: each step cuts what is left, and lost bytes are resent after the plan. */
private class TcpPieceWriter(
    private val rawPackets: Boolean,
) {
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
                lose(PassagePieceKind.Decoy, position, PassagePayloadEnd)
            }

            TcpChainStepKind.FakeDisorder -> {
                // send_first_disorder_segment uses TTL 1, so this decoy ends at the first hop. TCP then
                // resends from the oldest unacknowledged byte, so both real parts land in order.
                pieces += PassagePiece(PassagePieceKind.Decoy, start, position, endsBeforeDpi = true)
                lose(PassagePieceKind.Decoy, position, PassagePayloadEnd)
                resends.add(resends.size - 1, PassagePiece(PassagePieceKind.Retransmit, start, position))
            }

            TcpChainStepKind.HostFake -> {
                writeHostFake(start, position, step)
            }

            TcpChainStepKind.FakeRst -> {
                if (rawPackets) pieces += PassagePiece(PassagePieceKind.FakeReset, start, start)
                pieces += real(start, position)
            }

            TcpChainStepKind.SeqOverlap -> {
                pieces += real(start, position).copy(hasFakePrefix = rawPackets)
                pieces += real(position, PassagePayloadEnd)
            }

            TcpChainStepKind.IpFrag2 -> {
                pieces += PassagePiece(PassagePieceKind.IpFragment, start, position)
                pieces += PassagePiece(PassagePieceKind.IpFragment, position, PassagePayloadEnd)
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
                -> PassagePayloadEnd

                else -> position
            }
    }

    fun position(step: TcpChainStepModel): Int? =
        schematicPosition(normalizeTcpChainStepModel(step).marker).takeIf { it > cursor && it <= PassagePayloadEnd }

    fun finish(): List<PassagePiece> {
        if (cursor < PassagePayloadEnd) {
            pieces += real(cursor, PassagePayloadEnd)
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
        if (start > PassageHostStart || end < PassageHostEnd) {
            pieces += real(start, end)
            return
        }
        if (start < PassageHostStart) {
            pieces += real(start, PassageHostStart)
        }
        if (rawPackets) pieces += PassagePiece(PassagePieceKind.Decoy, PassageHostStart, PassageHostEnd)
        val middle = step.midhostMarker.takeIf { it.isNotBlank() }?.let(::schematicPosition)
        if (middle != null && middle > PassageHostStart && middle < PassageHostEnd) {
            pieces += real(PassageHostStart, middle)
            pieces += real(middle, PassageHostEnd)
        } else {
            pieces += real(PassageHostStart, PassageHostEnd)
        }
        if (rawPackets) pieces += PassagePiece(PassagePieceKind.Decoy, PassageHostStart, PassageHostEnd)
        if (PassageHostEnd < end) {
            pieces += real(PassageHostEnd, end)
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
                List(count) { index -> first + (PassagePayloadEnd - first) * index / count }
            }
        }.distinct()
        .sorted()

private fun compileTcpPieces(
    sendSteps: List<TcpChainStepModel>,
    rawPackets: Boolean,
): List<PassagePiece> {
    // send_multi_disorder_tcp emits every segment at the normal TTL, last segment first.
    if (sendSteps.any { it.kind == TcpChainStepKind.MultiDisorder }) {
        val cuts = sendSteps.map { schematicPosition(normalizeTcpChainStepModel(it).marker) }
        return (listOf(0) + cuts + PassagePayloadEnd)
            .distinct()
            .sorted()
            .zipWithNext { start, end -> PassagePiece(PassagePieceKind.Real, start, end) }
            .reversed()
    }
    val writer = TcpPieceWriter(rawPackets)
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
            end = PassagePayloadEnd,
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
                    PassagePiece(PassagePieceKind.IpFragment, QuicFragmentCut, PassagePayloadEnd),
                )
            } else {
                udpCopies(step)
            }
        }
    return if (fragmented) pieces else pieces + PassagePiece(PassagePieceKind.Real, 0, PassagePayloadEnd)
}

/** Builds the panel's scene from the configured chain. Chain order is the order the panel names. */
fun compileStrategyPassageScene(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
    rawPackets: Boolean = true,
): StrategyPassageScene {
    val activeTcp = tcpSteps.filterNot { it.activationFilter.skipsFirstRound }
    // plan_udp skips a copy step without copies. ipfrag2_udp has no count.
    val activeUdp =
        udpSteps.filter {
            !it.activationFilter.skipsFirstRound && (it.kind == UdpChainStepKind.IpFrag2Udp || it.count > 0)
        }
    val (preludeSteps, sendSteps) = activeTcp.partition { it.kind.isTlsPrelude }
    val tcpPieces = compileTcpPieces(sendSteps, rawPackets)
    val tcp =
        if (tcpSteps.isEmpty() && udpSteps.isNotEmpty()) {
            null
        } else {
            PassageLane(
                pieces = tcpPieces.toImmutableList(),
                recordBoundaries = recordBoundaries(preludeSteps).toImmutableList(),
            )
        }
    val udp =
        udpSteps.takeIf { it.isNotEmpty() }?.let {
            PassageLane(pieces = compileUdpPieces(activeUdp).toImmutableList(), recordBoundaries = persistentListOf())
        }
    // writeHostFake draws two decoys over the host. Another fake step can draw only one there.
    val hostMasked =
        tcpPieces.count {
            it.kind == PassagePieceKind.Decoy && it.start == PassageHostStart && it.end == PassageHostEnd
        } >= HostFakeDecoys
    val families =
        (activeTcp.map { it.family(rawPackets, hostMasked) } + activeUdp.map { it.kind.family }).distinct()
    return StrategyPassageScene(
        families = families.toImmutableList(),
        stepCount = activeTcp.size + activeUdp.size,
        tcp = tcp,
        udp = udp,
    )
}

/**
 * Null where the chain does not describe the traffic: a relay carries it, custom arguments or rules
 * replace the chain, the chain has no TCP protocol to act on, the chain needs raw packets that this
 * device cannot send, the group does not act on the first packet, or the plan fails on backward cuts.
 */
fun strategyPassageFor(settings: AppSettings): StrategyPassageScene? {
    val tcpSteps = settings.effectiveTcpChainSteps()
    // Native code drops the UDP group when UDP desync is off (append_udp_group).
    val udpSteps = if (settings.desyncUdp) settings.effectiveUdpChainSteps() else emptyList()
    // shortcut: root mode can be on while the helper fails to start, read the live helper state when the UI has it.
    val rawPackets = settings.rootModeEnabled
    val replaced =
        settings.toRelaySettingsModel().enabled ||
            settings.enableCmdSettings ||
            settings.strategyChainYaml.isNotBlank()
    val hidden =
        replaced ||
            (!settings.desyncHttp && !settings.desyncHttps) ||
            (!rawPackets && blockedWithoutRawPackets(tcpSteps, udpSteps)) ||
            settings.effectiveGroupActivationFilter().skipsFirstRound ||
            cutsRunBackward(tcpSteps.filterNot { it.activationFilter.skipsFirstRound })
    return if (hidden) null else compileStrategyPassageScene(tcpSteps, udpSteps, rawPackets)
}

/** When a piece leaves the device. The order follows the plan; the spacing is for the reader, not the wire. */
internal fun passageFlightStartMillis(pieceIndex: Int): Int =
    PassageStartDelayMillis + pieceIndex * PassageStaggerMillis

/** When every piece has landed and the packet starts to settle. */
internal val PassageLane.settleStartMillis: Int
    get() = passageFlightStartMillis((pieces.size - 1).coerceAtLeast(0)) + PassageFlightMillis

internal val StrategyPassageScene.durationMillis: Int
    get() = listOfNotNull(tcp, udp).maxOf { it.settleStartMillis } + PassageSettleMillis

/** The pieces that have reached their place in the reassembled packet at this moment. */
internal fun PassageLane.landedPieces(timeMillis: Float): List<PassagePiece> =
    pieces.filterIndexed { index, piece ->
        piece.arrives && timeMillis >= passageFlightStartMillis(index) + PassageFlightMillis
    }
