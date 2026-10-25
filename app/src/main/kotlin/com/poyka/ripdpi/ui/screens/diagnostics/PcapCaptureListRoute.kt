package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import com.poyka.ripdpi.pcap.PcapController
import com.poyka.ripdpi.pcap.PcapReader
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PcapCaptureListRoute(
    onCaptureSelected: (PcapCaptureMetadata) -> Unit,
    onBack: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { PcapController(context) }
    var captures by remember { mutableStateOf<ImmutableList<PcapCaptureMetadata>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller, attempt) {
        failed = false
        try {
            captures =
                withContext(Dispatchers.IO) {
                    val listed = controller.listCaptures(controller.captureDirectory)
                    val valid =
                        listed.mapNotNull { capture ->
                            runCatching {
                                val file = captureFile(controller.captureDirectory, File(capture.path).name)
                                require(file.canonicalPath == File(capture.path).canonicalPath) {
                                    "Capture outside private directory"
                                }
                                PcapReader.open(file).use { reader ->
                                    var count = 0L
                                    while (reader.readOne() != null) count++
                                    capture.copy(packetCount = count)
                                }
                            }.getOrNull()
                        }
                    check(listed.isEmpty() || valid.isNotEmpty()) { "No readable captures" }
                    valid.toPersistentList()
                }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    PcapCaptureListScreen(
        captures = captures ?: persistentListOf(),
        onCaptureSelected = onCaptureSelected,
        onBack = onBack,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        loading = captures == null,
        failed = failed,
        onRetry = { attempt++ },
    )
}
