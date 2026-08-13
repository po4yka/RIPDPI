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

private const val MinCustomPort = 0
private const val MaxCustomPort = 65535

@HiltViewModel
internal class DetectionSettingsViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DetectionSettingsUiState())
        val uiState: StateFlow<DetectionSettingsUiState> = _uiState.asStateFlow()
        private var settingsObserved = false

        init {
            observeSettings()
        }

        private fun observeSettings() {
            viewModelScope.launch {
                appSettingsRepository.settings.collect { settings ->
                    val previous = _uiState.value
                    _uiState.value =
                        DetectionSettingsUiState
                            .from(settings)
                            .copy(
                                dnsTextReplacementRevision =
                                    previous.dnsTextReplacementRevision + if (settingsObserved) 0 else 1,
                            )
                    settingsObserved = true
                }
            }
        }

        val setNetworkRequestsEnabled: (Boolean) -> Unit = { enabled ->
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
                    detectionCheckCustomPortStart =
                        value.toIntOrNull()?.coerceIn(MinCustomPort, MaxCustomPort) ?: MinCustomPort
                }
            }
        }

        fun setCustomPortEnd(value: String) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCustomPortEnd =
                        value.toIntOrNull()?.coerceIn(MinCustomPort, MaxCustomPort) ?: MinCustomPort
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
            _uiState.value =
                _uiState.value
                    .selectDnsPreset(preset)
                    .copy(dnsTextReplacementRevision = _uiState.value.dnsTextReplacementRevision + 1)
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = preset.wireValue
                    if (!preset.isCustom) {
                        detectionCheckDnsDirectServers = preset.directServers
                        detectionCheckDnsDohUrl = preset.dohUrl
                        detectionCheckDnsDohBootstrapIps = preset.dohBootstrapIps
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

        fun setDnsRouteThroughProxy(enabled: Boolean) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsRouteThroughProxy = enabled
                }
            }
        }

        val setDiagnosticRandomHostnamesEnabled: (Boolean) -> Unit = { enabled ->
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

        val setPrivacyModeEnabled: (Boolean) -> Unit = { enabled ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckPrivacyModeEnabled = enabled
                }
            }
        }

        val setDebugModeEnabled: (Boolean) -> Unit = { enabled ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDebugModeEnabled = enabled
                }
            }
        }

        val setColorVisionMode: (DetectionColorVisionMode) -> Unit = { mode ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckColorVisionMode = mode.wireValue
                }
            }
        }
    }
