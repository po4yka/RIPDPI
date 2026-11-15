package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI adapter for the process-wide capture lifecycle, not a local UI toggle. */
@HiltViewModel
class PcapCaptureViewModel
    @Inject
    constructor(
        private val captureRuntime: PcapCaptureRuntimeController,
    ) : ViewModel() {
        val state = captureRuntime.state

        fun toggle() {
            viewModelScope.launch {
                if (captureRuntime.state.value.canStopCapture()) {
                    captureRuntime.stop()
                } else {
                    captureRuntime.start()
                }
            }
        }
    }

internal fun PcapCaptureRuntimeState.canStopCapture(): Boolean =
    this is PcapCaptureRuntimeState.Recording || this is PcapCaptureRuntimeState.StopFailed
