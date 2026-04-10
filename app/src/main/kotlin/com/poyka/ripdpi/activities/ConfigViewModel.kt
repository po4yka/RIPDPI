package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
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

private const val defaultTtlMax = 255
private const val defaultRelayPort = 443
private const val bufferSizeDiv = 4

private val DefaultConfigDnsSeed = canonicalDefaultEncryptedDnsSettings()

data class ConfigDraft(
    val mode: Mode = Mode.VPN,
    val dnsIp: String = DefaultConfigDnsSeed.dnsIp,
    val dnsSummary: String = DefaultConfigDnsSeed.summary(),
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val maxConnections: String = "512",
    val bufferSize: String = "16384",
    val useCommandLineSettings: Boolean = false,
    val commandLineArgs: String = "",
    val tcpChainSteps: ImmutableList<TcpChainStepModel> = persistentListOf(),
    val udpChainSteps: ImmutableList<UdpChainStepModel> = persistentListOf(),
    val chainDsl: String = "",
    val desyncMethod: String = "split",
    val defaultTtl: String = "",
    val relayEnabled: Boolean = false,
    val relayKind: String = RelayKindOff,
    val relayProfileId: String = DefaultRelayProfileId,
    val relayServer: String = "",
    val relayServerPort: String = "443",
    val relayServerName: String = "",
    val relayRealityPublicKey: String = "",
    val relayRealityShortId: String = "",
    val relayVlessTransport: String = RelayVlessTransportRealityTcp,
    val relayXhttpPath: String = "",
    val relayXhttpHost: String = "",
    val relayVlessUuid: String = "",
    val relayHysteriaPassword: String = "",
    val relayHysteriaSalamanderKey: String = "",
    val relayChainEntryServer: String = "",
    val relayChainEntryPort: String = "443",
    val relayChainEntryServerName: String = "",
    val relayChainEntryPublicKey: String = "",
    val relayChainEntryShortId: String = "",
    val relayChainEntryUuid: String = "",
    val relayChainExitServer: String = "",
    val relayChainExitPort: String = "443",
    val relayChainExitServerName: String = "",
    val relayChainExitPublicKey: String = "",
    val relayChainExitShortId: String = "",
    val relayChainExitUuid: String = "",
    val relayMasqueUrl: String = "",
    val relayMasqueAuthMode: String = RelayMasqueAuthModeBearer,
    val relayMasqueAuthToken: String = "",
    val relayMasqueUseHttp2Fallback: Boolean = true,
    val relayMasqueCloudflareMode: Boolean = false,
    val relayUdpEnabled: Boolean = false,
    val relayLocalSocksPort: String = DefaultRelayLocalSocksPort.toString(),
) {
    val chainSummary: String
        get() = resolvedChainSet().let { formatChainSummary(it.tcpSteps, it.udpSteps) }

    val relaySummary: String
        get() =
            when {
                !relayEnabled || relayKind == RelayKindOff -> "Disabled"
                relayKind == RelayKindChainRelay -> "Chain relay"
                relayKind == RelayKindMasque -> "MASQUE"
                relayKind == RelayKindHysteria2 -> "Hysteria2"
                relayKind == RelayKindCloudflareTunnel -> "Cloudflare Tunnel"
                else -> "VLESS + Reality"
            }

    fun resolvedChainSet(): StrategyChainSet =
        parseStrategyChainDsl(chainDsl).getOrNull()
            ?: StrategyChainSet(tcpSteps = tcpChainSteps, udpSteps = udpChainSteps)

    fun withChainDsl(value: String): ConfigDraft {
        val parsed = parseStrategyChainDsl(value).getOrNull()
        return copy(
            chainDsl = value,
            tcpChainSteps = parsed?.tcpSteps?.toImmutableList() ?: tcpChainSteps,
            udpChainSteps = parsed?.udpSteps?.toImmutableList() ?: udpChainSteps,
            desyncMethod = parsed?.let { primaryDesyncMethod(it.tcpSteps) } ?: desyncMethod,
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
    val presets: ImmutableList<ConfigPreset> = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
    val editingPreset: ConfigPreset? = null,
    val draft: ConfigDraft = AppSettingsSerializer.defaultValue.toConfigDraft(),
    val validationErrors: ImmutableMap<String, String> = persistentMapOf(),
    val supportsMasquePrivacyPass: Boolean = false,
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
internal const val ConfigFieldRelayServerPort = "relayServerPort"
internal const val ConfigFieldRelayChainEntryPort = "relayChainEntryPort"
internal const val ConfigFieldRelayChainExitPort = "relayChainExitPort"
internal const val ConfigFieldRelayLocalSocksPort = "relayLocalSocksPort"
internal const val ConfigFieldRelayServer = "relayServer"
internal const val ConfigFieldRelayCredentials = "relayCredentials"

internal fun AppSettings.toConfigDraft(): ConfigDraft =
    toRelaySettingsModel().let { relay ->
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
            tcpChainSteps = effectiveTcpChainSteps().toImmutableList(),
            udpChainSteps = effectiveUdpChainSteps().toImmutableList(),
            chainDsl = formatStrategyChainDsl(effectiveTcpChainSteps(), effectiveUdpChainSteps()),
            desyncMethod = primaryDesyncMethod(effectiveTcpChainSteps()).ifEmpty { "none" },
            defaultTtl = if (customTtl && defaultTtl > 0) defaultTtl.toString() else "",
            relayEnabled = relay.enabled,
            relayKind = relay.kind,
            relayProfileId = relay.profileId,
            relayServer = relay.profile.server,
            relayServerPort = relay.profile.serverPort.toString(),
            relayServerName = relay.profile.serverName,
            relayRealityPublicKey = relay.profile.realityPublicKey,
            relayRealityShortId = relay.profile.realityShortId,
            relayVlessTransport = relay.profile.vlessTransport,
            relayXhttpPath = relay.profile.xhttpPath,
            relayXhttpHost = relay.profile.xhttpHost,
            relayChainEntryServer = relay.profile.chainEntryServer,
            relayChainEntryPort = relay.profile.chainEntryPort.toString(),
            relayChainEntryServerName = relay.profile.chainEntryServerName,
            relayChainEntryPublicKey = relay.profile.chainEntryPublicKey,
            relayChainEntryShortId = relay.profile.chainEntryShortId,
            relayChainExitServer = relay.profile.chainExitServer,
            relayChainExitPort = relay.profile.chainExitPort.toString(),
            relayChainExitServerName = relay.profile.chainExitServerName,
            relayChainExitPublicKey = relay.profile.chainExitPublicKey,
            relayChainExitShortId = relay.profile.chainExitShortId,
            relayMasqueUrl = relay.profile.masqueUrl,
            relayMasqueAuthMode =
                if (relay.profile.masqueCloudflareMode) {
                    RelayMasqueAuthModePrivacyPass
                } else {
                    RelayMasqueAuthModeBearer
                },
            relayMasqueUseHttp2Fallback = relay.profile.masqueUseHttp2Fallback,
            relayMasqueCloudflareMode = relay.profile.masqueCloudflareMode,
            relayUdpEnabled = relay.profile.udpEnabled,
            relayLocalSocksPort = relay.profile.localSocksPort.toString(),
        )
    }

internal fun buildConfigPresets(currentDraft: ConfigDraft): ImmutableList<ConfigPreset> {
    val recommendedDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
    val proxyDraft = recommendedDraft.copy(mode = Mode.Proxy)
    val selectedId =
        when (currentDraft) {
            recommendedDraft -> "recommended"
            proxyDraft -> "proxy"
            else -> "custom"
        }

    return persistentListOf(
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

internal fun sanitizeMasqueAuthModeForCurrentBuild(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
): ConfigDraft =
    if (!supportsMasquePrivacyPass && draft.relayMasqueAuthMode == RelayMasqueAuthModePrivacyPass) {
        draft.copy(
            relayMasqueAuthMode = RelayMasqueAuthModeBearer,
            relayMasqueCloudflareMode = false,
        )
    } else {
        draft
    }

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun validateConfigDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean = false,
): ImmutableMap<String, String> =
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

        if (!validateIntRange(draft.bufferSize, 1, Int.MAX_VALUE / bufferSizeDiv)) {
            put(ConfigFieldBufferSize, "out_of_range")
        }

        if (draft.defaultTtl.isNotEmpty() && !validateIntRange(draft.defaultTtl, 0, defaultTtlMax)) {
            put(ConfigFieldDefaultTtl, "out_of_range")
        }

        if (draft.relayEnabled && !draft.useCommandLineSettings) {
            if (!validatePort(draft.relayLocalSocksPort)) {
                put(ConfigFieldRelayLocalSocksPort, "invalid_port")
            }
            when (draft.relayKind) {
                RelayKindVlessReality -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    val isVlessRealityIncomplete =
                        draft.relayServerName.isBlank() ||
                            draft.relayRealityPublicKey.isBlank() ||
                            draft.relayRealityShortId.isBlank() ||
                            draft.relayVlessUuid.isBlank()
                    if (isVlessRealityIncomplete) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayVlessTransport == RelayVlessTransportXhttp && draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindCloudflareTunnel -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (draft.relayVlessUuid.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindHysteria2 -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    if (draft.relayServerName.isBlank() || draft.relayHysteriaPassword.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                }

                RelayKindChainRelay -> {
                    if (draft.relayChainEntryServer.isBlank() || draft.relayChainExitServer.isBlank()) {
                        put(ConfigFieldRelayServer, "required")
                    }
                    if (!validatePort(draft.relayChainEntryPort)) put(ConfigFieldRelayChainEntryPort, "invalid_port")
                    if (!validatePort(draft.relayChainExitPort)) put(ConfigFieldRelayChainExitPort, "invalid_port")
                    val isChainRelayIncomplete =
                        draft.relayChainEntryServerName.isBlank() ||
                            draft.relayChainEntryPublicKey.isBlank() ||
                            draft.relayChainEntryShortId.isBlank() ||
                            draft.relayChainEntryUuid.isBlank() ||
                            draft.relayChainExitServerName.isBlank() ||
                            draft.relayChainExitPublicKey.isBlank() ||
                            draft.relayChainExitShortId.isBlank() ||
                            draft.relayChainExitUuid.isBlank()
                    if (isChainRelayIncomplete) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                }

                RelayKindMasque -> {
                    if (draft.relayMasqueUrl.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    when (normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode, draft.relayMasqueCloudflareMode)) {
                        RelayMasqueAuthModeBearer,
                        RelayMasqueAuthModePreshared,
                        -> {
                            if (draft.relayMasqueAuthToken.isBlank()) {
                                put(ConfigFieldRelayCredentials, "required")
                            }
                        }

                        RelayMasqueAuthModePrivacyPass -> {
                            if (!supportsMasquePrivacyPass) {
                                put(ConfigFieldRelayCredentials, "unsupported")
                            }
                        }

                        else -> {
                            put(ConfigFieldRelayCredentials, "required")
                        }
                    }
                }
            }

            if (
                draft.relayUdpEnabled &&
                draft.relayKind != RelayKindHysteria2 &&
                draft.relayKind != RelayKindMasque
            ) {
                put(ConfigFieldRelayCredentials, "unsupported")
            }
        }

        if (!draft.useCommandLineSettings) {
            val chainValidation =
                parseStrategyChainDsl(draft.chainDsl).map { chain ->
                    validateStrategyChainUsage(
                        tcpSteps = chain.tcpSteps,
                        udpSteps = chain.udpSteps,
                        mode = draft.mode,
                        useCommandLineSettings = draft.useCommandLineSettings,
                    )
                }
            if (chainValidation.isFailure) {
                put(ConfigFieldStrategyChain, "invalid_chain")
            }
        }
    }.toImmutableMap()

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
        setRelayEnabled(draft.relayEnabled && draft.relayKind != RelayKindOff)
        setRelayKind(draft.relayKind)
        setRelayProfileId(draft.relayProfileId.ifBlank { DefaultRelayProfileId })
        setRelayServer(draft.relayServer)
        setRelayServerPort(draft.relayServerPort.toIntOrNull() ?: defaultRelayPort)
        setRelayServerName(
            when (draft.relayKind) {
                RelayKindCloudflareTunnel -> draft.relayServerName.ifBlank { draft.relayServer }
                else -> draft.relayServerName
            },
        )
        setRelayRealityPublicKey(draft.relayRealityPublicKey)
        setRelayRealityShortId(draft.relayRealityShortId)
        setRelayVlessTransport(draft.relayVlessTransport)
        setRelayXhttpPath(draft.relayXhttpPath)
        setRelayXhttpHost(draft.relayXhttpHost)
        setRelayChainEntryServer(draft.relayChainEntryServer)
        setRelayChainEntryPort(draft.relayChainEntryPort.toIntOrNull() ?: defaultRelayPort)
        setRelayChainEntryServerName(draft.relayChainEntryServerName)
        setRelayChainEntryPublicKey(draft.relayChainEntryPublicKey)
        setRelayChainEntryShortId(draft.relayChainEntryShortId)
        setRelayChainExitServer(draft.relayChainExitServer)
        setRelayChainExitPort(draft.relayChainExitPort.toIntOrNull() ?: defaultRelayPort)
        setRelayChainExitServerName(draft.relayChainExitServerName)
        setRelayChainExitPublicKey(draft.relayChainExitPublicKey)
        setRelayChainExitShortId(draft.relayChainExitShortId)
        setRelayMasqueUrl(draft.relayMasqueUrl)
        setRelayMasqueUseHttp2Fallback(draft.relayMasqueUseHttp2Fallback)
        setRelayMasqueCloudflareMode(
            normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode, draft.relayMasqueCloudflareMode) ==
                RelayMasqueAuthModePrivacyPass,
        )
        setRelayLocalSocksHost("127.0.0.1")
        setRelayLocalSocksPort(draft.relayLocalSocksPort.toIntOrNull() ?: DefaultRelayLocalSocksPort)
        setRelayUdpEnabled(draft.relayUdpEnabled && draft.relayKind != RelayKindCloudflareTunnel)
        setRelayTcpFallbackEnabled(draft.relayMasqueUseHttp2Fallback)
    }

@HiltViewModel
class ConfigViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val masquePrivacyPassAvailability: MasquePrivacyPassAvailability,
    ) : ViewModel() {
        private val editorSession = MutableStateFlow(ConfigEditorSession())
        private val supportsMasquePrivacyPass = masquePrivacyPassAvailability.isAvailable()

        private val _effects = Channel<ConfigEffect>(Channel.BUFFERED)
        val effects: Flow<ConfigEffect> = _effects.receiveAsFlow()

        val uiState: StateFlow<ConfigUiState> =
            combine(
                appSettingsRepository.settings,
                editorSession,
            ) { settings, session ->
                val currentDraft =
                    sanitizeMasqueAuthModeForCurrentBuild(
                        draft = settings.toConfigDraft(),
                        supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    )
                val draft =
                    sanitizeMasqueAuthModeForCurrentBuild(
                        draft = session.draft ?: currentDraft,
                        supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    )
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
                    validationErrors = validateConfigDraft(draft, supportsMasquePrivacyPass),
                    supportsMasquePrivacyPass = supportsMasquePrivacyPass,
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
            viewModelScope.launch {
                hydrateRelaySecrets(presetId, draft)
            }
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
            if (validateConfigDraft(draft, supportsMasquePrivacyPass).isNotEmpty()) {
                _effects.trySend(ConfigEffect.ValidationFailed)
                return
            }

            viewModelScope.launch {
                appSettingsRepository.update {
                    applyConfigDraft(draft)
                }
                persistRelayArtifacts(draft)
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

        private suspend fun hydrateRelaySecrets(
            presetId: String,
            draft: ConfigDraft,
        ) {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            val credentials = relayCredentialStore.load(profileId)
            editorSession.update { current ->
                if (current.presetId != presetId) {
                    current
                } else {
                    current.copy(
                        draft =
                            (current.draft ?: draft).copy(
                                relayVlessUuid = credentials?.vlessUuid.orEmpty(),
                                relayHysteriaPassword = credentials?.hysteriaPassword.orEmpty(),
                                relayHysteriaSalamanderKey = credentials?.hysteriaSalamanderKey.orEmpty(),
                                relayChainEntryUuid = credentials?.chainEntryUuid.orEmpty(),
                                relayChainExitUuid = credentials?.chainExitUuid.orEmpty(),
                                relayMasqueAuthMode =
                                    normalizeRelayMasqueAuthMode(
                                        credentials?.masqueAuthMode,
                                        (current.draft ?: draft).relayMasqueCloudflareMode,
                                    ) ?: (current.draft ?: draft).relayMasqueAuthMode,
                                relayMasqueAuthToken = credentials?.masqueAuthToken.orEmpty(),
                            ),
                    )
                }
            }
        }

        private suspend fun persistRelayArtifacts(draft: ConfigDraft) {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            relayProfileStore.save(
                RelayProfileRecord(
                    id = profileId,
                    kind = draft.relayKind,
                    server = draft.relayServer,
                    serverPort = draft.relayServerPort.toIntOrNull() ?: 443,
                    serverName = draft.relayServerName,
                    realityPublicKey = draft.relayRealityPublicKey,
                    realityShortId = draft.relayRealityShortId,
                    vlessTransport = draft.relayVlessTransport,
                    xhttpPath = draft.relayXhttpPath,
                    xhttpHost = draft.relayXhttpHost,
                    chainEntryServer = draft.relayChainEntryServer,
                    chainEntryPort = draft.relayChainEntryPort.toIntOrNull() ?: 443,
                    chainEntryServerName = draft.relayChainEntryServerName,
                    chainEntryPublicKey = draft.relayChainEntryPublicKey,
                    chainEntryShortId = draft.relayChainEntryShortId,
                    chainExitServer = draft.relayChainExitServer,
                    chainExitPort = draft.relayChainExitPort.toIntOrNull() ?: 443,
                    chainExitServerName = draft.relayChainExitServerName,
                    chainExitPublicKey = draft.relayChainExitPublicKey,
                    chainExitShortId = draft.relayChainExitShortId,
                    masqueUrl = draft.relayMasqueUrl,
                    masqueUseHttp2Fallback = draft.relayMasqueUseHttp2Fallback,
                    masqueCloudflareMode =
                        normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode, draft.relayMasqueCloudflareMode) ==
                            RelayMasqueAuthModePrivacyPass,
                    udpEnabled = draft.relayUdpEnabled && draft.relayKind != RelayKindCloudflareTunnel,
                    tcpFallbackEnabled = draft.relayMasqueUseHttp2Fallback,
                    localSocksPort = draft.relayLocalSocksPort.toIntOrNull() ?: DefaultRelayLocalSocksPort,
                ),
            )
            relayCredentialStore.save(
                RelayCredentialRecord(
                    profileId = profileId,
                    vlessUuid = draft.relayVlessUuid.ifBlank { null },
                    chainEntryUuid = draft.relayChainEntryUuid.ifBlank { null },
                    chainExitUuid = draft.relayChainExitUuid.ifBlank { null },
                    hysteriaPassword = draft.relayHysteriaPassword.ifBlank { null },
                    hysteriaSalamanderKey = draft.relayHysteriaSalamanderKey.ifBlank { null },
                    masqueAuthMode =
                        normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode, draft.relayMasqueCloudflareMode),
                    masqueAuthToken = draft.relayMasqueAuthToken.ifBlank { null },
                ),
            )
        }
    }
