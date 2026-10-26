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

        // Text the user typed, shown at once. A DataStore round trip lags behind keystrokes, and a
        // text field that shows the lagging value drops characters. Main-confined.
        private val drafts = mutableMapOf<DraftField, String>()

        init {
            observeSettings()
        }

        private fun observeSettings() {
            viewModelScope.launch {
                appSettingsRepository.settings.collect { settings ->
                    _uiState.value = DetectionSettingsUiState.from(settings).withDrafts(drafts)
                }
            }
        }

        private fun editDraft(
            field: DraftField,
            value: String,
        ) {
            drafts[field] = value
            _uiState.value = _uiState.value.withDrafts(drafts)
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
            editDraft(DraftField.PortStart, value)
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCustomPortStart =
                        value.toIntOrNull()?.coerceIn(MinCustomPort, MaxCustomPort) ?: MinCustomPort
                }
            }
        }

        fun setCustomPortEnd(value: String) {
            editDraft(DraftField.PortEnd, value)
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
            if (!preset.isCustom) {
                // The preset replaces the DNS fields, so the typed text no longer applies.
                drafts -= listOf(DraftField.DnsDirectServers, DraftField.DnsDohUrl, DraftField.DnsDohBootstrapIps)
                _uiState.value = _uiState.value.selectDnsPreset(preset)
            }
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
            editDraft(DraftField.DnsDirectServers, value)
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = DetectionDnsPreset.CUSTOM.wireValue
                    detectionCheckDnsDirectServers = value
                }
            }
        }

        fun setDnsDohUrl(value: String) {
            editDraft(DraftField.DnsDohUrl, value)
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDnsPreset = DetectionDnsPreset.CUSTOM.wireValue
                    detectionCheckDnsDohUrl = value
                }
            }
        }

        fun setDnsDohBootstrapIps(value: String) {
            editDraft(DraftField.DnsDohBootstrapIps, value)
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
            editDraft(DraftField.TlsKeylogPath, path)
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

private fun DetectionSettingsUiState.withDrafts(drafts: Map<DraftField, String>): DetectionSettingsUiState {
    var state = this
    drafts[DraftField.PortStart]?.let { state = state.withCustomPortStart(it) }
    drafts[DraftField.PortEnd]?.let { state = state.withCustomPortEnd(it) }
    return state.copy(
        dnsDirectServers = drafts[DraftField.DnsDirectServers] ?: state.dnsDirectServers,
        dnsDohUrl = drafts[DraftField.DnsDohUrl] ?: state.dnsDohUrl,
        dnsDohBootstrapIps = drafts[DraftField.DnsDohBootstrapIps] ?: state.dnsDohBootstrapIps,
        tlsKeylogPath = drafts[DraftField.TlsKeylogPath] ?: state.tlsKeylogPath,
    )
}

private enum class DraftField {
    PortStart,
    PortEnd,
    DnsDirectServers,
    DnsDohUrl,
    DnsDohBootstrapIps,
    TlsKeylogPath,
}
