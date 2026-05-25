package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import kotlinx.collections.immutable.persistentListOf

@Composable
fun PcapCaptureListRoute(onCaptureSelected: (PcapCaptureMetadata) -> Unit) {
    val captures = remember { demoPcapCaptures() }
    PcapCaptureListScreen(
        captures = captures,
        onCaptureSelected = onCaptureSelected,
    )
}

private fun demoPcapCaptures() =
    persistentListOf(
        PcapCaptureMetadata(
            path = "/data/data/com.poyka.ripdpi/files/pcap/0000000000000001-1716640000000-00.pcap",
            byteSize = 16_384,
            packetCount = 482,
            startedAtMs = 1_716_640_000_000L,
            endedAtMs = 1_716_640_090_000L,
            drops = 0,
        ),
        PcapCaptureMetadata(
            path = "/data/data/com.poyka.ripdpi/files/pcap/0000000000000001-1716640090000-01.pcap",
            byteSize = 8_192,
            packetCount = 240,
            startedAtMs = 1_716_640_090_000L,
            endedAtMs = 1_716_640_140_000L,
            drops = 12,
        ),
    )
