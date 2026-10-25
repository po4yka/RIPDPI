package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import java.io.FileNotFoundException

@Composable
fun PcapViewerRoute(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { PcapController(context) }
    var attempt by remember(fileName) { mutableIntStateOf(0) }
    var capture by remember(fileName) { mutableStateOf<CapturePackets?>(null) }
    var failureReason by remember(fileName) { mutableStateOf<Int?>(null) }
    LaunchedEffect(fileName, attempt) {
        failureReason = null
        try {
            capture = withContext(Dispatchers.IO) { readCapturePackets(controller.captureDirectory, fileName) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: FileNotFoundException) {
            failureReason = R.string.vpn_pcap_read_failed_missing
        } catch (_: Exception) {
            failureReason = R.string.vpn_pcap_read_failed_invalid
        }
    }
    PcapViewerScreen(
        fileName = fileName,
        packetCount = capture?.packets?.size,
        packets = capture?.packets ?: persistentListOf(),
        onBack = onBack,
        loading = capture == null && failureReason == null,
        failureReason = failureReason?.let { stringResource(it) },
        // Reading again cannot bring back a deleted file.
        onRetry = if (failureReason == R.string.vpn_pcap_read_failed_missing) null else ({ attempt += 1 }),
        message =
            when {
                capture?.packets?.isEmpty() == true -> stringResource(R.string.vpn_pcap_no_packets)
                capture?.hasMore == true -> stringResource(R.string.vpn_pcap_truncated)
                else -> null
            },
    )
}
