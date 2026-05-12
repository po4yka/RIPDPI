package com.poyka.ripdpi.ui.screens.detection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class DetectionSettingsViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DetectionSettingsUiState())
        val uiState: StateFlow<DetectionSettingsUiState> = _uiState.asStateFlow()

        init {
            observeSettings()
        }

        private fun observeSettings() {
            viewModelScope.launch {
                appSettingsRepository.settings.collect { settings ->
                    _uiState.value = DetectionSettingsUiState.from(settings)
                }
            }
        }

        fun setNetworkRequestsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckNetworkRequestsEnabled = enabled
                }
            }
        }

        fun setCdnPullingEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCdnPullingEnabled = enabled
                }
            }
        }

        fun setCdnPullingMeduzaEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCdnPullingMeduzaEnabled = enabled
                }
            }
        }

        fun setCallTransportProbeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCallTransportProbeEnabled = enabled
                }
            }
        }

        fun setRttTriangulationEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckRttTriangulationEnabled = enabled
                }
            }
        }

        fun setProxyScanEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckIncludeBypass = enabled
                }
            }
        }

        fun setXrayApiScanEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckXrayApiScanEnabled = enabled
                }
            }
        }

        fun setTunProbeMode(mode: DetectionTunProbeMode) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckTunProbeMode = mode.wireValue
                }
            }
        }

        fun setPortRangeMode(mode: DetectionPortRangeMode) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckPortRangeMode = mode.wireValue
                }
            }
        }

        fun setCustomPortStart(value: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCustomPortStart = value.toIntOrNull()?.coerceIn(0, 65535) ?: 0
                }
            }
        }

        fun setCustomPortEnd(value: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCustomPortEnd = value.toIntOrNull()?.coerceIn(0, 65535) ?: 0
                }
            }
        }

        fun setDnsResolverMode(mode: DetectionDnsResolverMode) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsResolverMode = mode.wireValue
                }
            }
        }

        fun selectDnsPreset(preset: DetectionDnsPreset) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = preset.wireValue
                    if (preset != DetectionDnsPreset.CUSTOM) {
                        detectionCheckDnsDirectServers = preset.directServers
                        detectionCheckDnsDohUrl = preset.dohUrl
                        detectionCheckDnsDohBootstrapIps = preset.directServers
                    }
                }
            }
        }

        fun setDnsDirectServers(value: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = DetectionDnsPreset.CUSTOM.wireValue
                    detectionCheckDnsDirectServers = value
                }
            }
        }

        fun setDnsDohUrl(value: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = DetectionDnsPreset.CUSTOM.wireValue
                    detectionCheckDnsDohUrl = value
                }
            }
        }

        fun setDnsDohBootstrapIps(value: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = DetectionDnsPreset.CUSTOM.wireValue
                    detectionCheckDnsDohBootstrapIps = value
                }
            }
        }

        fun setDiagnosticRandomHostnamesEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionDiagnosticRandomHostnamesEnabled = enabled
                }
            }
        }

        fun setTlsKeylogPath(path: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionDiagnosticTlsKeylogPath = path
                }
            }
        }

        fun setPrivacyModeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckPrivacyModeEnabled = enabled
                }
            }
        }

        fun setDebugModeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDebugModeEnabled = enabled
                }
            }
        }

        fun setColorVisionMode(mode: DetectionColorVisionMode) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckColorVisionMode = mode.wireValue
                }
            }
        }
    }
