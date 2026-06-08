package com.poyka.ripdpi.ui.screens.diagnostics

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.abs

enum class PcapDesyncActionKind {
    Split,
    FakePacket,
    SeqOverlap,
    Disorder,
}

data class PcapDesyncPlanAction(
    val planActionIndex: Int,
    val kind: PcapDesyncActionKind,
    val packetIndex: Int? = null,
    val timestampMicros: Long? = null,
    val sequenceStart: Long? = null,
    val sequenceEnd: Long? = null,
    val splitPoint: Int? = null,
    val ttl: Int? = null,
)

data class PcapDesyncAnnotation(
    val planActionIndex: Int,
    val kind: PcapDesyncActionKind,
    val splitPoint: Int? = null,
    val ttl: Int? = null,
    val sequenceStart: Long? = null,
    val sequenceEnd: Long? = null,
)

private const val TimestampToleranceMicros = 2_000L

fun annotatePcapPackets(
    packets: ImmutableList<PcapPacket>,
    planActions: List<PcapDesyncPlanAction>,
): ImmutableList<PcapPacket> {
    if (planActions.isEmpty()) return packets
    return packets
        .map { packet ->
            val annotations =
                planActions
                    .filter { action -> action.matches(packet) }
                    .map(PcapDesyncPlanAction::toAnnotation)
            if (annotations.isEmpty()) {
                packet
            } else {
                packet.copy(desyncAnnotations = annotations.toPersistentList())
            }
        }.toPersistentList()
}

private fun PcapDesyncPlanAction.matches(packet: PcapPacket): Boolean =
    matchesPacketIndex(packet) || matchesTimestamp(packet) || matchesSequence(packet)

private fun PcapDesyncPlanAction.matchesPacketIndex(packet: PcapPacket): Boolean = packetIndex == packet.index

private fun PcapDesyncPlanAction.matchesTimestamp(packet: PcapPacket): Boolean {
    val actionTimestamp = timestampMicros
    val packetTimestamp = packet.capturedAtMicros
    return actionTimestamp != null &&
        packetTimestamp != null &&
        abs(packetTimestamp - actionTimestamp) <= TimestampToleranceMicros
}

private fun PcapDesyncPlanAction.matchesSequence(packet: PcapPacket): Boolean {
    val start = sequenceStart
    val packetStart = packet.tcpSequence
    if (start == null || packetStart == null) return false
    val end = sequenceEnd ?: start
    val packetEnd = packetStart + packet.payloadLength.coerceAtLeast(1)
    return packetStart < end && packetEnd > start
}

private fun PcapDesyncPlanAction.toAnnotation(): PcapDesyncAnnotation =
    PcapDesyncAnnotation(
        planActionIndex = planActionIndex,
        kind = kind,
        splitPoint = splitPoint,
        ttl = ttl,
        sequenceStart = sequenceStart,
        sequenceEnd = sequenceEnd,
    )
