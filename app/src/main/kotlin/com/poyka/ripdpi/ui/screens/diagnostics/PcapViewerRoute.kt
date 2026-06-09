package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.persistentListOf

@Composable
fun PcapViewerRoute(onBack: () -> Unit) {
    val demoPackets = remember { demoPcapPackets().withSampleDesyncAnnotations() }
    PcapViewerScreen(
        fileName = "ripdpi-demo-capture.pcap",
        packetCount = demoPackets.size,
        packets = demoPackets,
        onBack = onBack,
    )
}

private fun demoPcapPackets() =
    persistentListOf(
        PcapPacket(
            index = 1,
            timeLabel = "0.000 000",
            src = "10.0.0.2:54321",
            dst = "1.1.1.1:53",
            protocol = PcapPacketProtocol.Udp,
            summary = "DNS query · A youtube.com",
            hexDump =
                "00 35 d4 31 00 1d 60 5a 4d a3 01 00 00 01 00 00\n" +
                    "00 00 00 00 07 79 6f 75 74 75 62 65 03 63 6f 6d\n" +
                    "00 00 01 00 01",
        ),
        PcapPacket(
            index = 2,
            timeLabel = "0.012 184",
            src = "1.1.1.1:53",
            dst = "10.0.0.2:54321",
            protocol = PcapPacketProtocol.Udp,
            summary = "DNS response · 142.250.190.78",
            hexDump =
                "00 35 d4 31 00 31 60 5a 4d a3 81 80 00 01 00 01\n" +
                    "00 00 00 00 07 79 6f 75 74 75 62 65 03 63 6f 6d\n" +
                    "00 00 01 00 01 c0 0c 00 01 00 01 00 00 00 11 00\n" +
                    "04 8e fa be 4e",
        ),
        PcapPacket(
            index = 3,
            timeLabel = "0.041 932",
            src = "10.0.0.2:48292",
            dst = "142.250.190.78:443",
            protocol = PcapPacketProtocol.Tcp,
            summary = "SYN · seq=0 win=65535",
            hexDump = "bc a4 01 bb 00 00 00 00 00 00 00 00 a0 02 ff ff\n74 8e 00 00 02 04 05 b4 04 02 08 0a",
            capturedAtMicros = 41_932,
            tcpSequence = 0,
        ),
        PcapPacket(
            index = 4,
            timeLabel = "0.084 471",
            src = "142.250.190.78:443",
            dst = "10.0.0.2:48292",
            protocol = PcapPacketProtocol.Tcp,
            summary = "SYN+ACK · seq=0 ack=1",
            hexDump = "01 bb bc a4 00 00 00 00 00 00 00 01 a0 12 ff ff",
        ),
        PcapPacket(
            index = 5,
            timeLabel = "0.085 003",
            src = "10.0.0.2:48292",
            dst = "142.250.190.78:443",
            protocol = PcapPacketProtocol.Tls,
            summary = "ClientHello · SNI=youtube.com",
            hexDump =
                "16 03 01 02 05 01 00 02 01 03 03 5a 4d a3 11 9e\n" +
                    "4b 23 c8 90 7f 88 d2 a4 03 7e 88 ad 0c 71 11 14\n" +
                    "bc 5e 35 6f 18 c3 87 b3",
            capturedAtMicros = 85_003,
            tcpSequence = 1,
            payloadLength = 517,
        ),
        PcapPacket(
            index = 6,
            timeLabel = "0.143 882",
            src = "142.250.190.78:443",
            dst = "10.0.0.2:48292",
            protocol = PcapPacketProtocol.Tls,
            summary = "ServerHello + Certificate",
            hexDump =
                "16 03 03 00 7a 02 00 00 76 03 03 5a 4d a3 9c b1\n" +
                    "de 5b 11 5e 81 23 b4 0c 7f 9b 81 04 7c 7e 33 cd",
        ),
    )
