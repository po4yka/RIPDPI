package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.poyka.ripdpi.data.FailureClass as RuntimeFailureClass

@HiltViewModel
class ConfigViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val masqueClientCredentialImporter: MasqueClientCredentialImporter,
        private val masquePrivacyPassAvailability: MasquePrivacyPassAvailability,
        private val relayPresetCatalog: RelayPresetCatalog,
        private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val serverCapabilityStore: ServerCapabilityStore,
        private val serviceStateStore: ServiceStateStore,
    ) : ViewModel() {
        private val editorSession = MutableStateFlow(ConfigEditorSession())
        private val supportsMasquePrivacyPass = masquePrivacyPassAvailability.isAvailable()
        private val masquePrivacyPassBuildStatus = masquePrivacyPassAvailability.buildStatus()

        private val _effects =
            MutableSharedFlow<ConfigEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val effects: SharedFlow<ConfigEffect> = _effects.asSharedFlow()

        init {
            observeCapabilityEvidence()
        }

        val uiState: StateFlow<ConfigUiState> =
            combine(
                appSettingsRepository.settings,
                editorSession,
                serviceStateStore.telemetry,
            ) { settings, session, serviceTelemetry ->
                val relayPresets = relayPresetCatalog.all()
                val networkSnapshot = runCatching { networkSnapshotProvider.capture() }.getOrNull()
                val capabilityRecords =
                    runCatching {
                        networkFingerprintProvider
                            .capture()
                            ?.let { fingerprint ->
                                serverCapabilityStore.relayCapabilitiesForFingerprint(fingerprint.scopeKey())
                            }.orEmpty()
                    }.getOrDefault(emptyList())
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
                    relayPresets =
                        relayPresets
                            .map { preset ->
                                RelayPresetUiState(
                                    id = preset.id,
                                    title = preset.title,
                                    selected = draft.relayPresetId == preset.id,
                                )
                            }.toImmutableList(),
                    relayPresetSuggestion =
                        resolveRelayPresetSuggestion(
                            heuristicSuggestion = relayPresetCatalog.suggestFor(networkSnapshot, capabilityRecords),
                            serviceTelemetry = serviceTelemetry,
                            capabilityRecords = capabilityRecords,
                        ).toUiState(draft),
                    supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    masquePrivacyPassBuildStatus = masquePrivacyPassBuildStatus,
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

        fun applyRelayPreset(presetId: String) {
            val preset = relayPresetCatalog.find(presetId) ?: return
            updateDraft {
                applyRelayPresetDefinition(preset)
            }
        }

        fun updateChainDsl(value: String) {
            updateDraft { withChainDsl(value) }
        }

        fun cancelEditing() {
            editorSession.value = ConfigEditorSession()
        }

        fun importRelayMasqueCertificateChain(uri: Uri) {
            viewModelScope.launch {
                runCatching { masqueClientCredentialImporter.importCertificateChainPem(uri) }
                    .onSuccess { certificateChain ->
                        updateDraft { copy(relayMasqueClientCertificateChainPem = certificateChain) }
                    }.onFailure { error ->
                        _effects.emit(ConfigEffect.Message(error.message ?: "Certificate import failed."))
                    }
            }
        }

        fun importRelayMasquePrivateKey(uri: Uri) {
            viewModelScope.launch {
                runCatching { masqueClientCredentialImporter.importPrivateKeyPem(uri) }
                    .onSuccess { privateKey ->
                        updateDraft { copy(relayMasqueClientPrivateKeyPem = privateKey) }
                    }.onFailure { error ->
                        _effects.emit(ConfigEffect.Message(error.message ?: "Private key import failed."))
                    }
            }
        }

        fun importRelayMasquePkcs12(
            uri: Uri,
            password: String?,
        ) {
            viewModelScope.launch {
                runCatching { masqueClientCredentialImporter.importPkcs12Identity(uri, password) }
                    .onSuccess(::applyImportedMasqueIdentity)
                    .onFailure { error ->
                        _effects.emit(ConfigEffect.Message(error.message ?: "PKCS#12 import failed."))
                    }
            }
        }

        fun saveDraft() {
            val draft = editorSession.value.draft ?: uiState.value.draft
            if (validateConfigDraft(draft, supportsMasquePrivacyPass).isNotEmpty()) {
                _effects.tryEmit(ConfigEffect.ValidationFailed)
                return
            }

            viewModelScope.launch {
                val persistedDraft =
                    prepareRelayDraftForPersistence(
                        draft = draft,
                        relayProfileStore = relayProfileStore,
                        relayCredentialStore = relayCredentialStore,
                    )
                appSettingsRepository.update {
                    applyConfigDraft(persistedDraft)
                }
                persistRelayArtifacts(persistedDraft)
                editorSession.value = ConfigEditorSession()
                _effects.emit(ConfigEffect.SaveSuccess)
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

        private fun applyImportedMasqueIdentity(identity: ImportedMasqueClientIdentity) {
            updateDraft {
                copy(
                    relayMasqueClientCertificateChainPem = identity.certificateChainPem,
                    relayMasqueClientPrivateKeyPem = identity.privateKeyPem,
                )
            }
        }

        private suspend fun hydrateRelaySecrets(
            presetId: String,
            draft: ConfigDraft,
        ) {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            val profile = relayProfileStore.load(profileId)
            val credentials = relayCredentialStore.load(profileId)
            editorSession.update { current ->
                if (current.presetId != presetId) {
                    current
                } else {
                    current.copy(
                        draft =
                            (current.draft ?: draft).copy(
                                relayPresetId = profile?.presetId.orEmpty(),
                                relayVlessUuid = credentials?.vlessUuid.orEmpty(),
                                relayHysteriaPassword = credentials?.hysteriaPassword.orEmpty(),
                                relayHysteriaSalamanderKey = credentials?.hysteriaSalamanderKey.orEmpty(),
                                relayTuicUuid = credentials?.tuicUuid.orEmpty(),
                                relayTuicPassword = credentials?.tuicPassword.orEmpty(),
                                relayShadowTlsPassword = credentials?.shadowTlsPassword.orEmpty(),
                                relayNaiveUsername = credentials?.naiveUsername.orEmpty(),
                                relayNaivePassword = credentials?.naivePassword.orEmpty(),
                                relayCloudflareCredentialsRef =
                                    profile
                                        ?.cloudflareCredentialsRef
                                        ?.ifBlank { (current.draft ?: draft).relayCloudflareCredentialsRef }
                                        ?: (current.draft ?: draft).relayCloudflareCredentialsRef,
                                relayCloudflareTunnelToken = credentials?.cloudflareTunnelToken.orEmpty(),
                                relayCloudflareTunnelCredentialsJson =
                                    credentials?.cloudflareTunnelCredentialsJson.orEmpty(),
                                relayPtBridgeLine = profile?.ptBridgeLine.orEmpty(),
                                relayWebTunnelUrl = profile?.ptWebTunnelUrl.orEmpty(),
                                relaySnowflakeBrokerUrl =
                                    profile
                                        ?.ptSnowflakeBrokerUrl
                                        ?.ifBlank { DefaultSnowflakeBrokerUrl }
                                        ?: DefaultSnowflakeBrokerUrl,
                                relaySnowflakeFrontDomain =
                                    profile
                                        ?.ptSnowflakeFrontDomain
                                        ?.ifBlank { DefaultSnowflakeFrontDomain }
                                        ?: DefaultSnowflakeFrontDomain,
                                relayChainEntryUuid = credentials?.chainEntryUuid.orEmpty(),
                                relayChainExitUuid = credentials?.chainExitUuid.orEmpty(),
                                relayMasqueAuthMode =
                                    normalizeRelayMasqueAuthMode(credentials?.masqueAuthMode)
                                        ?: (current.draft ?: draft).relayMasqueAuthMode,
                                relayMasqueAuthToken = credentials?.masqueAuthToken.orEmpty(),
                                relayMasqueClientCertificateChainPem =
                                    credentials?.masqueClientCertificateChainPem.orEmpty(),
                                relayMasqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem.orEmpty(),
                            ),
                    )
                }
            }
        }

        @Suppress("LongMethod")
        private suspend fun persistRelayArtifacts(draft: ConfigDraft) {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            relayProfileStore.save(
                RelayProfileRecord(
                    id = profileId,
                    kind = draft.relayKind,
                    presetId = draft.relayPresetId,
                    server = draft.relayServer,
                    serverPort = draft.relayServerPort.toIntOrNull() ?: 443,
                    serverName = draft.relayServerName,
                    realityPublicKey = draft.relayRealityPublicKey,
                    realityShortId = draft.relayRealityShortId,
                    vlessTransport = draft.relayVlessTransport,
                    xhttpPath = draft.relayXhttpPath,
                    xhttpHost = draft.relayXhttpHost,
                    cloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(draft.relayCloudflareTunnelMode),
                    cloudflarePublishLocalOriginUrl = draft.relayCloudflarePublishLocalOriginUrl,
                    cloudflareCredentialsRef =
                        draft.relayCloudflareCredentialsRef.ifBlank {
                            draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                        },
                    chainEntryServer = "",
                    chainEntryPort = 443,
                    chainEntryServerName = "",
                    chainEntryPublicKey = "",
                    chainEntryShortId = "",
                    chainEntryProfileId =
                        if (draft.relayKind ==
                            RelayKindChainRelay
                        ) {
                            draft.relayChainEntryProfileId
                        } else {
                            ""
                        },
                    chainExitServer = "",
                    chainExitPort = 443,
                    chainExitServerName = "",
                    chainExitPublicKey = "",
                    chainExitShortId = "",
                    chainExitProfileId =
                        if (draft.relayKind ==
                            RelayKindChainRelay
                        ) {
                            draft.relayChainExitProfileId
                        } else {
                            ""
                        },
                    masqueUrl = draft.relayMasqueUrl,
                    masqueUseHttp2Fallback = draft.relayMasqueUseHttp2Fallback,
                    masqueCloudflareGeohashEnabled = draft.relayMasqueCloudflareGeohashEnabled,
                    tuicZeroRtt = draft.relayTuicZeroRtt,
                    tuicCongestionControl = normalizeRelayCongestionControl(draft.relayTuicCongestionControl),
                    shadowTlsInnerProfileId = draft.relayShadowTlsInnerProfileId,
                    naivePath = draft.relayNaivePath,
                    ptBridgeLine = draft.relayPtBridgeLine,
                    ptWebTunnelUrl = draft.relayWebTunnelUrl,
                    ptSnowflakeBrokerUrl = draft.relaySnowflakeBrokerUrl.ifBlank { DefaultSnowflakeBrokerUrl },
                    ptSnowflakeFrontDomain = draft.relaySnowflakeFrontDomain.ifBlank { DefaultSnowflakeFrontDomain },
                    udpEnabled =
                        draft.relayUdpEnabled &&
                            (
                                draft.relayKind == RelayKindHysteria2 || draft.relayKind == RelayKindMasque ||
                                    draft.relayKind == RelayKindTuicV5
                            ),
                    tcpFallbackEnabled = draft.relayMasqueUseHttp2Fallback,
                    localSocksPort = draft.relayLocalSocksPort.toIntOrNull() ?: DefaultRelayLocalSocksPort,
                    finalmaskType = normalizeRelayFinalmaskType(draft.relayFinalmaskType),
                    finalmaskHeaderHex = draft.relayFinalmaskHeaderHex,
                    finalmaskTrailerHex = draft.relayFinalmaskTrailerHex,
                    finalmaskRandRange = draft.relayFinalmaskRandRange,
                    finalmaskSudokuSeed = draft.relayFinalmaskSudokuSeed,
                    finalmaskFragmentPackets = draft.relayFinalmaskFragmentPackets.toIntOrNull() ?: 0,
                    finalmaskFragmentMinBytes = draft.relayFinalmaskFragmentMinBytes.toIntOrNull() ?: 0,
                    finalmaskFragmentMaxBytes = draft.relayFinalmaskFragmentMaxBytes.toIntOrNull() ?: 0,
                ),
            )
            relayCredentialStore.save(
                RelayCredentialRecord(
                    profileId = profileId,
                    vlessUuid = draft.relayVlessUuid.ifBlank { null },
                    chainEntryUuid = null,
                    chainExitUuid = null,
                    hysteriaPassword = draft.relayHysteriaPassword.ifBlank { null },
                    hysteriaSalamanderKey = draft.relayHysteriaSalamanderKey.ifBlank { null },
                    tuicUuid = draft.relayTuicUuid.ifBlank { null },
                    tuicPassword = draft.relayTuicPassword.ifBlank { null },
                    shadowTlsPassword = draft.relayShadowTlsPassword.ifBlank { null },
                    naiveUsername = draft.relayNaiveUsername.ifBlank { null },
                    naivePassword = draft.relayNaivePassword.ifBlank { null },
                    masqueAuthMode = normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode),
                    masqueAuthToken = draft.relayMasqueAuthToken.ifBlank { null },
                    masqueClientCertificateChainPem = draft.relayMasqueClientCertificateChainPem.ifBlank { null },
                    masqueClientPrivateKeyPem = draft.relayMasqueClientPrivateKeyPem.ifBlank { null },
                    cloudflareTunnelToken = draft.relayCloudflareTunnelToken.ifBlank { null },
                    cloudflareTunnelCredentialsJson = draft.relayCloudflareTunnelCredentialsJson.ifBlank { null },
                ),
            )
        }

        private fun observeCapabilityEvidence() {
            viewModelScope.launch {
                combine(
                    appSettingsRepository.settings,
                    serviceStateStore.telemetry,
                ) { settings, telemetry ->
                    settings.toConfigDraft() to telemetry
                }.collect { (draft, telemetry) ->
                    persistCapabilityEvidence(draft, telemetry)
                }
            }
        }

        private suspend fun persistCapabilityEvidence(
            draft: ConfigDraft,
            telemetry: ServiceTelemetrySnapshot,
        ) {
            val fingerprint = runCatching { networkFingerprintProvider.capture() }.getOrNull() ?: return
            buildRelayCapabilityObservation(draft, telemetry)?.let { (authority, observation) ->
                serverCapabilityStore.rememberRelayObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    relayProfileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId },
                    observation = observation,
                    source = "config_viewmodel",
                )
            }
            buildDirectPathCapabilityObservation(telemetry)?.let { (authority, observation) ->
                serverCapabilityStore.rememberDirectPathObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    observation = observation,
                    source = "config_viewmodel",
                )
            }
        }
    }
