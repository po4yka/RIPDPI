package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI adapter for the process-wide capture lifecycle, not a local UI toggle. */
@HiltViewModel
class PcapCaptureViewModel
    @Inject
    constructor(
        private val captureRuntime: PcapCaptureRuntimeController,
    ) : ViewModel() {
        val recording: StateFlow<Boolean> =
            captureRuntime.state
                .map { it is PcapCaptureRuntimeState.Recording }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = false,
                )

        fun toggle() {
            viewModelScope.launch {
                if (captureRuntime.state.value is PcapCaptureRuntimeState.Recording) {
                    captureRuntime.stop()
                } else {
                    captureRuntime.start()
                }
            }
        }
    }
