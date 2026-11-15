package com.poyka.ripdpi.ui.screens.diagnostics

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.poyka.ripdpi.R
import com.poyka.ripdpi.pcap.PcapController
import com.poyka.ripdpi.pcap.PcapDocumentWriteException
import com.poyka.ripdpi.pcap.PreparedPcapExport
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.EOFException

private const val PcapMimeType = "application/vnd.tcpdump.pcap"

@Composable
internal fun rememberPcapViewerExportAction(
    fileName: String,
    controller: PcapController,
    captureAvailable: Boolean,
): (@Composable () -> Unit)? {
    val export =
        viewModel<PcapViewerExportViewModel>(key = "pcap-export:$fileName") { PcapViewerExportViewModel(controller) }
    val state by export.state.collectAsStateWithLifecycle()
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(PcapMimeType),
            export::onDocumentCreated,
        )
    LaunchedEffect(state.openPicker) {
        if (state.openPicker) {
            export.launchPicker { launcher.launch("${fileName.removeSuffix(".pcap")}-redacted.pcap") }
        }
    }
    if (!captureAvailable) return null
    return {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiButton(
                text = stringResource(R.string.vpn_pcap_export_redacted),
                onClick = { export.prepare(fileName) },
                enabled = !state.working,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.vpn_pcap_export_details),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            state.notice?.let { resource ->
                Text(
                    text = stringResource(resource),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.foreground,
                )
            }
        }
    }
}

private data class PcapViewerExportState(
    val working: Boolean = false,
    val openPicker: Boolean = false,
    @StringRes val notice: Int? = null,
)

private class PcapViewerExportViewModel(
    private val controller: PcapController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PcapViewerExportState())
    val state = mutableState.asStateFlow()
    private var prepared: PreparedPcapExport? = null

    fun prepare(fileName: String) {
        if (state.value.working) return
        mutableState.value = PcapViewerExportState(working = true)
        viewModelScope.launch {
            mutableState.value =
                try {
                    prepared = controller.prepareRedactedExport(fileName)
                    PcapViewerExportState(working = true, openPicker = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: EOFException) {
                    PcapViewerExportState(notice = R.string.vpn_pcap_export_incomplete)
                } catch (_: Exception) {
                    PcapViewerExportState(notice = R.string.vpn_pcap_export_failed)
                }
        }
    }

    fun launchPicker(open: () -> Unit) {
        mutableState.value = state.value.copy(openPicker = false)
        if (runCatching(open).isFailure) {
            onDocumentCreated(null)
            mutableState.value = PcapViewerExportState(notice = R.string.vpn_pcap_export_failed)
        }
    }

    fun onDocumentCreated(uri: Uri?) {
        if (uri == null) {
            prepared?.close()
            prepared = null
            mutableState.value = PcapViewerExportState()
            return
        }

        mutableState.value = PcapViewerExportState(working = true)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val staged = prepared
            prepared = null
            mutableState.value =
                try {
                    controller.publishRedactedExport(staged, uri)
                    PcapViewerExportState(notice = R.string.vpn_pcap_export_saved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PcapDocumentWriteException) {
                    val notice =
                        if (error.documentRemoved) {
                            R.string.vpn_pcap_export_failed
                        } else {
                            R.string.vpn_pcap_export_cleanup_failed
                        }
                    PcapViewerExportState(notice = notice)
                }
        }
    }

    override fun onCleared() {
        prepared?.close()
    }
}
