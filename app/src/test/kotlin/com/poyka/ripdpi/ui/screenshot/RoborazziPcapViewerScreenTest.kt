package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.diagnostics.PcapDesyncActionKind
import com.poyka.ripdpi.ui.screens.diagnostics.PcapDesyncPlanAction
import com.poyka.ripdpi.ui.screens.diagnostics.PcapPacket
import com.poyka.ripdpi.ui.screens.diagnostics.PcapPacketProtocol
import com.poyka.ripdpi.ui.screens.diagnostics.PcapViewerScreen
import com.poyka.ripdpi.ui.screens.diagnostics.annotatePcapPackets
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RoborazziPcapViewerScreenTest {
    @Test
    fun pcapViewerShowsDesyncPlanAnnotations() {
        captureRipDpiScreenshot(widthDp = 640, heightDp = 900) {
            RipDpiTheme(themePreference = "light") {
                PcapViewerScreen(
                    fileName = "seeded-desync-capture.pcap",
                    packetCount = SeededPackets.size,
                    packets = SeededPackets,
                    onBack = {},
                    initiallySelectedIndex = 3,
                )
            }
        }
    }
}

private val SeededPackets =
    annotatePcapPackets(
        packets =
            persistentListOf(
                packet(index = 1, timeLabel = "0.000 211", summary = "SYN", sequence = 0, payloadLength = 0),
                packet(index = 2, timeLabel = "0.011 804", summary = "SYN+ACK", sequence = 0, payloadLength = 0),
                packet(
                    index = 3,
                    timeLabel = "0.014 902",
                    protocol = PcapPacketProtocol.Tls,
                    summary = "ClientHello fragment · SNI=youtube.com",
                    sequence = 1,
                    payloadLength = 260,
                    capturedAtMicros = 14_902,
                    hexDump =
                        "16 03 01 01 04 01 00 01 00 03 03 5a 4d a3 11 9e\n" +
                            "4b 23 c8 90 7f 88 d2 a4 03 7e 88 ad 0c 71 11 14",
                ),
                packet(
                    index = 4,
                    timeLabel = "0.015 410",
                    protocol = PcapPacketProtocol.Tls,
                    summary = "Fake ClientHello decoy",
                    sequence = 261,
                    payloadLength = 256,
                    capturedAtMicros = 15_410,
                ),
                packet(
                    index = 5,
                    timeLabel = "0.016 003",
                    protocol = PcapPacketProtocol.Tls,
                    summary = "ClientHello tail",
                    sequence = 517,
                    payloadLength = 96,
                    capturedAtMicros = 16_003,
                ),
            ),
        planActions =
            listOf(
                PcapDesyncPlanAction(
                    planActionIndex = 1,
                    kind = PcapDesyncActionKind.Split,
                    packetIndex = 3,
                    timestampMicros = 14_900,
                    sequenceStart = 1,
                    sequenceEnd = 261,
                    splitPoint = 260,
                ),
                PcapDesyncPlanAction(
                    planActionIndex = 2,
                    kind = PcapDesyncActionKind.FakePacket,
                    timestampMicros = 15_410,
                    sequenceStart = 261,
                    sequenceEnd = 517,
                    ttl = 8,
                ),
                PcapDesyncPlanAction(
                    planActionIndex = 3,
                    kind = PcapDesyncActionKind.SeqOverlap,
                    sequenceStart = 520,
                    sequenceEnd = 560,
                ),
                PcapDesyncPlanAction(
                    planActionIndex = 4,
                    kind = PcapDesyncActionKind.Disorder,
                    sequenceStart = 570,
                    sequenceEnd = 610,
                ),
            ),
    )

private fun packet(
    index: Int,
    timeLabel: String,
    summary: String,
    sequence: Long,
    payloadLength: Int,
    protocol: PcapPacketProtocol = PcapPacketProtocol.Tcp,
    capturedAtMicros: Long = index * 1_000L,
    hexDump: String = "45 00 00 28 ab cd 40 00 40 06 7c 35 0a 00 00 02",
): PcapPacket =
    PcapPacket(
        index = index,
        timeLabel = timeLabel,
        src = "10.0.0.2:48292",
        dst = "142.250.190.78:443",
        protocol = protocol,
        summary = summary,
        hexDump = hexDump,
        capturedAtMicros = capturedAtMicros,
        tcpSequence = sequence,
        payloadLength = payloadLength,
    )
