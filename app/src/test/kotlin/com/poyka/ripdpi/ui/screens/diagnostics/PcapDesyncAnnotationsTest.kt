package com.poyka.ripdpi.ui.screens.diagnostics

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcapDesyncAnnotationsTest {
    @Test
    fun `annotatePcapPackets matches plan actions by packet index timestamp and sequence`() {
        val packets =
            persistentListOf(
                packet(index = 1, capturedAtMicros = 10_000, tcpSequence = 100, payloadLength = 20),
                packet(index = 2, capturedAtMicros = 20_000, tcpSequence = 200, payloadLength = 80),
                packet(index = 3, capturedAtMicros = 30_000, tcpSequence = 300, payloadLength = 40),
            )

        val annotated =
            annotatePcapPackets(
                packets = packets,
                planActions =
                    listOf(
                        PcapDesyncPlanAction(
                            planActionIndex = 1,
                            kind = PcapDesyncActionKind.Split,
                            packetIndex = 1,
                            splitPoint = 12,
                        ),
                        PcapDesyncPlanAction(
                            planActionIndex = 2,
                            kind = PcapDesyncActionKind.FakePacket,
                            timestampMicros = 20_900,
                            ttl = 8,
                        ),
                        PcapDesyncPlanAction(
                            planActionIndex = 3,
                            kind = PcapDesyncActionKind.SeqOverlap,
                            sequenceStart = 320,
                            sequenceEnd = 360,
                        ),
                    ),
            )

        assertEquals(PcapDesyncActionKind.Split, annotated[0].desyncAnnotations.single().kind)
        assertEquals(8, annotated[1].desyncAnnotations.single().ttl)
        assertEquals(PcapDesyncActionKind.SeqOverlap, annotated[2].desyncAnnotations.single().kind)
    }

    @Test
    fun `annotatePcapPackets leaves unrelated packets unannotated`() {
        val annotated =
            annotatePcapPackets(
                packets = persistentListOf(packet(index = 1, capturedAtMicros = 10_000, tcpSequence = 100)),
                planActions =
                    listOf(
                        PcapDesyncPlanAction(
                            planActionIndex = 1,
                            kind = PcapDesyncActionKind.Disorder,
                            timestampMicros = 30_000,
                            sequenceStart = 500,
                            sequenceEnd = 540,
                        ),
                    ),
            )

        assertTrue(annotated.single().desyncAnnotations.isEmpty())
    }
}

private fun packet(
    index: Int,
    capturedAtMicros: Long,
    tcpSequence: Long,
    payloadLength: Int = 1,
): PcapPacket =
    PcapPacket(
        index = index,
        timeLabel = "0.000 000",
        src = "10.0.0.2:12345",
        dst = "93.184.216.34:443",
        protocol = PcapPacketProtocol.Tcp,
        summary = "TCP payload",
        hexDump = "45 00",
        capturedAtMicros = capturedAtMicros,
        tcpSequence = tcpSequence,
        payloadLength = payloadLength,
    )
