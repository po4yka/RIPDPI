package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.legacyDesyncMethod
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.checkNotLocalIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConfigDraft(
    val mode: Mode = Mode.VPN,
    val dnsIp: String = "1.1.1.1",
    val dnsSummary: String = "Encrypted DNS · Cloudflare (DoH)",
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val maxConnections: String = "512",
    val bufferSize: String = "16384",
    val useCommandLineSettings: Boolean = false,
    val commandLineArgs: String = "",
    val tcpChainSteps: List<TcpChainStepModel> = emptyList(),
    val udpChainSteps: List<UdpChainStepModel> = emptyList(),
    val chainDsl: String = "",
    val desyncMethod: String = "disorder",
    val defaultTtl: String = "",
) {
    val chainSummary: String
        get() = resolvedChainSet().let { formatChainSummary(it.tcpSteps, it.udpSteps) }

    fun resolvedChainSet(): StrategyChainSet =
        parseStrategyChainDsl(chainDsl).getOrNull()
            ?: StrategyChainSet(tcpSteps = tcpChainSteps, udpSteps = udpChainSteps)

    fun withChainDsl(value: String): ConfigDraft {
        val parsed = parseStrategyChainDsl(value).getOrNull()
        return copy(
            chainDsl = value,
            tcpChainSteps = parsed?.tcpSteps ?: tcpChainSteps,
            udpChainSteps = parsed?.udpSteps ?: udpChainSteps,
            desyncMethod = parsed?.let { legacyDesyncMethod(it.tcpSteps) } ?: desyncMethod,
        )
    }
}

enum class ConfigPresetKind {
    Recommended,
    Proxy,
    Custom,
}

data class ConfigPreset(
    val id: String,
    val kind: ConfigPresetKind,
    val draft: ConfigDraft,
    val isSelected: Boolean = false,
)

data class ConfigUiState(
    val activeMode: Mode = Mode.VPN,
    val presets: List<ConfigPreset> = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
    val editingPreset: ConfigPreset? = null,
    val draft: ConfigDraft = AppSettingsSerializer.defaultValue.toConfigDraft(),
    val validationErrors: Map<String, String> = emptyMap(),
)

sealed interface ConfigEffect {
    data object SaveSuccess : ConfigEffect

    data object ValidationFailed : ConfigEffect
}

private data class ConfigEditorSession(
    val presetId: String? = null,
    val draft: ConfigDraft? = null,
)

internal const val ConfigFieldDnsIp = "dnsIp"
internal const val ConfigFieldProxyIp = "proxyIp"
internal const val ConfigFieldProxyPort = "proxyPort"
internal const val ConfigFieldMaxConnections = "maxConnections"
internal const val ConfigFieldBufferSize = "bufferSize"
internal const val ConfigFieldDefaultTtl = "defaultTtl"
internal const val ConfigFieldStrategyChain = "strategyChain"

internal fun AppSettings.toConfigDraft(): ConfigDraft =
    ConfigDraft(
        mode = Mode.fromString(ripdpiMode.ifEmpty { "vpn" }),
        dnsIp = activeDnsSettings().dnsIp,
        dnsSummary = activeDnsSettings().summary(),
        proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = (proxyPort.takeIf { it > 0 } ?: 1080).toString(),
        maxConnections = (maxConnections.takeIf { it > 0 } ?: 512).toString(),
        bufferSize = (bufferSize.takeIf { it > 0 } ?: 16_384).toString(),
        useCommandLineSettings = enableCmdSettings,
        commandLineArgs = cmdArgs,
        tcpChainSteps = effectiveTcpChainSteps(),
        udpChainSteps = effectiveUdpChainSteps(),
        chainDsl = formatStrategyChainDsl(effectiveTcpChainSteps(), effectiveUdpChainSteps()),
        desyncMethod = legacyDesyncMethod(effectiveTcpChainSteps()).ifEmpty { "none" },
        defaultTtl = if (customTtl && defaultTtl > 0) defaultTtl.toString() else "",
    )

internal fun buildConfigPresets(currentDraft: ConfigDraft): List<ConfigPreset> {
    val recommendedDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
    val proxyDraft = recommendedDraft.copy(mode = Mode.Proxy)
    val selectedId =
        when (currentDraft) {
            recommendedDraft -> "recommended"
            proxyDraft -> "proxy"
            else -> "custom"
        }

    return listOf(
        ConfigPreset(
            id = "recommended",
            kind = ConfigPresetKind.Recommended,
            draft = recommendedDraft,
            isSelected = selectedId == "recommended",
        ),
        ConfigPreset(
            id = "proxy",
            kind = ConfigPresetKind.Proxy,
            draft = proxyDraft,
            isSelected = selectedId == "proxy",
        ),
        ConfigPreset(
            id = "custom",
            kind = ConfigPresetKind.Custom,
            draft = currentDraft,
            isSelected = selectedId == "custom",
        ),
    )
}

internal fun validateConfigDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        if (!checkIp(draft.proxyIp)) {
            put(ConfigFieldProxyIp, "invalid_proxy_ip")
        }

        if (!validatePort(draft.proxyPort)) {
            put(ConfigFieldProxyPort, "invalid_port")
        }

        if (!validateIntRange(draft.maxConnections, 1, Short.MAX_VALUE.toInt())) {
            put(ConfigFieldMaxConnections, "out_of_range")
        }

        if (!validateIntRange(draft.bufferSize, 1, Int.MAX_VALUE / 4)) {
            put(ConfigFieldBufferSize, "out_of_range")
        }

        if (draft.defaultTtl.isNotEmpty() && !validateIntRange(draft.defaultTtl, 0, 255)) {
            put(ConfigFieldDefaultTtl, "out_of_range")
        }

        if (!draft.useCommandLineSettings && parseStrategyChainDsl(draft.chainDsl).isFailure) {
            put(ConfigFieldStrategyChain, "invalid_chain")
        }
    }

private fun AppSettings.Builder.applyConfigDraft(draft: ConfigDraft): AppSettings.Builder =
    apply {
        val defaults = AppSettingsSerializer.defaultValue
        setRipdpiMode(draft.mode.preferenceValue)
        setDnsIp(draft.dnsIp.ifBlank { defaults.dnsIp })
        setEnableCmdSettings(draft.useCommandLineSettings)
        setCmdArgs(draft.commandLineArgs)
        setProxyIp(draft.proxyIp.ifBlank { defaults.proxyIp })
        setProxyPort(draft.proxyPort.toIntOrNull() ?: defaults.proxyPort)
        setMaxConnections(draft.maxConnections.toIntOrNull() ?: defaults.maxConnections)
        setBufferSize(draft.bufferSize.toIntOrNull() ?: defaults.bufferSize)
        val chains = draft.resolvedChainSet()
        setStrategyChains(chains.tcpSteps, chains.udpSteps)
        setCustomTtl(draft.defaultTtl.isNotBlank())
        setDefaultTtl(draft.defaultTtl.toIntOrNull() ?: 0)
    }

@HiltViewModel
class ConfigViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val editorSession = MutableStateFlow(ConfigEditorSession())

        private val _effects = Channel<ConfigEffect>(Channel.BUFFERED)
        val effects: Flow<ConfigEffect> = _effects.receiveAsFlow()

        val uiState: StateFlow<ConfigUiState> =
            combine(
                appSettingsRepository.settings,
                editorSession,
            ) { settings, session ->
                val currentDraft = settings.toConfigDraft()
                val draft = session.draft ?: currentDraft
                val presets = buildConfigPresets(currentDraft)
                val editingPreset =
                    session.presetId?.let { presetId ->
                        presets.firstOrNull { it.id == presetId }?.copy(draft = draft)
                            ?: ConfigPreset(
                                id = presetId,
                                kind = ConfigPresetKind.Custom,
                                draft = draft,
                            )
                    }

                ConfigUiState(
                    activeMode = currentDraft.mode,
                    presets = presets,
                    editingPreset = editingPreset,
                    draft = draft,
                    validationErrors = validateConfigDraft(draft),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConfigUiState(),
            )

        fun selectMode(mode: Mode) {
            editorSession.update { current ->
                current.copy(draft = current.draft?.copy(mode = mode))
            }

            viewModelScope.launch {
                appSettingsRepository.update {
                    setRipdpiMode(mode.preferenceValue)
                }
            }
        }

        fun selectPreset(presetId: String) {
            val preset = uiState.value.presets.firstOrNull { it.id == presetId } ?: return
            if (preset.kind == ConfigPresetKind.Custom) {
                startEditingPreset(presetId)
                return
            }

            viewModelScope.launch {
                appSettingsRepository.update {
                    applyConfigDraft(preset.draft)
                }
                editorSession.value = ConfigEditorSession()
            }
        }

        fun startEditingPreset(presetId: String = "custom") {
            val preset = uiState.value.presets.firstOrNull { it.id == presetId }
            val draft = preset?.draft ?: uiState.value.draft
            editorSession.value =
                ConfigEditorSession(
                    presetId = presetId,
                    draft = draft,
                )
        }

        fun updateDraft(transform: ConfigDraft.() -> ConfigDraft) {
            editorSession.update { current ->
                val baseDraft = current.draft ?: uiState.value.draft
                current.copy(draft = baseDraft.transform())
            }
        }

        fun updateChainDsl(value: String) {
            updateDraft { withChainDsl(value) }
        }

        fun cancelEditing() {
            editorSession.value = ConfigEditorSession()
        }

        fun saveDraft() {
            val draft = editorSession.value.draft ?: uiState.value.draft
            if (validateConfigDraft(draft).isNotEmpty()) {
                _effects.trySend(ConfigEffect.ValidationFailed)
                return
            }

            viewModelScope.launch {
                appSettingsRepository.update {
                    applyConfigDraft(draft)
                }
                editorSession.value = ConfigEditorSession()
                _effects.send(ConfigEffect.SaveSuccess)
            }
        }

        fun resetToDefaults() {
            viewModelScope.launch {
                val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
                appSettingsRepository.update {
                    applyConfigDraft(defaultDraft)
                }
                editorSession.value = ConfigEditorSession()
            }
        }
    }
