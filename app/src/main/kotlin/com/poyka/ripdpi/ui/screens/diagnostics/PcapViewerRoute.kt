package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.pcap.PcapController
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PcapViewerRoute(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { PcapController(context) }
    var capture by remember(fileName) { mutableStateOf<CapturePackets?>(null) }
    var failed by remember(fileName) { mutableStateOf(false) }
    LaunchedEffect(fileName) {
        try {
            capture = withContext(Dispatchers.IO) { readCapturePackets(controller.captureDirectory, fileName) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    PcapViewerScreen(
        fileName = fileName,
        packetCount = capture?.packets?.size ?: 0,
        packets = capture?.packets ?: persistentListOf(),
        onBack = onBack,
        message =
            when {
                failed -> stringResource(R.string.vpn_pcap_read_failed)
                capture == null -> stringResource(R.string.vpn_pcap_loading)
                capture?.packets?.isEmpty() == true -> stringResource(R.string.vpn_pcap_no_packets)
                capture?.hasMore == true -> stringResource(R.string.vpn_pcap_truncated)
                else -> null
            },
    )
}
