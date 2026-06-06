package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.config.relay.resolveRelayPresetSuggestion
import com.poyka.ripdpi.config.relay.toUiState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
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
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel
    @Inject
    constructor(
        dependencies: ConfigViewModelDependencies,
        importDependencies: ConfigImportDependencies,
    ) : ViewModel() {
        private val appSettingsRepository = dependencies.appSettingsRepository
        private val relayArtifacts = dependencies.relayArtifacts
        private val relayPresetCatalog = dependencies.relayPresetCatalog
        private val networkSnapshotProvider = dependencies.networkSnapshotProvider
        private val serviceStateStore = dependencies.serviceStateStore
        private val latestDirectModeOutcomeStore = dependencies.latestDirectModeOutcomeStore
        private val capabilityObserver = dependencies.capabilityObserver
        private val masqueClientCredentialImporter = importDependencies.masqueClientCredentialImporter
        private val masquePrivacyPassAvailability = importDependencies.masquePrivacyPassAvailability
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
                latestDirectModeOutcomeStore.outcome,
            ) { settings, session, serviceTelemetry, latestDirectModeOutcome ->
                val relayPresets = relayPresetCatalog.all()
                val relayProfileRecords = runCatching { relayArtifacts.listProfiles() }.getOrDefault(emptyList())
                val networkSnapshot = runCatching { networkSnapshotProvider.capture() }.getOrNull()
                val capabilityRecords = capabilityObserver.relayCapabilitiesForCurrentNetwork()
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
                val relayProfiles =
                    buildRelayProfileOptions(
                        records = relayProfileRecords,
                        chainProfileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId },
                    )
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
                    uiPersona = settings.uiPersona.ifBlank { "simple" },
                    presets = presets,
                    editingPreset = editingPreset,
                    draft = draft,
                    validationErrors =
                        validateConfigDraft(
                            draft = draft,
                            supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                            relayProfiles = relayProfileRecords,
                        ),
                    relayProfiles = relayProfiles,
                    relayChainTrustWarning = resolveRelayChainTrustWarning(draft, relayProfiles),
                    relayChainHopStatus = buildRelayChainHopStatus(serviceTelemetry.relayTelemetry),
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
                            transportRemediation =
                                recommendTransportRemediation(
                                    result = latestDirectModeOutcome?.result,
                                    reasonCode = latestDirectModeOutcome?.reasonCode,
                                    transportClass = latestDirectModeOutcome?.transportClass,
                                ),
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
                val hydratedDraft = relayArtifacts.hydrate(draft)
                editorSession.update { current ->
                    if (current.presetId != presetId) {
                        current
                    } else {
                        current.copy(draft = hydratedDraft)
                    }
                }
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
            viewModelScope.launch {
                val relayProfileRecords = relayArtifacts.listProfiles()
                if (
                    validateConfigDraft(
                        draft = draft,
                        supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                        relayProfiles = relayProfileRecords,
                    ).isNotEmpty()
                ) {
                    _effects.emit(ConfigEffect.ValidationFailed)
                    return@launch
                }
                val persistedDraft =
                    relayArtifacts.prepareForPersistence(draft)
                appSettingsRepository.update {
                    applyConfigDraft(persistedDraft)
                }
                relayArtifacts.persist(persistedDraft)
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

        private fun observeCapabilityEvidence() {
            viewModelScope.launch {
                combine(
                    appSettingsRepository.settings,
                    serviceStateStore.telemetry,
                ) { settings, telemetry ->
                    settings.toConfigDraft() to telemetry
                }.collect { (draft, telemetry) ->
                    capabilityObserver.rememberCapabilityEvidence(draft, telemetry)
                }
            }
        }
    }
